package com.zcshou.service;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.location.Criteria;
import android.location.GnssStatus;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.location.provider.ProviderProperties;
import android.net.wifi.WifiManager;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Message;
import android.os.PowerManager;
import android.os.Process;
import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;

import com.elvishew.xlog.XLog;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;
import com.zcshou.gogogo.MainActivity;
import com.zcshou.gogogo.R;
import com.zcshou.joystick.JoyStick;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

public class ServiceGo extends Service {
    // 定位相关变量
    public static final double DEFAULT_LAT = 36.667662;
    public static final double DEFAULT_LNG = 117.027707;
    public static final double DEFAULT_ALT = 55.0D;
    public static final float DEFAULT_BEA = 0.0F;
    private double mCurLat = DEFAULT_LAT;
    private double mCurLng = DEFAULT_LNG;
    private double mCurAlt = DEFAULT_ALT;
    private float mCurBea = DEFAULT_BEA;
    private double mSpeed = 1.2;        /* 摇杆的意图速度, 单位 m/s。注入时改用实测速度, 见 snapshot() */
    private static final int HANDLER_MSG_ID = 0;
    // 33ms (~30Hz) tick: 缩短"mock 过期"窗口,避免某些应用在两次 push 之间读到真实位置后漂移
    private static final long TICK_INTERVAL_MS = 33;
    private static final String SERVICE_GO_HANDLER_NAME = "ServiceGoLocation";
    // 相邻两次 tick 超过该阈值即判定循环被挂起 (正常 tick 为 33ms)。
    private static final long STALL_THRESHOLD_MS = 5000;
    // 真实 GPS 精度通常 5~20m、网络定位 20~100m。此前直接传入 Criteria.ACCURACY_FINE
    // (=1) / ACCURACY_COARSE (=2) 这两个"等级常量"当作米数使用, 上报的精度物理上不可信,
    // 融合定位可能因此给该来源降权。改为真实量级, 并保持 GPS 优于 NETWORK。
    private static final float ACCURACY_GPS_M = 8.0f;
    private static final float ACCURACY_NETWORK_M = 35.0f;
    // 真实 GPS fix 会同时给出垂直/速度/方向精度。缺失这些字段时, 融合定位可能
    // 判定该位置质量不足而降低权重。卫星数取室外常见值, 而非固定 7。
    private static final int SATELLITE_COUNT = 9;
    // 与水平精度配套的垂直/速度/方向精度, 取值来自常见室外 GPS fix 的量级。
    private static final float ACCURACY_VERTICAL_M = 12.0f;
    private static final float ACCURACY_SPEED_MPS = 0.5f;
    private static final float ACCURACY_BEARING_DEG = 10.0f;
    private LocationManager mLocManager;
    private HandlerThread mLocHandlerThread;
    private Handler mLocHandler;
    private LocationListener mPersistentListener;     // 保持 Provider 活跃订阅,见 onCreate 注释
    private FusedLocationProviderClient mFusedClient; // Google Play Services fused mock, null = GMS 不可用
    private volatile boolean mFusedMockEnabled = false;
    private volatile boolean isStop = false;
    private boolean mHasPosition = false;
    private boolean mLoopStarted = false;
    private final Object mPositionLock = new Object();
    private static volatile MockPosition sCurrentPosition;
    private final AtomicBoolean mFusedWritePending = new AtomicBoolean();
    // GMS 写入在途超时。超过该时长仍无回调, 视为卡死并允许新的写入接管。
    private static final long FUSED_WRITE_TIMEOUT_MS = 2000;
    private volatile long mFusedWriteStartMs;
    private final Map<String, Long> mLastErrorLog = new HashMap<>();
    private volatile long mLastGmsSuccessMs;
    private volatile long mLastSystemSuccessMs;
    private long mLastHeartbeatMs;
    private long mLastTickMs;
    // 前台服务只能防进程被杀,不能阻止 CPU 休眠。屏幕熄灭后 HandlerThread 的 33ms tick
    // 会被冻结 (日志中单次最长停摆 349s),导致系统里的 mock 位置变成"过期数据",
    // 依赖定位的应用会退回 WiFi/基站并拿到真实位置。运行期间持有 PARTIAL_WAKE_LOCK。
    private volatile PowerManager.WakeLock mWakeLock;

    // ---- 位置插值与真实速度 ----
    // 直接瞬移会让"位移"和"报告速度"互相矛盾: 33ms 内移动几百米, 却报告 1.2m/s,
    // 融合定位的运动学校验会判定该位置不可能而丢弃。这里改为从上一个已知位置
    // 出发平滑过渡到目标, 并按实际位移计算速度, 使两者始终自洽。
    // 瞬移过渡速度上限。取值需兼顾两点: 足够快以免用户感到"传送延迟",
    // 又要落在融合定位认为交通可达的范围内, 否则仍会被运动学校验丢弃。
    private static final double TELEPORT_MAX_SPEED_MPS = 80.0;   // 约 288km/h
    private static final double ARRIVE_EPS_M = 0.5;
    private double mRenderLng;
    private double mRenderLat;
    private boolean mRenderValid = false;
    private long mLastStepMs;
    private double mTeleportRateMps = 0;

    public static MockPosition getCurrentPosition() {
        return sCurrentPosition;
    }

    /** 只读快照, 不推进插值。用于 UI 状态展示与目标设置。 */
    private MockPosition currentSnapshot() {
        synchronized (mPositionLock) {
            return new MockPosition(mRenderValid ? mRenderLng : mCurLng,
                    mRenderValid ? mRenderLat : mCurLat,
                    mCurAlt, (float) mCurBea, 0f, 0,
                    mLastSystemSuccessMs, mLastGmsSuccessMs);
        }
    }

    /**
     * 推进一帧插值并返回本帧应注入的位置。
     *
     * 速度不再是摇杆的"意图速度", 而是由上一帧与本帧的实际位移算出的真实速度,
     * 保证位移与速度在运动学上自洽。瞬移时按 TELEPORT_MAX_SPEED_MPS 匀速过渡,
     * 避免出现"33ms 移动几公里却报告 1.2m/s"这种不可能的数据。
     */
    private MockPosition snapshot(long tick) {
        long nowMs = tick > 0 ? tick : SystemClock.elapsedRealtime();
        double targetLng;
        double targetLat;
        double targetAlt;
        double targetBea;
        synchronized (mPositionLock) {
            targetLng = mCurLng;
            targetLat = mCurLat;
            targetAlt = mCurAlt;
            targetBea = mCurBea;
        }

        if (!mRenderValid) {
            // 首帧直接落在目标上, 不做过渡
            synchronized (mPositionLock) {
                mRenderLng = targetLng;
                mRenderLat = targetLat;
                mRenderValid = true;
                mLastStepMs = nowMs;
                mTeleportRateMps = 0;
            }
            return new MockPosition(targetLng, targetLat, targetAlt, (float) targetBea, 0f,
                    tick, mLastSystemSuccessMs, mLastGmsSuccessMs);
        }

        synchronized (mPositionLock) {
            double dt = (nowMs - mLastStepMs) / 1000.0;
            mLastStepMs = nowMs;
            if (dt <= 0) dt = TICK_INTERVAL_MS / 1000.0;

            double distance = distanceMeters(mRenderLat, mRenderLng, targetLat, targetLng);

            if (distance <= ARRIVE_EPS_M) {
                // 已到达目标: 位置固定, 速度为 0
                mRenderLng = targetLng;
                mRenderLat = targetLat;
                mTeleportRateMps = 0;
                return new MockPosition(mRenderLng, mRenderLat, targetAlt, (float) targetBea, 0f,
                        tick, mLastSystemSuccessMs, mLastGmsSuccessMs);
            }

            // 按最大可达速度推进, 使位移/时长恒等于报告速度
            double stepMeters = TELEPORT_MAX_SPEED_MPS * dt;
            double movedMeters;
            if (stepMeters >= distance) {
                mRenderLng = targetLng;
                mRenderLat = targetLat;
                movedMeters = distance;
            } else {
                double ratio = stepMeters / distance;
                mRenderLng += (targetLng - mRenderLng) * ratio;
                mRenderLat += (targetLat - mRenderLat) * ratio;
                movedMeters = stepMeters;
            }

            mTeleportRateMps = movedMeters / dt;
            return new MockPosition(mRenderLng, mRenderLat, targetAlt, (float) targetBea,
                    (float) mTeleportRateMps, tick, mLastSystemSuccessMs, mLastGmsSuccessMs);
        }
    }

    /** 球面近似距离, 单位米。 */
    private static double distanceMeters(double lat1, double lng1, double lat2, double lng2) {
        double dLat = (lat2 - lat1) * 110574.0;
        double dLng = (lng2 - lng1) * 111320.0 * Math.cos(Math.toRadians((lat1 + lat2) / 2.0));
        return Math.hypot(dLat, dLng);
    }

    private void logProviderFailure(String provider, Exception error) {
        synchronized (mLastErrorLog) {
            long now = SystemClock.elapsedRealtime();
            Long last = mLastErrorLog.get(provider);
            if (last == null || now - last >= 10000) {
                mLastErrorLog.put(provider, now);
                XLog.e("SERVICEGO: " + provider + " failed: " + error.toString());
            }
        }
    }
    // 通知栏消息
    private static final int SERVICE_GO_NOTE_ID = 1;
    private static final String SERVICE_GO_NOTE_ACTION_JOYSTICK_SHOW = "ShowJoyStick";
    private static final String SERVICE_GO_NOTE_ACTION_JOYSTICK_HIDE = "HideJoyStick";
    private static final String SERVICE_GO_NOTE_CHANNEL_ID = "SERVICE_GO_NOTE";
    private static final String SERVICE_GO_NOTE_CHANNEL_NAME = "SERVICE_GO_NOTE";
    private NoteActionReceiver mActReceiver;
    // 摇杆相关
    private JoyStick mJoyStick;

    private final ServiceGoBinder mBinder = new ServiceGoBinder();

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    @Override
    public void onCreate() {
        super.onCreate();

        mLocManager = (LocationManager) this.getSystemService(Context.LOCATION_SERVICE);
        initNotification();

        removeTestProviderNetwork();
        addTestProviderNetwork();

        removeTestProviderGPS();
        addTestProviderGPS();

        // Android 12+ 起 LocationManager 也有 "fused" provider,部分应用(WeChat 走的就是
        // 这条) 不直接读 GPS/NETWORK 而是读 fused。能注就注,失败也无所谓。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            removeTestProviderFused();
            addTestProviderFused();
        }

        initGoLocation();

        initJoyStick();

        logAvailableProviders();

        logGnssStatus();

        // 关键:维持 Provider 活跃订阅。Android 12+ 上没有活跃 listener 的 Provider
        // 会进入低功耗/缓存退化状态,微信小程序调 getLastKnownLocation 拿不到
        // 刚 push 的 mock 数据 (高德/百度 App 自己有订阅就能看到)。
        // Baidu 版靠 LocationClient(:remote, scanSpan=1000)做这件事,迁移后需要补回来。
        initPersistentLocationListener();

        // 第二条路径: 用 GMS FusedLocationProviderClient.setMockMode + setMockLocation
        // 覆盖走 Google Play Services 路径的应用(部分 WeChat/腾讯小程序场景)。
        // 设备没装 GMS 就 try/catch 静默跳过。
        initFusedMock();
    }

    @SuppressLint("MissingPermission")
    private void initFusedMock() {
        try {
            mFusedClient = LocationServices.getFusedLocationProviderClient(this);
            mFusedClient.setMockMode(true)
                    .addOnSuccessListener(unused -> {
                        if (isStop) {
                            mFusedClient.setMockMode(false);
                            return;
                        }
                        mFusedMockEnabled = true;
                        XLog.i("SERVICEGO: FusedLocation setMockMode(true) OK");
                    })
                    .addOnFailureListener(e -> XLog.e("SERVICEGO: FusedLocation setMockMode failed: " + e.getMessage()));
        } catch (Throwable t) {
            // 设备没 GMS / play-services-location 不可用 → 退化到仅 LocationManager 注入
            XLog.e("SERVICEGO: FusedLocation init failed (GMS not available?): " + t.getMessage());
            mFusedClient = null;
        }
    }

    @SuppressLint("MissingPermission")
    private void initPersistentLocationListener() {
        mPersistentListener = new LocationListener() {
            @Override public void onLocationChanged(@NonNull Location location) {
                // 若某个 Provider 送来非 mock 位置, 说明我们的注入被其他来源覆盖了。
                // 这是判断"系统里到底是谁的位置"最直接的证据, 按 Provider 限频记录。
                if (!isMockLocation(location)) {
                    logProviderFailure("nonMock/" + location.getProvider(),
                            new IllegalStateException(String.format(Locale.US,
                                    "lat=%.6f lng=%.6f acc=%.0f",
                                    location.getLatitude(), location.getLongitude(),
                                    location.getAccuracy())));
                }
            }
            @Override public void onProviderEnabled(@NonNull String provider) {}
            @Override public void onProviderDisabled(@NonNull String provider) {}
            @Override public void onStatusChanged(String provider, int status, Bundle extras) {}
        };
        try {
            mLocManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 0f, mPersistentListener);
            mLocManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 1000, 0f, mPersistentListener);
        } catch (SecurityException e) {
            XLog.e("SERVICEGO: ERROR - requestLocationUpdates permission missing");
        } catch (Exception e) {
            XLog.e("SERVICEGO: ERROR - initPersistentLocationListener: " + e.getMessage());
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || !intent.hasExtra(MainActivity.LNG_MSG_ID)
                || !intent.hasExtra(MainActivity.LAT_MSG_ID)) {
            XLog.e("SERVICEGO: missing target coordinates; stopping");
            stopSelf();
            return START_NOT_STICKY;
        }
        double lng = intent.getDoubleExtra(MainActivity.LNG_MSG_ID, Double.NaN);
        double lat = intent.getDoubleExtra(MainActivity.LAT_MSG_ID, Double.NaN);
        double alt = intent.getDoubleExtra(MainActivity.ALT_MSG_ID, DEFAULT_ALT);
        if (!Double.isFinite(lng) || !Double.isFinite(lat) || !Double.isFinite(alt)
                || Math.abs(lng) > 180 || Math.abs(lat) > 90) {
            XLog.e("SERVICEGO: invalid target coordinates; stopping");
            stopSelf();
            return START_NOT_STICKY;
        }
        synchronized (mPositionLock) {
            mCurLng = lng;
            mCurLat = lat;
            mCurAlt = alt;
            mHasPosition = true;
            sCurrentPosition = currentSnapshot();
        }
        mJoyStick.setCurrentPosition(lng, lat, alt);
        // 循环即将启动,同步持锁,避免第一帧就落在休眠窗口里。
        acquireWakeLock();
        // Do not publish the built-in default coordinates before receiving the Intent.
        if (!mLoopStarted) {
            mLoopStarted = true;
            mLocHandler.sendEmptyMessage(HANDLER_MSG_ID);
        }
        XLog.i("SERVICEGO: target received; location loop active");
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        synchronized (mPositionLock) {
            isStop = true;
            mHasPosition = false;
            sCurrentPosition = null;
        }
        XLog.i("SERVICEGO: onDestroy; mock location stopped");
        mLocHandler.removeMessages(HANDLER_MSG_ID);
        mLocHandlerThread.quit();

        releaseWakeLock();

        mJoyStick.destroy();

        removeTestProviderNetwork();
        removeTestProviderGPS();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            removeTestProviderFused();
        }

        if (mPersistentListener != null) {
            try {
                mLocManager.removeUpdates(mPersistentListener);
            } catch (Exception ignored) {
            }
        }

        if (mFusedClient != null && mFusedMockEnabled) {
            try {
                mFusedClient.setMockMode(false);
            } catch (Exception ignored) {
            }
        }

        unregisterReceiver(mActReceiver);
        stopForeground(STOP_FOREGROUND_REMOVE);

        super.onDestroy();
    }

    /**
     * 只在模拟运行期间持有 PARTIAL_WAKE_LOCK:保持 CPU 运行,不点亮屏幕。
     * 前台服务通知 + startForeground 已保证进程优先级,这里补的是 CPU 不被挂起。
     */
    private void acquireWakeLock() {
        if (mWakeLock != null && mWakeLock.isHeld()) return;
        try {
            PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
            if (powerManager == null) {
                XLog.e("SERVICEGO: PowerManager unavailable; wake lock not acquired");
                return;
            }
            mWakeLock = powerManager.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK, "GoGoGo::MockLocationLoop");
            mWakeLock.setReferenceCounted(false);
            // 不加超时:进程被杀时系统会自动释放 wakelock,不存在泄漏风险。
            // 加超时反而会让长时间模拟在超时后静默失去锁。
            mWakeLock.acquire();
            XLog.i("SERVICEGO: PARTIAL_WAKE_LOCK acquired");
        } catch (Exception e) {
            mWakeLock = null;
            XLog.e("SERVICEGO: acquireWakeLock failed: " + e.getMessage());
        }
    }

    private void releaseWakeLock() {
        if (mWakeLock == null) return;
        try {
            if (mWakeLock.isHeld()) {
                mWakeLock.release();
                XLog.i("SERVICEGO: PARTIAL_WAKE_LOCK released");
            }
        } catch (Exception e) {
            XLog.e("SERVICEGO: releaseWakeLock failed: " + e.getMessage());
        } finally {
            mWakeLock = null;
        }
    }

    private void initNotification() {
        mActReceiver = new NoteActionReceiver();
        IntentFilter filter = new IntentFilter();
        filter.addAction(SERVICE_GO_NOTE_ACTION_JOYSTICK_SHOW);
        filter.addAction(SERVICE_GO_NOTE_ACTION_JOYSTICK_HIDE);
        // 熄屏/亮屏是判断"冻结是否跟随熄屏"的直接证据, 心跳只能看到恢复后的状态。
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        registerReceiver(mActReceiver, filter);

        NotificationChannel mChannel = new NotificationChannel(SERVICE_GO_NOTE_CHANNEL_ID, SERVICE_GO_NOTE_CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT);
        NotificationManager notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);

        if (notificationManager != null) {
            notificationManager.createNotificationChannel(mChannel);
        }

        //准备intent
        Intent clickIntent = new Intent(this, MainActivity.class);
        PendingIntent clickPI = PendingIntent.getActivity(this, 1, clickIntent, PendingIntent.FLAG_IMMUTABLE);
        Intent showIntent = new Intent(SERVICE_GO_NOTE_ACTION_JOYSTICK_SHOW);
        PendingIntent showPendingPI = PendingIntent.getBroadcast(this, 0, showIntent, PendingIntent.FLAG_IMMUTABLE);
        Intent hideIntent = new Intent(SERVICE_GO_NOTE_ACTION_JOYSTICK_HIDE);
        PendingIntent hidePendingPI = PendingIntent.getBroadcast(this, 0, hideIntent, PendingIntent.FLAG_IMMUTABLE);

        Notification notification = new NotificationCompat.Builder(this, SERVICE_GO_NOTE_CHANNEL_ID)
                .setChannelId(SERVICE_GO_NOTE_CHANNEL_ID)
                .setContentTitle(getResources().getString(R.string.app_name))
                .setContentText(getResources().getString(R.string.app_service_tips))
                .setContentIntent(clickPI)
                .addAction(new NotificationCompat.Action(null, getResources().getString(R.string.note_show), showPendingPI))
                .addAction(new NotificationCompat.Action(null, getResources().getString(R.string.note_hide), hidePendingPI))
                .setSmallIcon(R.mipmap.ic_launcher)
                .build();

        // Android 14+ (API 34) 要求显式声明 foregroundServiceType,否则在前台服务中
        // requestLocationUpdates 会抛 SecurityException
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(SERVICE_GO_NOTE_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
        } else {
            startForeground(SERVICE_GO_NOTE_ID, notification);
        }
    }

    private void initJoyStick() {
        mJoyStick = new JoyStick(this);
        mJoyStick.setListener(new JoyStick.JoyStickClickListener() {
            @Override
            public void onMoveInfo(double speed, double disLng, double disLat, double angle) {
                synchronized (mPositionLock) {
                    mSpeed = speed;
                    mCurLng += disLng / (111.320 * Math.cos(Math.abs(mCurLat) * Math.PI / 180));
                    mCurLat += disLat / 110.574;
                    mCurBea = (float) angle;
                }
            }

            @Override
            public void onPositionInfo(double lng, double lat, double alt) {
                synchronized (mPositionLock) {
                    mCurLng = lng;
                    mCurLat = lat;
                    mCurAlt = alt;
                }
            }
        });
        mJoyStick.show();
    }

    private void initGoLocation() {
        // 创建 HandlerThread 实例，第一个参数是线程的名字
        mLocHandlerThread = new HandlerThread(SERVICE_GO_HANDLER_NAME, Process.THREAD_PRIORITY_FOREGROUND);
        // 启动 HandlerThread 线程
        mLocHandlerThread.start();
        // Handler 对象与 HandlerThread 的 Looper 对象的绑定
        mLocHandler = new Handler(mLocHandlerThread.getLooper()) {
            // 这里的Handler对象可以看作是绑定在HandlerThread子线程中，所以handlerMessage里的操作是在子线程中运行的
            @Override
            public void handleMessage(@NonNull Message msg) {
                MockPosition position;
                synchronized (mPositionLock) {
                    if (isStop || !mHasPosition) return;
                    position = snapshot(SystemClock.elapsedRealtime());
                }
                setLocationNetwork(position);
                setLocationGPS(position);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    setLocationFused(position);
                }
                setLocationFusedClient(position);
                synchronized (mPositionLock) {
                    if (isStop) return;
                    // Preserve a target change made while the previous write was in flight.
                    // 只读快照: 插值已在上面推进过一帧, 这里不能再推进。
                    sCurrentPosition = currentSnapshot();
                }
                long now = SystemClock.elapsedRealtime();
                // tick 间隔远超预期说明循环被挂起过 (CPU 休眠 / 进程冻结)。
                // 这是判断后台是否停摆的直接指标,比外部推断可靠。
                if (mLastTickMs > 0 && now - mLastTickMs > STALL_THRESHOLD_MS) {
                    XLog.w("SERVICEGO: loop stalled " + (now - mLastTickMs)
                            + "ms; wakeLockHeld=" + (mWakeLock != null && mWakeLock.isHeld()));
                }
                mLastTickMs = now;
                if (now - mLastHeartbeatMs >= 10000) {
                    mLastHeartbeatMs = now;
                    XLog.i("SERVICEGO: heartbeat systemAgeMs="
                            + (mLastSystemSuccessMs == 0 ? -1 : now - mLastSystemSuccessMs)
                            + " gmsAgeMs="
                            + (mLastGmsSuccessMs == 0 ? -1 : now - mLastGmsSuccessMs)
                            + " " + describeTarget()
                            + " " + describeReadback()
                            + " " + describeEnvironment());
                }
                sendEmptyMessageDelayed(HANDLER_MSG_ID, TICK_INTERVAL_MS);
            }
        };
        // onStartCommand starts the loop only after coordinates have been validated.
    }

    /**
     * 输出当前 mock 目标的绝对坐标。没有这行就无法把"微信读到的坐标"和"我们设置的目标"
     * 做直接对比, 也就无法判断微信是走了缓存/基站, 还是真的无视了系统 mock。
     */
    private String describeTarget() {
        double lng;
        double lat;
        double alt;
        double renderLat;
        double renderLng;
        double rate;
        synchronized (mPositionLock) {
            lng = mCurLng;
            lat = mCurLat;
            alt = mCurAlt;
            renderLat = mRenderLat;
            renderLng = mRenderLng;
            rate = mTeleportRateMps;
        }
        double remain = distanceMeters(renderLat, renderLng, lat, lng);
        return String.format(Locale.US,
                "target[wgs=%.6f,%.6f alt=%.0f] render[lat=%.6f,lng=%.6f rem=%.0fm v=%.1fm/s]",
                lat, lng, alt, renderLat, renderLng, remain, rate);
    }

    /**
     * 回读各 Provider 的 getLastKnownLocation, 与当前 mock 目标比对。
     * setTestProviderLocation 不抛异常只说明调用被接受, 不代表系统真的存了这个位置;
     * 这是目前日志唯一缺失的关键证据。
     */
    private String describeReadback() {
        StringBuilder sb = new StringBuilder("readback[");
        sb.append("gps=").append(describeProviderReadback(LocationManager.GPS_PROVIDER));
        sb.append(" net=").append(describeProviderReadback(LocationManager.NETWORK_PROVIDER));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            sb.append(" fused=").append(describeProviderReadback(LocationManager.FUSED_PROVIDER));
        }
        return sb.append(']').toString();
    }

    @SuppressLint("MissingPermission")
    private String describeProviderReadback(String provider) {
        try {
            Location loc = mLocManager.getLastKnownLocation(provider);
            if (loc == null) return "null";
            double dLng;
            double dLat;
            synchronized (mPositionLock) {
                dLng = loc.getLongitude() - mCurLng;
                dLat = loc.getLatitude() - mCurLat;
            }
            // 用米表示偏差, 便于判断是否仍是旧位置
            double dMeters = Math.hypot(dLng * 111320 * Math.cos(Math.toRadians(loc.getLatitude())),
                    dLat * 110574);
            return String.format(Locale.US, "age=%dms dev=%.0fm mock=%b",
                    System.currentTimeMillis() - loc.getTime(), dMeters, isMockLocation(loc));
        } catch (Exception e) {
            return "err:" + e.getClass().getSimpleName();
        }
    }

    private static boolean isMockLocation(Location loc) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return loc.isMock();
        }
        //noinspection deprecation
        return loc.isFromMockProvider();
    }

    /** 记录与定位稳定性直接相关的运行环境, 用于解释"为什么这次失效"。 */
    private String describeEnvironment() {
        String wifi;
        String screen;
        try {
            WifiManager wifiManager = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            wifi = wifiManager != null && wifiManager.isWifiEnabled() ? "on" : "off";
        } catch (Exception e) {
            wifi = "?";
        }
        try {
            PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
            screen = powerManager != null && powerManager.isInteractive() ? "on" : "off";
        } catch (Exception e) {
            screen = "?";
        }
        return "env[wifi=" + wifi + " screen=" + screen + "]";
    }

    /**
     * 启动时枚举系统上所有 Provider 及其位置来源。
     * 若存在我们未 mock 的 Provider, 它可能就是应用绕过 mock 读取真实位置的通路。
     */
    @SuppressLint("MissingPermission")
    private void logAvailableProviders() {
        try {
            StringBuilder sb = new StringBuilder();
            for (String provider : mLocManager.getAllProviders()) {
                String state;
                try {
                    Location loc = mLocManager.getLastKnownLocation(provider);
                    state = loc == null ? "noloc"
                            : String.format(Locale.US, "mock=%b lat=%.6f lng=%.6f",
                                    isMockLocation(loc), loc.getLatitude(), loc.getLongitude());
                } catch (Exception e) {
                    state = "err";
                }
                sb.append(provider).append('{').append(state).append("} ");
            }
            XLog.i("SERVICEGO: providers=" + sb);
        } catch (Exception e) {
            XLog.e("SERVICEGO: logAvailableProviders failed: " + e.getMessage());
        }
    }

    /**
     * 记录 GNSS 卫星状态。系统实际可见卫星数为 0 时, 融合定位会判定 GPS 不可用,
     * 转而采信基站/WiFi, 即使我们已经注入了 mock 位置。
     */
    @SuppressLint("MissingPermission")
    private void logGnssStatus() {
        try {
            GnssStatus.Callback callback = new GnssStatus.Callback() {
                @Override
                public void onSatelliteStatusChanged(@NonNull GnssStatus status) {
                    int used = 0;
                    for (int i = 0; i < status.getSatelliteCount(); i++) {
                        if (status.usedInFix(i)) used++;
                    }
                    XLog.i("SERVICEGO: gnss visible=" + status.getSatelliteCount() + " usedInFix=" + used);
                }
            };
            boolean ok;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // 带 Handler 的重载自 API 30 起才存在
                ok = mLocManager.registerGnssStatusCallback(callback, mLocHandler);
            } else {
                ok = mLocManager.registerGnssStatusCallback(callback);
            }
            XLog.i("SERVICEGO: gnss callback registered=" + ok);
        } catch (Exception e) {
            XLog.e("SERVICEGO: gnss status unavailable: " + e.getMessage());
        }
    }

    private void removeTestProviderGPS() {
        try {
            if (mLocManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                mLocManager.setTestProviderEnabled(LocationManager.GPS_PROVIDER, false);
                mLocManager.removeTestProvider(LocationManager.GPS_PROVIDER);
            }
        } catch (Exception e) {
            XLog.e("SERVICEGO: ERROR - removeTestProviderGPS");
        }
    }

    // 注意下面临时添加 @SuppressLint("wrongconstant") 以处理 addTestProvider 参数值的 lint 错误
    @SuppressLint("wrongconstant")
    private void addTestProviderGPS() {
        try {
            // 注意，由于 android api 问题，下面的参数会提示错误(以下参数是通过相关API获取的真实GPS参数，不是随便写的)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                mLocManager.addTestProvider(LocationManager.GPS_PROVIDER, false, true, false,
                        false, true, true, true, ProviderProperties.POWER_USAGE_HIGH, ProviderProperties.ACCURACY_FINE);
            } else {
                mLocManager.addTestProvider(LocationManager.GPS_PROVIDER, false, true, false,
                        false, true, true, true, Criteria.POWER_HIGH, Criteria.ACCURACY_FINE);
            }
            if (!mLocManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                mLocManager.setTestProviderEnabled(LocationManager.GPS_PROVIDER, true);
            }
        } catch (Exception e) {
            XLog.e("SERVICEGO: ERROR - addTestProviderGPS");
        }
    }

    /**
     * 补齐真实 GPS fix 会带的精度字段, 并写入 GNSS 状态 extras。
     * 若这些字段缺失, 部分融合定位会判定该位置质量不足而降低权重,
     * 转而采信基站/WiFi, 表现为"mock 生效但对方仍读到真实位置"。
     */
    private void applyGpsQuality(Location loc) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            loc.setVerticalAccuracyMeters(ACCURACY_VERTICAL_M);
            loc.setSpeedAccuracyMetersPerSecond(ACCURACY_SPEED_MPS);
            loc.setBearingAccuracyDegrees(ACCURACY_BEARING_DEG);
        }
    }

    private void setLocationGPS(MockPosition position) {
        try {
            // 尽可能模拟真实的 GPS 数据
            Location loc = new Location(LocationManager.GPS_PROVIDER);
            loc.setAccuracy(ACCURACY_GPS_M);    // 单位: 米。真实 GPS 量级, 而非等级常量 1
            loc.setAltitude(position.altitude);                     // 设置高度，在 WGS 84 参考坐标系中的米
            loc.setBearing(position.bearing);                       // 方向（度）
            loc.setLatitude(position.latitude);                   // 纬度（度）
            loc.setLongitude(position.longitude);                  // 经度（度）
            loc.setTime(System.currentTimeMillis());    // 本地时间
            loc.setSpeed(position.speed);
            loc.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
            applyGpsQuality(loc);
            Bundle bundle = new Bundle();
            bundle.putInt("satellites", SATELLITE_COUNT);            loc.setExtras(bundle);

            mLocManager.setTestProviderLocation(LocationManager.GPS_PROVIDER, loc);
            mLastSystemSuccessMs = SystemClock.elapsedRealtime();
        } catch (Exception e) {
            logProviderFailure("gps/write", e);
        }
    }

    private void removeTestProviderNetwork() {
        try {
            if (mLocManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                mLocManager.setTestProviderEnabled(LocationManager.NETWORK_PROVIDER, false);
                mLocManager.removeTestProvider(LocationManager.NETWORK_PROVIDER);
            }
        } catch (Exception e) {
            XLog.e("SERVICEGO: ERROR - removeTestProviderNetwork");
        }
    }

    // 注意下面临时添加 @SuppressLint("wrongconstant") 以处理 addTestProvider 参数值的 lint 错误
    @SuppressLint("wrongconstant")
    private void addTestProviderNetwork() {
        try {
            // 注意，由于 android api 问题，下面的参数会提示错误(以下参数是通过相关API获取的真实NETWORK参数，不是随便写的)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                mLocManager.addTestProvider(LocationManager.NETWORK_PROVIDER, true, false,
                        true, true, true, true,
                        true, ProviderProperties.POWER_USAGE_LOW, ProviderProperties.ACCURACY_COARSE);
            } else {
                mLocManager.addTestProvider(LocationManager.NETWORK_PROVIDER, true, false,
                        true, true, true, true,
                        true, Criteria.POWER_LOW, Criteria.ACCURACY_COARSE);
            }
            if (!mLocManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                mLocManager.setTestProviderEnabled(LocationManager.NETWORK_PROVIDER, true);
            }
        } catch (SecurityException e) {
            XLog.e("SERVICEGO: ERROR - addTestProviderNetwork");
        }
    }

    private void setLocationNetwork(MockPosition position) {
        try {
            // 尽可能模拟真实的 NETWORK 数据
            Location loc = new Location(LocationManager.NETWORK_PROVIDER);
            loc.setAccuracy(ACCURACY_NETWORK_M);  // 单位: 米。网络定位精度天然低于 GPS
            loc.setAltitude(position.altitude);                     // 设置高度，在 WGS 84 参考坐标系中的米
            loc.setBearing(position.bearing);                       // 方向（度）
            loc.setLatitude(position.latitude);                   // 纬度（度）
            loc.setLongitude(position.longitude);                  // 经度（度）
            loc.setTime(System.currentTimeMillis());    // 本地时间
            loc.setSpeed(position.speed);
            loc.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
            applyGpsQuality(loc);

            mLocManager.setTestProviderLocation(LocationManager.NETWORK_PROVIDER, loc);
            mLastSystemSuccessMs = SystemClock.elapsedRealtime();
        } catch (Exception e) {
            logProviderFailure("network/write", e);
        }
    }

    private void removeTestProviderFused() {
        try {
            if (mLocManager.isProviderEnabled(LocationManager.FUSED_PROVIDER)) {
                mLocManager.setTestProviderEnabled(LocationManager.FUSED_PROVIDER, false);
                mLocManager.removeTestProvider(LocationManager.FUSED_PROVIDER);
            }
        } catch (Exception e) {
            // 系统 fused 通常不让 addTestProvider,失败正常 — 不写日志免刷屏
        }
    }

    @SuppressLint("wrongconstant")
    private void addTestProviderFused() {
        try {
            mLocManager.addTestProvider(LocationManager.FUSED_PROVIDER, false, true, false,
                    false, true, true, true, ProviderProperties.POWER_USAGE_HIGH, ProviderProperties.ACCURACY_FINE);
            if (!mLocManager.isProviderEnabled(LocationManager.FUSED_PROVIDER)) {
                mLocManager.setTestProviderEnabled(LocationManager.FUSED_PROVIDER, true);
            }
            XLog.i("SERVICEGO: FUSED_PROVIDER test provider added");
        } catch (Exception e) {
            // 系统 fused 通常不让 addTestProvider,失败正常 — GMS 路径仍可覆盖
        }
    }

    private void setLocationFused(MockPosition position) {
        try {
            Location loc = new Location(LocationManager.FUSED_PROVIDER);
            loc.setAccuracy(ACCURACY_GPS_M);
            loc.setAltitude(position.altitude);
            loc.setBearing(position.bearing);
            loc.setLatitude(position.latitude);
            loc.setLongitude(position.longitude);
            loc.setTime(System.currentTimeMillis());
            loc.setSpeed(position.speed);
            loc.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
            applyGpsQuality(loc);

            mLocManager.setTestProviderLocation(LocationManager.FUSED_PROVIDER, loc);
            mLastSystemSuccessMs = SystemClock.elapsedRealtime();
        } catch (Exception e) {
            logProviderFailure("fused/write", e);
        }
    }

    @SuppressLint("MissingPermission")
    private void setLocationFusedClient(MockPosition position) {
        if (mFusedClient == null || !mFusedMockEnabled || isStop) return;

        // 只允许一个请求在途, 但必须带超时: 若 GMS 的回调迟迟不返回, 这个闸门会一直
        // 关闭, 后续所有写入被静默丢弃。日志中实测出现 10~26 秒的连续中断
        // (gmsAgeMs 从 5998 涨到 26028 又回落), 期间读取方会退回真实位置。
        if (mFusedWritePending.get()
                && SystemClock.elapsedRealtime() - mFusedWriteStartMs < FUSED_WRITE_TIMEOUT_MS) {
            return;
        }
        if (!mFusedWritePending.compareAndSet(false, true)) {
            // 上一个请求疑似卡死, 强制接管
            XLog.w("SERVICEGO: gms/write timeout, taking over after "
                    + (SystemClock.elapsedRealtime() - mFusedWriteStartMs) + "ms");
        }
        mFusedWriteStartMs = SystemClock.elapsedRealtime();
        try {
            Location loc = new Location("fused");
            loc.setAccuracy(ACCURACY_GPS_M);
            loc.setAltitude(position.altitude);
            loc.setBearing(position.bearing);
            loc.setLatitude(position.latitude);
            loc.setLongitude(position.longitude);
            loc.setTime(System.currentTimeMillis());
            loc.setSpeed(position.speed);
            loc.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
            applyGpsQuality(loc);
            final long writeStartMs = mFusedWriteStartMs;
            mFusedClient.setMockLocation(loc)
                    .addOnSuccessListener(unused -> mLastGmsSuccessMs = SystemClock.elapsedRealtime())
                    .addOnFailureListener(e -> logProviderFailure("gms/write", e))
                    // 只有仍是本次写入时才释放闸门; 若已被超时接管, 迟到的回调
                    // 不能把新请求的 pending 标志清掉。
                    .addOnCompleteListener(task -> {
                        // 只有仍是本次写入时才释放闸门; 若已被超时接管,
                        // 迟到的回调不能把新请求的 pending 标志清掉。
                        if (mFusedWriteStartMs == writeStartMs && mFusedWritePending.get()) {
                            mFusedWritePending.set(false);
                        }
                    });
        } catch (Exception e) {
            mFusedWritePending.set(false);
            logProviderFailure("gms/write", e);
        }
    }

    public class NoteActionReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action != null) {
                if (action.equals(SERVICE_GO_NOTE_ACTION_JOYSTICK_SHOW)) {
                    mJoyStick.show();
                }

                if (action.equals(SERVICE_GO_NOTE_ACTION_JOYSTICK_HIDE)) {
                    mJoyStick.hide();
                }

                if (action.equals(Intent.ACTION_SCREEN_OFF)) {
                    XLog.i("SERVICEGO: screen off; loop=" + describeLoopState());
                }

                if (action.equals(Intent.ACTION_SCREEN_ON)) {
                    XLog.i("SERVICEGO: screen on; loop=" + describeLoopState());
                }
            }
        }
    }

    /** 熄屏/亮屏时输出循环状态, 用于判断冻结与熄屏的对应关系。 */
    private String describeLoopState() {
        long age = mLastTickMs == 0 ? -1 : SystemClock.elapsedRealtime() - mLastTickMs;
        return "lastTickAgeMs=" + age
                + " wakeLockHeld=" + (mWakeLock != null && mWakeLock.isHeld());
    }

    public class ServiceGoBinder extends Binder {
        public void setPosition(double lng, double lat, double alt) {
            synchronized (mPositionLock) {
                mCurLng = lng;
                mCurLat = lat;
                mCurAlt = alt;
                sCurrentPosition = currentSnapshot();
            }
            mJoyStick.setCurrentPosition(mCurLng, mCurLat, mCurAlt);
        }
    }
}



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
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.location.provider.ProviderProperties;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Message;
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
    private double mSpeed = 1.2;        /* 默认的速度，单位 m/s */
    private static final int HANDLER_MSG_ID = 0;
    // 33ms (~30Hz) tick: 缩短"mock 过期"窗口,避免某些应用在两次 push 之间读到真实位置后漂移
    private static final long TICK_INTERVAL_MS = 33;
    private static final String SERVICE_GO_HANDLER_NAME = "ServiceGoLocation";
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
    private final Map<String, Long> mLastErrorLog = new HashMap<>();
    private volatile long mLastGmsSuccessMs;
    private volatile long mLastSystemSuccessMs;
    private long mLastHeartbeatMs;

    public static MockPosition getCurrentPosition() {
        return sCurrentPosition;
    }

    private MockPosition snapshot(long tick) {
        return new MockPosition(mCurLng, mCurLat, mCurAlt, mCurBea, (float) mSpeed,
                tick, mLastSystemSuccessMs, mLastGmsSuccessMs);
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
            @Override public void onLocationChanged(@NonNull Location location) {}
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
            sCurrentPosition = snapshot(0);
        }
        mJoyStick.setCurrentPosition(lng, lat, alt);
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

    private void initNotification() {
        mActReceiver = new NoteActionReceiver();
        IntentFilter filter = new IntentFilter();
        filter.addAction(SERVICE_GO_NOTE_ACTION_JOYSTICK_SHOW);
        filter.addAction(SERVICE_GO_NOTE_ACTION_JOYSTICK_HIDE);
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
                    sCurrentPosition = snapshot(SystemClock.elapsedRealtime());
                }
                long now = SystemClock.elapsedRealtime();
                if (now - mLastHeartbeatMs >= 10000) {
                    mLastHeartbeatMs = now;
                    XLog.i("SERVICEGO: heartbeat systemAgeMs="
                            + (mLastSystemSuccessMs == 0 ? -1 : now - mLastSystemSuccessMs)
                            + " gmsAgeMs="
                            + (mLastGmsSuccessMs == 0 ? -1 : now - mLastGmsSuccessMs));
                }
                sendEmptyMessageDelayed(HANDLER_MSG_ID, TICK_INTERVAL_MS);
            }
        };
        // onStartCommand starts the loop only after coordinates have been validated.
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

    private void setLocationGPS(MockPosition position) {
        try {
            // 尽可能模拟真实的 GPS 数据
            Location loc = new Location(LocationManager.GPS_PROVIDER);
            loc.setAccuracy(Criteria.ACCURACY_FINE);    // 设定此位置的估计水平精度，以米为单位。
            loc.setAltitude(position.altitude);                     // 设置高度，在 WGS 84 参考坐标系中的米
            loc.setBearing(position.bearing);                       // 方向（度）
            loc.setLatitude(position.latitude);                   // 纬度（度）
            loc.setLongitude(position.longitude);                  // 经度（度）
            loc.setTime(System.currentTimeMillis());    // 本地时间
            loc.setSpeed(position.speed);
            loc.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
            Bundle bundle = new Bundle();
            bundle.putInt("satellites", 7);
            loc.setExtras(bundle);

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
            loc.setAccuracy(Criteria.ACCURACY_COARSE);  // 设定此位置的估计水平精度，以米为单位。
            loc.setAltitude(position.altitude);                     // 设置高度，在 WGS 84 参考坐标系中的米
            loc.setBearing(position.bearing);                       // 方向（度）
            loc.setLatitude(position.latitude);                   // 纬度（度）
            loc.setLongitude(position.longitude);                  // 经度（度）
            loc.setTime(System.currentTimeMillis());    // 本地时间
            loc.setSpeed(position.speed);
            loc.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());

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
            loc.setAccuracy(Criteria.ACCURACY_FINE);
            loc.setAltitude(position.altitude);
            loc.setBearing(position.bearing);
            loc.setLatitude(position.latitude);
            loc.setLongitude(position.longitude);
            loc.setTime(System.currentTimeMillis());
            loc.setSpeed(position.speed);
            loc.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());

            mLocManager.setTestProviderLocation(LocationManager.FUSED_PROVIDER, loc);
            mLastSystemSuccessMs = SystemClock.elapsedRealtime();
        } catch (Exception e) {
            logProviderFailure("fused/write", e);
        }
    }

    @SuppressLint("MissingPermission")
    private void setLocationFusedClient(MockPosition position) {
        if (mFusedClient == null || !mFusedMockEnabled || isStop
                || !mFusedWritePending.compareAndSet(false, true)) return;
        try {
            Location loc = new Location("fused");
            loc.setAccuracy(Criteria.ACCURACY_FINE);
            loc.setAltitude(position.altitude);
            loc.setBearing(position.bearing);
            loc.setLatitude(position.latitude);
            loc.setLongitude(position.longitude);
            loc.setTime(System.currentTimeMillis());
            loc.setSpeed(position.speed);
            loc.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
            mFusedClient.setMockLocation(loc)
                    .addOnSuccessListener(unused -> mLastGmsSuccessMs = SystemClock.elapsedRealtime())
                    .addOnFailureListener(e -> logProviderFailure("gms/write", e))
                    .addOnCompleteListener(task -> mFusedWritePending.set(false));
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
            }
        }
    }

    public class ServiceGoBinder extends Binder {
        public void setPosition(double lng, double lat, double alt) {
            synchronized (mPositionLock) {
                mCurLng = lng;
                mCurLat = lat;
                mCurAlt = alt;
                sCurrentPosition = snapshot(0);
            }
            mJoyStick.setCurrentPosition(mCurLng, mCurLat, mCurAlt);
        }
    }
}



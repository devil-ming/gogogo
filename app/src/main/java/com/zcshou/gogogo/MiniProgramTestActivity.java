package com.zcshou.gogogo;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.elvishew.xlog.XLog;
import com.zcshou.utils.MapUtils;
import com.zcshou.utils.TencentWebKey;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

public class MiniProgramTestActivity extends BaseActivity {

    private static final int PERMISSION_REQUEST_CODE = 100;
    // Key 由 local.properties 在 build 时注入(gitignored),拷贝项目后请在 local.properties 配置自己申请的 Key
    private static final String DEFAULT_BAIDU_AK = BuildConfig.MAPS_API_KEY;

    private TextView tvStatus;
    private TextView tvWgs84;
    private TextView tvGcj02;
    private TextView tvBd09;
    private TextView tvAddressBaidu;
    private TextView tvAddressTencent;
    private TextView tvProvider;
    private TextView tvAccuracy;
    private TextView tvTime;
    private TextView tvMockStatus;
    private Button btnRefresh;

    private LocationManager mLocManager;
    private OkHttpClient mOkHttpClient;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_mini_program_test);

        tvStatus = findViewById(R.id.tv_status);
        tvWgs84 = findViewById(R.id.tv_wgs84);
        tvGcj02 = findViewById(R.id.tv_gcj02);
        tvBd09 = findViewById(R.id.tv_bd09);
        tvAddressBaidu = findViewById(R.id.tv_address_baidu);
        tvAddressTencent = findViewById(R.id.tv_address_tencent);
        tvProvider = findViewById(R.id.tv_provider);
        tvAccuracy = findViewById(R.id.tv_accuracy);
        tvTime = findViewById(R.id.tv_time);
        tvMockStatus = findViewById(R.id.tv_mock_status);
        btnRefresh = findViewById(R.id.btn_refresh);

        mLocManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        mOkHttpClient = new OkHttpClient();

        btnRefresh.setOnClickListener(v -> refreshLocation());

        checkPermissionAndStart();
    }

    private void checkPermissionAndStart() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION},
                    PERMISSION_REQUEST_CODE);
        } else {
            refreshLocation();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                refreshLocation();
            } else {
                tvStatus.setText("定位权限被拒绝");
            }
        }
    }

    private void refreshLocation() {
        tvStatus.setText("正在获取定位...");
        tvAddressBaidu.setText("百度: 解析中...");
        tvAddressTencent.setText("腾讯: 解析中...");

        if (mLocManager == null) {
            tvStatus.setText("LocationManager 不可用");
            return;
        }

        Location loc = null;
        try {
            loc = mLocManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
        } catch (SecurityException e) {
            XLog.e("获取GPS位置失败: " + e.getMessage());
        }

        if (loc == null) {
            try {
                loc = mLocManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
            } catch (SecurityException e) {
                XLog.e("获取Network位置失败: " + e.getMessage());
            }
        }

        if (loc == null) {
            tvStatus.setText("正在请求新位置...");
            try {
                mLocManager.requestSingleUpdate(LocationManager.GPS_PROVIDER, new LocationListener() {
                    @Override
                    public void onLocationChanged(@NonNull Location location) {
                        runOnUiThread(() -> displayLocation(location));
                    }
                    @Override public void onStatusChanged(String provider, int status, Bundle extras) {}
                    @Override public void onProviderEnabled(@NonNull String provider) {}
                    @Override public void onProviderDisabled(@NonNull String provider) {}
                }, null);
            } catch (SecurityException e) {
                tvStatus.setText("请求位置失败: " + e.getMessage());
            }
            return;
        }

        displayLocation(loc);
    }

    private void displayLocation(Location loc) {
        double wgsLat = loc.getLatitude();
        double wgsLng = loc.getLongitude();

        double[] gcj = MapUtils.wgs2gcj02(wgsLng, wgsLat);
        double[] bd = MapUtils.wgs2bd09(wgsLng, wgsLat);

        tvWgs84.setText(String.format(Locale.getDefault(), "纬度: %.6f\n经度: %.6f", wgsLat, wgsLng));
        tvGcj02.setText(String.format(Locale.getDefault(), "纬度: %.6f\n经度: %.6f", gcj[1], gcj[0]));
        tvBd09.setText(String.format(Locale.getDefault(), "纬度: %.6f\n经度: %.6f", bd[1], bd[0]));

        tvProvider.setText(loc.getProvider() != null ? loc.getProvider() : "未知");
        tvAccuracy.setText(String.format(Locale.getDefault(), "%.1f 米", loc.getAccuracy()));

        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
        tvTime.setText(sdf.format(new Date(loc.getTime())));

        boolean isMock = false;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            isMock = loc.isMock();
        } else {
            isMock = loc.isFromMockProvider();
        }
        if (isMock) {
            tvMockStatus.setText("模拟位置 (Mock)");
            tvMockStatus.setTextColor(getColor(R.color.colorAccent));
        } else {
            tvMockStatus.setText("真实位置");
            tvMockStatus.setTextColor(getColor(android.R.color.holo_green_dark));
        }

        tvStatus.setText("定位成功");

        // 调用百度反向地理编码 (HTTP API)
        queryBaiduAddress(bd[1], bd[0]);
        // 调用腾讯反向地理编码 (HTTP API)
        queryTencentAddress(gcj[1], gcj[0]);
    }

    private void queryBaiduAddress(double bdLat, double bdLng) {
        String url = "https://api.map.baidu.com/reverse_geocoding/v3/?ak=" + DEFAULT_BAIDU_AK
                + "&output=json&coordtype=bd09ll&location=" + bdLat + "," + bdLng;

        Request request = new Request.Builder().url(url).get().build();
        mOkHttpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                runOnUiThread(() -> tvAddressBaidu.setText("百度: 请求失败"));
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                ResponseBody body = response.body();
                if (body != null) {
                    try {
                        JSONObject json = new JSONObject(body.string());
                        int status = Integer.parseInt(json.getString("status"));
                        if (status == 0) {
                            String address = json.getJSONObject("result").getString("formatted_address");
                            runOnUiThread(() -> tvAddressBaidu.setText("百度: " + address));
                        } else {
                            String msg = json.optString("msg", "未知错误");
                            runOnUiThread(() -> tvAddressBaidu.setText("百度: " + msg));
                        }
                    } catch (JSONException e) {
                        runOnUiThread(() -> tvAddressBaidu.setText("百度: 解析失败"));
                    }
                }
            }
        });
    }

    private void queryTencentAddress(double gcjLat, double gcjLng) {
        final TencentWebKey webKey = TencentWebKey.load(this);
        XLog.i("Tencent test/geocoder request: " + webKey.diagnostic);
        String url = "https://apis.map.qq.com/ws/geocoder/v1/?location=" + gcjLat + "," + gcjLng
                + "&key=" + webKey.value + "&get_poi=0";

        Request request = new Request.Builder().url(url).get().build();
        mOkHttpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                runOnUiThread(() -> tvAddressTencent.setText("腾讯: 请求失败"));
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                ResponseBody body = response.body();
                if (body != null) {
                    try {
                        JSONObject json = new JSONObject(body.string());
                        int status = json.getInt("status");
                        XLog.i("Tencent test/geocoder response: http=" + response.code()
                                + " status=" + status + " " + webKey.diagnostic);
                        if (status == 0) {
                            JSONObject result = json.getJSONObject("result");
                            String address = result.getString("address");
                            String recommend = result.optJSONObject("formatted_addresses") != null
                                    ? result.getJSONObject("formatted_addresses").optString("recommend", "")
                                    : "";
                            final String display = recommend.isEmpty() ? address : recommend;
                            runOnUiThread(() -> tvAddressTencent.setText("腾讯: " + display));
                        } else {
                            String msg = json.optString("message", "未知错误");
                            XLog.e("Tencent test/geocoder failed: status=" + status
                                    + " message=" + msg + " " + webKey.diagnostic);
                            runOnUiThread(() -> tvAddressTencent.setText(
                                    "腾讯: " + msg + " (status=" + status + ")\n" + webKey.diagnostic));
                        }
                    } catch (JSONException e) {
                        runOnUiThread(() -> tvAddressTencent.setText("腾讯: 解析失败"));
                    }
                }
            }
        });
    }
}

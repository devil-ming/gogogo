package com.zcshou.gogogo;

import android.app.Application;

import androidx.preference.PreferenceManager;

import com.elvishew.xlog.LogConfiguration;
import com.elvishew.xlog.LogLevel;
import com.elvishew.xlog.XLog;
import com.elvishew.xlog.printer.ConsolePrinter;
import com.elvishew.xlog.printer.Printer;
import com.elvishew.xlog.printer.file.FilePrinter;
import com.elvishew.xlog.printer.file.backup.NeverBackupStrategy;
import com.elvishew.xlog.printer.file.clean.FileLastModifiedCleanStrategy;
import com.elvishew.xlog.printer.file.naming.ChangelessFileNameGenerator;
import com.tencent.tencentmap.mapsdk.maps.TencentMapInitializer;

import java.io.File;

public class GoApplication extends Application {
    public static final String APP_NAME = "GoGoGo";
    public static final String LOG_FILE_NAME = APP_NAME + ".log";
    private static final long MAX_TIME = 1000 * 60 * 60 * 24 * 3; // 3 days

    @Override
    public void onCreate() {
        super.onCreate();

        initXlog();

        // 腾讯地图 SDK 隐私合规 + 初始化
        try {
            // 6.x 起 setAgreePrivacy 需要 Context 参数
            TencentMapInitializer.setAgreePrivacy(this, true);
            // 6.x 需要显式 start;5.x 不需要。否则 MapView.getMap() 返回 null
            TencentMapInitializer.start(this);
        } catch (Exception e) {
            XLog.e("TencentMap init failed: " + e.getMessage());
        }
    }

    /**
     * Initialize XLog.
     */
    private void initXlog() {
        File logPath = getExternalFilesDir("Logs");
        if (logPath != null) {
            // setting_log_off 之前只挂在设置界面上, 从未被读取; 这里让它真正生效。
            boolean logOff = PreferenceManager.getDefaultSharedPreferences(this)
                    .getBoolean("setting_log_off", false);
            LogConfiguration config = new LogConfiguration.Builder()
                    .logLevel(logOff ? LogLevel.NONE : LogLevel.ALL)
                    .tag(APP_NAME)                                         // 指定 TAG，默认为 "X-LOG"
                    .enableThreadInfo()                                    // 允许打印线程信息，默认禁止
                    .enableStackTrace(2)                                   // 允许打印深度为 2 的调用栈信息，默认禁止
                    .enableBorder()                                        // 允许打印日志边框，默认禁止
                    .build();

            Printer consolePrinter = new ConsolePrinter();                  // 通过 System.out 打印日志到控制台的打印器
            Printer filePrinter = new FilePrinter                           // 打印日志到文件的打印器
                    .Builder(logPath.getPath())                             // 指定保存日志文件的路径
                    .fileNameGenerator(new ChangelessFileNameGenerator(LOG_FILE_NAME))         // 指定日志文件名生成器，默认为 ChangelessFileNameGenerator("log")
                    .backupStrategy(new NeverBackupStrategy())              // 指定日志文件备份策略，默认为 FileSizeBackupStrategy(1024 * 1024)
                    .cleanStrategy(new FileLastModifiedCleanStrategy(MAX_TIME))     // 指定日志文件清除策略，默认为 NeverCleanStrategy()
                    .build();
            XLog.init(config, consolePrinter, filePrinter);
        }
    }
}

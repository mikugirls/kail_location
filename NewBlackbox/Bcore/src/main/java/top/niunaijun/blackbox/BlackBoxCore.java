package top.niunaijun.blackbox;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

import top.niunaijun.blackbox.app.configuration.ClientConfiguration;

public class BlackBoxCore {

    private static final BlackBoxCore INSTANCE = new BlackBoxCore();
    private boolean mainProcess = false;
    private boolean serverProcess = false;
    private boolean supportGms = false;

    public static BlackBoxCore get() {
        return INSTANCE;
    }

    public static Context getContext() {
        return null;
    }

    public static String getHostPkg() {
        return "";
    }

    public static PackageManager getPackageManager() {
        return null;
    }

    public void closeCodeInit() {}
    public void onBeforeMainApplicationAttach(android.app.Application app, Context base) {}
    public void doAttachBaseContext(android.app.Application app, ClientConfiguration config) {}
    public void onAfterMainApplicationAttach(android.app.Application app, Context base) {}
    public void doCreate() {}

    public boolean isMainProcess() { return mainProcess; }
    public boolean isServerProcess() { return serverProcess; }

    public List<ApplicationInfo> getInstalledApplications(int flags, int userId) {
        return new ArrayList<>();
    }

    public boolean isInstalled(String packageName, int userId) { return false; }
    public InstallResult installPackageAsUser(String packageName, int userId) {
        return new InstallResult();
    }

    public static class InstallResult {
        public boolean success = false;
        public String msg = "stub";

        public boolean component1() { return success; }
        public String component2() { return msg; }
    }
    public void uninstallPackageAsUser(String packageName, int userId) {}
    public boolean launchApk(String packageName, int userId) { return false; }
    public void clearPackage(String packageName, int userId) {}
    public void stopPackage(String packageName, int userId) {}

    public boolean isSupportGms() { return supportGms; }
    public void setSupportGms(boolean v) { supportGms = v; }

    public void sendLogs(String message, boolean zip, LogSendListener listener) {}

    public interface LogSendListener {
        void onSuccess();
        void onFailure(String error);
    }
}

package top.niunaijun.blackbox.app.configuration;

import java.io.File;

public class ClientConfiguration {
    public String getHostPackageName() { return ""; }
    public boolean isHideRoot() { return false; }
    public boolean isEnableDaemonService() { return false; }
    public boolean isUseVpnNetwork() { return false; }
    public boolean isDisableFlagSecure() { return false; }
    public boolean requestInstallPackage(File file, int userId) { return false; }
}

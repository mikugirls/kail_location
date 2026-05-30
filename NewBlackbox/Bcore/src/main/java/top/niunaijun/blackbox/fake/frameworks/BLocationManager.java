package top.niunaijun.blackbox.fake.frameworks;

import top.niunaijun.blackbox.entity.location.BLocation;

public class BLocationManager {

    public static final int GLOBAL_MODE = 1;
    public static final int CLOSE_MODE = 0;

    private static final BLocationManager INSTANCE = new BLocationManager();

    public static BLocationManager get() {
        return INSTANCE;
    }

    public void setPattern(int userId, String packageName, int mode) {}
    public void setGlobalLocation(BLocation location) {}
}

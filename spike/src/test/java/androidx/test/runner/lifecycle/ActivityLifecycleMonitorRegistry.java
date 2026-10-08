package androidx.test.runner.lifecycle;
public final class ActivityLifecycleMonitorRegistry {
    private static ActivityLifecycleMonitor instance;
    public static void registerInstance(ActivityLifecycleMonitor m) { instance = m; }
    public static ActivityLifecycleMonitor getInstance() { return instance; }
}

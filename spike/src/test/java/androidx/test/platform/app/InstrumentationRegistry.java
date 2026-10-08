// Minimal stand-in for androidx.test (Google Maven is unreachable from the spike environment).
package androidx.test.platform.app;
public final class InstrumentationRegistry {
    private static android.app.Instrumentation instrumentation;
    public static void registerInstance(android.app.Instrumentation i, android.os.Bundle args) { instrumentation = i; }
    public static android.app.Instrumentation getInstrumentation() { return instrumentation; }
}

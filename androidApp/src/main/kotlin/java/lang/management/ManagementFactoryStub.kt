package java.lang.management

// Stub to satisfy R8 for ktor's IntellijIdeaDebugDetector, whose lazy lambda
// calls ManagementFactory.getRuntimeMXBean() and RuntimeMXBean.getInputArguments().
// java.lang.management is JVM-only (absent from Android's boot classpath), and R8
// in full mode treats that as a hard error even with -dontwarn / -assumevalues.
// The stub returns null; ktor wraps the call in try/catch, so isDebuggerConnected()
// evaluates to false. Keep the method descriptors matching the compiled lambda.
public class ManagementFactory {

    public companion object {
        @JvmStatic
        public fun getRuntimeMXBean(): RuntimeMXBean? = null
    }
}

public interface RuntimeMXBean {

    public fun getInputArguments(): List<String>
}

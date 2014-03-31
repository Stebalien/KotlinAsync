public final class JavaUtils {
    private JavaUtils() {}
    public static void setStackTrace(Throwable throwable, StackTraceElement[] trace) {
        throwable.setStackTrace(trace);
    }
}

package neiaddon;
/** Stub for the plain-Java checks: the log stays silent. */
public class NeiAddon {
    public interface Log {
        void warn(String s, Object... o);
        void info(String s, Object... o);
        void debug(String s, Object... o);
    }
    public static final String MODID = "neiaddon";
    public static final Log LOGGER = new Log() {
        @Override public void warn(String s, Object... o) {
        }
        @Override public void info(String s, Object... o) {
        }
        @Override public void debug(String s, Object... o) {
        }
    };
}

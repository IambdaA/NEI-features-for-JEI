package neiaddon;
/** Stub for the plain-Java checks: the shipped defaults, never read from or written to a file. */
public final class AddonConfig {
    public static final class BoolV {
        private final boolean value;
        BoolV(boolean d) {
            value = d;
        }
        public boolean get() {
            return value;
        }
    }
    public static final class IntV {
        private final int value;
        IntV(int d) {
            value = d;
        }
        public int get() {
            return value;
        }
    }
    public static final BoolV BM_WORLD_SPECIFIC = new BoolV(false);
    public static final BoolV FAVORITES_AUTO = new BoolV(false);
    public static final IntV FAVORITES_DEPTH = new IntV(9);
}

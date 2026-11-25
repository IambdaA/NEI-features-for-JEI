package neiaddon.autocraft;

import neiaddon.AddonConfig;

/**
 * The Autocrafting Speed setting: the most crafts one client tick may ask for, over every path and over the up to three
 * phase steps of a tick. {@link Runner#onTick} resets the count each tick; a path asks {@link #fits} before it sends
 * crafts and calls {@link #spend} when it does. With the setting at 0 nothing is limited.
 */
final class Speed {

    private static long spent;

    private Speed() {}

    /** The setting: crafts per tick, 0 for no limit. */
    static int limit() {
        return AddonConfig.AUTOCRAFTING_SPEED.get();
    }

    /** Starts a new tick's count. */
    static void reset() {
        spent = 0;
    }

    /** How many of {@code n} crafts may still be asked this tick. */
    static long allowed(long n) {
        int limit = limit();
        if (limit <= 0) return n;
        return Math.max(0, Math.min(n, limit - spent));
    }

    /**
     * Whether {@code n} crafts may be asked now. A request larger than the setting itself (the setting was lowered in
     * the middle of a run) goes out on a tick that has asked for nothing yet, so the run never waits forever.
     */
    static boolean fits(long n) {
        int limit = limit();
        if (limit <= 0 || n <= limit - spent) return true;
        return n > limit && spent == 0;
    }

    /** Counts {@code n} crafts as asked this tick. */
    static void spend(long n) {
        spent += n;
    }
}

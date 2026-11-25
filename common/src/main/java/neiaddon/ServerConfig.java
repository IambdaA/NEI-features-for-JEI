package neiaddon;

import neiaddon.config.ConfigSpec;

/**
 * The server's own settings file, {@code config/neiaddon-server.toml}. Common code that a dedicated server loads: no
 * client classes and none of the client settings. The file is read once, on the first craft request.
 */
public final class ServerConfig {

    private static final ConfigSpec.Builder B = new ConfigSpec.Builder();

    public static final ConfigSpec.IntValue AUTOCRAFT_MILLIS_PER_TICK = B
        .comment("The most milliseconds of server time one player's autocrafting request may use per tick. 0 means no "
            + "limit. At least one step (up to 64 crafts of one recipe) always runs, so a request can go over a small "
            + "limit by one step. The server reads this file once, so a change needs a server restart.")
        .defineInRange("autocraftMillisPerTick", 0, 0, 1000);

    private static final ConfigSpec SPEC = B.build("neiaddon-server.toml");

    private static boolean warned;

    private ServerConfig() {}

    /** The time limit of one chain request in nanoseconds, 0 for none. Never throws: a failed read counts as 0. */
    public static long limitNanos() {
        try {
            return AUTOCRAFT_MILLIS_PER_TICK.get() * 1_000_000L;
        } catch (RuntimeException | LinkageError e) {
            if (!warned) {
                warned = true;
                NeiAddon.LOGGER.warn("Could not read neiaddon-server.toml, autocrafting has no time limit", e);
            }
            return 0;
        }
    }
}

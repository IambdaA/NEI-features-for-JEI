package neiaddon.platform;
import java.nio.file.Path;
import net.neoforged.fml.loading.FMLPaths;
/** Stub for the plain-Java checks: the config dir is the scratch one FMLPaths already points at. */
public class Platform {
    private static final Platform INSTANCE = new Platform();
    public static Platform get() {
        return INSTANCE;
    }
    public Path configDir() {
        return FMLPaths.CONFIGDIR.get();
    }
}

package neiaddon.platform;
/** Stub for the plain-Java checks: no fluids in this world. */
public class ClientPlatform {
    private static final ClientPlatform INSTANCE = new ClientPlatform();
    public static ClientPlatform get() {
        return INSTANCE;
    }
    public long fluidAmount(Object fluid) {
        return 0;
    }
}

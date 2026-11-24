package net.minecraft.resources;
import java.util.Objects;
/** Stub for the plain-Java checks (1.20.1): keeps the string it was built from, so ids round-trip unchanged. */
public class ResourceLocation {
    private final String full;
    public ResourceLocation(String full) {
        this.full = full;
    }
    public ResourceLocation(String namespace, String path) {
        this.full = namespace + ":" + path;
    }
    public String getNamespace() {
        int c = full.indexOf(':');
        return c < 0 ? "minecraft" : full.substring(0, c);
    }
    public String getPath() {
        int c = full.indexOf(':');
        return c < 0 ? full : full.substring(c + 1);
    }
    @Override public String toString() {
        return full;
    }
    @Override public boolean equals(Object o) {
        return o instanceof ResourceLocation r && r.full.equals(full);
    }
    @Override public int hashCode() {
        return Objects.hash(full);
    }
}

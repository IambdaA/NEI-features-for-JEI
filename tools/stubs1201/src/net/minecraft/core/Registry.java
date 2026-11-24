package net.minecraft.core;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;
/** Stub for the plain-Java checks (1.20.1): ResourceLocation-keyed. */
public interface Registry<T> {
    ResourceLocation getKey(T t);
    Optional<T> getOptional(ResourceLocation id);
}

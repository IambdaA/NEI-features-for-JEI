package net.minecraft.core.registries;
import java.util.Optional;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.material.Fluid;
/** Stub for the plain-Java checks (1.20.1): the item registry is Item.REGISTRY; fluids are inert. */
public class BuiltInRegistries {
    public static final Registry<Item> ITEM = new Registry<>() {
        @Override public ResourceLocation getKey(Item i) {
            return new ResourceLocation(i.id);
        }
        @Override public Optional<Item> getOptional(ResourceLocation id) {
            return Optional.ofNullable(Item.REGISTRY.get(id.toString()));
        }
    };
    public static final Registry<Fluid> FLUID = new Registry<>() {
        @Override public ResourceLocation getKey(Fluid f) {
            return new ResourceLocation(f.id);
        }
        @Override public Optional<Fluid> getOptional(ResourceLocation id) {
            return Optional.empty();
        }
    };
}

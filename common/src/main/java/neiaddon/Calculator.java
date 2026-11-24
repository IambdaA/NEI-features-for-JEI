package neiaddon;

import mezz.jei.api.runtime.IJeiRuntime;
import java.util.Objects;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * Holds the JEI runtime for the addon's JEI lookups, and the identity key of a stack ({@link StackKey}: item and NBT,
 * ignoring count).
 */
public final class Calculator {

    /** Identity of an item with its NBT tag, ignoring count. */
    public static final class StackKey {

        public final Item item;
        public final CompoundTag patch;
        public final ItemStack sample;

        public StackKey(ItemStack stack) {
            this.item = stack.getItem();
            this.patch = stack.getTag() == null ? null : stack.getTag().copy();
            this.sample = stack.copy();
            this.sample.setCount(1);
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof StackKey k && k.item == item && Objects.equals(k.patch, patch);
        }

        @Override
        public int hashCode() {
            return 31 * item.hashCode() + Objects.hashCode(patch);
        }
    }

    public static IJeiRuntime runtime;

    private Calculator() {}

    public static void onRuntime(IJeiRuntime rt) {
        runtime = rt;
        FluidStacks.reset();
        Bees.reset();
        RecipeLookup.reset();
        CraftablesPanel.reset();
    }

    public static boolean available() {
        return runtime != null;
    }
}

package neiaddon;

import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * Holds the JEI runtime for the addon's JEI lookups, and the identity key of a stack ({@link StackKey}: item and
 * components, ignoring count).
 */
public final class Calculator {

    /** Identity of an item with its components, ignoring count. */
    public static final class StackKey {

        public final Item item;
        public final DataComponentPatch patch;
        public final ItemStack sample;

        public StackKey(ItemStack stack) {
            this.item = stack.getItem();
            this.patch = stack.getComponentsPatch();
            this.sample = stack.copyWithCount(1);
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof StackKey k && k.item == item && k.patch.equals(patch);
        }

        @Override
        public int hashCode() {
            return 31 * item.hashCode() + patch.hashCode();
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

package neiaddon;

import java.util.Optional;
import mezz.jei.api.helpers.IPlatformFluidHelper;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.runtime.IJeiRuntime;
import neiaddon.platform.ClientPlatform;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;

/**
 * A fluid of JEI (the platform's fluid ingredient) as one of the addon's own carrier stacks, see {@link FluidStacks}. Used
 * for the bookmark key over a fluid in JEI's item list or a recipe, and for a fluid that JEI's own bookmark list took.
 */
final class JeiFluids {

    /** The carrier stack of the fluid and how much of it the slot showed, in millibuckets. */
    record Hit(ItemStack carrier, long milliBuckets) {}

    private JeiFluids() {}

    /** The fluid under the mouse in JEI's item list or in a recipe of its recipe screen, or null. */
    static Hit underMouse() {
        if (!Calculator.available()) return null;
        try {
            return underMouse(Calculator.runtime, Calculator.runtime.getJeiHelpers()
                .getPlatformFluidHelper());
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static <T> Hit underMouse(IJeiRuntime rt, IPlatformFluidHelper<T> helper) {
        // the item list's typed lookup returns a nullable value on some JEI versions and an Optional on others; the
        // untyped one is the same everywhere
        Optional<ITypedIngredient<?>> listed = rt.getIngredientListOverlay()
            .getIngredientUnderMouse();
        if (listed.isPresent()) return of(helper, listed.get());
        Optional<T> fluid = rt.getRecipesGui()
            .getIngredientUnderMouse(helper.getFluidIngredientType());
        return fluid.isPresent() ? hit(helper, fluid.get()) : null;
    }

    /** The fluid of a JEI ingredient, or null when it is not a fluid (or an empty one). */
    static Hit of(ITypedIngredient<?> ingredient) {
        if (!Calculator.available()) return null;
        try {
            return of(Calculator.runtime.getJeiHelpers()
                .getPlatformFluidHelper(), ingredient);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static <T> Hit of(IPlatformFluidHelper<T> helper, ITypedIngredient<?> ingredient) {
        Class<? extends T> type = helper.getFluidIngredientType()
            .getIngredientClass();
        Object raw = ingredient.getIngredient();
        return type.isInstance(raw) ? hit(helper, type.cast(raw)) : null;
    }

    private static <T> Hit hit(IPlatformFluidHelper<T> helper, T fluid) {
        Fluid base = helper.getFluidIngredientType()
            .getBase(fluid);
        if (base == null || base == Fluids.EMPTY) return null;
        long unitsPerMilliBucket = Math.max(1, helper.bucketVolume() / 1000);
        long milli = ClientPlatform.get()
            .fluidAmount(fluid) / unitsPerMilliBucket;
        return new Hit(FluidStacks.carrier(BuiltInRegistries.FLUID.getKey(base)), Math.max(1, milli));
    }
}

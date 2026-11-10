package neiaddon;

import java.util.ArrayList;
import java.util.List;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.helpers.IPlatformFluidHelper;
import mezz.jei.api.ingredients.IIngredientTypeWithSubtypes;
import mezz.jei.api.runtime.IIngredientManager;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.material.Fluid;
import neiaddon.event.SubscribeEvent;
import neiaddon.event.TickEvent;

/**
 * NTM - Continuum shows every one of its fluids in JEI's item list as an {@code hbm:fluid_icon} item (the droplet). Its
 * builds before 26.2 also register the fluids with the game, so JEI listed each NTM fluid a second time as a plain fluid.
 * Here those plain NTM fluids are taken out of JEI's item list (only when the droplets are there), so each fluid shows
 * once, as on 26.2. Recipes keep showing the fluids where NTM puts them.
 */
final class NtmFluids {

    private static final String NAMESPACE = "hbm";
    private static Object seenRuntime;

    private NtmFluids() {}

    @SubscribeEvent
    static void onTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        IJeiRuntime rt = Calculator.runtime;
        if (rt == seenRuntime) return;
        seenRuntime = rt;
        if (rt == null) return;
        try {
            hide(rt.getIngredientManager(), rt.getJeiHelpers()
                .getPlatformFluidHelper());
        } catch (RuntimeException | LinkageError e) {
            NeiAddon.LOGGER.warn("Could not take NTM's duplicate fluids out of JEI's item list", e);
        }
    }

    private static <T> void hide(IIngredientManager manager, IPlatformFluidHelper<T> helper) {
        boolean droplets = false;
        for (ItemStack stack : manager.getAllIngredients(VanillaTypes.ITEM_STACK)) {
            if ("hbm:fluid_icon".equals(BuiltInRegistries.ITEM.getKey(stack.getItem())
                .toString())) {
                droplets = true;
                break;
            }
        }
        if (!droplets) return;
        IIngredientTypeWithSubtypes<Fluid, T> type = helper.getFluidIngredientType();
        List<T> twins = new ArrayList<>();
        for (T fluid : manager.getAllIngredients(type)) {
            if (NAMESPACE.equals(BuiltInRegistries.FLUID.getKey(type.getBase(fluid))
                .getNamespace())) twins.add(fluid);
        }
        if (twins.isEmpty()) return;
        manager.removeIngredientsAtRuntime(type, twins);
        NeiAddon.LOGGER.info("Took {} NTM fluids out of JEI's item list (NTM's droplet items stand for them)", twins.size());
    }
}

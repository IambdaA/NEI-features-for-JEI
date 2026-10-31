package neiaddon;

import java.util.HashMap;
import java.util.Map;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.gui.drawable.IDrawable;
import mezz.jei.api.recipe.IFocus;
import mezz.jei.api.recipe.IFocusFactory;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.runtime.IIngredientManager;
import mezz.jei.api.runtime.IJeiRuntime;
import java.util.List;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.TooltipFlag;
import mezz.jei.api.helpers.IGuiHelper;
import mezz.jei.api.helpers.IPlatformFluidHelper;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;

/**
 * Fluids in the bookmark model. Everything the addon stores and calculates is keyed by an item id string and carried as
 * an {@link ItemStack}; a fluid rides along as a <em>carrier</em> stack (a bucket with the fluid's id in its custom data
 * and the fluid's name as its item name) and the id {@code fluid:namespace:path}. So saving, the chain maths and the
 * panels need no second code path; only the places that draw a stack, look an id up in the item registry or ask JEI
 * about a stack have to know about this class.
 *
 * <p>
 * The amount of a fluid is counted in millibuckets (1000 = one bucket).
 */
public final class FluidStacks {

    public static final String PREFIX = "fluid:";
    private static final String TAG = "neiaddon_fluid";

    private FluidStacks() {}

    public static boolean isFluidId(String id) {
        return id != null && id.startsWith(PREFIX);
    }

    public static boolean isFluid(ItemStack stack) {
        return fluidIdOf(stack) != null;
    }

    /** {@code namespace:path} of the fluid a carrier stack stands for, or null for anything else. */
    public static String fluidIdOf(ItemStack stack) {
        if (stack.isEmpty() || !stack.is(Items.BUCKET)) return null;
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) return null;
        String id = data.copyTag()
            .getString(TAG);
        return id.isEmpty() ? null : id;
    }

    /** The item id string used everywhere in the model: {@code fluid:ns:path}. */
    public static String itemIdOf(ItemStack stack) {
        String id = fluidIdOf(stack);
        return id == null ? null : PREFIX + id;
    }

    public static ItemStack carrier(ResourceLocation fluidId) {
        ItemStack stack = new ItemStack(Items.BUCKET);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.EMPTY.update(tag -> tag.putString(TAG, fluidId.toString())));
        stack.set(DataComponents.ITEM_NAME, nameOf(fluidId));
        return stack;
    }

    /**
     * The stack for an id of the model: a carrier for {@code fluid:ns:path} (when that fluid exists), else the item of
     * that registry id; {@link ItemStack#EMPTY} when it is unknown (the mod is not installed).
     */
    public static ItemStack stackOf(String id) {
        try {
            if (isFluidId(id)) {
                ResourceLocation fluid = ResourceLocation.parse(id.substring(PREFIX.length()));
                if (BuiltInRegistries.FLUID.getOptional(fluid)
                    .filter(f -> f != Fluids.EMPTY)
                    .isEmpty()) return ItemStack.EMPTY;
                return carrier(fluid);
            }
            return StackIds.parse(id);
        } catch (RuntimeException e) {
            return ItemStack.EMPTY;
        }
    }

    /** The fluid's own name where it has a block ("Water"), else its id. */
    private static Component nameOf(ResourceLocation id) {
        try {
            Fluid fluid = BuiltInRegistries.FLUID.getOptional(id)
                .orElse(Fluids.EMPTY);
            var block = fluid.defaultFluidState()
                .createLegacyBlock()
                .getBlock();
            if (block != net.minecraft.world.level.block.Blocks.AIR) return block.getName();
        } catch (RuntimeException ignored) {
            // falls back to the id
        }
        return Component.literal(id.toString());
    }

    // ---------------------------------------------------------------- drawing

    private static final Map<String, IDrawable> drawables = new HashMap<>();

    public static void reset() {
        drawables.clear();
        StackIds.reset();
    }

    /** {@code g.renderFakeItem(stack, x, y)}, or the fluid's picture (as JEI draws it in its list) for a carrier stack. */
    public static void item(GuiGraphics g, ItemStack stack, int x, int y) {
        String id = fluidIdOf(stack);
        if (id != null && Calculator.available()) {
            IDrawable d = drawables.computeIfAbsent(id, FluidStacks::makeDrawable);
            if (d != null) {
                d.draw(g, x, y);
                return;
            }
        }
        g.renderFakeItem(stack, x, y);
    }

    private static IDrawable makeDrawable(String id) {
        try {
            var helpers = Calculator.runtime.getJeiHelpers();
            Holder<Fluid> fluid = BuiltInRegistries.FLUID.getHolder(ResourceLocation.parse(id))
                .map(h -> (Holder<Fluid>) h)
                .orElse(null);
            if (fluid == null) return null;
            return drawable(helpers.getPlatformFluidHelper(), helpers.getGuiHelper(), fluid);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static <T> IDrawable drawable(IPlatformFluidHelper<T> helper, IGuiHelper gui, Holder<Fluid> fluid) {
        return gui.createDrawableIngredient(helper.getFluidIngredientType(), helper.create(fluid, helper.bucketVolume()));
    }

    // ---------------------------------------------------------------- names and tooltips

    /** The stack's name: a fluid's own display name as JEI shows it ("Petroleum Gas"), else the item's hover name. */
    public static Component name(ItemStack stack) {
        String id = fluidIdOf(stack);
        if (id != null && Calculator.available()) {
            try {
                Holder<Fluid> fluid = fluidHolder(id);
                if (fluid != null) {
                    var helpers = Calculator.runtime.getJeiHelpers();
                    return Component.literal(
                        displayName(helpers.getPlatformFluidHelper(), Calculator.runtime.getIngredientManager(), fluid));
                }
            } catch (RuntimeException ignored) {
                // falls back to the stored name
            }
        }
        return stack.getHoverName();
    }

    private static <T> String displayName(IPlatformFluidHelper<T> helper, IIngredientManager manager, Holder<Fluid> fluid) {
        return manager.getIngredientHelper(helper.getFluidIngredientType())
            .getDisplayName(helper.create(fluid, helper.bucketVolume()));
    }

    /**
     * The tooltip lines of a stack: for a fluid what JEI shows for it (its traits, then the amount in millibuckets),
     * else the item's own tooltip.
     */
    public static List<Component> tooltip(ItemStack stack, long milliBuckets, Item.TooltipContext context, Player player,
        TooltipFlag flag) {
        String id = fluidIdOf(stack);
        if (id != null && Calculator.available()) {
            try {
                Holder<Fluid> fluid = fluidHolder(id);
                if (fluid != null) {
                    var helpers = Calculator.runtime.getJeiHelpers();
                    return fluidTooltip(helpers.getPlatformFluidHelper(), Calculator.runtime.getIngredientManager(), fluid,
                        Math.max(1, milliBuckets), flag);
                }
            } catch (RuntimeException e) {
                NeiAddon.LOGGER.debug("Could not build the tooltip of fluid {}", id, e);
            }
        }
        return stack.getTooltipLines(context, player, flag);
    }

    @SuppressWarnings("deprecation")
    private static <T> List<Component> fluidTooltip(IPlatformFluidHelper<T> helper, IIngredientManager manager,
        Holder<Fluid> fluid, long milliBuckets, TooltipFlag flag) {
        long units = Math.max(1, helper.bucketVolume() / 1000) * milliBuckets;
        return manager.getIngredientRenderer(helper.getFluidIngredientType())
            .getTooltip(helper.create(fluid, units), flag);
    }

    private static Holder<Fluid> fluidHolder(String id) {
        return BuiltInRegistries.FLUID.getHolder(ResourceLocation.parse(id))
            .map(h -> (Holder<Fluid>) h)
            .orElse(null);
    }

    /** The namespace the item or fluid comes from. */
    public static String namespaceOf(ItemStack stack) {
        String id = fluidIdOf(stack);
        if (id != null) return ResourceLocation.parse(id)
            .getNamespace();
        return BuiltInRegistries.ITEM.getKey(stack.getItem())
            .getNamespace();
    }

    /** A JEI focus on this stack: the fluid for a carrier stack, the item otherwise. */
    public static IFocus<?> focus(IJeiRuntime rt, RecipeIngredientRole role, ItemStack stack) {
        var helpers = rt.getJeiHelpers();
        String id = fluidIdOf(stack);
        if (id != null) {
            Holder<Fluid> fluid = BuiltInRegistries.FLUID.getHolder(ResourceLocation.parse(id))
                .map(h -> (Holder<Fluid>) h)
                .orElse(null);
            if (fluid != null) return fluidFocus(helpers.getPlatformFluidHelper(), helpers.getFocusFactory(), role, fluid);
        }
        return helpers.getFocusFactory()
            .createFocus(role, VanillaTypes.ITEM_STACK, stack);
    }

    private static <T> IFocus<T> fluidFocus(IPlatformFluidHelper<T> helper, IFocusFactory factory, RecipeIngredientRole role,
        Holder<Fluid> fluid) {
        return factory.createFocus(role, helper.getFluidIngredientType(), helper.create(fluid, helper.bucketVolume()));
    }

    /** Whether this id can be handled by the item-only features (autocraft, pull, chat links of items). */
    public static boolean usable(String id) {
        return !isFluidId(id);
    }
}

package neiaddon;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import mezz.jei.api.gui.IRecipeLayoutDrawable;
import mezz.jei.api.gui.ingredient.IRecipeSlotView;
import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import mezz.jei.api.helpers.IPlatformFluidHelper;
import neiaddon.platform.ClientPlatform;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.category.IRecipeCategory;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

/**
 * A recipe as read from a JEI recipe layout: its input and output slots, each with every item it accepts (tags) and the
 * count of each. The tree builder picks the {@linkplain Slot#active active} item of each input slot, then
 * {@link #toData} turns the shape into what the bookmark panel stores.
 *
 * <p>
 * Only INPUT and OUTPUT slots count; catalysts, fuel and render-only slots are not real inputs and are skipped.
 */
public final class RecipeShape {

    public record Alt(ItemStack stack, int count) {

        public String id() {
            return RecipeData.idOf(stack);
        }
    }

    public static final class Slot {

        public final List<Alt> alts;
        public int active;

        Slot(List<Alt> alts) {
            this.alts = alts;
        }

        public Alt chosen() {
            return alts.get(Math.max(0, Math.min(active, alts.size() - 1)));
        }

        public int indexOf(String itemId) {
            for (int i = 0; i < alts.size(); i++) {
                if (alts.get(i)
                    .id()
                    .equals(itemId)) return i;
            }
            return -1;
        }
    }

    public final String id;
    public final List<Slot> inputs = new ArrayList<>();
    public final List<Slot> outputs = new ArrayList<>();
    /** The result the recipe tree needs from this recipe: listed first by {@link #toData}. Null = the recipe's own order. */
    public String leadResult;

    public RecipeShape(String id) {
        this.id = id;
    }

    public RecipeShape copy() {
        RecipeShape c = new RecipeShape(id);
        c.leadResult = leadResult;
        for (Slot s : inputs) {
            Slot n = new Slot(s.alts);
            n.active = s.active;
            c.inputs.add(n);
        }
        for (Slot s : outputs) {
            Slot n = new Slot(s.alts);
            n.active = s.active;
            c.outputs.add(n);
        }
        return c;
    }

    private static final class Acc {

        final ItemStack stack;
        long factor;
        final Set<String> options = new LinkedHashSet<>();

        Acc(ItemStack stack) {
            this.stack = stack;
        }
    }

    /**
     * Results: one part per distinct item, counts added up. Ingredients: one part per distinct chosen item, the count
     * being the sum over every slot that chose it. {@code onlyResultId} keeps just that result (NEI's single-recipe
     * bookmark); null keeps all of them (recipe trees).
     */
    public RecipeData toData(String onlyResultId) {
        RecipeData data = new RecipeData(id);

        Map<String, Acc> results = new LinkedHashMap<>();
        for (Slot slot : outputs) {
            Alt a = slot.chosen();
            Acc acc = results.computeIfAbsent(a.id(), k -> new Acc(a.stack()));
            acc.factor += a.count();
            acc.options.add(a.id());
        }
        if (leadResult != null && results.containsKey(leadResult)) {
            Map<String, Acc> ordered = new LinkedHashMap<>();
            ordered.put(leadResult, results.remove(leadResult));
            ordered.putAll(results);
            results = ordered;
        }
        if (onlyResultId != null && results.containsKey(onlyResultId)) {
            results.keySet()
                .removeIf(k -> !k.equals(onlyResultId));
        }
        for (Acc acc : results.values()) {
            data.results.add(new RecipeData.Part(acc.stack, acc.factor, new ArrayList<>(acc.options)));
        }

        Map<String, Acc> ingredients = new LinkedHashMap<>();
        for (Slot slot : inputs) {
            Alt a = slot.chosen();
            Acc acc = ingredients.computeIfAbsent(a.id(), k -> new Acc(a.stack()));
            acc.factor += a.count();
            for (Alt alt : slot.alts) acc.options.add(alt.id());
        }
        for (Acc acc : ingredients.values()) {
            data.ingredients.add(new RecipeData.Part(acc.stack, acc.factor, new ArrayList<>(acc.options)));
        }
        return data;
    }

    // ---------------------------------------------------------------- reading a JEI layout

    public static RecipeShape read(IRecipeLayoutDrawable<?> layout) {
        return read(layout.getRecipeSlotsView(), idOf(layout));
    }

    public static RecipeShape read(IRecipeSlotsView view, String id) {
        RecipeShape shape = new RecipeShape(id);
        for (IRecipeSlotView slot : view.getSlotViews()) {
            RecipeIngredientRole role = slot.getRole();
            if (role != RecipeIngredientRole.INPUT && role != RecipeIngredientRole.OUTPUT) continue;
            List<Alt> alts = altsOf(slot);
            if (alts.isEmpty()) continue;
            (role == RecipeIngredientRole.INPUT ? shape.inputs : shape.outputs).add(new Slot(alts));
        }
        return shape;
    }

    /** Every item and fluid the slot accepts, each once. */
    public static List<Alt> altsOf(IRecipeSlotView slot) {
        List<Alt> alts = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (ItemStack s : slot.getItemStacks()
            .toList()) {
            if (s.isEmpty()) continue;
            if (seen.add(RecipeData.idOf(s))) alts.add(new Alt(s.copyWithCount(1), Math.max(1, s.getCount())));
        }
        addFluids(slot, alts, seen);
        return alts;
    }

    /** Fluid ingredients of the slot (JEI's platform fluid type), as carrier stacks counted in millibuckets. */
    private static void addFluids(IRecipeSlotView slot, List<Alt> alts, Set<String> seen) {
        if (!Calculator.available()) return;
        try {
            addFluids(slot, Calculator.runtime.getJeiHelpers()
                .getPlatformFluidHelper(), alts, seen);
        } catch (RuntimeException e) {
            NeiAddon.LOGGER.debug("Could not read the fluids of a recipe slot", e);
        }
    }

    private static <T> void addFluids(IRecipeSlotView slot, IPlatformFluidHelper<T> helper, List<Alt> alts, Set<String> seen) {
        long unitsPerMilliBucket = Math.max(1, helper.bucketVolume() / 1000);
        for (T fluid : slot.getIngredients(helper.getFluidIngredientType())
            .toList()) {
            Fluid base = helper.getFluidIngredientType()
                .getBase(fluid);
            if (base == null || base == Fluids.EMPTY) continue;
            ItemStack carrier = FluidStacks.carrier(BuiltInRegistries.FLUID.getKey(base));
            if (!seen.add(RecipeData.idOf(carrier))) continue;
            long milliBuckets = ClientPlatform.get()
                .fluidAmount(fluid) / unitsPerMilliBucket;
            alts.add(new Alt(carrier, (int) Math.max(1, Math.min(Integer.MAX_VALUE, milliBuckets))));
        }
    }

    // ---------------------------------------------------------------- recipe ids

    /**
     * A stable id for the recipe on screen: {@code recipeTypeUid|registryId} when JEI knows the recipe's identifier,
     * else {@code recipeTypeUid#hash} of its slots. Equal recipes give equal ids, also after a restart.
     */
    public static <R> String idOf(IRecipeLayoutDrawable<R> layout) {
        return idOf(layout.getRecipeCategory(), layout.getRecipe(), layout.getRecipeSlotsView());
    }

    public static <R> String idOf(IRecipeCategory<R> category, R recipe, IRecipeSlotsView slots) {
        String type = category.getRecipeType()
            .getUid()
            .toString();
        Identifier identifier = null;
        try {
            identifier = category.getIdentifier(recipe);
        } catch (RuntimeException ignored) {}
        if (identifier != null) return type + "|" + identifier;
        return type + "#" + hash(slots);
    }

    /**
     * What identifies a non-item ingredient in a recipe id: its registry id (and the amount of a fluid). Recipes whose
     * slots hold only fluids must differ by which fluids and how much, or they would all get the same id.
     */
    private static <V> String describe(ITypedIngredient<V> ing) {
        String type = ing.getType()
            .getIngredientClass()
            .getName();
        if (!Calculator.available()) return type;
        try {
            var helpers = Calculator.runtime.getJeiHelpers();
            String id = String.valueOf(Calculator.runtime.getIngredientManager()
                .getIngredientHelper(ing.getType())
                .getIdentifier(ing.getIngredient()));
            String amount = "";
            if (ing.getType() == helpers.getPlatformFluidHelper()
                .getFluidIngredientType()) {
                amount = "x" + ClientPlatform.get()
                    .fluidAmount(ing.getIngredient());
            }
            return type + ":" + id + amount;
        } catch (RuntimeException e) {
            return type;
        }
    }

    private static String hash(IRecipeSlotsView view) {
        StringBuilder sb = new StringBuilder();
        for (IRecipeSlotView slot : view.getSlotViews()) {
            sb.append(
                slot.getRole()
                    .name())
                .append(':');
            for (ITypedIngredient<?> ing : slot.getAllIngredientsList()) {
                if (ing == null) continue;
                var stack = ing.getItemStack();
                if (stack.isPresent()) {
                    sb.append(RecipeData.idOf(stack.get()))
                        .append('x')
                        .append(stack.get()
                            .getCount());
                } else {
                    sb.append(describe(ing));
                }
                sb.append(',');
            }
            sb.append(';');
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-1")
                .digest(
                    sb.toString()
                        .getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 8; i++) hex.append(String.format("%02x", digest[i]));
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(sb.toString()
                .hashCode());
        }
    }
}

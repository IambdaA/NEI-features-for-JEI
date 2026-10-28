package neiaddon;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.gui.IRecipeLayoutDrawable;
import mezz.jei.api.gui.ingredient.IRecipeSlotView;
import mezz.jei.api.recipe.IFocus;
import mezz.jei.api.recipe.IFocusFactory;
import mezz.jei.api.recipe.IFocusGroup;
import mezz.jei.api.recipe.IRecipeManager;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.api.runtime.IJeiRuntime;
import java.util.Objects;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * NEI-style crafting calculator. Uses JEI's recipe lookup, so it works with any recipe type JEI knows about.
 * Limitations: no leftover sharing between crafts; each ingredient slot uses its first alternative.
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

    /** One ingredient slot of a recipe: the chosen alternative and how many are needed per craft. */
    public record Input(StackKey key, int count) {}

    /** One way to make an item. */
    public record Option(String category, int outPerCraft, List<Input> inputs) {}

    /** A crafting step in the plan. */
    public static final class Step {

        public final StackKey out;
        public final Option option;
        public final int optionIndex;
        public final int optionCount;
        public long crafts;

        Step(StackKey out, Option option, int optionIndex, int optionCount) {
            this.out = out;
            this.option = option;
            this.optionIndex = optionIndex;
            this.optionCount = optionCount;
        }
    }

    public record Plan(List<Step> steps, Map<StackKey, Long> raw) {}

    private static final int MAX_DEPTH = 16;

    public static IJeiRuntime runtime;

    /** Items treated as raw materials regardless of recipes. */
    public static final Set<StackKey> forcedRaw = new HashSet<>();
    /** Chosen recipe index per item (cycled from the UI). */
    public static final Map<StackKey, Integer> choice = new HashMap<>();

    private static final Map<StackKey, List<Option>> optionCache = new HashMap<>();

    private Calculator() {}

    public static void onRuntime(IJeiRuntime rt) {
        runtime = rt;
        optionCache.clear();
        RecipeLookup.reset();
        CraftablesPanel.reset();
    }

    public static boolean available() {
        return runtime != null;
    }

    // ------------------------------------------------------------------ recipe lookup

    private static boolean sameItem(ItemStack a, ItemStack b) {
        return ItemStack.isSameItemSameTags(a, b);
    }

    public static List<Option> options(StackKey key) {
        List<Option> cached = optionCache.get(key);
        if (cached != null) return cached;
        List<Option> result = new ArrayList<>();
        if (runtime != null) {
            try {
                collectOptions(key, result);
            } catch (RuntimeException e) {
                NeiAddon.LOGGER.warn("Recipe lookup failed for {}", key.sample, e);
            }
        }
        // crafting-like categories first, then everything else, stable otherwise
        result.sort((a, b) -> Integer.compare(rank(a.category()), rank(b.category())));
        optionCache.put(key, result);
        return result;
    }

    private static int rank(String category) {
        String c = category.toLowerCase();
        return c.contains("craft") ? 0 : 1;
    }

    private static void collectOptions(StackKey key, List<Option> out) {
        IRecipeManager manager = runtime.getRecipeManager();
        IFocusFactory focusFactory = runtime.getJeiHelpers()
            .getFocusFactory();
        IFocus<ItemStack> focus = focusFactory.createFocus(RecipeIngredientRole.OUTPUT, VanillaTypes.ITEM_STACK, key.sample);
        List<IFocus<?>> focuses = List.of(focus);
        IFocusGroup empty = focusFactory.getEmptyFocusGroup();
        manager.createRecipeCategoryLookup()
            .limitFocus(focuses)
            .get()
            .forEach(category -> collectFromCategory(manager, category, focuses, empty, key, out));
    }

    private static <R> void collectFromCategory(IRecipeManager manager, IRecipeCategory<R> category, List<IFocus<?>> focuses,
        IFocusGroup empty, StackKey key, List<Option> out) {
        String categoryName = category.getTitle()
            .getString();
        manager.createRecipeLookup(category.getRecipeType())
            .limitFocus(focuses)
            .get()
            .forEach(recipe -> {
                var layout = manager.createRecipeLayoutDrawable(category, recipe, empty);
                layout.ifPresent(l -> toOption(l, categoryName, key).ifPresent(out::add));
            });
    }

    private static java.util.Optional<Option> toOption(IRecipeLayoutDrawable<?> layout, String category, StackKey key) {
        var slots = layout.getRecipeSlotsView();
        int perCraft = 0;
        for (IRecipeSlotView slot : slots.getSlotViews(RecipeIngredientRole.OUTPUT)) {
            for (ItemStack alt : slot.getItemStacks()
                .toList()) {
                if (sameItem(alt, key.sample)) {
                    perCraft += Math.max(1, alt.getCount());
                    break;
                }
            }
        }
        if (perCraft <= 0) return java.util.Optional.empty();

        Map<StackKey, Integer> merged = new LinkedHashMap<>();
        for (IRecipeSlotView slot : slots.getSlotViews(RecipeIngredientRole.INPUT)) {
            ItemStack first = slot.getItemStacks()
                .filter(s -> !s.isEmpty())
                .findFirst()
                .orElse(ItemStack.EMPTY);
            if (first.isEmpty()) continue;
            merged.merge(new StackKey(first), Math.max(1, first.getCount()), Integer::sum);
        }
        if (merged.isEmpty() || merged.containsKey(key)) return java.util.Optional.empty();
        List<Input> inputs = new ArrayList<>();
        merged.forEach((k, c) -> inputs.add(new Input(k, c)));
        return java.util.Optional.of(new Option(category, perCraft, inputs));
    }

    // ------------------------------------------------------------------ planning

    public static Plan compute(Map<StackKey, Long> targets) {
        Map<StackKey, Step> steps = new LinkedHashMap<>();
        Map<StackKey, Long> raw = new LinkedHashMap<>();
        for (var entry : targets.entrySet()) {
            expand(entry.getKey(), entry.getValue(), new ArrayDeque<>(), steps, raw, 0);
        }
        // steps were recorded dependencies-first; present final products last, which is craft order
        return new Plan(new ArrayList<>(steps.values()), raw);
    }

    private static void expand(StackKey key, long needed, Deque<StackKey> path, Map<StackKey, Step> steps, Map<StackKey, Long> raw,
        int depth) {
        if (needed <= 0) return;
        List<Option> options = forcedRaw.contains(key) || depth >= MAX_DEPTH || path.contains(key) ? List.of() : options(key);
        if (options.isEmpty()) {
            raw.merge(key, needed, Long::sum);
            return;
        }
        int index = Math.floorMod(choice.getOrDefault(key, 0), options.size());
        Option option = options.get(index);
        long crafts = (needed + option.outPerCraft() - 1) / option.outPerCraft();
        path.push(key);
        for (Input input : option.inputs()) {
            expand(input.key(), crafts * input.count(), path, steps, raw, depth + 1);
        }
        path.pop();
        Step step = steps.get(key);
        if (step == null) {
            step = new Step(key, option, index, options.size());
            steps.put(key, step);
        }
        step.crafts += crafts;
    }

    public static void cycleRecipe(StackKey key) {
        choice.merge(key, 1, Integer::sum);
    }

    public static void toggleRaw(StackKey key) {
        if (!forcedRaw.remove(key)) forcedRaw.add(key);
    }

    public static void resetChoices() {
        forcedRaw.clear();
        choice.clear();
    }
}

package neiaddon;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import mezz.jei.api.gui.IRecipeLayoutDrawable;
import mezz.jei.api.gui.ingredient.IRecipeSlotView;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.world.item.ItemStack;
import neiaddon.search.SearchQuery;

/**
 * Turns one JEI recipe into something {@link SearchQuery} can test: its slots (ingredients, results, catalysts and
 * everything each slot accepts), read from the layout JEI would draw. Only items are read; fluid and other slots are
 * left out.
 */
public final class RecipeSearch {

    private static final int MAX_ALTERNATIVES = 32;

    /** One indexed recipe. */
    public static final class Entry implements SearchQuery.Recipe {

        private final List<List<SearchQuery.Item>> in = new ArrayList<>();
        private final List<List<SearchQuery.Item>> out = new ArrayList<>();
        private final List<List<SearchQuery.Item>> other = new ArrayList<>();

        @Override
        public List<List<SearchQuery.Item>> ingredients() {
            return in;
        }

        @Override
        public List<List<SearchQuery.Item>> results() {
            return out;
        }

        @Override
        public List<List<SearchQuery.Item>> others() {
            return other;
        }
    }

    private RecipeSearch() {}

    /** The searchable form of a recipe, or null when it has no item slots (or JEI cannot lay it out). */
    public static Entry build(IJeiRuntime rt, IRecipeCategory<?> category, Object recipe) {
        return typed(rt, category, recipe);
    }

    @SuppressWarnings("unchecked")
    private static <R> Entry typed(IJeiRuntime rt, IRecipeCategory<R> category, Object recipe) {
        IRecipeLayoutDrawable<?> layout = rt.getRecipeManager()
            .createRecipeLayoutDrawable(
                category,
                (R) recipe,
                rt.getJeiHelpers()
                    .getFocusFactory()
                    .getEmptyFocusGroup())
            .orElse(null);
        if (layout == null) return null;
        Entry e = new Entry();
        for (IRecipeSlotView slot : layout.getRecipeSlotsView()
            .getSlotViews()) {
            RecipeIngredientRole role = slot.getRole();
            List<SearchQuery.Item> alts = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (ItemStack s : slot.getItemStacks()
                .toList()) {
                if (s.isEmpty() || alts.size() >= MAX_ALTERNATIVES || !seen.add(RecipeData.idOf(s))) continue;
                alts.add(SearchItem.of(s));
            }
            if (alts.isEmpty()) continue;
            if (role == RecipeIngredientRole.INPUT) e.in.add(alts);
            else if (role == RecipeIngredientRole.OUTPUT) e.out.add(alts);
            else if (!role.name().equals("RENDER_ONLY")) e.other.add(alts); // catalysts: JEI renamed the constant between versions
        }
        return e.in.isEmpty() && e.out.isEmpty() && e.other.isEmpty() ? null : e;
    }
}

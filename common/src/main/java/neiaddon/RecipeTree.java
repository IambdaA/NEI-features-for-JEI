package neiaddon;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Builds a recipe tree like NEI's {@code GuiFavoriteButton#getRecipesTree}: the recipe, then the favorite recipe of each
 * of its ingredients, then theirs, level by level up to a depth limit. Nothing is searched for automatically; the
 * player decides which recipe makes an ingredient by setting favorites.
 *
 * <p>
 * Ported from GTNH NotEnoughItems (LGPL-3.0-or-later).
 */
public final class RecipeTree {

    /** NEI's {@code inventory.favorites.depth} default. */
    public static final int DEPTH = 9;

    private RecipeTree() {}

    /**
     * @return the recipes in row order (top recipe first, then level by level). The {@linkplain RecipeShape.Slot#active
     *         active} item of every input slot is set to the item the tree chose for it.
     */
    public static List<RecipeShape> build(RecipeShape main, int depth) {
        Set<String> seen = new HashSet<>();
        List<RecipeShape> result = new ArrayList<>();
        List<RecipeShape> loop = new ArrayList<>();
        loop.add(main);
        result.add(main);

        while (!loop.isEmpty() && depth-- >= 0) {
            List<RecipeShape> next = new ArrayList<>();

            for (RecipeShape recipe : loop) {
                for (RecipeShape.Slot slot : recipe.inputs) {
                    RecipeShape.Alt chosen = null;
                    for (RecipeShape.Alt alt : slot.alts) {
                        if (seen.contains(alt.id())) {
                            chosen = alt;
                            break;
                        }
                    }

                    if (chosen == null) {
                        chosen = slot.alts.get(0);
                        String recipeId = Favorites.recipeFor(chosen.id());

                        if (recipeId == null) {
                            for (RecipeShape.Alt alt : slot.alts) {
                                recipeId = Favorites.recipeFor(alt.id());
                                if (recipeId != null) {
                                    chosen = alt;
                                    break;
                                }
                            }
                        }

                        RecipeShape sub = recipeId == null ? null : RecipeLookup.find(recipeId, chosen.stack());
                        if (sub == null) {
                            chosen = null;
                        } else {
                            seen.add(chosen.id());
                            if (depth >= 0) {
                                result.add(sub);
                                next.add(sub);
                            }
                        }
                    }

                    if (chosen != null) {
                        slot.active = Math.max(0, slot.indexOf(chosen.id()));
                    }
                }
            }

            loop = next;
        }

        return result;
    }

    /**
     * NEI's {@code saveRecipeInBookmark} for a recipe tree: saves the tree as one new group of the active bookmark
     * page, in todo-list view with the crafting chain on.
     *
     * @return the new group id, or 0 if nothing was saved
     */
    public static int save(RecipeShape main) {
        List<RecipeData> data = new ArrayList<>();
        for (RecipeShape shape : build(main.copy(), DEPTH)) {
            data.add(shape.toData(null));
        }
        return Bookmarks.addGroup(data, true, true);
    }
}

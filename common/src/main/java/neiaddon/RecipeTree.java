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

    private RecipeTree() {}

    /**
     * @return the recipes in row order (top recipe first, then level by level). The {@linkplain RecipeShape.Slot#active
     *         active} item of every input slot is set to the item the tree chose for it.
     */
    public static List<RecipeShape> build(RecipeShape main, int depth) {
        return build(main, depth, null);
    }

    /**
     * As {@link #build(RecipeShape, int)}, but a slot of the refreshed group that has already picked one of the items
     * its tag accepts keeps that item ({@code picks}, the group's own ingredient entries, each used once; see
     * {@link Bookmarks#slotPicks}). The pick comes before the tag default and the favorites (the 2026-11-18 decision
     * order): a favorite may set a slot's default, it never overwrites a pick. #39.
     */
    public static List<RecipeShape> build(RecipeShape main, int depth, List<Bookmarks.Entry> picks) {
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
                    boolean kept = false;
                    // the kept entry was the player's own pick (a favourite's choice is kept too, but is no pick)
                    boolean keptPicked = false;
                    boolean tagDefault = false;

                    // #39: the group's saved pick for this slot stands before seen alts, the tag default
                    // and the favorites. Stale when the live recipe no longer takes exactly the items the entry
                    // lists (or no longer names the picked one): such a slot resolves fresh, as if unpicked.
                    if (picks != null) {
                        List<String> accepted = slot.alts.stream()
                            .map(RecipeShape.Alt::id)
                            .toList();
                        for (int i = 0; i < picks.size(); i++) {
                            Bookmarks.Entry pick = picks.get(i);
                            if (!Favorites.sameRecipe(recipe.id, pick.recipeId) || !new HashSet<>(pick.options).equals(
                                new HashSet<>(accepted))) continue;
                            int at = slot.indexOf(pick.itemId());
                            if (at < 0) continue;
                            chosen = slot.alts.get(at);
                            kept = true;
                            keptPicked = pick.picked;
                            picks.remove(i);
                            break;
                        }
                    }

                    if (chosen == null) {
                        for (RecipeShape.Alt alt : slot.alts) {
                            if (seen.contains(alt.id())) {
                                chosen = alt;
                                break;
                            }
                        }
                    }

                    if (chosen == null) {
                        // #39: the default saved for the tag comes before the favorite, so it can override
                        // a favorited recipe; a default naming an item this slot no longer accepts is ignored
                        String preferred = TagDefaults.defaultFor(slot.alts.stream()
                            .map(RecipeShape.Alt::id)
                            .toList());
                        RecipeShape.Alt wanted = preferred == null ? null : slot.alts.stream()
                            .filter(a -> preferred.equals(a.id()))
                            .findFirst()
                            .orElse(null);
                        chosen = wanted != null ? wanted : slot.alts.get(0);
                        // the saved tag default is the player's pick (#39); a favorite's choice is not
                        tagDefault = wanted != null && slot.alts.size() > 1;
                        String recipeId = Favorites.recipeFor(chosen.id(), chosen.stack());

                        if (recipeId == null && wanted == null) {
                            for (RecipeShape.Alt alt : slot.alts) {
                                recipeId = Favorites.recipeFor(alt.id(), alt.stack());
                                if (recipeId != null) {
                                    chosen = alt;
                                    break;
                                }
                            }
                        }

                        RecipeShape sub = recipeId == null ? null : RecipeLookup.find(recipeId, chosen.stack());
                        NeiAddon.LOGGER.debug(
                            "Recipe tree: recipe {} slot {} -> {} favorite={} found={}",
                            recipe.id,
                            slot.alts.stream()
                                .map(RecipeShape.Alt::id)
                                .toList(),
                            chosen.id(),
                            recipeId,
                            sub != null);
                        if (sub == null) {
                            // the tag default stands without a favorite recipe; only a favorite-chosen alt is dropped
                            if (wanted == null) chosen = null;
                        } else {
                            seen.add(chosen.id());
                            // the result the parent recipe needs comes first in the sub-recipe's row
                            sub.leadResult = chosen.id();
                            if (depth >= 0) {
                                result.add(sub);
                                next.add(sub);
                            }
                        }
                    } else if (kept) {
                        // #39: the picked item stands whatever the favorites say; when the picked item
                        // has a favorited recipe of its own, that recipe nests under the slot, but no favorite for
                        // it discards the pick (unlike a favorite-chosen alt, which is chosen only for its recipe)
                        String recipeId = Favorites.recipeFor(chosen.id(), chosen.stack());
                        RecipeShape sub = recipeId == null ? null : RecipeLookup.find(recipeId, chosen.stack());
                        NeiAddon.LOGGER.debug(
                            "Recipe tree: recipe {} slot {} -> {} favorite={} found={} (kept pick)",
                            recipe.id,
                            slot.alts.stream()
                                .map(RecipeShape.Alt::id)
                                .toList(),
                            chosen.id(),
                            recipeId,
                            sub != null);
                        if (sub != null) {
                            seen.add(chosen.id());
                            sub.leadResult = chosen.id();
                            if (depth >= 0) {
                                result.add(sub);
                                next.add(sub);
                            }
                        }
                    }

                    if (chosen != null) {
                        slot.active = Math.max(0, slot.indexOf(chosen.id()));
                        slot.picked = keptPicked || tagDefault;
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
        return Bookmarks.addGroup(rows(main), true, true);
    }

    /**
     * The tree key over a heart (T, or Shift+T): builds the tree from the current favorites, as {@link #save}, except that
     * a crafting-chain group of the active page whose top recipe is this one is refreshed in place
     * ({@link Bookmarks#replaceGroup}: same group, same place, its top recipe keeps its run count).
     *
     * <p>#39: a refreshed group keeps the item its tag slots already picked (the chooser, the wheel):
     * each slot resolves already-chosen first, then the tag default, then the favorites; a pick the live recipe no
     * longer accepts is stale and resolves fresh.</p>
     *
     * @return the group id, or 0 if nothing was saved
     */
    public static int saveOrReplace(RecipeShape main) {
        Bookmarks.Page page = Bookmarks.page();
        int gid = Bookmarks.treeGroupOf(page, main.id);
        if (gid == Bookmarks.NO_GROUP) return Bookmarks.addGroup(rows(main), true, true);
        return Bookmarks.replaceGroup(page, gid, rows(main, Bookmarks.slotPicks(page, gid)));
    }

    /** The rows of the recipe tree, top recipe first. */
    private static List<RecipeData> rows(RecipeShape main) {
        return rows(main, null);
    }

    /** As {@link #rows(RecipeShape)}, keeping the group's picked tag-slot items ({@code picks}; issue #39). */
    private static List<RecipeData> rows(RecipeShape main, List<Bookmarks.Entry> picks) {
        List<RecipeData> data = new ArrayList<>();
        for (RecipeShape shape : build(main.copy(), AddonConfig.FAVORITES_DEPTH.get(), picks)) {
            // a recipe the tree pulls in for one of its fluids or items shows only that result, not its byproducts
            data.add(shape.toData(shape.leadResult));
        }
        return data;
    }
}

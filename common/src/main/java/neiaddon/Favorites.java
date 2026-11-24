package neiaddon;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import neiaddon.platform.Platform;
import net.minecraft.world.item.ItemStack;

/**
 * NEI's {@code FavoriteRecipes} (manual favorites only): one favorite recipe per result item.
 * Saved to {@code config/neiaddon/favorites.txt}, one {@code itemId <tab> recipeId} per line. Lines of items or
 * recipes that are not installed are kept.
 *
 * <p>
 * With EMI installed, a default recipe the player set in EMI (EMI's own "Set as default recipe" button; EMI's built-in
 * defaults do not count) is the item's favorite too. It is read first. Each favorite is stored in one place: in EMI when
 * the recipe is an EMI recipe and EMI is ready, else in the favorites file. Reading never writes either store.
 *
 * <p>
 * Ported from GTNH NotEnoughItems (LGPL-3.0-or-later).
 */
public final class Favorites {

    private static final Map<String, String> byItem = new LinkedHashMap<>();
    /**
     * EMI's player-set default recipe of each item, looked up once per client tick (see {@link #clearEmiLookups}); an empty
     * string for none.
     */
    private static final Map<String, String> emiThisTick = new HashMap<>();
    private static boolean loaded;

    private Favorites() {}

    private static Path file() {
        return Platform.get().configDir()
            .resolve("neiaddon")
            .resolve("favorites.txt");
    }

    private static void ensureLoaded() {
        if (loaded) return;
        loaded = true;
        try {
            Path file = file();
            if (!Files.exists(file)) return;
            for (String line : Files.readAllLines(file)) {
                String[] p = line.split("\t");
                if (p.length >= 2 && !p[0].isEmpty() && !p[1].isEmpty() && !line.startsWith("#")) byItem.put(p[0], p[1]);
            }
        } catch (IOException | RuntimeException e) {
            NeiAddon.LOGGER.warn("Could not read favorites", e);
        }
    }

    /** The item ids that have a favorite recipe saved in the addon's own file (a copy; reads only the file). */
    static java.util.Set<String> itemIds() {
        ensureLoaded();
        return new java.util.HashSet<>(byItem.keySet());
    }

    /** Forgets the EMI lookups of the last client tick (called once per tick, and on every change of a favorite). */
    public static void clearEmiLookups() {
        emiThisTick.clear();
    }

    /**
     * Whether two recipe ids name the same recipe: equal, or both identifier ids ({@code type|id}) with the same id part
     * (the rule {@code EmiBridge} uses to find a recipe by its id).
     */
    public static boolean sameRecipe(String a, String b) {
        if (a == null || b == null) return false;
        if (a.equals(b)) return true;
        String ra = identifierPart(a);
        return ra != null && ra.equals(identifierPart(b));
    }

    /** The part after the bar of an identifier id ({@code type|id}), or null for any other id. */
    private static String identifierPart(String id) {
        int bar = id.indexOf('|');
        int hash = id.indexOf('#');
        return bar >= 0 && (hash < 0 || bar < hash) ? id.substring(bar + 1) : null;
    }

    /**
     * The favorite recipe id of an item (registry id), or null. EMI's player-set default comes first when EMI has one. A
     * favorite that is a page which only shows information (saved before that was ruled out) is ignored: null here, and the
     * entry stays in the file, unchanged.
     */
    public static String recipeFor(String itemId) {
        ensureLoaded();
        String emi = emiDefault(itemId);
        if (emi != null && !RecipeShape.infoOnly(emi)) return emi;
        String saved = byItem.get(itemId);
        return saved == null || RecipeShape.infoOnly(saved) ? null : saved;
    }

    /** EMI's player-set default recipe of the item, once per tick; null when there is none or EMI is not ready. Never writes. */
    private static String emiDefault(String itemId) {
        if (!EmiCompat.present()) return null;
        String known = emiThisTick.get(itemId);
        if (known != null) return known.isEmpty() ? null : known;
        if (!EmiCompat.defaultsReady()) return null;
        ItemStack stack = FluidStacks.stackOf(itemId);
        String recipe = stack.isEmpty() ? null : EmiCompat.defaultRecipe(stack);
        emiThisTick.put(itemId, recipe == null ? "" : recipe);
        return recipe;
    }

    /**
     * The favorite recipe of an item for the recipe tree and the keys: the one set by hand (EMI's default first, see
     * {@link #recipeFor(String)}), else, when generated favorites are on, the only recipe that makes the item. {@code stack}
     * is the item (for the search).
     */
    public static String recipeFor(String itemId, ItemStack stack) {
        String manual = recipeFor(itemId);
        if (manual != null || stack == null || !AddonConfig.FAVORITES_AUTO.get()) return manual;
        return RecipeLookup.onlyRecipeFor(stack);
    }

    /**
     * The item a recipe is the favorite of, or null. An item counts only when {@link #recipeFor} of it is this recipe, so
     * this agrees with the heart and the keys.
     */
    public static String itemFor(String recipeId) {
        ensureLoaded();
        String emi = EmiCompat.defaultItemOf(recipeId);
        if (emi != null && sameRecipe(recipeFor(emi), recipeId)) return emi;
        for (Map.Entry<String, String> e : byItem.entrySet()) {
            if (sameRecipe(e.getValue(), recipeId) && sameRecipe(recipeFor(e.getKey()), recipeId)) return e.getKey();
        }
        return null;
    }

    /**
     * NEI's {@code setFavorite}. A recipe that is an EMI recipe, with EMI ready, becomes EMI's player-set default of the
     * item, and the item's favorites-file entry goes. Otherwise the favorites file gets the entry, and an EMI player-set
     * default of the item (if any, EMI ready) is removed, as it would hide the entry. The other items that had this recipe
     * lose it: their favorites-file entries always, and their EMI player-set defaults for it. A null recipe removes the
     * item's favorite from both stores. EMI is never touched while it loads.
     */
    public static void set(String itemId, String recipeId) {
        ensureLoaded();
        // while EMI loads its defaults can't be read or changed; a change now would split the two stores
        if (EmiCompat.present() && EmiCompat.loading()) {
            NeiAddon.LOGGER.info("Favorite of {} not changed: EMI is still loading its recipes", itemId);
            return;
        }
        clearEmiLookups();
        ItemStack item = FluidStacks.stackOf(itemId);
        boolean ready = EmiCompat.defaultsReady();
        if (recipeId == null) {
            byItem.remove(itemId);
            if (ready && !item.isEmpty()) EmiCompat.clearDefault(item, null);
        } else {
            // NEI: one favorite per recipe, so the other items with this recipe lose it
            List<String> others = new ArrayList<>();
            for (Map.Entry<String, String> e : byItem.entrySet()) {
                if (sameRecipe(e.getValue(), recipeId) && !e.getKey().equals(itemId)) others.add(e.getKey());
            }
            for (String other : others) byItem.remove(other);
            if (ready) EmiCompat.clearOtherDefaults(recipeId, itemId);
            byItem.remove(itemId);
            if (!(ready && EmiCompat.setDefault(item, recipeId, item))) {
                byItem.put(itemId, recipeId);
                if (ready) EmiCompat.clearDefault(item, null);
            }
        }
        save();
    }

    private static void save() {
        List<String> lines = new ArrayList<>();
        byItem.forEach((item, recipe) -> lines.add(item + "\t" + recipe.replace('\t', ' ')));
        try {
            Path file = file();
            Files.createDirectories(file.getParent());
            Files.write(file, lines);
        } catch (IOException e) {
            NeiAddon.LOGGER.warn("Could not save favorites", e);
        }
    }
}

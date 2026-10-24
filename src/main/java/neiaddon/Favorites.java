package neiaddon;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.neoforged.fml.loading.FMLPaths;

/**
 * NEI's {@code FavoriteRecipes} (manual favorites only): one favorite recipe per result item.
 * Saved to {@code config/neiaddon/favorites.txt}, one {@code itemId <tab> recipeId} per line. Lines of items or
 * recipes that are not installed are kept.
 *
 * <p>
 * Ported from GTNH NotEnoughItems (LGPL-3.0-or-later).
 */
public final class Favorites {

    private static final Map<String, String> byItem = new LinkedHashMap<>();
    private static boolean loaded;

    private Favorites() {}

    private static Path file() {
        return FMLPaths.CONFIGDIR.get()
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

    /** The favorite recipe id of an item (registry id), or null. */
    public static String recipeFor(String itemId) {
        ensureLoaded();
        return byItem.get(itemId);
    }

    /** The item a recipe is the favorite of, or null. */
    public static String itemFor(String recipeId) {
        ensureLoaded();
        for (Map.Entry<String, String> e : byItem.entrySet()) {
            if (e.getValue()
                .equals(recipeId)) return e.getKey();
        }
        return null;
    }

    public static boolean isFavorite(String recipeId) {
        return itemFor(recipeId) != null;
    }

    /**
     * NEI's {@code setFavorite}: removes the item's old favorite and any other item using the same recipe, then
     * stores the new one. A null recipe id just removes the item's favorite.
     */
    public static void set(String itemId, String recipeId) {
        ensureLoaded();
        byItem.remove(itemId);
        if (recipeId != null) {
            byItem.values()
                .removeIf(recipeId::equals);
            byItem.put(itemId, recipeId);
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

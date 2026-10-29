package neiaddon;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.world.item.ItemStack;

/**
 * Reads the player's JEI bookmarks. JEI's public API cannot list bookmarks, so this reaches into JEI's internal
 * {@code BookmarkManager -> BookmarkList -> bookmarksList} by reflection. If JEI changes these internals the import
 * fails gracefully (returns empty) and the manual "add hovered item" key keeps working.
 */
public final class BookmarkImport {

    private BookmarkImport() {}

    /** @return the bookmarked item stacks in bookmark order; empty if unavailable. */
    public static List<ItemStack> read(IJeiRuntime runtime) {
        List<ItemStack> result = new ArrayList<>();
        if (runtime == null) return result;
        try {
            Object manager = runtime.getBookmarkManager();
            Object list = field(manager, "bookmarkList");
            Object raw = field(list, "bookmarksList");
            if (!(raw instanceof List<?> bookmarks)) return result;
            for (Object bookmark : new ArrayList<>(bookmarks)) {
                bookmarkStack(bookmark).ifPresent(result::add);
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            NeiAddon.LOGGER.warn("Could not read JEI bookmarks (JEI internals changed?): {}", e.toString());
        }
        return result;
    }

    private static Optional<ItemStack> bookmarkStack(Object bookmark) throws ReflectiveOperationException {
        String type = bookmark.getClass()
            .getSimpleName();
        Object typed;
        if (type.equals("IngredientBookmark")) {
            typed = call(bookmark, "getIngredient");
        } else if (type.equals("RecipeBookmark")) {
            // a bookmarked recipe: use its output (what the player wants to craft); ignore input-displayed ones
            if (!(call(bookmark, "isDisplayIsOutput") instanceof Boolean output) || !output) return Optional.empty();
            typed = call(bookmark, "getDisplayIngredient");
        } else {
            return Optional.empty();
        }
        if (typed instanceof ITypedIngredient<?> ingredient) {
            return ingredient.getItemStack()
                .filter(s -> !s.isEmpty());
        }
        return Optional.empty();
    }

    private static Object field(Object target, String name) throws ReflectiveOperationException {
        for (Class<?> c = target.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(target);
            } catch (NoSuchFieldException ignored) {
                // try superclass
            }
        }
        throw new NoSuchFieldException(name);
    }

    private static Object call(Object target, String name) throws ReflectiveOperationException {
        Method m = target.getClass()
            .getMethod(name);
        m.setAccessible(true);
        return m.invoke(target);
    }

    /** Adds every JEI-bookmarked item not already in the group (amount 1). @return how many were added. */
    public static int importInto(Bookmarks.Group group) {
        int added = 0;
        for (ItemStack stack : read(Calculator.runtime)) {
            Calculator.StackKey key = new Calculator.StackKey(stack);
            if (!group.items.containsKey(key)) {
                group.items.put(key, 1L);
                added++;
            }
        }
        if (added > 0) group.commit();
        return added;
    }
}

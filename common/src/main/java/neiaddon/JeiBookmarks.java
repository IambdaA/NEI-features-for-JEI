package neiaddon;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import mezz.jei.api.gui.IRecipeLayoutDrawable;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.recipe.IRecipeManager;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import neiaddon.event.ClientTickEvent;
import neiaddon.event.EventPriority;
import neiaddon.event.ScreenEvent;
import neiaddon.event.SubscribeEvent;

/**
 * Sends what JEI's own bookmark button (the one on every recipe) and JEI's bookmark key add to JEI's bookmark list into
 * this addon's panel instead: a recipe bookmark becomes a saved recipe (like Shift + the bookmarks key over a recipe), an
 * item bookmark a plain bookmark, and JEI's own list is left empty. Pressing JEI's button for a recipe that the panel
 * already has takes it out again, so the button still toggles.
 *
 * <p>
 * JEI has no API for this, so its bookmark list is found by reflection below the runtime (by the class name
 * {@code BookmarkList}) and polled every tick, and again right after each click or key press so that JEI's own list is
 * never drawn with the new entry in it. If that does not work (a different JEI) one warning is logged and JEI keeps its own
 * list, as before. A bookmark that cannot be turned into one of ours (a recipe that no longer exists) is left where it is.
 */
public final class JeiBookmarks {

    private static boolean failed;
    private static Object seenRuntime;
    private static Object list;
    private static Field backing;
    private static final Set<Object> skipped = Collections.newSetFromMap(new IdentityHashMap<>());

    private JeiBookmarks() {}

    @SubscribeEvent
    static void onTick(ClientTickEvent.Post event) {
        pollNow();
    }

    /**
     * JEI's button and key add to its list while the click or key press is handled, and JEI draws that list in the same
     * frame, so a poll on a tick alone lets it flash up for a moment. Checking just before each frame of a screen is drawn
     * (cheap: one field read while JEI's list is empty) takes the entry out before JEI ever draws it; the tick poll stays
     * for the time no screen is open.
     */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    static void onRender(ScreenEvent.Render.Pre event) {
        pollNow();
    }

    private static void pollNow() {
        if (failed || !AddonConfig.BM_JEI_BOOKMARKS.get() || Calculator.runtime == null) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;
        try {
            poll();
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            failed = true;
            NeiAddon.LOGGER.warn("Could not move JEI's bookmarks into the bookmark panel; JEI keeps its own bookmark list", e);
        }
    }

    private static void poll() throws ReflectiveOperationException {
        if (seenRuntime != Calculator.runtime) {
            seenRuntime = Calculator.runtime;
            list = null;
            backing = null;
            skipped.clear();
        }
        if (list == null) {
            list = findList();
            backing = findBacking(list);
            NeiAddon.LOGGER.info("JEI's bookmark list found ({}, field {})", list.getClass()
                .getName(), backing.getName());
        }
        Object value = backing.get(list);
        if (!(value instanceof Collection<?> all) || all.isEmpty()) return;
        for (Object bookmark : new ArrayList<>(all)) {
            if (skipped.contains(bookmark)) continue;
            boolean moved;
            try {
                moved = move(bookmark);
            } catch (ReflectiveOperationException | RuntimeException e) {
                NeiAddon.LOGGER.warn("Could not turn a JEI bookmark into one of ours", e);
                moved = false;
            }
            if (moved) takeOut(bookmark);
            else skipped.add(bookmark);
        }
    }

    // ---------------------------------------------------------------- finding JEI's list

    private static Object findList() throws ReflectiveOperationException {
        List<Object> roots = new ArrayList<>();
        try {
            Object overlay = Calculator.runtime.getBookmarkOverlay();
            if (overlay != null) roots.add(overlay);
        } catch (RuntimeException ignored) {}
        roots.add(Calculator.runtime);
        Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Deque<Object[]> queue = new ArrayDeque<>();
        for (Object root : roots) {
            if (seen.add(root)) queue.add(new Object[] { root, 0 });
        }
        while (!queue.isEmpty()) {
            Object[] entry = queue.poll();
            Object o = entry[0];
            int depth = (Integer) entry[1];
            for (Class<?> c = o.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                if (c.getSimpleName()
                    .equals("BookmarkList")) return o;
                if (depth >= 4) continue;
                for (Field f : c.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers()) || f.getType()
                        .isPrimitive()) continue;
                    try {
                        f.setAccessible(true);
                        Object v = f.get(o);
                        if (v == null) continue;
                        String name = v.getClass()
                            .getName();
                        if (name.startsWith("java.") || name.startsWith("net.minecraft.") || name.startsWith("com.mojang.")) continue;
                        if (seen.add(v)) queue.add(new Object[] { v, depth + 1 });
                    } catch (RuntimeException ignored) {}
                }
            }
        }
        throw new NoSuchFieldException("JEI's BookmarkList");
    }

    /** The field holding the bookmarks: a collection whose name mentions bookmarks, else the first collection. */
    private static Field findBacking(Object bookmarkList) throws ReflectiveOperationException {
        Field first = null;
        for (Class<?> c = bookmarkList.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers()) || !Collection.class.isAssignableFrom(f.getType())) continue;
                f.setAccessible(true);
                if (f.getName()
                    .toLowerCase()
                    .contains("bookmark")) return f;
                if (first == null) first = f;
            }
        }
        if (first == null) throw new NoSuchFieldException("the bookmarks of JEI's BookmarkList");
        return first;
    }

    /** Removes a bookmark from JEI's list the way JEI does (its own remove, which also saves and refreshes), else directly. */
    @SuppressWarnings("unchecked")
    private static void takeOut(Object bookmark) throws ReflectiveOperationException {
        for (Class<?> c = list.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (!m.getName()
                    .equals("remove") || m.getParameterCount() != 1
                    || !m.getParameterTypes()[0].isInstance(bookmark)
                    || m.getParameterTypes()[0] == Object.class) continue;
                m.setAccessible(true);
                m.invoke(list, bookmark);
                return;
            }
        }
        ((Collection<Object>) backing.get(list)).remove(bookmark);
    }

    // ---------------------------------------------------------------- turning a JEI bookmark into ours

    private static boolean move(Object bookmark) throws ReflectiveOperationException {
        IRecipeCategory<?> category = null;
        ITypedIngredient<?> shown = null;
        Identifier uid = null;
        List<Object> others = new ArrayList<>();
        for (Class<?> c = bookmark.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers()) || f.getType()
                    .isPrimitive()) continue;
                f.setAccessible(true);
                Object v = f.get(bookmark);
                if (v == null) continue;
                if (v instanceof IRecipeCategory<?> rc && category == null) category = rc;
                else if (v instanceof ITypedIngredient<?> t && shown == null) shown = t;
                else if (v instanceof Identifier id && uid == null) uid = id;
                else others.add(v);
            }
        }
        if (category != null) return moveRecipe(category, others, uid, shown);
        if (shown != null) return moveItem(shown);
        return false;
    }

    private static boolean moveItem(ITypedIngredient<?> shown) {
        Optional<ItemStack> stack = shown.getItemStack();
        if (stack.isEmpty() || stack.get()
            .isEmpty()) {
            // a fluid: one bucket of it, as the bookmark key itself gives
            JeiFluids.Hit fluid = JeiFluids.of(shown);
            if (fluid == null) return false;
            Bookmarks.toggle(fluid.carrier(), 1000);
            return true;
        }
        Bookmarks.toggle(stack.get(), 1);
        return true;
    }

    /** The class of the category's recipes, by reflection (the recipe type interface differs between JEI versions). */
    private static Class<?> recipeClass(IRecipeCategory<?> category) {
        try {
            Object type = category.getRecipeType();
            for (Class<?> c = type.getClass(); c != null; c = c.getSuperclass()) {
                for (Class<?> i : c.getInterfaces()) {
                    try {
                        Object r = i.getMethod("getRecipeClass")
                            .invoke(type);
                        if (r instanceof Class<?> k) return k;
                    } catch (ReflectiveOperationException ignored) {}
                }
            }
            Object r = type.getClass()
                .getMethod("getRecipeClass")
                .invoke(type);
            return r instanceof Class<?> k ? k : null;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    private static <T> boolean moveRecipe(IRecipeCategory<T> category, List<Object> others, Identifier uid,
        ITypedIngredient<?> shown) {
        IJeiRuntime rt = Calculator.runtime;
        IRecipeManager manager = rt.getRecipeManager();
        Class<?> type = recipeClass(category);
        T recipe = null;
        for (Object o : others) {
            if (type != null && type.isInstance(o)) {
                @SuppressWarnings("unchecked")
                T cast = (T) o;
                recipe = cast;
                break;
            }
        }
        if (recipe == null && uid != null) {
            for (T candidate : manager.createRecipeLookup(category.getRecipeType())
                .includeHidden()
                .get()
                .toList()) {
                if (uid.equals(category.getIdentifier(candidate))) {
                    recipe = candidate;
                    break;
                }
            }
        }
        if (recipe == null) return false;
        Optional<IRecipeLayoutDrawable<T>> layout = manager.createRecipeLayoutDrawable(
            category,
            recipe,
            rt.getJeiHelpers()
                .getFocusFactory()
                .getEmptyFocusGroup());
        if (layout.isEmpty()) return false;
        RecipeShape shape = RecipeShape.read(layout.get());
        RecipeLookup.register(shape);
        String lead = null;
        if (shown != null) {
            Optional<ItemStack> stack = shown.getItemStack();
            if (stack.isPresent() && !stack.get()
                .isEmpty()) lead = RecipeData.idOf(stack.get());
        }
        RecipeData data = shape.toData(lead);
        if (data.isEmpty() || data.results.isEmpty()) return false;
        // like JEI's own list: the recipe's result as an item that remembers the recipe (an icon of the recipe type on it, the
        // recipe in its tooltip on Shift). The button toggles: pressing it for a recipe the panel has takes it out again
        ItemStack result = data.results.get(0).stack;
        Bookmarks.Page page = Bookmarks.page();
        Calculator.StackKey key = new Calculator.StackKey(result);
        for (Bookmarks.Entry e : new ArrayList<>(page.entries)) {
            if (e.isPlain() && e.key.equals(key) && data.recipeId.equals(e.recipeId)) {
                Bookmarks.remove(page, e);
                return true;
            }
        }
        Bookmarks.add(result, 1, Bookmarks.NO_GROUP, data.recipeId, true);
        return true;
    }
}

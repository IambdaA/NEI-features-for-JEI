package neiaddon;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import mezz.jei.api.gui.handlers.IGhostIngredientHandler;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.recipe.RecipeIngredientRole;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.world.item.ItemStack;
import org.lwjgl.glfw.GLFW;

/**
 * A bookmark dragged out of the panel onto another mod's ghost slot (a filter or configuration slot that takes a copy of
 * an item), as JEI's own bookmark list allows. JEI has no call that starts such a drag from outside, so this does what
 * JEI does itself with the public {@link IGhostIngredientHandler} API: the handlers other mods registered with JEI are
 * read from JEI's screen helper (by reflection, the way {@link JeiAreas} reads JEI's other internals), each is asked for
 * its targets when the drag starts, and the ingredient is handed to the target under the mouse on release. With EMI
 * installed, EMI draws the targets instead ({@link EmiCompat#renderDropTargets}, as for a drag from EMI's own list, so the
 * screen's JEI handlers and EMI's handlers both show), and EMI's own drag-and-drop handlers get the drop first
 * ({@link EmiCompat#dropStack}; that includes the bookmark panel, see {@link NeiAddonJeiPlugin#dropOnPanel}). Nothing here
 * throws: a JEI that differs just means no targets (one logged warning).
 */
final class BookmarkDrag {

    private static final int TARGET = 0x4000FF00;
    private static final int TARGET_HOVER = 0x7000FF00;

    /** A handler of JEI's registry with the screen class it was registered for (null when the registry has no key). */
    private record Registered(Class<?> screenClass, IGhostIngredientHandler<?> handler) {}

    /** One drop target of this drag, with the handler that offered it. */
    @SuppressWarnings("rawtypes")
    private record Hit(IGhostIngredientHandler handler, IGhostIngredientHandler.Target target) {}

    private static final List<Hit> hits = new ArrayList<>();
    private static final List<IGhostIngredientHandler<?>> started = new ArrayList<>();
    private static ItemStack stack = ItemStack.EMPTY;
    private static ITypedIngredient<?> typed;
    private static boolean dragging;

    private static List<Registered> registered = List.of();
    private static Object scannedFor;
    private static boolean warned;

    private BookmarkDrag() {}

    /** Starts dragging the item (or fluid) of a bookmark over {@code screen}: asks every ghost handler for its targets. */
    static void start(Screen screen, ItemStack sample) {
        complete();
        stack = sample.copy();
        if (!FluidStacks.isFluid(stack)) stack.setCount(1);
        dragging = true;
        var rt = Calculator.runtime;
        if (rt == null) return;
        try {
            // the focus holds the typed ingredient (the fluid for a fluid carrier); read by name, as JEI's focus API has changed
            typed = (ITypedIngredient<?>) JeiAreas.invoke(FluidStacks.focus(rt, RecipeIngredientRole.OUTPUT, stack),
                "getTypedValue");
            for (IGhostIngredientHandler<?> handler : handlers(screen)) addTargets(handler, screen);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            warn("Could not start dragging a bookmark onto a slot", e);
        }
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static void addTargets(IGhostIngredientHandler handler, Screen screen) {
        try {
            List<?> targets = handler.getTargetsTyped(screen, typed, true);
            if (targets == null || targets.isEmpty()) return;
            started.add(handler);
            for (Object target : targets) hits.add(new Hit(handler, (IGhostIngredientHandler.Target) target));
        } catch (RuntimeException | LinkageError e) {
            // a handler that does not take this screen or ingredient: it just has no targets
        }
    }

    static boolean dragging() {
        return dragging;
    }

    /**
     * Draws the targets and the dragged item; ends the drag when the left button is no longer held (a release that was lost).
     * With EMI, EMI draws the targets (its handlers include the screen's JEI handlers) and our own fills are skipped.
     */
    static void render(GuiGraphics g, int mouseX, int mouseY) {
        if (!dragging) return;
        // not MouseHandler.isLeftPressed(): vanilla only sets that when no screen is open
        if (GLFW.glfwGetMouseButton(Minecraft.getInstance()
            .getWindow()
            .getWindow(), GLFW.GLFW_MOUSE_BUTTON_LEFT) != GLFW.GLFW_PRESS) {
            complete();
            return;
        }
        // EMI's drawing at its own Z, as for a drag from its list; partial tick is 0 (EMI's handlers ignore it)
        boolean emiDrew = EmiCompat.renderDropTargets(McCompat.screen(), stack, g, mouseX, mouseY, 0f);
        if (!emiDrew) {
            for (Hit hit : hits) {
                try {
                    if (!hit.handler()
                        .shouldHighlightTargets()) continue;
                    Rect2i area = hit.target()
                        .getArea();
                    g.fill(area.getX(), area.getY(), area.getX() + area.getWidth(), area.getY() + area.getHeight(),
                        area.contains(mouseX, mouseY) ? TARGET_HOVER : TARGET);
                } catch (RuntimeException | LinkageError e) {
                    warn("Could not draw a drop target of a bookmark drag", e);
                }
            }
        }
        if (!emiDrew) {
            g.renderItem(stack, mouseX - 8, mouseY - 8);
            return;
        }
        // EMI draws its own dragged stack at this Z too: the item goes in front of EMI's targets
        g.pose()
            .pushPose();
        try {
            g.pose()
                .translate(0, 0, 400);
            g.renderItem(stack, mouseX - 8, mouseY - 8);
        } finally {
            g.pose()
                .popPose();
        }
    }

    /** Ends the drag over {@code screen}: the target under the mouse takes the ingredient. True when one did. */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    static boolean drop(Screen screen, double mouseX, double mouseY) {
        boolean used = false;
        try {
            if (dragging && !stack.isEmpty()) {
                used = EmiCompat.dropStack(screen, stack, (int) mouseX, (int) mouseY);
                if (!used && typed != null) {
                    for (Hit hit : hits) {
                        if (!hit.target()
                            .getArea()
                            .contains((int) mouseX, (int) mouseY)) continue;
                        hit.target()
                            .accept(typed.getIngredient());
                        used = true;
                        break;
                    }
                }
            }
        } catch (RuntimeException | LinkageError e) {
            warn("Could not drop a bookmark onto a slot", e);
        } finally {
            complete();
        }
        return used;
    }

    /** Ends the drag without dropping anything. */
    static void cancel() {
        complete();
    }

    /** What JEI does when a drag is over: every handler that offered targets hears it. */
    private static void complete() {
        for (IGhostIngredientHandler<?> handler : started) {
            try {
                handler.onComplete();
            } catch (RuntimeException | LinkageError e) {
                warn("A drop handler failed to end a bookmark drag", e);
            }
        }
        started.clear();
        hits.clear();
        typed = null;
        stack = ItemStack.EMPTY;
        dragging = false;
    }

    // ---------------------------------------------------------------- JEI's handlers

    /** The ghost handlers other mods registered for this screen (this addon's own, which takes drops into the panel, is left out). */
    private static List<IGhostIngredientHandler<?>> handlers(Screen screen) {
        List<IGhostIngredientHandler<?>> out = new ArrayList<>();
        var rt = Calculator.runtime;
        if (rt == null) return out;
        Object helper = rt.getScreenHelper();
        if (helper == null) return out;
        if (scannedFor != rt) scan(helper, rt);
        for (Registered r : registered) {
            if (r.screenClass() != null && !r.screenClass()
                .isInstance(screen)) continue;
            if (!out.contains(r.handler())) out.add(r.handler());
        }
        // JEI's own answer for this screen, in case the scan did not find its registry
        Object own = askJei(helper, screen);
        if (own instanceof IGhostIngredientHandler<?> h && !isOurs(h) && !out.contains(h)) out.add(h);
        return out;
    }

    /** Reads the handlers out of JEI's screen helper (its fields, and the objects those hold) once per JEI runtime. */
    private static void scan(Object helper, Object runtime) {
        scannedFor = runtime;
        List<Registered> found = new ArrayList<>();
        try {
            Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
            seen.add(helper);
            collect(helper, 0, seen, found);
        } catch (RuntimeException | LinkageError e) {
            warn("Could not read JEI's drop handlers; bookmarks can only be dragged onto slots that JEI names by itself", e);
        }
        registered = found;
    }

    private static void collect(Object owner, int depth, Set<Object> seen, List<Registered> into) {
        for (Class<?> c = owner.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers()) || f.getType()
                    .isPrimitive()) continue;
                Object value;
                try {
                    f.setAccessible(true);
                    value = f.get(owner);
                } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                    continue; // a field that cannot be opened is skipped: the scan takes whatever it can read
                }
                if (value == null || !seen.add(value)) continue;
                if (read(value, into)) continue;
                if (depth < 1 && insideJei(value)) collect(value, depth + 1, seen, into);
            }
        }
    }

    /** Takes the handlers a map, a collection or a multimap holds into {@code into}; true when it held any. */
    private static boolean read(Object value, List<Registered> into) {
        int before = into.size();
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> e : map.entrySet()) add(e.getKey(), e.getValue(), into);
        } else if (value instanceof Iterable<?> items) {
            for (Object item : items) {
                if (item instanceof Map.Entry<?, ?> e) add(e.getKey(), e.getValue(), into);
                else add(null, item, into);
            }
        } else if (value.getClass()
            .getName()
            .contains("Multimap")) {
            // Guava's multimaps are not maps: their entries() are (screen class, handler) pairs
            try {
                Object entries = value.getClass()
                    .getMethod("entries")
                    .invoke(value);
                if (entries instanceof Collection<?> all) {
                    for (Object item : all) {
                        if (item instanceof Map.Entry<?, ?> e) add(e.getKey(), e.getValue(), into);
                    }
                }
            } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
                // not a Guava multimap after all: nothing taken from it, the scan goes on
            }
        }
        return into.size() > before;
    }

    private static void add(Object key, Object value, List<Registered> into) {
        Class<?> screenClass = key instanceof Class<?> c ? c : null;
        if (value instanceof IGhostIngredientHandler<?> h) {
            if (!isOurs(h)) into.add(new Registered(screenClass, h));
        } else if (value instanceof Iterable<?> many) {
            for (Object item : many) {
                if (item instanceof IGhostIngredientHandler<?> h && !isOurs(h)) into.add(new Registered(screenClass, h));
            }
        }
    }

    /** JEI's {@code getGhostIngredientHandler(screen)}, if its screen helper has one (an Optional or the handler itself). */
    private static Object askJei(Object helper, Screen screen) {
        for (Method m : helper.getClass()
            .getMethods()) {
            if (!m.getName()
                .equals("getGhostIngredientHandler") || m.getParameterCount() != 1
                || !m.getParameterTypes()[0].isInstance(screen)) continue;
            try {
                m.setAccessible(true);
                Object result = m.invoke(helper, screen);
                return result instanceof Optional<?> o ? o.orElse(null) : result;
            } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                warn("Could not ask JEI for the drop handler of this screen; only the handlers found at load are used", e);
                return null;
            }
        }
        return null;
    }

    private static boolean isOurs(Object handler) {
        return handler.getClass()
            .getName()
            .startsWith("neiaddon.");
    }

    /** Only JEI's own objects are looked into, not the game's or the JDK's. */
    private static boolean insideJei(Object value) {
        String name = value.getClass()
            .getName();
        return !(name.startsWith("java.") || name.startsWith("net.minecraft.") || name.startsWith("com.mojang.")
            || name.startsWith("com.google."));
    }

    private static void warn(String message, Throwable e) {
        if (warned) return;
        warned = true;
        NeiAddon.LOGGER.warn(message, e);
    }
}

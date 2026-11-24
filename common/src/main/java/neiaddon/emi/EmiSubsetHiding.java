package neiaddon.emi;

import dev.emi.emi.api.stack.EmiStack;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import net.minecraft.world.item.ItemStack;
import neiaddon.NeiAddon;

/**
 * Subsets' hiding in EMI's item list (see {@code neiaddon.Subsets}). JEI's runtime removal does not reach EMI's list, so the
 * stacks EMI searches ({@code EmiStackList.filteredStacks}) are replaced by a copy without the hidden item stacks, and
 * {@code EmiSearch.update()} runs again so the list shows the change. EMI's internals are reached by reflection only. On any
 * failure one warning is logged and the hiding is off for the session ({@link #failed()}); the clicks then fall back to
 * EMI's search. Runs on the client thread only.
 */
public final class EmiSubsetHiding {

    private static final String STACK_LIST = "dev.emi.emi.registry.EmiStackList";
    private static final String SEARCH = "dev.emi.emi.search.EmiSearch";

    private static Field filtered;
    private static Method update;
    private static boolean failed;

    /** EMI's own list (the one EMI baked last); this class's copy is built from it. */
    private static List<EmiStack> base;
    /** The copy this class last wrote into EMI's field, or null while the field holds EMI's own list. */
    private static List<EmiStack> written;
    private static Set<String> hidden = Set.of();
    private static Function<ItemStack, String> keyOf;
    private static boolean dirty;
    /** The key of each stack of {@link #base} ("" for a stack that is not an item), cleared when base changes. */
    private static final Map<EmiStack, String> keys = new IdentityHashMap<>();

    private EmiSubsetHiding() {}

    /** Did a change to EMI's list fail in this session (the hiding is off for good)? */
    public static boolean failed() {
        return failed;
    }

    /**
     * Hides the item stacks whose key is in {@code hiddenKeys} from EMI's list. {@code keyOf} gives an item stack's key the
     * way {@code ListHiding} does. Nothing is written when the hidden keys and EMI's list are unchanged.
     */
    public static void apply(Set<String> hiddenKeys, Function<ItemStack, String> keyer) {
        if (failed) return;
        keyOf = keyer;
        if (!hiddenKeys.equals(hidden)) {
            hidden = Set.copyOf(hiddenKeys);
            dirty = true;
        }
        sync();
    }

    /** Client tick: when EMI rebuilt its list (a reload), the hiding is applied to the new list. */
    public static void tick() {
        if (failed) return;
        sync();
    }

    private static void sync() {
        if (failed || keyOf == null) return;
        try {
            init();
            List<EmiStack> cur = list();
            if (cur == null) return;
            if (cur != written) {
                // EMI's own list: the first one, or a new one after EMI reloaded (then this class's copy is gone too)
                written = null;
                if (cur != base) {
                    base = cur;
                    keys.clear();
                    dirty = true;
                }
            }
            if (!dirty) return;
            dirty = false;
            List<EmiStack> target = hidden.isEmpty() ? base : wanted();
            if (sameStacks(cur, target)) return;
            filtered.set(null, target);
            written = target == base ? null : target;
            update.invoke(null);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            fail(e);
        }
    }

    private static void init() throws ReflectiveOperationException {
        if (filtered != null) return;
        filtered = Class.forName(STACK_LIST)
            .getField("filteredStacks");
        update = Class.forName(SEARCH)
            .getMethod("update");
    }

    @SuppressWarnings("unchecked")
    private static List<EmiStack> list() throws ReflectiveOperationException {
        return (List<EmiStack>) filtered.get(null);
    }

    /** EMI's list without the hidden item stacks, as an unmodifiable copy (EMI's own lists are never changed). */
    private static List<EmiStack> wanted() {
        List<EmiStack> out = new ArrayList<>();
        for (EmiStack s : base) {
            if (!isHidden(s)) out.add(s);
        }
        return Collections.unmodifiableList(out);
    }

    private static boolean isHidden(EmiStack s) {
        String key = keys.computeIfAbsent(s, EmiSubsetHiding::keyOfStack);
        return !key.isEmpty() && hidden.contains(key);
    }

    private static String keyOfStack(EmiStack s) {
        ItemStack item = s.getItemStack();
        if (item == null || item.isEmpty()) return "";
        String key = keyOf.apply(item);
        return key == null ? "" : key;
    }

    private static boolean sameStacks(List<EmiStack> a, List<EmiStack> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            if (a.get(i) != b.get(i)) return false;
        }
        return true;
    }

    private static void fail(Throwable e) {
        failed = true;
        NeiAddon.LOGGER.warn("Could not change EMI's item list; Subsets clicks set EMI's search instead", e);
        // best effort: EMI's own list back and its search run again, so the index is not left short of the stacks it hid
        try {
            if (written != null && filtered != null && update != null && base != null) {
                filtered.set(null, base);
                update.invoke(null);
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            // the warning above already says the hiding is off
        }
        written = null;
        keys.clear();
    }
}

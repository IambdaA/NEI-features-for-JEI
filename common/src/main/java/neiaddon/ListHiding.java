package neiaddon;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.ingredients.IIngredientHelper;
import mezz.jei.api.ingredients.IIngredientType;
import mezz.jei.api.ingredients.subtypes.UidContext;
import mezz.jei.api.runtime.IIngredientManager;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import neiaddon.event.ClientTickEvent;
import neiaddon.event.SubscribeEvent;

/**
 * One record of what the addon takes out of JEI's item list. Several features hide entries (the subsets menu, collapsible
 * item groups), each under its own reason; the entries to hide are the union of the reasons, so one feature never brings
 * back what another hides. Every removal and every re-adding goes through here.
 *
 * <p>
 * Per JEI runtime a snapshot of every entry of every ingredient type is taken, before the first removal: JEI's own
 * {@code getAllIngredients} no longer lists removed entries. A new runtime (world joined, JEI reloaded) has every entry
 * again, so what is removed is forgotten and the wanted set is applied again. Nothing happens while JEI has no runtime.
 */
final class ListHiding {

    /** One entry of JEI's list: its type, the ingredient and the key that names it (type uid, "|", JEI's unique id). */
    record Entry(IIngredientType<?> type, Object ingredient, String key) {}

    private static IJeiRuntime runtime;
    private static List<Entry> snapshot;
    private static Map<String, Entry> byKey = Map.of();
    private static final Map<String, Set<String>> reasons = new LinkedHashMap<>();
    /** Keys taken out of JEI's list right now. */
    private static final Set<String> removed = new HashSet<>();
    private static boolean failed;

    private ListHiding() {}

    // ---------------------------------------------------------------- the snapshot

    /** Makes the snapshot of the current runtime when it is a new one; false when JEI has no runtime. */
    private static boolean sync() {
        IJeiRuntime rt = Calculator.runtime;
        if (rt == null) return false;
        if (rt != runtime || snapshot == null) {
            runtime = rt;
            removed.clear();
            failed = false;
            snapshot = take(rt);
            Map<String, Entry> map = new HashMap<>();
            for (Entry e : snapshot) map.putIfAbsent(e.key(), e);
            byKey = map;
        }
        return true;
    }

    private static List<Entry> take(IJeiRuntime rt) {
        List<Entry> out = new ArrayList<>();
        try {
            IIngredientManager mgr = rt.getIngredientManager();
            for (IIngredientType<?> type : mgr.getRegisteredIngredientTypes()) {
                takeType(mgr, type, out);
            }
        } catch (RuntimeException | LinkageError e) {
            NeiAddon.LOGGER.warn("Could not read JEI's ingredient list; item hiding is off for this session", e);
            failed = true;
        }
        return out;
    }

    private static <T> void takeType(IIngredientManager mgr, IIngredientType<T> type, List<Entry> out) {
        IIngredientHelper<T> helper = mgr.getIngredientHelper(type);
        String prefix = typeId(type) + "|";
        for (T ingredient : mgr.getAllIngredients(type)) {
            try {
                String uid = uidOf(helper, ingredient);
                if (uid != null) out.add(new Entry(type, ingredient, prefix + uid));
            } catch (RuntimeException e) {
                // an ingredient without an id is left out
            }
        }
    }

    /**
     * JEI 29's id of an entry: its registry id, and for a stack with subtype data also the data (JEI's uid of such a stack
     * is a list of the item and the data; a plain stack's uid is just the item). Null when it has none.
     */
    static <T> String uidOf(IIngredientHelper<T> helper, T ingredient) {
        Identifier id = helper.getIdentifier(ingredient);
        if (id == null) return null;
        Object uid = helper.getUid(ingredient, UidContext.Ingredient);
        if (uid instanceof List<?> parts && parts.size() >= 2) return id + "#" + parts.get(1);
        return id.toString();
    }

    private static String typeId(IIngredientType<?> type) {
        return type.getUid();
    }

    /** The full list of the current runtime, hidden entries included (empty without JEI). */
    static List<Entry> entries() {
        return sync() ? snapshot : List.of();
    }

    /** The item stacks of {@link #entries()}. */
    static List<ItemStack> items() {
        List<ItemStack> out = new ArrayList<>();
        for (Entry e : entries()) {
            if (e.ingredient() instanceof ItemStack s) out.add(s);
        }
        return out;
    }

    /** The key of an item stack, or null when JEI is not there or has no id for it. */
    static String keyOf(ItemStack stack) {
        IJeiRuntime rt = Calculator.runtime;
        if (rt == null || stack.isEmpty()) return null;
        try {
            String uid = uidOf(rt.getIngredientManager()
                .getIngredientHelper(VanillaTypes.ITEM_STACK), stack);
            return uid == null ? null : typeId(VanillaTypes.ITEM_STACK) + "|" + uid;
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }

    /** Is the key wanted hidden by any reason (whether or not JEI has been told yet)? */
    static boolean isHidden(String key) {
        for (Set<String> keys : reasons.values()) {
            if (keys.contains(key)) return true;
        }
        return false;
    }

    /** Did a change to JEI's list fail in this runtime (hiding is off until JEI reloads)? */
    static boolean failed() {
        return sync() && failed;
    }

    // ---------------------------------------------------------------- hiding

    /** Sets the keys one reason wants hidden (empty: none), then brings JEI's list in line with all reasons. */
    static void setHidden(String reason, Collection<String> keys) {
        if (keys.isEmpty()) {
            if (reasons.remove(reason) == null) return;
        } else {
            reasons.put(reason, new LinkedHashSet<>(keys));
        }
        apply();
    }

    /** Compares what is wanted with what is removed now and makes the difference, one JEI call per type and direction. */
    static void apply() {
        if (!sync() || failed) return;
        Set<String> wanted = new HashSet<>();
        for (Set<String> keys : reasons.values()) {
            for (String k : keys) {
                if (byKey.containsKey(k)) wanted.add(k);
            }
        }
        Map<IIngredientType<?>, List<Object>> remove = new LinkedHashMap<>();
        Map<IIngredientType<?>, List<Object>> add = new LinkedHashMap<>();
        Map<IIngredientType<?>, List<String>> removeKeys = new LinkedHashMap<>();
        Map<IIngredientType<?>, List<String>> addKeys = new LinkedHashMap<>();
        for (String k : wanted) {
            if (!removed.contains(k)) {
                Entry e = byKey.get(k);
                remove.computeIfAbsent(e.type(), t -> new ArrayList<>())
                    .add(e.ingredient());
                removeKeys.computeIfAbsent(e.type(), t -> new ArrayList<>())
                    .add(k);
            }
        }
        for (String k : removed) {
            if (!wanted.contains(k)) {
                Entry e = byKey.get(k);
                if (e == null) continue;
                add.computeIfAbsent(e.type(), t -> new ArrayList<>())
                    .add(e.ingredient());
                addKeys.computeIfAbsent(e.type(), t -> new ArrayList<>())
                    .add(k);
            }
        }
        if (remove.isEmpty() && add.isEmpty()) return;
        try {
            IIngredientManager mgr = runtime.getIngredientManager();
            for (Map.Entry<IIngredientType<?>, List<Object>> e : add.entrySet()) {
                change(mgr, e.getKey(), e.getValue(), false);
                removed.removeAll(addKeys.get(e.getKey()));
            }
            for (Map.Entry<IIngredientType<?>, List<Object>> e : remove.entrySet()) {
                change(mgr, e.getKey(), e.getValue(), true);
                removed.addAll(removeKeys.get(e.getKey()));
            }
        } catch (RuntimeException | LinkageError e) {
            failed = true;
            NeiAddon.LOGGER.warn("Could not change JEI's item list; hiding items is off for this session", e);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> void change(IIngredientManager mgr, IIngredientType<T> type, List<Object> list, boolean remove) {
        if (list.isEmpty()) return; // JEI throws on an empty list
        List<T> typed = (List<T>) list;
        if (remove) mgr.removeIngredientsAtRuntime(type, typed);
        else mgr.addIngredientsAtRuntime(type, typed);
    }

    /** A new JEI runtime has every entry again: what was removed is forgotten and the wanted set is applied again. */
    @SubscribeEvent
    static void onTick(ClientTickEvent.Post event) {
        IJeiRuntime rt = Calculator.runtime;
        if (rt != null && rt != runtime && !reasons.isEmpty()) apply();
    }
}

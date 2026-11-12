package neiaddon;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;

/**
 * JEI's own config values, read through JEI's config manager by reflection (a JEI without that API gives no categories)
 * and written back through the same API, which saves JEI's file itself. {@link NeiSettings} shows them as the "JEI
 * Options" pages of the NEI Options page. Nothing here draws or takes input; every reflective call degrades to a
 * fallback, and reading the config logs one warning.
 */
final class JeiSettings {

    /** One page of JEI's settings: its title and the config values under it. */
    record Category(String title, List<Object> values) {}

    /** Longest choice list shown as cycling choices; longer lists are typed in instead. */
    private static final int MAX_CHOICES = 64;
    /** Per class, the public methods already looked up, by name and argument count (empty when the class has none). */
    private static final ClassValue<Map<String, Optional<Method>>> METHODS = new ClassValue<>() {

        @Override
        protected Map<String, Optional<Method>> computeValue(Class<?> type) {
            return new ConcurrentHashMap<>();
        }
    };

    private JeiSettings() {}

    /** JEI's config files as pages: categories with the same title merged, categories without values left out. */
    static List<Category> categories() {
        Map<String, List<Object>> byTitle = new LinkedHashMap<>();
        try {
            if (Calculator.runtime == null) return List.of();
            Object manager = Calculator.runtime.getClass()
                .getMethod("getConfigManager")
                .invoke(Calculator.runtime);
            Collection<?> files = (Collection<?>) call(manager, "getConfigFiles");
            for (Object file : files) {
                for (Object category : (List<?>) call(file, "getCategories")) {
                    List<Object> values = new ArrayList<>((Collection<?>) call(category, "getConfigValues"));
                    if (values.isEmpty()) continue;
                    byTitle.computeIfAbsent(title(file, category), k -> new ArrayList<>()).addAll(values);
                }
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            NeiAddon.LOGGER.warn("Could not read JEI's config values", e);
            return List.of();
        }
        List<Category> out = new ArrayList<>();
        for (Map.Entry<String, List<Object>> e : byTitle.entrySet()) out.add(new Category(e.getKey(), e.getValue()));
        return out;
    }

    /** Can JEI's config values be read? */
    static boolean available() {
        return !categories().isEmpty();
    }

    /** The lang key {@code jei.config.<file>.<category>} when JEI has it, else the category name made readable. */
    private static String title(Object file, Object category) throws ReflectiveOperationException {
        String name = String.valueOf(call(category, "getName"));
        String base = fileBase(file);
        if (base == null) return pretty(name);
        String key = "jei.config." + base + "." + name;
        return Language.getInstance()
            .has(key) ? Language.getInstance()
                .getOrDefault(key) : pretty(name);
    }

    /** The file's name without the {@code jei-} prefix and the {@code .ini} suffix, or null when JEI does not say. */
    private static String fileBase(Object file) {
        try {
            String base = ((Path) call(file, "getPath")).getFileName()
                .toString();
            if (base.endsWith(".ini")) base = base.substring(0, base.length() - 4);
            if (base.startsWith("jei-")) base = base.substring(4);
            return base;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return null;
        }
    }

    // ---------------------------------------------------------------- one value (read live, every call)

    /** The value's name: JEI's localized name, else its raw name made readable, else "?". */
    static String name(Object v) {
        try {
            if (call(v, "getLocalizedName") instanceof Component c && !c.getString()
                .isEmpty()) return c.getString();
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {}
        try {
            return pretty(String.valueOf(call(v, "getName")));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return "?";
        }
    }

    /** The value's description: JEI's localized one, else its own, else "". */
    static String description(Object v) {
        try {
            if (call(v, "getLocalizedDescription") instanceof Component c && !c.getString()
                .isEmpty()) return c.getString();
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {}
        try {
            Object d = call(v, "getDescription");
            return d == null ? "" : String.valueOf(d);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return "";
        }
    }

    /** A yes / no value. */
    static boolean isToggle(Object v) {
        try {
            return call(v, "getValue") instanceof Boolean;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return false;
        }
    }

    /** The fixed list of values a choice cycles through, or nothing when there is none or it is too long. */
    static List<Object> choices(Object v) {
        try {
            Optional<?> all = (Optional<?>) call(call(v, "getSerializer"), "getAllValidValues");
            if (all.isEmpty() || !(all.get() instanceof Collection<?> c) || c.isEmpty() || c.size() > MAX_CHOICES) return List.of();
            return new ArrayList<>(c);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return List.of();
        }
    }

    /** Flips a yes / no value. False when JEI refuses it. */
    static boolean toggle(Object v) {
        try {
            if (!(call(v, "getValue") instanceof Boolean b)) return false;
            return store(v, !b);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return false;
        }
    }

    /** Moves a choice to the next value, or the previous one with {@code back}, wrapping around. */
    static void cycle(Object v, boolean back) {
        try {
            Object current = call(v, "getValue");
            List<Object> all = choices(v);
            if (all.isEmpty()) return;
            int i = all.indexOf(current);
            int next = (i + (back ? -1 : 1) + all.size()) % all.size();
            store(v, all.get(next));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {}
    }

    /** Is a yes / no value on? */
    static boolean isOn(Object v) {
        try {
            return Boolean.TRUE.equals(call(v, "getValue"));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return false;
        }
    }

    /** A choice's current value, made readable ("Mouse Pickup"); "?" when it cannot be read. */
    static String shown(Object v) {
        return pretty(text(v));
    }

    /** A typed value as JEI writes it in its file; "?" when it cannot be read. */
    static String text(Object v) {
        try {
            Object serializer = call(v, "getSerializer");
            return String.valueOf(call(serializer, "serialize", call(v, "getValue")));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return "?";
        }
    }

    /** Parses typed text and sets the value if JEI accepts it. False when any step fails. */
    static boolean setText(Object v, String s) {
        try {
            Object serializer = call(v, "getSerializer");
            Object result = call(serializer, "deserialize", s);
            if (!(call(result, "getResult") instanceof Optional<?> parsed) || parsed.isEmpty()) return false;
            if (!Boolean.TRUE.equals(call(serializer, "isValid", parsed.get()))) return false;
            return store(v, parsed.get());
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return false;
        }
    }

    /** What JEI accepts for a typed value, or "" when JEI says nothing. */
    static String validValues(Object v) {
        try {
            Object text = call(call(v, "getSerializer"), "getValidValuesDescription");
            return text == null ? "" : String.valueOf(text);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return "";
        }
    }

    /**
     * Sets the value through JEI. JEI's set returns whether it took the value; no answer counts as taken. JEI also says no
     * to the value it already has, so that one counts as taken without asking.
     */
    private static boolean store(Object v, Object value) throws ReflectiveOperationException {
        if (Objects.equals(call(v, "getValue"), value)) return true;
        Object ok = call(v, "set", value);
        return !Boolean.FALSE.equals(ok);
    }

    /**
     * Words of a raw id made readable: split at {@code _}, {@code -}, spaces and lower-to-upper case changes, each word
     * with a capital first letter and the rest lower case: {@code MOUSE_PICKUP} gives "Mouse Pickup", {@code searchColors}
     * gives "Search Colors".
     */
    static String pretty(String raw) {
        if (raw == null || raw.isEmpty()) return raw == null ? "" : raw;
        StringBuilder out = new StringBuilder();
        for (String word : raw.split("[_\\- ]+|(?<=\\p{Ll})(?=\\p{Lu})")) {
            if (word.isEmpty()) continue;
            if (out.length() > 0) out.append(' ');
            out.append(Character.toUpperCase(word.charAt(0)))
                .append(word.substring(1)
                    .toLowerCase(Locale.ROOT));
        }
        return out.toString();
    }

    private static Object call(Object target, String name, Object... args) throws ReflectiveOperationException {
        // the pages read JEI's values every frame: each method is looked up once per class
        Map<String, Optional<Method>> known = METHODS.get(target.getClass());
        String key = name + "/" + args.length;
        Optional<Method> m = known.get(key);
        if (m == null) {
            m = Optional.empty();
            for (Method found : target.getClass()
                .getMethods()) {
                if (found.getName()
                    .equals(name) && found.getParameterCount() == args.length) {
                    found.setAccessible(true);
                    m = Optional.of(found);
                    break;
                }
            }
            known.put(key, m);
        }
        if (m.isEmpty()) throw new NoSuchMethodException(name);
        return m.get()
            .invoke(target, args);
    }
}

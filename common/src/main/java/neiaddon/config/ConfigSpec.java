package neiaddon.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import neiaddon.NeiAddon;
import neiaddon.platform.Platform;

/**
 * A small settings file the same on every loader: {@code config/neiaddon-client.toml}, one {@code key = value} line per
 * setting with its comment above (the file NeoForge wrote before, so existing settings carry over). The values are
 * read on first use, {@code set} changes them in memory and {@code save} writes the file.
 */
public final class ConfigSpec {

    private static final String FILE = "neiaddon-client.toml";

    private final List<ConfigValue<?>> values;
    private boolean loaded;

    private ConfigSpec(List<ConfigValue<?>> values) {
        this.values = values;
        for (ConfigValue<?> v : values) v.spec = this;
    }

    public static final class Builder {

        private final List<ConfigValue<?>> values = new ArrayList<>();
        private String comment = "";

        public Builder comment(String text) {
            comment = text;
            return this;
        }

        public BooleanValue define(String key, boolean def) {
            BooleanValue v = new BooleanValue(key, comment, def);
            comment = "";
            values.add(v);
            return v;
        }

        public IntValue defineInRange(String key, int def, int min, int max) {
            IntValue v = new IntValue(key, comment, def, min, max);
            comment = "";
            values.add(v);
            return v;
        }

        public ConfigSpec build() {
            return new ConfigSpec(values);
        }
    }

    public abstract static class ConfigValue<T> {

        final String key;
        final String comment;
        final T def;
        T value;
        ConfigSpec spec;

        ConfigValue(String key, String comment, T def) {
            this.key = key;
            this.comment = comment;
            this.def = def;
            this.value = def;
        }

        abstract T parse(String text);

        abstract T clamp(T v);

        String range() {
            return null;
        }

        public T get() {
            spec.ensureLoaded();
            return value;
        }

        public void set(T v) {
            spec.ensureLoaded();
            value = clamp(v);
        }

        public void save() {
            spec.save();
        }
    }

    public static final class BooleanValue extends ConfigValue<Boolean> {

        BooleanValue(String key, String comment, boolean def) {
            super(key, comment, def);
        }

        @Override
        Boolean parse(String text) {
            if (text.equals("true")) return true;
            if (text.equals("false")) return false;
            throw new IllegalArgumentException(text);
        }

        @Override
        Boolean clamp(Boolean v) {
            return v;
        }
    }

    public static final class IntValue extends ConfigValue<Integer> {

        final int min;
        final int max;

        IntValue(String key, String comment, int def, int min, int max) {
            super(key, comment, def);
            this.min = min;
            this.max = max;
        }

        @Override
        Integer parse(String text) {
            return clamp(Integer.parseInt(text));
        }

        @Override
        Integer clamp(Integer v) {
            return Math.max(min, Math.min(max, v));
        }

        @Override
        String range() {
            return "Range: " + min + " ~ " + max;
        }
    }

    private Path file() {
        return Platform.get().configDir().resolve(FILE);
    }

    synchronized void ensureLoaded() {
        if (loaded) return;
        loaded = true;
        Path file = file();
        if (!Files.isRegularFile(file)) {
            save();
            return;
        }
        Map<String, String> read = new HashMap<>();
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String t = line.trim();
                int eq = t.indexOf('=');
                if (t.isEmpty() || t.startsWith("#") || t.startsWith("[") || eq < 0) continue;
                read.put(t.substring(0, eq).trim(), t.substring(eq + 1).trim());
            }
        } catch (IOException e) {
            NeiAddon.LOGGER.warn("Could not read {}; using the defaults", file, e);
            return;
        }
        for (ConfigValue<?> v : values) {
            String text = read.get(v.key);
            if (text == null) continue;
            try {
                v.value = cast(v.parse(text));
            } catch (RuntimeException e) {
                NeiAddon.LOGGER.warn("Setting {} = {} is not valid; using {}", v.key, text, v.def);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T cast(Object o) {
        return (T) o;
    }

    public synchronized void save() {
        ensureLoadedForSave();
        StringBuilder out = new StringBuilder();
        for (ConfigValue<?> v : values) {
            for (String line : v.comment.split("\n")) {
                if (!line.isEmpty()) out.append('#').append(line).append('\n');
            }
            if (v.range() != null) out.append('#').append(v.range()).append('\n');
            out.append(v.key).append(" = ").append(v.value).append("\n\n");
        }
        Path file = file();
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(FILE + ".tmp");
            Files.writeString(tmp, out.toString(), StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            NeiAddon.LOGGER.warn("Could not write {}", file, e);
        }
    }

    /** A save before any read must not overwrite the file with defaults. */
    private void ensureLoadedForSave() {
        if (!loaded) ensureLoaded();
    }
}

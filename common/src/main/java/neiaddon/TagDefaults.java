package neiaddon;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import neiaddon.platform.Platform;

/**
 * The default item of a tag (#39): Ctrl + left click in the tag chooser remembers one accepted item, and
 * every recipe with the same tag uses it first when its tree is built. JEI hands the addon the items a slot accepts,
 * never the tag's own id, so a tag is keyed by its identity: a hash of the sorted ids of the items it accepts (the
 * same way {@code RecipeShape} names a recipe with no id). One {@code key<TAB>itemId} line per tag, in
 * config/neiaddon/tagdefaults.txt; unknown or invalid lines are ignored.
 */
public final class TagDefaults {

    private static boolean loaded;
    /** The file could not be read: it is never written over this session, so a bad read can't wipe the saved defaults. */
    private static boolean loadFailed;
    private static final Map<String, String> defaults = new HashMap<>();
    /** The last options asked for and their key: the tag chooser asks for the same options every frame. */
    private static Collection<String> lastOptions;
    private static int lastSize;
    private static String lastKey;

    private TagDefaults() {}

    /** The key of a tag: a hash of the sorted ids of the items it accepts, so order does not matter. */
    public static String keyFor(Collection<String> options) {
        if (options == lastOptions && options.size() == lastSize && lastKey != null) return lastKey;
        List<String> sorted = new ArrayList<>(new LinkedHashSet<>(options));
        Collections.sort(sorted);
        String key = "alts:" + digest(String.join(",", sorted));
        lastOptions = options;
        lastSize = options.size();
        lastKey = key;
        return key;
    }

    /** The default item id of a tag (its accepted item ids), or null when it has none. */
    public static String defaultFor(Collection<String> options) {
        load();
        return defaults.get(keyFor(options));
    }

    /** Remembers the default item of a tag (its accepted item ids) and saves. */
    public static void setDefault(Collection<String> options, String itemId) {
        load();
        defaults.put(keyFor(options), itemId);
        save();
    }

    private static Path file() {
        return Platform.get().configDir()
            .resolve("neiaddon")
            .resolve("tagdefaults.txt");
    }

    private static void load() {
        if (loaded) return;
        loaded = true;
        defaults.clear();
        Path file = file();
        if (!Files.isRegularFile(file)) return;
        try {
            for (String line : Files.readAllLines(file)) {
                int at = line.indexOf('\t');
                if (at <= 0 || at == line.length() - 1) continue;
                String item = line.substring(at + 1).trim();
                if (!item.contains(":")) continue; // invalid id: ignore the line
                defaults.put(line.substring(0, at), item);
            }
        } catch (IOException | RuntimeException ex) {
            loadFailed = true;
            NeiAddon.LOGGER.warn("Could not load the tag defaults; new defaults are kept until the game closes and the file is left as it is", ex);
        }
    }

    private static void save() {
        if (loadFailed) return;
        Path file = file();
        List<String> lines = new ArrayList<>();
        for (Map.Entry<String, String> e : new TreeMap<>(defaults).entrySet()) {
            lines.add(e.getKey() + "\t" + e.getValue()
                .replace('\t', ' ')
                .replace('\n', ' ')
                .replace('\r', ' '));
        }
        try {
            Files.createDirectories(file.getParent());
            // written to a temporary file first, then moved over the old one: a crash mid-write never leaves half a file
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.write(temp, lines);
            try {
                Files.move(temp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException ex) {
                Files.move(temp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | RuntimeException ex) {
            NeiAddon.LOGGER.warn("Could not save the tag defaults", ex);
        }
    }

    /** The first eight bytes of the SHA-1 of the text, as hex; the hash of the text when SHA-1 is missing. */
    private static String digest(String text) {
        try {
            byte[] hash = java.security.MessageDigest.getInstance("SHA-1")
                .digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) sb.append(String.format("%02x", hash[i]));
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            return Integer.toHexString(text.hashCode());
        }
    }
}

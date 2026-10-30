package neiaddon;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.WeakHashMap;
import neiaddon.chain.ChainItem;
import neiaddon.chain.TreeCamera;
import neiaddon.chain.TreeSlot;
import neiaddon.platform.Platform;

/**
 * NEI's {@code CraftingTreeState}. The settings the toolbar changes are saved ({@code config/neiaddon/crafting_tree
 * .properties}, NEI keeps them in the world's NEI data); everything else (the camera, which items are folded, the
 * search) lives until the game is closed, once per group.
 *
 * <p>
 * Ported from GTNH NotEnoughItems (LGPL-3.0-or-later).
 */
public final class TreeState {

    public enum CollapseMode {
        EXPANDED,
        SMART,
        ALL
    }

    public enum StatsSection {
        INGREDIENTS_NEEDED,
        INGREDIENTS_AVAILABLE,
        CRAFTING_NEEDED,
        CRAFTING_AVAILABLE,
        RESULTS,
        REMAINDERS,
        HANDLERS
    }

    /** What a search found: the nodes, and the one the view jumped to. */
    public static final class SearchResult {

        /** The search text (a String), a stats slot's item ({@link ItemGuid}) or its recipe type (a String). */
        public final Object guid;
        public TreeSlot highlightNode;
        public final Set<TreeSlot> matches = new LinkedHashSet<>();

        public SearchResult(Object guid) {
            this.guid = guid;
        }

        public boolean matchesGuid(Object other) {
            return guid.equals(other);
        }
    }

    /** A stats slot's item as a search target. */
    public record ItemGuid(String id) {}

    // ---------------------------------------------------------------- saved settings

    private static boolean loaded;
    private static boolean statsPanelVisible = true;
    private static final Set<StatsSection> visibleSections = EnumSet.allOf(StatsSection.class);
    private static boolean useInventorySnapshot;
    private static CollapseMode collapseMode = CollapseMode.EXPANDED;

    private TreeState() {}

    private static Path file() {
        return Platform.get().configDir()
            .resolve("neiaddon")
            .resolve("crafting_tree.properties");
    }

    private static void ensureLoaded() {
        if (loaded) return;
        loaded = true;
        Path file = file();
        if (!Files.exists(file)) return;
        Properties p = new Properties();
        try (Reader in = Files.newBufferedReader(file)) {
            p.load(in);
        } catch (IOException | RuntimeException e) {
            NeiAddon.LOGGER.warn("Could not read the crafting tree settings", e);
            return;
        }
        statsPanelVisible = Boolean.parseBoolean(p.getProperty("statsVisible", "true"));
        int mask = parseInt(p.getProperty("statsSections"), -1);
        visibleSections.clear();
        for (StatsSection s : StatsSection.values()) {
            if ((mask & (1 << s.ordinal())) != 0) visibleSections.add(s);
        }
        useInventorySnapshot = Boolean.parseBoolean(p.getProperty("useInventorySnapshot", "false"));
        int mode = parseInt(p.getProperty("collapseMode"), 0);
        collapseMode = mode >= 0 && mode < CollapseMode.values().length ? CollapseMode.values()[mode] : CollapseMode.EXPANDED;
    }

    private static int parseInt(String s, int fallback) {
        try {
            return s == null ? fallback : Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static void save() {
        Properties p = new Properties();
        p.setProperty("statsVisible", Boolean.toString(statsPanelVisible));
        p.setProperty("statsSections", Integer.toString(sectionMask()));
        p.setProperty("useInventorySnapshot", Boolean.toString(useInventorySnapshot));
        p.setProperty("collapseMode", Integer.toString(collapseMode.ordinal()));
        try {
            Path file = file();
            Files.createDirectories(file.getParent());
            try (Writer out = Files.newBufferedWriter(file)) {
                p.store(out, "Crafting tree window");
            }
        } catch (IOException | RuntimeException e) {
            NeiAddon.LOGGER.warn("Could not save the crafting tree settings", e);
        }
    }

    private static int sectionMask() {
        int mask = 0;
        for (StatsSection s : visibleSections) mask |= 1 << s.ordinal();
        return mask;
    }

    public static boolean statsPanelVisible() {
        ensureLoaded();
        return statsPanelVisible;
    }

    public static void toggleStatsPanelVisible() {
        ensureLoaded();
        statsPanelVisible = !statsPanelVisible;
        save();
    }

    public static Set<StatsSection> visibleSections() {
        ensureLoaded();
        return visibleSections;
    }

    public static void toggleStatsSection(StatsSection section) {
        ensureLoaded();
        if (!visibleSections.remove(section)) visibleSections.add(section);
        save();
    }

    public static boolean useInventorySnapshot() {
        ensureLoaded();
        return useInventorySnapshot;
    }

    public static void toggleUseInventorySnapshot() {
        ensureLoaded();
        useInventorySnapshot = !useInventorySnapshot;
        save();
    }

    public static CollapseMode collapseMode() {
        ensureLoaded();
        return collapseMode;
    }

    public static void cycleCollapseMode() {
        ensureLoaded();
        collapseMode = CollapseMode.values()[(collapseMode.ordinal() + 1) % CollapseMode.values().length];
        save();
    }

    // ---------------------------------------------------------------- per group

    /** The view of one group: where the camera is, which items are folded, what is searched. */
    public static final class Group {

        public boolean initialized;
        public final TreeCamera camera = new TreeCamera();
        public final Set<ChainItem> collapsedItems = new HashSet<>();
        public TreeSlot replaceRecipeNode;
        public SearchResult searchItemResult;
        public SearchResult searchStatsResult;

        public SearchResult activeSearchResult() {
            return searchItemResult != null ? searchItemResult : searchStatsResult;
        }
    }

    private static final Map<Bookmarks.Page, Map<Integer, Group>> GROUPS = new WeakHashMap<>();

    public static Group of(Bookmarks.Page page, int groupId) {
        return GROUPS.computeIfAbsent(page, p -> new HashMap<>())
            .computeIfAbsent(groupId, g -> new Group());
    }
}

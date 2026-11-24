package neiaddon;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;
import mezz.jei.api.ingredients.IIngredientHelper;
import mezz.jei.api.ingredients.IIngredientRenderer;
import mezz.jei.api.ingredients.IIngredientType;
import mezz.jei.api.runtime.IIngredientManager;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.tooltip.DefaultTooltipPositioner;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.alchemy.PotionBrewing;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.equipment.Equippable;
import neiaddon.event.ClientTickEvent;
import neiaddon.event.EventPriority;
import neiaddon.event.ScreenEvent;
import neiaddon.event.SubscribeEvent;
import neiaddon.platform.Platform;
import org.joml.Matrix3x2fStack;
import org.lwjgl.glfw.GLFW;

/**
 * NEI's subsets menu: a tree of tags (Blocks, Items with its tools, weapons, armor, food, fluids and potions, one tag per
 * mod, one per creative tab, favorites, and the tags of {@code config/neiaddon/subsets/*.cfg}). The menu opens from the S
 * button or the Item Subsets button at the top of the screen, in columns of 18 px rows: hovering a tag opens its column
 * beside it, a column lists the tag's children, then its entries as icons. A row is a button in three states (all shown,
 * some hidden, all hidden). Left click on a tag shows all its entries, right click hides them, two left clicks show only
 * this tag; on an entry left shows it and right hides it. Hidden entries are taken out of JEI's list ({@link ListHiding})
 * and saved per world in {@code config/neiaddon/subsets-hidden/}. Shift + left searches (a mod: {@code @mod}, a creative tab: {@code "%tab"}), Ctrl
 * + click on an entry gives it. The menu lives only on the screen it was opened on: it closes when
 * another screen opens, when that screen is gone, or when its button is no longer shown.
 */
public final class Subsets {

    private static final int ROW = 18;
    private static final int SCROLL_BAR = 6;
    private static final int BACKGROUND = 0xFF202020;
    private static final int TAB_WIDTH_MIN = 20;
    private static final long DOUBLE_MS = 500;
    private static final int CLOSE_TICKS = 10;
    private static final String REASON = "subsets";

    // ---------------------------------------------------------------- the tree

    private static final int OTHER = 0, MOD = 1, TAB = 2;

    private static final class Tag {

        String name;
        final int kind;
        /** The mod's namespace (kind MOD). */
        String ns;
        final Map<String, Tag> byName = new LinkedHashMap<>();
        List<Tag> kids = List.of();
        final List<ListHiding.Entry> entries = new ArrayList<>();
        /** The keys of this tag's entries and its children's, built when first needed. */
        Set<String> keys;
        int stateVersion = -1, state;

        Tag(String name, int kind) {
            this.name = name;
            this.kind = kind;
        }

        int count() {
            return kids.size() + entries.size();
        }
    }

    private static Tag root;
    private static Object builtFor;
    private static Set<String> allKeys = Set.of();

    private static Tag child(Tag parent, String name) {
        return parent.byName.computeIfAbsent(name, n -> new Tag(n, OTHER));
    }

    private static Tag child(Tag parent, String name, int kind) {
        Tag t = parent.byName.get(name);
        if (t == null) {
            t = new Tag(name, kind);
            parent.byName.put(name, t);
        }
        return t;
    }

    private static void add(Tag tag, ListHiding.Entry e) {
        int n = tag.entries.size();
        if (n == 0 || tag.entries.get(n - 1) != e) tag.entries.add(e);
    }

    /** Builds the tree into a local root; the fields are set only when it is complete. */
    private static void build() {
        IJeiRuntime rt = Calculator.runtime;
        // no level yet: brewing ingredients need it, so build on a later click rather than cache a tree without them
        if (rt == null || Minecraft.getInstance().level == null) return;
        Tag root = new Tag("", OTHER);
        List<ListHiding.Entry> all = ListHiding.entries();
        Set<String> keys = new HashSet<>();
        for (ListHiding.Entry e : all) keys.add(e.key());
        List<Tag> rootTags = new ArrayList<>();
        boolean warned = false;
        IIngredientManager mgr = rt.getIngredientManager();
        IIngredientType<?> fluidType = fluidType(rt);
        Minecraft mc = Minecraft.getInstance();
        PotionBrewing brewing = mc.level == null ? null : mc.level.potionBrewing();

        Tag mods = child(root, "Mod");
        Tag blocks = child(root, "Blocks");
        Tag spawners = child(blocks, "Mob Spawners");
        Tag items = child(root, "Items");
        Tag pickaxes = child(child(items, "Tools"), "Pickaxes");
        Tag shovels = child(child(items, "Tools"), "Shovels");
        Tag axes = child(child(items, "Tools"), "Axes");
        Tag hoes = child(child(items, "Tools"), "Hoes");
        Tag otherTools = child(child(items, "Tools"), "Other");
        Tag swords = child(child(items, "Weapons"), "Swords");
        Tag ranged = child(child(items, "Weapons"), "Ranged");
        Tag helmets = child(child(items, "Armor"), "Helmets");
        Tag chestplates = child(child(items, "Armor"), "Chestplates");
        Tag leggings = child(child(items, "Armor"), "Leggings");
        Tag boots = child(child(items, "Armor"), "Boots");
        Tag food = child(items, "Food");
        Tag fluids = child(items, "Fluids");
        Tag potions = child(items, "Potions");
        Tag positive = child(potions, "Positive");
        Tag negative = child(potions, "Negative");
        Tag neutral = child(potions, "Neutral");
        Tag lingering = child(potions, "Lingering");
        Tag ingredients = child(potions, "Ingredients");

        // creative tabs: which tags a key belongs to
        Map<String, List<Tag>> tabsOf = new HashMap<>();
        Tag tabs = child(root, "Creative Tabs");
        try {
            prepareTabs();
            for (CreativeModeTab tab : CreativeModeTabs.allTabs()) {
                if (tab.getType() != CreativeModeTab.Type.CATEGORY) continue;
                String name = tab.getDisplayName()
                    .getString();
                if (name.isBlank()) continue;
                Tag t = child(tabs, name, TAB);
                for (ItemStack s : tab.getDisplayItems()) {
                    String k = ListHiding.keyOf(s);
                    if (k != null) tabsOf.computeIfAbsent(k, x -> new ArrayList<>())
                        .add(t);
                }
            }
        } catch (RuntimeException | LinkageError ex) {
            NeiAddon.LOGGER.warn("Could not read the creative tabs; the subsets menu has no Creative Tabs tag", ex);
        }

        Tag favorites = child(root, "Favorites");
        Tag manual = child(favorites, "Manual");
        List<CustomRule> custom = customRules(rootTags);
        Set<String> favoriteBases = new HashSet<>();
        try {
            for (String id : Favorites.itemIds()) {
                int at = id.indexOf(StackIds.MARK);
                favoriteBases.add(at < 0 ? id : id.substring(0, at));
            }
        } catch (RuntimeException ex) {
            NeiAddon.LOGGER.warn("Could not read the favorites; the subsets menu has no Favorites tag", ex);
        }

        for (ListHiding.Entry e : all) {
            try {
            String ns = namespace(mgr, e);
            if (ns != null) {
                Tag m = mods.byName.get(ns);
                if (m == null) {
                    m = new Tag(Platform.get()
                        .modDisplayName(ns)
                        .orElse(ns), MOD);
                    m.ns = ns;
                    mods.byName.put(ns, m);
                }
                add(m, e);
            }
            if (fluidType != null && e.type()
                .equals(fluidType)) {
                add(fluids, e);
            }
            if (!(e.ingredient() instanceof ItemStack s) || s.isEmpty()) continue;
            Item item = s.getItem();
            List<Tag> ofTabs = tabsOf.get(e.key());
            if (ofTabs != null) for (Tag t : ofTabs) add(t, e);
            if (item instanceof BlockItem) {
                add(blocks, e);
                if (item == Items.SPAWNER) add(spawners, e);
            } else {
                add(items, e);
                if (s.is(ItemTags.PICKAXES)) add(pickaxes, e);
                if (s.is(ItemTags.SHOVELS)) add(shovels, e);
                if (s.is(ItemTags.AXES)) add(axes, e);
                if (s.is(ItemTags.HOES)) add(hoes, e);
                if (item == Items.FISHING_ROD || item == Items.FLINT_AND_STEEL || item == Items.SHEARS) add(otherTools, e);
                if (s.is(ItemTags.SWORDS)) add(swords, e);
                if (item == Items.BOW || item == Items.CROSSBOW || s.is(ItemTags.ARROWS)) add(ranged, e);
                EquipmentSlot slot = armorSlot(s);
                if (slot == EquipmentSlot.HEAD) add(helmets, e);
                else if (slot == EquipmentSlot.CHEST) add(chestplates, e);
                else if (slot == EquipmentSlot.LEGS) add(leggings, e);
                else if (slot == EquipmentSlot.FEET) add(boots, e);
                if (s.has(DataComponents.FOOD)) add(food, e);
                if (item == Items.POTION || item == Items.SPLASH_POTION || item == Items.LINGERING_POTION) {
                    add(potions, e);
                    if (item == Items.LINGERING_POTION) add(lingering, e);
                    PotionContents contents = s.get(DataComponents.POTION_CONTENTS);
                    Iterator<MobEffectInstance> effects = contents == null ? null : contents.getAllEffects()
                        .iterator();
                    if (effects != null && effects.hasNext()) {
                        MobEffectCategory cat = effects.next()
                            .getEffect()
                            .value()
                            .getCategory();
                        add(cat == MobEffectCategory.BENEFICIAL ? positive : cat == MobEffectCategory.HARMFUL ? negative : neutral, e);
                    }
                }
                if (brewing != null && brewing.isIngredient(s)) add(ingredients, e);
            }
            if (favoriteBases.contains(BuiltInRegistries.ITEM.getKey(item)
                .toString())) add(manual, e);
            if (!custom.isEmpty()) {
                String id = BuiltInRegistries.ITEM.getKey(item)
                    .toString();
                for (CustomRule rule : custom) {
                    for (String filter : rule.filters()) {
                        if (CollapsibleItems.matches(filter, s, id)) {
                            add(rule.tag(), e);
                            break;
                        }
                    }
                }
            }
                    } catch (RuntimeException | LinkageError ex) {
                if (!warned) {
                    warned = true;
                    NeiAddon.LOGGER.warn("Could not sort an entry into the subsets tree (further ones are skipped silently)", ex);
                }
            }
        }
        // two mods with the same name are told apart by their namespace
        Map<String, Integer> names = new HashMap<>();
        for (Tag m : mods.byName.values()) names.merge(m.name, 1, Integer::sum);
        for (Tag m : mods.byName.values()) {
            if (names.get(m.name) > 1) m.name = m.name + " (" + m.ns + ")";
        }
        for (Tag f : rootTags) root.byName.putIfAbsent(f.name, f);
        finish(root);
        Subsets.allKeys = keys;
        Subsets.root = root;
        Subsets.builtFor = rt;
    }

    /** The armor slot of a stack: the vanilla armor tags, else a damageable equippable item (not an elytra); null for none. */
    private static EquipmentSlot armorSlot(ItemStack s) {
        if (s.is(ItemTags.HEAD_ARMOR)) return EquipmentSlot.HEAD;
        if (s.is(ItemTags.CHEST_ARMOR)) return EquipmentSlot.CHEST;
        if (s.is(ItemTags.LEG_ARMOR)) return EquipmentSlot.LEGS;
        if (s.is(ItemTags.FOOT_ARMOR)) return EquipmentSlot.FEET;
        Equippable eq = s.get(DataComponents.EQUIPPABLE);
        if (eq == null || !s.has(DataComponents.MAX_DAMAGE) || s.is(Items.ELYTRA)) return null;
        EquipmentSlot slot = eq.slot();
        return slot == EquipmentSlot.HEAD || slot == EquipmentSlot.CHEST || slot == EquipmentSlot.LEGS || slot == EquipmentSlot.FEET ? slot : null;
    }

    /** The tags are built when the file's lines are read, so they are in the tree even before they get entries. */
    private record CustomRule(Tag tag, List<String> filters) {}

    private static final String EXAMPLE = """
        # NEI Features for JEI: your own groups for the Subsets menu (Settings > Inventory > Subsets).
        # Every .cfg file here with at least one group line becomes a group in the menu, named after the file,
        # and each line in it becomes a sub-group:  Name = filter, filter, ...
        # A filter is an item tag (#minecraft:wool), a mod (@ae2) or an item id with * wildcards (*:*_spawn_egg).
        # Lines starting with # are ignored; a file with no other lines adds nothing. Remove the # to use an example:
        # Wool = #minecraft:wool
        # Spawn Eggs = *:*_spawn_egg
        # Limited Barrels = sophisticatedstorage:limited_barrel_*
        # All of Mekanism = @mekanism
        # Changes here are read when you join a world or JEI reloads.
        """;

    /**
     * Writes {@code config/neiaddon/subsets/example.cfg} (comments only) when the folder is missing, so a player finds the
     * format next to their own files (#47 follow-up: the folder was never created). Once the folder exists
     * nothing is written again, so a deleted example stays deleted. Called at client start.
     */
    public static void writeExampleIfMissing() {
        Path dir = Platform.get()
            .configDir()
            .resolve("neiaddon")
            .resolve("subsets");
        try {
            if (Files.exists(dir)) return;
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("example.cfg"), EXAMPLE, StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            NeiAddon.LOGGER.warn("Could not write the example in {}", dir, e);
        }
    }

    private static List<CustomRule> customRules(List<Tag> rootTags) {
        List<CustomRule> out = new ArrayList<>();
        Path dir = Platform.get()
            .configDir()
            .resolve("neiaddon")
            .resolve("subsets");
        if (!Files.isDirectory(dir)) return out;
        try (Stream<Path> files = Files.list(dir)) {
            List<Path> cfgs = files.filter(p -> p.getFileName()
                .toString()
                .endsWith(".cfg"))
                .sorted()
                .toList();
            for (Path file : cfgs) {
                String fileName = file.getFileName()
                    .toString();
                Tag fileTag = new Tag(fileName.substring(0, fileName.length() - 4), OTHER);
                boolean any = false;
                for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                    line = line.strip();
                    if (line.isEmpty() || line.startsWith("#")) continue;
                    int eq = line.indexOf('=');
                    if (eq <= 0) continue;
                    List<String> filters = new ArrayList<>();
                    for (String f : line.substring(eq + 1)
                        .split(",")) {
                        if (!f.isBlank()) filters.add(f.strip());
                    }
                    if (filters.isEmpty()) continue;
                    out.add(new CustomRule(child(fileTag, line.substring(0, eq)
                        .strip()), filters));
                    any = true;
                }
                if (any) rootTags.add(fileTag);
            }
        } catch (IOException | RuntimeException e) {
            NeiAddon.LOGGER.warn("Could not read the subsets in {}", dir, e);
        }
        return out;
    }

    /** Sorts the children by name and drops the tags with no entries and no children. */
    private static void finish(Tag t) {
        List<Tag> kids = new ArrayList<>();
        for (Tag k : t.byName.values()) {
            finish(k);
            if (k.count() > 0) kids.add(k);
        }
        kids.sort(Comparator.comparing((Tag k) -> k.name.toLowerCase()));
        t.kids = kids;
    }

    private static IIngredientType<?> fluidType(IJeiRuntime rt) {
        try {
            return rt.getJeiHelpers()
                .getPlatformFluidHelper()
                .getFluidIngredientType();
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }

    private static String namespace(IIngredientManager mgr, ListHiding.Entry e) {
        try {
            if (e.ingredient() instanceof ItemStack s) {
                return s.isEmpty() ? null
                    : BuiltInRegistries.ITEM.getKey(s.getItem())
                        .getNamespace();
            }
            return namespaceOf(mgr, e.type(), e.ingredient());
        } catch (RuntimeException | LinkageError ex) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> String namespaceOf(IIngredientManager mgr, IIngredientType<T> type, Object ingredient) {
        IIngredientHelper<T> helper = mgr.getIngredientHelper(type);
        return helper.getIdentifier((T) ingredient)
            .getNamespace();
    }

    /** The creative tabs list nothing until they are built; build them once the way the creative screen does. */
    private static void prepareTabs() {
        for (CreativeModeTab tab : CreativeModeTabs.allTabs()) {
            if (tab.getType() == CreativeModeTab.Type.CATEGORY && !tab.getDisplayItems()
                .isEmpty()) return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        boolean permissions = mc.options.operatorItemsTab()
            .get() && mc.player.canUseGameMasterBlocks();
        CreativeModeTabs.tryRebuildTabContents(mc.player.connection.enabledFeatures(), permissions, mc.level.registryAccess());
    }

    private static Set<String> keys(Tag t) {
        if (t.keys == null) {
            Set<String> set = new LinkedHashSet<>();
            for (ListHiding.Entry e : t.entries) set.add(e.key());
            for (Tag k : t.kids) set.addAll(keys(k));
            t.keys = set;
        }
        return t.keys;
    }

    // ---------------------------------------------------------------- hidden entries, saved per world

    private static final Set<String> hidden = new LinkedHashSet<>();
    /** Counts every change, so the cached state of a tag is known to be old. */
    private static int version;

    private static Object seenRuntime;
    private static String seenWorld;
    private static boolean seenOn = true;

    private static Path hiddenFile(String world) {
        String safe = world.replaceAll("[^A-Za-z0-9\\-_.]", "_");
        return Platform.get()
            .configDir()
            .resolve("neiaddon")
            .resolve("subsets-hidden")
            .resolve(safe + ".txt");
    }

    private static void load(String world) {
        hidden.clear();
        version++;
        Path file = hiddenFile(world);
        try {
            if (!Files.exists(file)) return;
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                line = line.strip();
                if (!line.isEmpty()) hidden.add(line);
            }
        } catch (IOException | RuntimeException e) {
            NeiAddon.LOGGER.warn("Could not read {}; no hidden entries for this world", file, e);
        }
    }

    private static void save() {
        String world = McCompat.worldKey();
        if (world == null) return;
        Path file = hiddenFile(world);
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.write(tmp, hidden, StandardCharsets.UTF_8);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | RuntimeException e) {
            NeiAddon.LOGGER.warn("Could not save {}", file, e);
        }
    }

    /** Tells the item list which entries are hidden (none while the setting is off; the saved list is kept). */
    private static void applyHidden() {
        ListHiding.setHidden(REASON, AddonConfig.SUBSETS.get() ? hidden : Set.of());
    }

    private static void changed() {
        version++;
        applyHidden();
        save();
    }

    private static void show(Set<String> keys) {
        if (hidden.removeAll(keys)) changed();
    }

    private static void hide(Set<String> keys) {
        if (hidden.addAll(keys)) changed();
    }

    private static void showAll() {
        if (hidden.isEmpty()) return;
        // only what this runtime lists: keys of other packs and mods stay saved
        if (hidden.removeAll(allKeys)) changed();
    }

    private static void showOnly(Tag t) {
        Set<String> keep = keys(t);
        boolean change = false;
        for (String k : allKeys) {
            if (!keep.contains(k) && hidden.add(k)) change = true;
        }
        if (hidden.removeAll(keep)) change = true;
        if (change) changed();
    }

    /** 0 all of the tag's entries shown, 1 some hidden, 2 all hidden. */
    private static int state(Tag t) {
        if (t.stateVersion != version) {
            int n = 0;
            Set<String> keys = keys(t);
            for (String k : keys) {
                if (hidden.contains(k)) n++;
            }
            t.state = n == 0 ? 0 : n == keys.size() ? 2 : 1;
            t.stateVersion = version;
        }
        return t.state;
    }

    // ---------------------------------------------------------------- the menu

    /** One open column: a tag's children, then its entries. */
    private static final class Col {

        final Tag tag;
        int x, y, w, h, rows, scroll;

        Col(Tag tag) {
            this.tag = tag;
        }

        boolean bar() {
            return tag.count() > rows;
        }

        int rowWidth() {
            return w - (bar() ? SCROLL_BAR : 0);
        }
    }

    private static final List<Col> cols = new ArrayList<>();
    /** The screen the menu is open on (null while closed). */
    private static Screen owner;
    private static boolean rightSide;
    /** Opened from the top button: the menu hangs down from it, not up from the S button. */
    private static boolean downward;
    private static long lastButtonClick, lastTagClick;
    private static Tag lastTag;
    private static int idle, lastMx, lastMy;
    private static Col hoverCol;
    private static int hoverIdx = -1;

    private Subsets() {}

    static boolean open() {
        return !cols.isEmpty();
    }

    static void close() {
        cols.clear();
        hoverCol = null;
        hoverIdx = -1;
        owner = null;
        idle = 0;
        lastTag = null;
    }

    /**
     * A click on the S or top button: opens the menu, or (a second click within half a second) shows every hidden entry
     * again. {@code edge} is the button's top edge when the menu stands up from the S button (it ends there), or the
     * button's bottom edge when it hangs down from the top button (it starts there).
     */
    static void click(Screen s, int left, int edge, boolean down) {
        long now = System.currentTimeMillis();
        if (open()) {
            if (now - lastButtonClick < DOUBLE_MS) {
                showAll();
                lastButtonClick = 0;
            } else {
                lastButtonClick = now;
            }
            return;
        }
        if (Calculator.runtime == null) return;
        if (root == null || builtFor != Calculator.runtime) {
            try {
                build();
            } catch (RuntimeException | LinkageError ex) {
                NeiAddon.LOGGER.warn("Could not build the subsets tree", ex);
                return;
            }
        }
        if (root == null || root.kids.isEmpty()) return;
        Font font = Minecraft.getInstance().font;
        Col c = size(font, root, s.height, down ? s.height - edge : edge);
        c.x = Math.max(2, Math.min(left, s.width - c.w - 2));
        c.y = down ? edge : edge - c.h;
        rightSide = left < s.width / 2;
        cols.add(c);
        owner = s;
        downward = down;
        idle = 0;
        lastButtonClick = now;
    }

    /** A column for the tag that is as tall as fits in {@code space} pixels. */
    private static Col size(Font font, Tag tag, int screenHeight, int space) {
        Col c = new Col(tag);
        int w = 0;
        for (Tag k : tag.kids) w = Math.max(w, font.width(k.name) + 4);
        if (!tag.entries.isEmpty()) w = Math.max(w, TAB_WIDTH_MIN);
        int fit = Math.max(1, Math.min(space - 2, screenHeight - 4) / ROW);
        c.rows = Math.min(tag.count(), fit);
        c.h = c.rows * ROW;
        c.w = w + (tag.count() > c.rows ? SCROLL_BAR : 0);
        return c;
    }

    private static Col childColumn(Screen s, Col parent, int index, Tag tag) {
        Font font = Minecraft.getInstance().font;
        Col c = size(font, tag, s.height, s.height);
        int rowY = parent.y + (index - parent.scroll) * ROW;
        int rx = parent.x + parent.w;
        int lx = parent.x - c.w;
        boolean rightFits = rx + c.w <= s.width;
        boolean leftFits = lx >= 0;
        if (rightSide) c.x = rightFits ? rx : leftFits ? lx : Math.max(0, s.width - c.w);
        else c.x = leftFits ? lx : rightFits ? rx : 0;
        c.y = Math.max(0, Math.min(rowY, s.height - c.h));
        return c;
    }

    private static Col colAt(double mx, double my) {
        for (int i = cols.size() - 1; i >= 0; i--) {
            Col c = cols.get(i);
            if (mx >= c.x && mx < c.x + c.w && my >= c.y && my < c.y + c.h) return c;
        }
        return null;
    }

    /** The index of the row under the point in the column, or -1 (also over its scroll bar). */
    private static int rowAt(Col c, double mx, double my) {
        if (mx < c.x || mx >= c.x + c.rowWidth() || my < c.y || my >= c.y + c.h) return -1;
        int index = c.scroll + (int) ((my - c.y) / ROW);
        return index < c.tag.count() ? index : -1;
    }

    /** Closes the menu when the screen is not the one it opened on, or when the button it hangs from is no longer shown. */
    private static void checkScreen(Screen current) {
        if (open() && (current != owner || !ConfigButton.anchorShown(owner, downward))) close();
    }

    @SubscribeEvent
    static void onOpening(ScreenEvent.Opening event) {
        if (open()) close();
    }

    @SubscribeEvent
    static void onTick(ClientTickEvent.Post event) {
        Screen current = McCompat.screen();
        checkScreen(current);
        if (open()) {
            boolean near = colAt(lastMx, lastMy) != null || ConfigButton.overSubsets(owner, lastMx, lastMy);
            idle = near ? 0 : idle + 1;
            if (idle >= CLOSE_TICKS) close();
        }
        // a new JEI runtime or world: the tree is built again, the world's hidden list is read and applied
        IJeiRuntime rt = Calculator.runtime;
        if (rt == null) return;
        boolean on = AddonConfig.SUBSETS.get();
        String world = McCompat.worldKey();
        boolean changed = on != seenOn;
        if (rt != seenRuntime) {
            seenRuntime = rt;
            root = null;
            close();
            changed = true;
        }
        if (world != null && !world.equals(seenWorld)) {
            seenWorld = world;
            load(world);
            changed = true;
        } else if (world == null && seenWorld != null) {
            // no world key: nothing of the previous world stays applied
            seenWorld = null;
            hidden.clear();
            version++;
            changed = true;
        }
        seenOn = on;
        if (changed) applyHidden();
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    static void onMousePressed(ScreenEvent.MouseButtonPressed.Pre event) {
        if (!open() || event.getScreen() != owner) return;
        double mx = event.getMouseX();
        double my = event.getMouseY();
        Col c = colAt(mx, my);
        if (c == null) {
            // a click elsewhere closes the menu; on the S or top button itself the button's own handler decides
            if (!ConfigButton.overSubsets(event.getScreen(), mx, my)) close();
            return;
        }
        event.setCanceled(true);
        int index = rowAt(c, mx, my);
        int button = event.getButton();
        if (index < 0 || button > 1) return;
        if (index < c.tag.kids.size()) tagClick(c.tag.kids.get(index), button);
        else entryClick(c.tag.entries.get(index - c.tag.kids.size()), button);
    }

    private static void tagClick(Tag t, int button) {
        long now = System.currentTimeMillis();
        if (button == 1) {
            hide(keys(t));
            return;
        }
        if (Minecraft.getInstance().hasShiftDown()) {
            String text = null;
            if (t.kind == MOD && t.ns != null) text = "@" + t.ns;
            else if (t.kind == TAB) text = "\"%" + t.name.replace("\"", "") + "\"";
            if (text != null && Calculator.runtime != null) {
                Calculator.runtime.getIngredientFilter()
                    .setFilterText(text);
                close();
            }
            return;
        }
        if (lastTag == t && now - lastTagClick < DOUBLE_MS) {
            showOnly(t);
            lastTag = null;
            return;
        }
        lastTag = t;
        lastTagClick = now;
        show(keys(t));
    }

    private static void entryClick(ListHiding.Entry e, int button) {
        if (Minecraft.getInstance().hasControlDown()) {
            if (BookmarkPanel.canGive() && e.ingredient() instanceof ItemStack s) {
                JeiGive.give(s.copy(), button == 0 ? Math.max(1, s.getMaxStackSize()) : 1);
            }
            return;
        }
        if (button == 0 && Minecraft.getInstance().hasShiftDown()) {
            String name = nameOf(e);
            if (name != null && Calculator.runtime != null) {
                Calculator.runtime.getIngredientFilter()
                    .setFilterText(name);
                close();
            }
            return;
        }
        if (button == 0) show(Set.of(e.key()));
        else hide(Set.of(e.key()));
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    static void onScroll(ScreenEvent.MouseScrolled.Pre event) {
        if (!open() || event.getScreen() != owner) return;
        Col c = colAt(event.getMouseX(), event.getMouseY());
        if (c == null) {
            if (ConfigButton.overSubsets(event.getScreen(), event.getMouseX(), event.getMouseY())) event.setCanceled(true);
            return;
        }
        int max = Math.max(0, c.tag.count() - c.rows);
        c.scroll = Math.max(0, Math.min(max, c.scroll - 3 * (int) Math.signum(event.getScrollDeltaY())));
        event.setCanceled(true);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    static void onKey(ScreenEvent.KeyPressed.Pre event) {
        if (open() && event.getScreen() == owner && event.getKeyCode() == GLFW.GLFW_KEY_ESCAPE) {
            close();
            event.setCanceled(true);
        }
    }

    // ---------------------------------------------------------------- drawing

    @SubscribeEvent(priority = EventPriority.LOWEST)
    static void onRender(ScreenEvent.Render.Foreground event) {
        checkScreen(McCompat.screen());
        if (!open() || event.getScreen() != owner) return;
        lastMx = event.getMouseX();
        lastMy = event.getMouseY();
        hoverCol = colAt(lastMx, lastMy);
        hoverIdx = hoverCol == null ? -1 : rowAt(hoverCol, lastMx, lastMy);
        // hovering a tag opens its column beside it; hovering an entry keeps what is open
        if (hoverCol != null && hoverIdx >= 0 && hoverIdx < hoverCol.tag.kids.size()) {
            Tag t = hoverCol.tag.kids.get(hoverIdx);
            int k = cols.indexOf(hoverCol);
            if (cols.size() <= k + 1 || cols.get(k + 1).tag != t) {
                while (cols.size() > k + 1) cols.remove(cols.size() - 1);
                cols.add(childColumn(owner, hoverCol, hoverIdx, t));
            }
        }
        GuiGraphicsExtractor g = event.getGuiGraphics();
        g.nextStratum();
        Matrix3x2fStack pose = g.pose();
        pose.pushMatrix();
        pose.identity();
        try {
            Font font = Minecraft.getInstance().font;
            for (Col c : cols) drawColumn(g, font, c);
            drawTooltip(g, font);
        } finally {
            pose.popMatrix();
        }
    }

    private static void drawColumn(GuiGraphicsExtractor g, Font font, Col c) {
        g.fill(c.x, c.y, c.x + c.w, c.y + c.h, BACKGROUND);
        int rw = c.rowWidth();
        int nKids = c.tag.kids.size();
        for (int r = 0; r < c.rows; r++) {
            int index = c.scroll + r;
            if (index >= c.tag.count()) break;
            int y = c.y + r * ROW;
            boolean over = c == hoverCol && index == hoverIdx;
            if (index < nKids) {
                Tag t = c.tag.kids.get(index);
                int st = state(t);
                row(g, font, c.x, y, rw, t.name, st, over);
            } else {
                ListHiding.Entry e = c.tag.entries.get(index - nKids);
                boolean off = hidden.contains(e.key());
                row(g, font, c.x, y, rw, null, off ? 2 : 0, over);
                drawEntry(g, e, c.x + (rw - 16) / 2, y + 1);
            }
        }
        if (c.bar()) {
            int bx = c.x + rw;
            g.fill(bx, c.y, bx + SCROLL_BAR, c.y + c.h, 0xFF101010);
            int count = c.tag.count();
            int thumb = Math.max(8, c.h * c.rows / count);
            int at = c.y + (c.h - thumb) * c.scroll / Math.max(1, count - c.rows);
            g.fill(bx + 1, at, bx + SCROLL_BAR - 1, at + thumb, 0xFFA0A0A0);
        }
    }

    /** A row as one of the addon's buttons: state 0 normal, 1 grey-tinted, 2 dark. */
    private static void row(GuiGraphicsExtractor g, Font font, int x, int y, int w, String label, int state, boolean over) {
        NeiSettings.button(g, font, x, y, w, ROW, null, state == 2 ? 0 : over ? 2 : 1);
        if (state == 1) g.fill(x + 1, y + 1, x + w - 1, y + ROW - 1, 0x60808080);
        if (label == null) return;
        String text = font.plainSubstrByWidth(label, w - 4);
        g.text(font, text, x + 2, y + (ROW - 8) / 2, over ? 0xFFFFFFA0 : state == 0 ? 0xFFE0E0E0 : 0xFFA0A0A0, true);
    }

    private static boolean drawFailed;

    private static void drawEntry(GuiGraphicsExtractor g, ListHiding.Entry e, int x, int y) {
        try {
            if (e.ingredient() instanceof ItemStack s) {
                g.fakeItem(s, x, y);
                return;
            }
            IJeiRuntime rt = Calculator.runtime;
            if (rt == null) return;
            Matrix3x2fStack pose = g.pose();
            pose.pushMatrix();
            pose.translate(x, y);
            try {
                render(g, rt.getIngredientManager(), e.type(), e.ingredient());
            } finally {
                pose.popMatrix();
            }
        } catch (RuntimeException | LinkageError ex) {
            if (!drawFailed) {
                drawFailed = true;
                NeiAddon.LOGGER.warn("Could not draw an entry of the subsets menu", ex);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> void render(GuiGraphicsExtractor g, IIngredientManager mgr, IIngredientType<T> type, Object ingredient) {
        IIngredientRenderer<T> renderer = mgr.getIngredientRenderer(type);
        renderer.render(g, (T) ingredient);
    }

    @SuppressWarnings({ "unchecked", "deprecation" })
    private static <T> List<Component> tooltipOf(IIngredientManager mgr, IIngredientType<T> type, Object ingredient) {
        IIngredientRenderer<T> renderer = mgr.getIngredientRenderer(type);
        return renderer.getTooltip((T) ingredient, Minecraft.getInstance().options.advancedItemTooltips ? TooltipFlag.Default.ADVANCED : TooltipFlag.Default.NORMAL);
    }

    @SuppressWarnings("unchecked")
    private static <T> String displayNameOf(IIngredientManager mgr, IIngredientType<T> type, Object ingredient) {
        return mgr.getIngredientHelper(type)
            .getDisplayName((T) ingredient);
    }

    private static String nameOf(ListHiding.Entry e) {
        try {
            if (e.ingredient() instanceof ItemStack s) {
                return s.getHoverName()
                    .getString();
            }
            IJeiRuntime rt = Calculator.runtime;
            return rt == null ? null : displayNameOf(rt.getIngredientManager(), e.type(), e.ingredient());
        } catch (RuntimeException | LinkageError ex) {
            return null;
        }
    }

    private static void drawTooltip(GuiGraphicsExtractor g, Font font) {
        if (hoverCol == null || hoverIdx < 0) return;
        List<Component> lines = new ArrayList<>();
        Map<String, String> keys = new LinkedHashMap<>();
        int nKids = hoverCol.tag.kids.size();
        if (hoverIdx < nKids) {
            Tag t = hoverCol.tag.kids.get(hoverIdx);
            lines.add(Component.literal(t.name));
            keys.put("Double LMB", "Show only this Subset");
            if (t.kind == MOD || t.kind == TAB) keys.put("SHIFT + LMB", "Search by Subset");
            keys.put("LMB", "Show Subset");
            keys.put("RMB", "Hide Subset");
        } else {
            ListHiding.Entry e = hoverCol.tag.entries.get(hoverIdx - nKids);
            try {
                IJeiRuntime rt = Calculator.runtime;
                if (rt != null) lines.addAll(tooltipOf(rt.getIngredientManager(), e.type(), e.ingredient()));
            } catch (RuntimeException | LinkageError ex) {
                String name = nameOf(e);
                if (name != null) lines.add(Component.literal(name));
            }
            if (BookmarkPanel.canGive() && e.ingredient() instanceof ItemStack) keys.put("CTRL + LMB", "Give out an Item");
            keys.put("SHIFT + LMB", "Search by Item");
            keys.put("LMB", "Show Item");
            keys.put("RMB", "Hide Item");
        }
        if (lines.isEmpty()) return;
        for (String line : HotkeyTips.hotkeyLines(keys)) lines.add(Component.literal(line));
        List<FormattedCharSequence> out = new ArrayList<>();
        for (Component line : lines) out.add(line.getVisualOrderText());
        g.setTooltipForNextFrame(font, out, DefaultTooltipPositioner.INSTANCE, lastMx, lastMy, true);
    }
}

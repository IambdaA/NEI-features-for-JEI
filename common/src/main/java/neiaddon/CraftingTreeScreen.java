package neiaddon;

import com.mojang.blaze3d.platform.InputConstants;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import mezz.jei.api.gui.IRecipeLayoutDrawable;
import mezz.jei.api.gui.drawable.IDrawable;
import mezz.jei.api.recipe.RecipeIngredientRole;
import neiaddon.TreeState.CollapseMode;
import neiaddon.TreeState.ItemGuid;
import neiaddon.TreeState.SearchResult;
import neiaddon.TreeState.StatsSection;
import neiaddon.chain.ChainItem;
import neiaddon.chain.ReadableNumber;
import neiaddon.chain.TreeCamera;
import neiaddon.chain.TreeLayout;
import neiaddon.chain.TreeMath;
import neiaddon.chain.TreeSlot;
import neiaddon.chain.TreeStats;
import neiaddon.chain.TreeStatsLayout;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TooltipFlag;
import org.joml.Matrix3x2fStack;
import org.lwjgl.glfw.GLFW;

/**
 * NEI's crafting tree window ({@code GuiCraftingTree}): the recipes of a bookmark group in crafting-chain mode drawn as
 * a tree, with a stats panel under it. The maths, layout and camera live in {@code neiaddon.chain} (ports of NEI's
 * CraftingTreeMath, Canvas, Camera and StatsPanel layout); this class is the window around them.
 *
 * <p>
 * Not ported: the screenshot button, NEI's search language (a simple name / @mod / id search instead), drop chances and
 * fluids (the chain maths leaves them out too).
 *
 * <p>
 * Ported from GTNH NotEnoughItems (LGPL-3.0-or-later).
 */
public final class CraftingTreeScreen extends Screen {

    private static final int MARGIN = 4;
    private static final int PADDING = 9;
    private static final int PADDING_TOP = 22;
    private static final int SLOT = TreeLayout.SLOT_SIZE;
    private static final int BUTTON = 16;
    private static final int TOOL_ICON = 16;
    private static final int BUTTON_SPACING = 20;
    private static final int LINE_COLOR = 0xFF888888;
    private static final int HOVER_COLOR = 0x66888888;
    private static final int STATS_HOVER_COLOR = 0x66999999;
    private static final int TAG_MARK = 0xE0E0E0;
    private static final float BADGE_SCALE = 0.6f;
    private static final float FIT_MARGIN = 8f;
    private static final int MENU_ROW = 16;
    private static final int MENU_TOP = 4;
    /** Crafting mode re-reads the inventory this often, in client ticks (a second). */
    private static final int LIVE_TICKS = 20;

    private static final TreeDraw.Sprite BACKGROUND = new TreeDraw.Sprite(0, 0, 64, 64);
    private static final TreeDraw.Sprite STATS_BACKGROUND = new TreeDraw.Sprite(26, 84, 46, 16);

    private enum Tool {
        INVENTORY,
        CRAFTING,
        FIT,
        COLLAPSE,
        STATS,
        SECTIONS,
        LINK,
        SCREENSHOT
    }

    private final Screen prev;
    /** JEI's recipe screen's parentScreen when the tree was opened from that screen (see {@link #restoreJeiParent}). */
    private final Screen jeiParent;
    private final Bookmarks.Page page;
    private final int gid;
    private final TreeState.Group ui;

    private TreeMath math = new TreeMath(List.of(), Set.of());
    private final TreeLayout canvas = new TreeLayout();
    private Map<String, Long> inventory = Map.of();
    /** Client ticks since the inventory was last read for crafting mode. */
    private int liveTicks;
    private EditBox search;

    // stats panel
    private final List<String> statLabels = new ArrayList<>();
    private final List<List<TreeStats.Row>> statGroups = new ArrayList<>();
    private TreeStatsLayout statsLayout;
    private boolean statsVisible;
    private int statsX, statsY, statsW, statsH;
    private int statsScroll;
    private double statsGrab = -1;

    // stats sections menu
    private boolean menuOpen;
    private int menuX, menuY, menuW, menuH;

    // what the mouse is over (updated every frame)
    private TreeSlot hoveredNode;
    private TreeSlot hoveredBadge;
    private TreeStatsLayout.Placed hoveredStat;
    private Bookmarks.Entry accepting;
    private int lastMouseX, lastMouseY;

    private final Map<String, IRecipeLayoutDrawable<?>> pictures = new HashMap<>();
    private final Set<String> noPicture = new HashSet<>();

    private CraftingTreeScreen(Screen prev, Bookmarks.Page page, int gid) {
        super(Component.translatable("neiaddon.tree.title"));
        this.prev = prev;
        this.jeiParent = jeiParentOf(prev);
        this.page = page;
        this.gid = gid;
        this.ui = TreeState.of(page, gid);
    }

    public static void open(Bookmarks.Page page, int gid) {
        Minecraft mc = Minecraft.getInstance();
        McCompat.setScreen(new CraftingTreeScreen(McCompat.screen(), page, gid));
    }

    private static String tr(String key, Object... args) {
        return Component.translatable("neiaddon.tree." + key, args)
            .getString();
    }

    // ---------------------------------------------------------------- layout

    private int wx() {
        return MARGIN + PADDING;
    }

    private int wy() {
        return MARGIN + PADDING_TOP;
    }

    private int ww() {
        return width - MARGIN * 2 - PADDING * 2;
    }

    private int wh() {
        return height - MARGIN * 2 - PADDING_TOP - PADDING;
    }

    private int toolX(Tool tool) {
        return MARGIN + PADDING + BUTTON_SPACING * tool.ordinal();
    }

    private int toolY() {
        return MARGIN + 4;
    }

    private int searchWidth() {
        return Math.min(140, (width - MARGIN * 2 - PADDING * 2) / 3);
    }

    private static boolean in(double mx, double my, double x, double y, double w, double h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    // ---------------------------------------------------------------- setup

    @Override
    protected void init() {
        // the group can go while a recipe screen opened from the tree is shown
        if (!Bookmarks.groupExists(page, gid)) {
            restoreJeiParent();
            McCompat.setScreen(prev);
            return;
        }
        TreeRecipes.stopWaiting();
        TreeRecipes.reset();
        pictures.clear();
        noPicture.clear();
        ui.replaceRecipeNode = null;
        ui.searchItemResult = null;
        ui.searchStatsResult = null;
        ui.camera.endDrag();
        menuOpen = false;
        statsGrab = -1;

        search = new EditBox(font, width - MARGIN - searchWidth() - PADDING, MARGIN + 5, searchWidth(), 14, Component.literal("Search"));
        search.setMaxLength(256);
        search.setResponder(text -> onSearchTextChanged());

        ui.camera.setViewport(wx(), wy(), ww(), wh());
        rebuildTree(null);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        restoreJeiParent();
        McCompat.setScreen(prev);
    }

    // ---------------------------------------------------------------- JEI's recipe screen under the tree

    /** JEI's recipe screen, matched by class name (as RecipeHearts does; JEI's class is not part of this addon's API). */
    private static final String JEI_RECIPES_GUI = "mezz.jei.gui.recipes.RecipesGui";
    /** JEI's recipe screen's parentScreen (what its Esc goes back to), or null until first used. */
    private static Field jeiParentField;
    private static boolean warnedJeiParent;

    /** JEI's recipe screen's parentScreen when {@code prev} is that screen, else null. */
    private static Screen jeiParentOf(Screen prev) {
        if (!isJeiRecipesScreen(prev)) return null;
        try {
            return (Screen) jeiParentField(prev).get(prev);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            warnJeiParent(e);
            return null;
        }
    }

    /**
     * A lookup from the tree reuses JEI's recipe screen, and JEI then keeps the tree as that screen's parent, so Esc there
     * would go back into the tree. Before the tree goes back to JEI's screen, its parent is put back as it was when the tree
     * was opened from that screen. JEI empties the parent when its Esc already went back to the tree, so an empty parent is
     * put back too.
     */
    private void restoreJeiParent() {
        if (!isJeiRecipesScreen(prev)) return;
        try {
            Field parent = jeiParentField(prev);
            Object now = parent.get(prev);
            if (now == this || now == null) parent.set(prev, jeiParent);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            warnJeiParent(e);
        }
    }

    private static boolean isJeiRecipesScreen(Screen s) {
        return s != null && s.getClass()
            .getName()
            .equals(JEI_RECIPES_GUI);
    }

    private static Field jeiParentField(Screen recipes) throws NoSuchFieldException {
        if (jeiParentField == null) jeiParentField = JeiAreas.field(recipes.getClass(), "parentScreen");
        return jeiParentField;
    }

    private static void warnJeiParent(Throwable e) {
        if (warnedJeiParent) return;
        warnedJeiParent = true;
        NeiAddon.LOGGER.warn("Could not read or restore JEI's recipe screen parent; Esc on JEI's recipe screen may return to the crafting tree", e);
    }

    /** What the player has: every slot of the open menu except a result slot (NEI's getInventoryItems). */
    static Map<String, Long> readInventory() {
        Minecraft mc = Minecraft.getInstance();
        Map<String, Long> items = new LinkedHashMap<>();
        if (mc.player == null) return items;
        AbstractContainerMenu menu = mc.player.containerMenu;
        for (Slot slot : menu.slots) {
            if (slot.hasItem() && !CraftSlots.isResult(slot) && slot.mayPickup(mc.player)) {
                items.merge(RecipeData.idOf(slot.getItem()), (long) slot.getItem().getCount(), Long::sum);
            }
        }
        return items;
    }

    private Set<String> collapsedRecipes() {
        Bookmarks.GroupMeta meta = page.metas.get(gid);
        return meta != null ? meta.collapsedRecipes : Set.of();
    }

    /** Crafting mode of this tree's group (the toolbar button): the tree counts the player's inventory live. */
    private boolean craftingOn() {
        Bookmarks.GroupMeta meta = page.metas.get(gid);
        return meta != null && meta.crafting;
    }

    /** The tree counts the inventory: an inventory snapshot (the INVENTORY button), or crafting mode, which reads it live. */
    private boolean inventoryLive() {
        return TreeState.useInventorySnapshot() || craftingOn();
    }

    /** The inventory changed under the tree: the nodes are new, so the search's matches are found again (camera kept). */
    private void inventoryChanged() {
        rebuildTree(null);
        updateSearchItemMatches();
        updateSearchStatsMatches();
    }

    @Override
    public void tick() {
        super.tick();
        if (!craftingOn() || TreeShot.running(this) || ++liveTicks < LIVE_TICKS) return;
        liveTicks = 0;
        if (!readInventory().equals(inventory)) inventoryChanged();
    }

    /** The chooser took an item for one of the tree's bookmark entries: rebuild the tree as the wheel switch does. */
    void tagChoiceApplied() {
        rebuildTree(null);
    }

    private void rebuildTree(TreeSlot anchor) {
        List<ChainItem> chainItems = Bookmarks.chainItems(page, gid);
        math = new TreeMath(chainItems, collapsedRecipes());
        // issue #42 follow-up: the node of a multi-result recipe shows the result the player entered the recipe on
        // at its leftmost slot (the others keep their order); the choice is the result the recipe's heart selects
        math.setChosenOutputs(chosenOutputs(chainItems));
        math.initialItems.clear();
        inventory = inventoryLive() ? readInventory() : Map.of();
        for (Map.Entry<String, Long> e : inventory.entrySet()) {
            math.initialItems.add(new ChainItem(e.getKey(), null, ChainItem.Type.ITEM, 1, e.getValue(), null, null, -1));
        }
        canvas.graph.rebuild(math);

        if (!ui.initialized) updateCollapsedItems();
        else relayout(anchor);

        rebuildStats();

        if (!ui.initialized) {
            ui.initialized = true;
            fitToView();
        }
    }

    private void relayout(TreeSlot anchor) {
        int[] moved = canvas.recalculate(collapsedRecipes(), ui.collapsedItems, anchor);
        if (moved != null) {
            ui.camera.scrollX += moved[0];
            ui.camera.scrollY += moved[1];
        }
    }

    /** NEI's updateCollapsedItems: the toolbar's collapse mode decides which nodes start folded. */
    private void updateCollapsedItems() {
        ui.collapsedItems.clear();
        CollapseMode mode = TreeState.collapseMode();
        for (TreeSlot node : canvas.graph.childrenOf.keySet()) {
            if (node == null || canvas.graph.roots.contains(node)) continue;
            boolean collapse = switch (mode) {
                case EXPANDED -> false;
                case SMART -> !canvas.graph.childrenNeedCraftingCache.contains(node);
                case ALL -> true;
            };
            if (collapse) ui.collapsedItems.add(node.ingrItem);
        }
        relayout(null);
    }

    /** NEI's CraftingTreeCanvas.fitToView. */
    private void fitToView() {
        TreeCamera cam = ui.camera;
        float firstCenterX = canvas.graph.roots.isEmpty() ? canvas.treeWidth / 2f
            : canvas.pointByNode.get(canvas.graph.roots.get(0)).x + SLOT / 2f;
        cam.zoomLevel = 1.0f;
        cam.scrollX = canvas.treeWidth <= (cam.widgetW - FIT_MARGIN) ? (canvas.treeWidth - cam.widgetW) / 2f
            : firstCenterX - cam.widgetW / 2f;
        cam.scrollY = canvas.treeHeight <= (cam.widgetH - FIT_MARGIN) ? (canvas.treeHeight - cam.widgetH) / 2f : -FIT_MARGIN;
    }

    // ---------------------------------------------------------------- stats data

    private void addGroup(String label, List<TreeStats.Row> rows) {
        if (rows.isEmpty()) return;
        statLabels.add(label);
        statGroups.add(rows);
    }

    private void rebuildStats() {
        statLabels.clear();
        statGroups.clear();
        Set<StatsSection> sections = TreeState.visibleSections();

        List<TreeStats.Row> ingredients = new ArrayList<>();
        if (sections.contains(StatsSection.INGREDIENTS_NEEDED)) ingredients.addAll(TreeStats.inputs(math));
        if (sections.contains(StatsSection.INGREDIENTS_AVAILABLE)) ingredients.addAll(TreeStats.available(math));
        addGroup(tr("stats.ingredients"), ingredients);

        boolean needed = sections.contains(StatsSection.CRAFTING_NEEDED);
        boolean available = sections.contains(StatsSection.CRAFTING_AVAILABLE);
        if (needed || available) addGroup(tr("stats.crafting"), TreeStats.crafting(canvas.graph, needed, available));

        List<TreeStats.Row> results = new ArrayList<>();
        if (sections.contains(StatsSection.RESULTS)) results.addAll(TreeStats.outputs(math));
        if (sections.contains(StatsSection.REMAINDERS)) results.addAll(TreeStats.remainders(math));
        addGroup(tr("stats.results"), results);

        if (sections.contains(StatsSection.HANDLERS)) addGroup(tr("stats.handlers"), TreeStats.handlers(math));

        List<Integer> sizes = new ArrayList<>();
        for (List<TreeStats.Row> group : statGroups) sizes.add(group.size());
        statsLayout = TreeStatsLayout.compute(statLabels, sizes, ww(), wh(), text -> font.width(text));
        statsW = ww();
        statsX = wx();
        statsH = statsLayout.panelHeight;
        statsY = wy() + wh() - statsH;
        setStatsScroll(statsScroll);

        updateSearchItemMatches();
        updateSearchStatsMatches();
        statsVisible = TreeState.statsPanelVisible() && !statGroups.isEmpty();
        ui.camera.setViewport(wx(), wy(), ww(), wh() - (statsVisible ? statsH : 0));
    }

    private int statsActualWidth() {
        return statsLayout.shiftX + statsLayout.contentWidth;
    }

    private int statsVisibleWidth() {
        return statsW - 2;
    }

    private boolean statsCanScroll() {
        return statsVisible && statsVisibleWidth() < statsActualWidth();
    }

    private boolean setStatsScroll(int offset) {
        int max = Math.max(0, statsActualWidth() - statsVisibleWidth());
        int before = statsScroll;
        statsScroll = Math.max(0, Math.min(max, offset));
        return before != statsScroll;
    }

    // scrollbar geometry: NEI's ScrollBar with trackPadding(1, -5, 1, 0) and the 24 x 4 textures
    private int barX() {
        return statsX + 1;
    }

    private int barY() {
        return statsY + statsH - 5;
    }

    private int barW() {
        return statsW - 2;
    }

    private float thumbSize() {
        int track = barW();
        return Math.max(Math.min(track, track / (float) statsActualWidth() * statsVisibleWidth()), TreeDraw.STATS_THUMB.w());
    }

    private int thumbX() {
        float free = barW() - thumbSize();
        float offset = statsScroll / (float) (statsActualWidth() - statsVisibleWidth()) * free;
        return barX() + (int) Math.ceil(offset);
    }

    private boolean overBar(double mx, double my) {
        return statsCanScroll() && in(mx, my, barX(), barY(), barW(), TreeDraw.STATS_TRACK.h());
    }

    private void barDrag(double mx) {
        float free = barW() - thumbSize();
        if (free <= 0) return;
        float relative = (float) (mx - barX());
        float offset = (relative - (float) statsGrab) * (statsActualWidth() - statsVisibleWidth()) / free;
        setStatsScroll((int) offset);
    }

    // ---------------------------------------------------------------- hit tests

    private TreeLayout.Point pointOf(TreeSlot node) {
        return canvas.pointByNode.get(node);
    }

    private TreeSlot findNode(double mx, double my, java.util.function.BiPredicate<TreeSlot, double[]> test) {
        double[] local = ui.camera.toWorldPoint(mx, my);
        for (List<TreeSlot> row : canvas.rows.subMap(ui.camera.visibleRowMin(), ui.camera.visibleRowMax())
            .values()) {
            for (TreeSlot node : row) {
                if (test.test(node, local)) return node;
            }
        }
        return null;
    }

    private TreeSlot nodeAt(double mx, double my) {
        return findNode(mx, my, (node, local) -> {
            TreeLayout.Point p = pointOf(node);
            // issue #42 follow-up: a merged node is hovered, clicked and keyed as one over all its result slots
            return local[0] >= p.x && local[0] < p.x + SLOT * node.outputs().size() && local[1] >= p.y && local[1] < p.y + SLOT;
        });
    }

    /**
     * Which of a merged node's result slots the mouse is over, 0 = leftmost (issue #42 follow-up: each slot gets a
     * tooltip of its own; the mouse is inside the node, so the index is always within its outputs).
     */
    private int hoveredOutputIndex(TreeSlot node, int mx, int my) {
        double[] local = ui.camera.toWorldPoint(mx, my);
        TreeLayout.Point p = pointOf(node);
        return Math.max(0, Math.min((int) ((local[0] - p.x) / SLOT), node.outputs().size() - 1));
    }

    private float badgeSize() {
        return 16 * BADGE_SCALE;
    }

    private boolean hasBadge(TreeSlot node) {
        return node.prefItem != null && canvas.graph.childrenOf.containsKey(node);
    }

    private TreeSlot badgeAt(double mx, double my) {
        return findNode(mx, my, (node, local) -> {
            if (!hasBadge(node)) return false;
            TreeLayout.Point p = pointOf(node);
            float size = badgeSize();
            float bx = p.x - size / 2 + 1;
            float by = p.y - size / 2 + 1;
            return local[0] >= bx && local[0] < bx + size && local[1] >= by && local[1] < by + size;
        });
    }

    private boolean hasArrow(TreeSlot node) {
        return canvas.graph.childrenOf.containsKey(node) && node.prefItem != null
            && !(canvas.collapsedRecipes.contains(node.prefItem.recipeId) && !canvas.graph.roots.contains(node));
    }

    private TreeSlot arrowAt(double mx, double my) {
        return findNode(mx, my, (node, local) -> {
            if (!hasArrow(node)) return false;
            TreeLayout.Point p = pointOf(node);
            float ix = p.x + SLOT / 2 - TreeDraw.ICON_COLLAPSED.w() - 1;
            float iy = p.y + SLOT + 1;
            return local[0] >= ix && local[0] < ix + TreeDraw.ICON_COLLAPSED.w() && local[1] >= iy
                && local[1] < iy + TreeDraw.ICON_COLLAPSED.h();
        });
    }

    private Tool toolAt(double mx, double my) {
        for (Tool tool : Tool.values()) {
            if (in(mx, my, toolX(tool), toolY(), BUTTON, BUTTON)) return tool;
        }
        return null;
    }

    private TreeStatsLayout.Placed statAt(double mx, double my) {
        if (!statsVisible || !in(mx, my, statsX, statsY, statsW, statsH)) return null;
        for (TreeStatsLayout.Placed placed : statsLayout.placed) {
            if (placed.index() < 0) continue;
            if (in(mx, my, statX(placed), statY(placed), SLOT, SLOT)) return placed;
        }
        return null;
    }

    private int statX(TreeStatsLayout.Placed placed) {
        return statsX + 1 - statsScroll + statsLayout.shiftX + placed.x();
    }

    private int statY(TreeStatsLayout.Placed placed) {
        return statsY + 4 + placed.y();
    }

    private TreeStats.Row rowOf(TreeStatsLayout.Placed placed) {
        return statGroups.get(placed.section())
            .get(placed.index());
    }

    // ---------------------------------------------------------------- items

    static ItemStack stackOf(String id, Object payload) {
        if (payload instanceof Bookmarks.Entry e && e.key != null) return e.key.sample;
        if (payload instanceof ItemStack s) return s;
        return FluidStacks.stackOf(id);
    }

    private static ItemStack stackOf(ChainItem item) {
        return stackOf(item.item, item.payload);
    }

    private static ItemStack stackOf(TreeSlot node) {
        // issue #42 follow-up: the leftmost slot of a merged node is its first output, the chosen result when the
        // player has one; that is not always the item the maths keyed the node on (node.shown())
        return stackOf(node.outputs().get(0).shown());
    }

    /**
     * Issue #42 follow-up: for every recipe of the group, the result the player chose for it — the result whose
     * favorite points at this recipe, which is what the recipe's heart selects (see {@code RecipeHearts}). Recipes
     * with no chosen result keep their own order. The map feeds {@link TreeMath}, which puts that result first.
     */
    private static Map<String, String> chosenOutputs(List<ChainItem> chainItems) {
        Map<String, String> chosen = new HashMap<>();
        for (ChainItem item : chainItems) {
            if (item.type != ChainItem.Type.RESULT || item.recipeId == null) continue;
            if (Favorites.sameRecipe(item.recipeId, Favorites.recipeFor(item.item))) chosen.putIfAbsent(item.recipeId, item.item);
        }
        return chosen;
    }

    /** The stack under the mouse (for the global keys: copy name, bookmark...). */
    ItemStack hoveredStack() {
        if (hoveredNode != null) return stackOf(hoveredNode);
        if (hoveredStat != null) {
            TreeStats.Row row = rowOf(hoveredStat);
            if (row.item != null && row.recipeId == null) return stackOf(row.item, row.payload);
        }
        return ItemStack.EMPTY;
    }

    boolean searchFocused() {
        return search != null && search.isFocused();
    }

    // ---------------------------------------------------------------- drawing

    private void updateHover(int mx, int my) {
        TreeCamera cam = ui.camera;
        hoveredStat = statAt(mx, my);
        boolean canHover = !cam.isDragging() && cam.isInWidget(mx, my) && hoveredStat == null
            && !(menuOpen && in(mx, my, menuX, menuY, menuW, menuH));
        hoveredBadge = canHover ? badgeAt(mx, my) : null;
        hoveredNode = canHover && hoveredBadge == null ? nodeAt(mx, my) : null;

        // #39: the hover line and the shift+wheel use the same gate as the badge and the middle click
        // (optionEntry). A favorited recipe resolves one result into the slot's maths (its prefItem, its amounts
        // produced), but that only sets the slot's default: a tag slot keeps its chooser whatever the favorites
        // resolved to, and the chooser offers the entry's whole option set either way.
        accepting = optionEntry(hoveredNode);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
        super.extractRenderState(g, mx, my, delta);
        if (TreeShot.running(this)) {
            // saving the tree as a picture: just the tree, one screenful at a time, nothing hovered
            updateHover(-10000, -10000);
            g.fill(0, 0, width, height, 0xFFC6C6C6);
            TreeShot.prepare(this);
            drawCanvas(g, -10000, -10000);
            return;
        }
        lastMouseX = mx;
        lastMouseY = my;
        updateHover(mx, my);

        TreeDraw.sliced(g, BACKGROUND, PADDING, PADDING, PADDING_TOP, PADDING, MARGIN, MARGIN, width - MARGIN * 2, height - MARGIN * 2);

        if (math.outputRecipes.isEmpty()) {
            String empty = tr("empty");
            int y = MARGIN + PADDING_TOP + (height - MARGIN * 2 - PADDING_TOP) / 2;
            g.text(font, empty, (width - font.width(empty)) / 2, y, 0xFFAAAAAA, true);
        } else {
            drawCanvas(g, mx, my);
            if (statsVisible) drawStats(g, mx, my);
        }

        String title = tr("title");
        g.text(font, title, (width - font.width(title)) / 2, MARGIN + PADDING_TOP / 2 - 9 / 2 + 1, 0xFFFFFFFF, true);

        search.extractRenderState(g, mx, my, delta);
        drawToolbar(g, mx, my);

        g.nextStratum();
        drawMenu(g, mx, my);
        drawTooltips(g, mx, my);
    }

    // ---- canvas

    private void drawCanvas(GuiGraphicsExtractor g, int mx, int my) {
        TreeCamera cam = ui.camera;
        if (!TreeShot.running(this)) cam.clampToBounds(canvas.treeWidth, canvas.treeHeight);
        float zScrollX = cam.scrollX * cam.zoomLevel;
        float zScrollY = cam.scrollY * cam.zoomLevel;
        int cropXMin = (int) ((zScrollX - 32) / cam.zoomLevel);
        int cropXMax = (int) ((zScrollX + 32) / cam.zoomLevel) + (int) (cam.widgetW / cam.zoomLevel);
        TreeSlot collapseHover = cam.isDragging() ? null : arrowAt(mx, my);
        SearchResult search = ui.activeSearchResult();
        boolean snapshot = inventoryLive();
        boolean ctrl = Minecraft.getInstance().hasControlDown();

        g.enableScissor(cam.widgetX, cam.widgetY, cam.widgetX + cam.widgetW, cam.widgetY + cam.widgetH);
        Matrix3x2fStack pose = g.pose();
        pose.pushMatrix();
        pose.translate(cam.widgetX - zScrollX, cam.widgetY - zScrollY);
        pose.scale(cam.zoomLevel, cam.zoomLevel);
        try {
            for (List<TreeSlot> row : canvas.rows.subMap(cam.visibleRowMin(), cam.visibleRowMax())
                .values()) {
                for (TreeSlot node : row) {
                    TreeLayout.Point p = pointOf(node);
                    drawParentLine(g, node);
                    if (p.x < cropXMin || p.x > cropXMax) continue;
                    drawSlot(g, node, snapshot, search, ctrl, node == hoveredNode);
                    drawCollapseIcon(g, node, node == collapseHover);
                    if (node == hoveredNode) g.fill(p.x + 1, p.y + 1, p.x + SLOT * node.outputs().size() - 1, p.y + SLOT - 1, HOVER_COLOR);
                    if (hasBadge(node)) {
                        drawBadge(g, node, p.x, p.y);
                        if (node == hoveredBadge) drawDeleteButton(g, p.x, p.y);
                    }
                }
            }
        } finally {
            pose.popMatrix();
            g.disableScissor();
        }
        if (!TreeShot.running(this)) drawScrollbars(g);
    }

    private void drawParentLine(GuiGraphicsExtractor g, TreeSlot node) {
        TreeSlot parent = canvas.graph.parentOf.get(node);
        if (parent == null) return;
        TreeLayout.Point p = pointOf(node);
        TreeLayout.Point pp = pointOf(parent);
        int x0 = p.x + SLOT / 2;
        int y0 = p.y - 1;
        int x1 = pp.x + SLOT / 2;
        int y1 = pp.y + SLOT;
        if (x0 != x1) {
            int midY = (y0 + y1) / 2;
            rect(g, x0, Math.min(y0, midY), 1, Math.abs(midY - y0) + 1);
            rect(g, Math.min(x0, x1), midY, Math.abs(x1 - x0) + 1, 1);
            rect(g, x1, Math.min(midY, y1), 1, Math.abs(y1 - midY) + 1);
        } else {
            rect(g, x0, Math.min(y0, y1), 1, Math.abs(y1 - y0) + 1);
        }
    }

    private static void rect(GuiGraphicsExtractor g, int x, int y, int w, int h) {
        g.fill(x, y, x + w, y + h, LINE_COLOR);
    }

    private TreeDraw.Sprite slotSprite(TreeSlot node, boolean snapshot, SearchResult search) {
        if (search != null) {
            if (node == search.highlightNode) return TreeDraw.SLOT_HIGHLIGHTED;
            return search.matches.contains(node) ? TreeDraw.SLOT_DEFAULT : TreeDraw.SLOT_DISABLED;
        }
        if (!snapshot) return TreeDraw.SLOT_DEFAULT;
        if (node.leftAmount == 0) {
            TreeSlot parent = canvas.graph.parentOf.get(node);
            return parent != null && parent.leftAmount == 0 || node.requestedAmount == 0 ? TreeDraw.SLOT_DISABLED : TreeDraw.SLOT_DEFAULT;
        }
        if (!canvas.graph.childrenOf.containsKey(node)) return TreeDraw.SLOT_MISSING;
        if (ui.collapsedItems.contains(node.ingrItem)) {
            return canvas.graph.subtreeMissingCache.contains(node) ? TreeDraw.SLOT_MISSING : TreeDraw.SLOT_CRAFTING;
        }
        return canvas.graph.childrenNeedCraftingCache.contains(node) ? TreeDraw.SLOT_DEFAULT : TreeDraw.SLOT_CRAFTING;
    }

    private void drawSlot(GuiGraphicsExtractor g, TreeSlot node, boolean snapshot, SearchResult search, boolean ctrl, boolean hovered) {
        TreeLayout.Point p = pointOf(node);
        TreeDraw.draw(g, slotSprite(node, snapshot, search), p.x, p.y);
        FluidStacks.item(g, stackOf(node), p.x + (SLOT - 16) / 2, p.y + (SLOT - 16) / 2);

        drawOutputAmount(g, node, node.outputs().get(0), stackOf(node), p.x, p.y, ctrl, hovered);

        // issue #39: a small marker on a node whose ingredient accepts several items, so the chooser is findable
        // (middle click opens it); the slot itself is not moved or resized
        if (optionEntry(node) != null)
            TreeDraw.overlayText(g, font, "*", p.x + (SLOT - 16) / 2, p.y + (SLOT - 16) / 2, 16, 16, TAG_MARK,
                TreeDraw.Align.BOTTOM_LEFT);

        // issue #42: a recipe with several results is one node; its other results sit right of its main slot
        List<TreeSlot.Output> outputs = node.outputs();
        for (int i = 1; i < outputs.size(); i++) {
            TreeSlot.Output output = outputs.get(i);
            int x = p.x + i * SLOT;
            TreeDraw.draw(g, outputSprite(node, output, snapshot, search), x, p.y);
            ItemStack stack = stackOf(output.shown());
            FluidStacks.item(g, stack, x + (SLOT - 16) / 2, p.y + (SLOT - 16) / 2);
            drawOutputAmount(g, node, output, stack, x, p.y, false, false);
        }
    }

    /** The sprite for one of a merged node's extra result slots (issue #42). */
    private TreeDraw.Sprite outputSprite(TreeSlot node, TreeSlot.Output output, boolean snapshot, SearchResult search) {
        if (search != null) return search.matches.contains(node) ? TreeDraw.SLOT_DEFAULT : TreeDraw.SLOT_DISABLED;
        if (!snapshot) return TreeDraw.SLOT_DEFAULT;
        return output.requestedAmount > 0 && output.leftAmount > 0 ? TreeDraw.SLOT_DEFAULT : TreeDraw.SLOT_DISABLED;
    }

    /** One output's amount on its slot; the CTRL hint only ever applies to the node's main slot. */
    private void drawOutputAmount(GuiGraphicsExtractor g, TreeSlot node, TreeSlot.Output output, ItemStack stack, int x, int y,
        boolean ctrl, boolean hovered) {
        if (output.requestedAmount > 0 && output.leftAmount == 0) {
            drawStackSize(g, stack, output.requestedAmount, x, y, true, output.isContainerItem);
        } else if (output.requestedAmount > 0 && output.leftAmount > 0) {
            long amount = hovered && ctrl && node.prefItem != null && canvas.graph.roots.contains(node)
                && canvas.collapsedRecipes.contains(node.prefItem.recipeId) ? output.currentRequestedAmount : output.requestedAmount;
            drawStackSize(g, stack, amount, x, y, false, output.isContainerItem);
        }
    }

    /** {@code estimate}: NEI draws a tool that comes back worn from a craft as "~amount" in orange. */
    private void drawStackSize(GuiGraphicsExtractor g, ItemStack stack, long amount, int x, int y, boolean resolved, boolean estimate) {
        String text = FluidStacks.isMilli(stack) ? FluidStacks.milliLabel(amount) : ReadableNumber.amount(amount);
        int color = 0xFFFFFF;
        if (estimate) {
            text = "~" + text;
            color = 0xFFAA00;
        }
        if (resolved) color = 0x55FF55;
        TreeDraw.overlayText(g, font, text, x + (SLOT - 16) / 2, y + (SLOT - 16) / 2, 16, 16, color, TreeDraw.Align.BOTTOM_RIGHT);
    }

    /** A recipe type's icon in a 16 x 16 box (NEI's handler image or item; the little hammer when there is none). */
    private void drawHandlerIcon(GuiGraphicsExtractor g, String handler, int x, int y) {
        IDrawable icon = TreeRecipes.info(handler)
            .icon();
        if (icon == null) {
            TreeDraw.draw(g, TreeDraw.ICON_CRAFTING, x, y);
            return;
        }
        int size = Math.max(icon.getWidth(), icon.getHeight());
        if (size > 16) {
            Matrix3x2fStack pose = g.pose();
            pose.pushMatrix();
            pose.translate(x, y);
            pose.scale(16f / size, 16f / size);
            icon.draw(g, 0, 0);
            pose.popMatrix();
        } else {
            icon.draw(g, x + (16 - icon.getWidth()) / 2, y + (16 - icon.getHeight()) / 2);
        }
    }

    private void drawBadge(GuiGraphicsExtractor g, TreeSlot node, int x, int y) {
        float size = badgeSize();
        Matrix3x2fStack pose = g.pose();
        pose.pushMatrix();
        pose.translate(x - size / 2 + 1, y - size / 2 + 1);
        pose.scale(BADGE_SCALE, BADGE_SCALE);
        drawHandlerIcon(g, TreeStats.handlerOf(node.prefItem.recipeId), 0, 0);
        pose.popMatrix();
    }

    private void drawDeleteButton(GuiGraphicsExtractor g, int x, int y) {
        float size = badgeSize();
        Matrix3x2fStack pose = g.pose();
        pose.pushMatrix();
        pose.translate(x - size / 2 + 1, y - size / 2 + 1);
        pose.scale(BADGE_SCALE, BADGE_SCALE);
        TreeDraw.draw(g, TreeDraw.ICON_REMOVE, 0, 0);
        pose.popMatrix();
    }

    private void drawCollapseIcon(GuiGraphicsExtractor g, TreeSlot node, boolean hover) {
        if (!hasArrow(node)) return;
        TreeDraw.Sprite icon = ui.collapsedItems.contains(node.ingrItem) ? (hover ? TreeDraw.ICON_COLLAPSED_HOVER : TreeDraw.ICON_COLLAPSED)
            : (hover ? TreeDraw.ICON_EXPANDED_HOVER : TreeDraw.ICON_EXPANDED);
        TreeLayout.Point p = pointOf(node);
        TreeDraw.draw(g, icon, p.x + SLOT / 2 - icon.w() - 1, p.y + SLOT + 1);
    }

    private void drawScrollbars(GuiGraphicsExtractor g) {
        TreeCamera cam = ui.camera;
        if (canvas.treeWidth <= 0 || canvas.treeHeight <= 0) return;
        float scrollXPct = TreeCamera.clamp(cam.scrollX / canvas.treeWidth, 0f, 1f);
        float scrollYPct = TreeCamera.clamp(cam.scrollY / canvas.treeHeight, 0f, 1f);
        TreeDraw.draw(g, TreeDraw.SCROLLBAR_H, cam.widgetX + cam.widgetW - 3,
            (int) (cam.widgetY + scrollYPct * (cam.widgetH - TreeDraw.SCROLLBAR_H.h())));
        TreeDraw.draw(g, TreeDraw.SCROLLBAR_W, (int) (cam.widgetX + scrollXPct * (cam.widgetW - TreeDraw.SCROLLBAR_W.w())),
            cam.widgetY + cam.widgetH - 3);
    }

    // ---- stats panel

    private TreeDraw.Sprite statSlot(TreeStats.Row row) {
        TreeDraw.Sprite tree = switch (row.kind) {
            case INGREDIENT_NEEDED -> inventoryLive() ? TreeDraw.SLOT_MISSING : TreeDraw.SLOT_DEFAULT;
            case CRAFT_AVAILABLE -> TreeDraw.SLOT_CRAFTING;
            case REMAINDER -> TreeDraw.SLOT_DISABLED;
            default -> TreeDraw.SLOT_DEFAULT;
        };
        return TreeDraw.stats(tree);
    }

    private static Object guidOf(TreeStats.Row row) {
        return row.kind == TreeStats.Kind.HANDLER ? row.handler : new ItemGuid(row.item);
    }

    private void drawStats(GuiGraphicsExtractor g, int mx, int my) {
        TreeDraw.sliced(g, STATS_BACKGROUND, 0, 0, 2, 0, statsX, statsY, statsW, statsH);
        boolean scroll = statsCanScroll();
        if (scroll) g.enableScissor(statsX + 1, statsY + 4, statsX + statsW - 1, statsY + statsH - 4);
        SearchResult highlight = ui.searchStatsResult;
        try {
            for (TreeStatsLayout.Placed placed : statsLayout.placed) {
                int x = statX(placed);
                int y = statY(placed);
                if (placed.index() < 0) {
                    g.text(font, statLabels.get(placed.section()) + ":", x, y, 0xFFFFFFFF, true);
                    continue;
                }
                TreeStats.Row row = rowOf(placed);
                boolean lit = highlight != null && highlight.matchesGuid(guidOf(row));
                TreeDraw.draw(g, lit ? TreeDraw.stats(TreeDraw.SLOT_HIGHLIGHTED) : statSlot(row), x, y);
                if (row.kind == TreeStats.Kind.HANDLER) {
                    drawHandlerIcon(g, row.handler, x + 1, y + 1);
                    TreeDraw.overlayText(g, font, "x" + ReadableNumber.wide(row.iterations), x + 1, y + 1, SLOT - 2, SLOT - 2, 0xFFFFFF,
                        TreeDraw.Align.TOP_LEFT);
                    TreeDraw.overlayText(g, font, ReadableNumber.wide(row.recipeCount), x + 1, y + 1, SLOT - 2, SLOT - 2, 0xFFFFFF,
                        TreeDraw.Align.BOTTOM_RIGHT);
                } else {
                    FluidStacks.item(g, stackOf(row.item, row.payload), x + 1, y + 1);
                    drawStackSize(g, stackOf(row.item, row.payload), row.amount, x, y, false, false);
                }
                if (hoveredStat == placed) g.fill(x + 1, y + 1, x + SLOT - 1, y + SLOT - 1, STATS_HOVER_COLOR);
                if (row.recipeId != null) {
                    float scale = BADGE_SCALE;
                    Matrix3x2fStack pose = g.pose();
                    pose.pushMatrix();
                    pose.translate(x - 6 * scale, y - 6 * scale);
                    pose.scale(scale, scale);
                    drawHandlerIcon(g, TreeStats.handlerOf(row.recipeId), 0, 0);
                    pose.popMatrix();
                }
            }
        } finally {
            if (scroll) g.disableScissor();
        }
        if (scroll) {
            TreeDraw.tiledWide(g, TreeDraw.STATS_TRACK, barX(), barY(), barW());
            TreeDraw.tiledWide(g, TreeDraw.STATS_THUMB, thumbX(), barY(), (int) thumbSize());
        }
    }

    // ---- toolbar and menu

    /** The toolbar's 16 by 16 icon, or null for the item drawn in its place (no sprite of the texture fits crafting mode). */
    private TreeDraw.Sprite toolIcon(Tool tool) {
        return switch (tool) {
            case INVENTORY -> new TreeDraw.Sprite(56, 66, 16, 16);
            case CRAFTING -> null;
            case FIT -> new TreeDraw.Sprite(38, 66, 16, 16);
            case COLLAPSE -> switch (TreeState.collapseMode()) {
                case EXPANDED -> new TreeDraw.Sprite(128, 66, 16, 16);
                case SMART -> new TreeDraw.Sprite(110, 66, 16, 16);
                case ALL -> new TreeDraw.Sprite(92, 66, 16, 16);
            };
            case STATS -> TreeState.visibleSections()
                .size() == StatsSection.values().length ? new TreeDraw.Sprite(2, 66, 16, 16) : new TreeDraw.Sprite(20, 66, 16, 16);
            case SECTIONS -> new TreeDraw.Sprite(164, 66, 16, 16);
            case LINK -> new TreeDraw.Sprite(74, 66, 16, 16);
            case SCREENSHOT -> new TreeDraw.Sprite(146, 66, 16, 16);
        };
    }

    private boolean toolOn(Tool tool) {
        return switch (tool) {
            case INVENTORY -> TreeState.useInventorySnapshot() || craftingOn();
            case CRAFTING -> craftingOn();
            case COLLAPSE -> TreeState.collapseMode() != CollapseMode.EXPANDED;
            case STATS -> TreeState.statsPanelVisible();
            default -> false;
        };
    }

    /** A tool that is held on and can't be clicked: the inventory snapshot while crafting mode reads the inventory live. */
    private boolean toolLocked(Tool tool) {
        return tool == Tool.INVENTORY && craftingOn();
    }

    private String toolTip(Tool tool) {
        return switch (tool) {
            case INVENTORY -> tr(craftingOn() ? "toolbar.inventory_live"
                : TreeState.useInventorySnapshot() ? "toolbar.inventory_on" : "toolbar.inventory_off");
            case CRAFTING -> tr(craftingOn() ? "toolbar.crafting_on" : "toolbar.crafting_off");
            case FIT -> tr("toolbar.fit_to_view");
            case COLLAPSE -> tr(switch (TreeState.collapseMode()) {
                case EXPANDED -> "toolbar.collapse_resolved";
                case SMART -> "toolbar.collapse_all";
                case ALL -> "toolbar.expand_all";
            });
            case STATS -> tr(TreeState.statsPanelVisible() ? "toolbar.stats_on" : "toolbar.stats_off");
            case SECTIONS -> tr("toolbar.stats_sections");
            case LINK -> tr("toolbar.link");
            case SCREENSHOT -> tr("toolbar.screenshot");
        };
    }

    private void drawToolbar(GuiGraphicsExtractor g, int mx, int my) {
        for (Tool tool : Tool.values()) {
            int x = toolX(tool);
            int y = toolY();
            boolean on = toolOn(tool);
            boolean over = in(mx, my, x, y, BUTTON, BUTTON);
            g.fill(x, y, x + BUTTON, y + BUTTON, 0xFF000000);
            g.fill(x + 1, y + 1, x + BUTTON - 1, y + BUTTON - 1, on ? 0xFF5E5E5E : over ? 0xFF7A8CC8 : 0xFF8B8B8B);
            if (!on) {
                g.fill(x + 1, y + 1, x + BUTTON - 1, y + 2, 0xFFFFFFFF);
                g.fill(x + 1, y + 1, x + 2, y + BUTTON - 1, 0xFFFFFFFF);
                g.fill(x + 1, y + BUTTON - 2, x + BUTTON - 1, y + BUTTON - 1, 0xFF555555);
                g.fill(x + BUTTON - 2, y + 1, x + BUTTON - 1, y + BUTTON - 1, 0xFF555555);
            }
            // NEI draws the 16 x 16 icon at half size in the middle of the button
            TreeDraw.Sprite icon = toolIcon(tool);
            Matrix3x2fStack pose = g.pose();
            pose.pushMatrix();
            pose.translate(x + (BUTTON - TOOL_ICON / 2) / 2f, y + (BUTTON - TOOL_ICON / 2) / 2f);
            pose.scale(0.5f, 0.5f);
            if (icon != null) TreeDraw.draw(g, icon, 0, 0);
            else FluidStacks.item(g, new ItemStack(Items.CRAFTING_TABLE), 0, 0);
            pose.popMatrix();
        }
    }

    private static String sectionLabel(StatsSection section) {
        return tr("stats." + section.name().toLowerCase(java.util.Locale.ROOT));
    }

    private void openMenu() {
        int widest = 0;
        for (StatsSection s : StatsSection.values()) widest = Math.max(widest, font.width(sectionLabel(s)));
        menuW = Math.max(70, widest + 20);
        menuH = MENU_TOP + StatsSection.values().length * MENU_ROW;
        menuX = toolX(Tool.SECTIONS);
        menuY = toolY() + BUTTON + 2;
        menuOpen = true;
    }

    private void drawMenu(GuiGraphicsExtractor g, int mx, int my) {
        if (!menuOpen) return;
        StatsSection[] sections = StatsSection.values();
        for (int i = 0; i < sections.length; i++) {
            int rowY = menuY + MENU_TOP + i * MENU_ROW;
            boolean hover = in(mx, my, menuX, rowY, menuW, MENU_ROW);
            boolean checked = TreeState.visibleSections()
                .contains(sections[i]);
            g.fill(menuX, rowY, menuX + menuW, rowY + MENU_ROW, 0xFF000000);
            g.fill(menuX + 1, rowY + 1, menuX + menuW - 1, rowY + MENU_ROW - 1, checked ? 0xFF8B8B8B : 0xFF5A5A5A);
            if (checked) {
                g.fill(menuX + 1, rowY + 1, menuX + menuW - 1, rowY + 2, 0xFFFFFFFF);
                g.fill(menuX + 1, rowY + 1, menuX + 2, rowY + MENU_ROW - 1, 0xFFFFFFFF);
            }
            String label = sectionLabel(sections[i]);
            int color = hover ? 0xFFFFFFA0 : checked ? 0xFFE0E0E0 : 0xFFA0A0A0;
            g.text(font, label, menuX + (menuW - font.width(label)) / 2, rowY + (MENU_ROW - 8) / 2, color, true);
        }
    }

    // ---------------------------------------------------------------- tooltips

    private List<Component> itemLines(ItemStack stack, long amount) {
        Minecraft mc = Minecraft.getInstance();
        List<Component> lines = new ArrayList<>();
        if (mc.level != null && !stack.isEmpty()) {
            lines.addAll(FluidStacks.tooltip(stack, amount, Item.TooltipContext.of(mc.level), mc.player, TooltipFlag.Default.NORMAL));
        }
        if (lines.isEmpty()) lines.add(FluidStacks.name(stack));
        return lines;
    }

    private static Component gray(String text) {
        return Component.literal("§7" + text);
    }

    private void show(GuiGraphicsExtractor g, int mx, int my, List<Component> lines, List<TreeDraw.IconLine> icons, IRecipeLayoutDrawable<?> picture) {
        List<FormattedCharSequence> seqs = new ArrayList<>();
        for (Component line : lines) seqs.add(line.getVisualOrderText());
        int pw = 0;
        int ph = 0;
        if (picture != null) {
            Rect2i r = picture.getRect();
            pw = r.getWidth();
            ph = r.getHeight();
        }
        TreeDraw.tooltip(g, font, mx, my, width, height, seqs, icons, pw, ph, picture == null ? null : (g2, x, y) -> {
            picture.setPosition(x, y);
            // a layer of its own, above the tooltip box and the item icons under it
            g2.nextStratum();
            picture.drawRecipe(g2, -1000, -1000);
        });
    }

    private void drawTooltips(GuiGraphicsExtractor g, int mx, int my) {
        if (menuOpen && in(mx, my, menuX, menuY, menuW, menuH)) return;
        Tool tool = toolAt(mx, my);
        if (tool != null) {
            show(g, mx, my, List.of(Component.literal(toolTip(tool))), List.of(), null);
        } else if (hoveredBadge != null) {
            drawRecipeTooltip(g, mx, my, hoveredBadge.prefItem.recipeId, hoveredBadge.multiplier, null, stackOf(hoveredBadge));
        } else if (hoveredStat != null) {
            drawStatTooltip(g, mx, my);
        } else if (hoveredNode != null) {
            drawNodeTooltip(g, mx, my);
        }
    }

    private Map<String, String> nodeKeys(TreeSlot node) {
        Map<String, String> k = new HashMap<>();
        if (node.prefItem != null) k.put(HotkeyTips.keys("ALT", "LMB"), tr("item.toggle_recipe"));
        if (canvas.graph.roots.contains(node)) {
            k.put(HotkeyTips.keys("CTRL", "Scroll"), tr("change_quantity"));
            k.put(HotkeyTips.keys("CTRL", "ALT", "Scroll"), tr("change_quantity_step"));
        }
        if (accepting != null) k.put(HotkeyTips.keys("SHIFT", "Scroll"), tr("accepts.scroll"));
        if (optionEntry(node) != null) k.put(HotkeyTips.keys("MMB"), tr("accepts.scroll"));
        HotkeyTips.recipeAndCopyKeys(k);
        return k;
    }

    private void drawNodeTooltip(GuiGraphicsExtractor g, int mx, int my) {
        TreeSlot node = hoveredNode;
        // issue #42 follow-up: each result slot of a merged node has its own tooltip: the one under the mouse names
        // that result and quotes that slot's numbers (issue #42: the node's own totals are the sum over its results)
        List<TreeSlot.Output> outputs = node.outputs();
        int slot = hoveredOutputIndex(node, mx, my);
        TreeSlot.Output main = outputs.get(slot);
        ItemStack stack = stackOf(main.shown());
        List<Component> lines = itemLines(stack, main.requestedAmount > 0 ? main.requestedAmount : 1000);

        // NEI adds each of these at index 1, so the one added last ends up right under the name
        if (inventoryLive() && main.requestedAmount > 0) {
            if (main.leftAmount == 0) {
                lines.add(1, gray(tr("item.state.resolved", FluidStacks.name(stack).getString())));
            } else if (main.leftAmount != main.requestedAmount) {
                lines.add(1, gray(tr("item.state.partial", ReadableNumber.amount(main.leftAmount))));
            }
            if (main.leftAmount > 0 && canvas.graph.childrenOf.containsKey(node) && !canvas.graph.childrenNeedCraftingCache.contains(node)) {
                lines.add(1, gray(tr("item.state.craftable")));
            }
        }
        if (node.prefItem != null && canvas.graph.childrenOf.containsKey(node)) {
            long surplus = node.prefItem.amount(main.multiplier()) - main.requestedAmount;
            if (surplus > 0) lines.add(1, gray(tr("item.state.surplus", ReadableNumber.amount(surplus))));
        }

        int at = 1;
        for (String line : HotkeyTips.hotkeyLines(nodeKeys(node))) lines.add(at++, Component.literal(line));
        HotkeyTips.addModName(lines, stack);

        // the recipe's other results, drawn beside it on the canvas, are named the same way in the tooltip
        for (int i = 0; i < outputs.size(); i++) {
            if (i == slot) continue;
            TreeSlot.Output output = outputs.get(i);
            ItemStack alt = stackOf(output.shown());
            String text = FluidStacks.name(alt).getString()
                + (output.requestedAmount > 0 ? " " + ReadableNumber.amount(output.requestedAmount) : "");
            if (output.isContainerItem) text = "~" + text;
            lines.add(gray(text));
        }

        List<TreeDraw.IconLine> icons = new ArrayList<>();
        if (accepting != null) icons.add(acceptsLine(accepting));
        show(g, mx, my, lines, icons, null);
    }

    /**
     * The bookmark entry of a node whose ingredient accepts several items, or null (issue #39: the chooser gate, its
     * badge and its key line).
     */
    private static Bookmarks.Entry optionEntry(TreeSlot node) {
        return node != null && node.ingrItem.payload instanceof Bookmarks.Entry e && Bookmarks.hasOptions(e) ? e : null;
    }

    /** NEI's "Accepts following": the items the slot takes, the chosen one marked (a window of 16 around it). */
    private TreeDraw.IconLine acceptsLine(Bookmarks.Entry entry) {
        List<String> options = entry.options;
        int active = Math.max(0, options.indexOf(entry.itemId()));
        int start = Math.max(0, Math.min(active - 8, options.size() - 16));
        List<ItemStack> stacks = new ArrayList<>();
        for (int i = start; i < Math.min(options.size(), start + 16); i++) stacks.add(stackOf(options.get(i), null));
        return new TreeDraw.IconLine("§7" + tr("accepts"), stacks, List.of(), active - start);
    }

    private void drawStatTooltip(GuiGraphicsExtractor g, int mx, int my) {
        TreeStats.Row row = rowOf(hoveredStat);
        if (row.kind == TreeStats.Kind.HANDLER) {
            List<Component> lines = new ArrayList<>();
            lines.add(Component.literal(TreeRecipes.info(row.handler).title()));
            lines.add(gray(tr("stats.handler_recipes", row.recipeCount)));
            lines.add(gray(tr("stats.handler_iterations", row.iterations)));
            show(g, mx, my, lines, List.of(), null);
            return;
        }
        ItemStack stack = stackOf(row.item, row.payload);
        if (row.recipeId != null) {
            List<Component> head = new ArrayList<>();
            head.add(FluidStacks.name(stack));
            drawRecipeTooltip(g, mx, my, row.recipeId, row.multiplier, head, stack);
            return;
        }
        List<Component> lines = itemLines(stack, row.amount > 0 ? row.amount : 1000);
        Map<String, String> k = new HashMap<>();
        HotkeyTips.recipeAndCopyKeys(k);
        k.remove("LMB");
        k.remove("RMB");
        Object guid = guidOf(row);
        if (ui.searchStatsResult != null && ui.searchStatsResult.matchesGuid(guid)) {
            k.put("LMB", tr("stats.next_highlight"));
            k.put(HotkeyTips.keys("SHIFT", "LMB"), tr("stats.prev_highlight"));
            k.put("RMB", tr("stats.clear_highlight"));
        } else {
            k.put("LMB", tr("stats.highlight_handler"));
        }
        int at = 1;
        for (String line : HotkeyTips.hotkeyLines(k)) lines.add(at++, Component.literal(line));
        HotkeyTips.addModName(lines, stack);
        show(g, mx, my, lines, List.of(), null);
    }

    /** NEI's RecipeTooltipLineHandler (the recipe drawn in the tooltip) and, with Shift, RecipeItemsTooltipLineHandler. */
    private void drawRecipeTooltip(GuiGraphicsExtractor g, int mx, int my, String recipeId, long multiplier, List<Component> head,
        ItemStack hint) {
        if (Minecraft.getInstance().hasShiftDown()) {
            drawRecipeItemsTooltip(g, mx, my, recipeId, multiplier);
            return;
        }
        List<Component> lines = head != null ? head : new ArrayList<>();
        IRecipeLayoutDrawable<?> picture = picture(recipeId, hint);
        if (picture == null) lines.add(Component.literal(TreeRecipes.info(TreeStats.handlerOf(recipeId)).title()));
        if (!lines.isEmpty() || picture != null) show(g, mx, my, lines, List.of(), picture);
    }

    private IRecipeLayoutDrawable<?> picture(String recipeId, ItemStack hint) {
        if (pictures.containsKey(recipeId)) return pictures.get(recipeId);
        if (noPicture.contains(recipeId)) return null;
        try {
            RecipeLookup.Target<?> target = RecipeLookup.findTarget(recipeId, hint);
            IRecipeLayoutDrawable<?> layout = target == null ? null : target.layout();
            if (layout != null) {
                pictures.put(recipeId, layout);
                return layout;
            }
        } catch (RuntimeException e) {
            NeiAddon.LOGGER.warn("Could not draw recipe {} in the tree tooltip", recipeId, e);
        }
        noPicture.add(recipeId);
        return null;
    }

    private void drawRecipeItemsTooltip(GuiGraphicsExtractor g, int mx, int my, String recipeId, long multiplier) {
        Map<String, Long> results = new LinkedHashMap<>();
        Map<String, Long> ingredients = new LinkedHashMap<>();
        Map<String, Object> payloads = new HashMap<>();
        for (ChainItem r : math.recipeResults) {
            if (recipeId.equals(r.recipeId) && !r.emptyFactor()) {
                results.merge(r.item, r.amount(multiplier), Long::sum);
                payloads.putIfAbsent(r.item, r.payload);
            }
        }
        for (ChainItem i : math.recipeIngredients) {
            if (recipeId.equals(i.recipeId) && !i.emptyFactor()) {
                ingredients.merge(i.item, i.amount(multiplier), Long::sum);
                payloads.putIfAbsent(i.item, i.payload);
            }
        }
        Map<String, Long> have = readInventory();
        Map<String, Long> missing = new LinkedHashMap<>();
        for (Map.Entry<String, Long> e : ingredients.entrySet()) {
            long left = e.getValue() - have.getOrDefault(e.getKey(), 0L);
            if (left > 0) missing.put(e.getKey(), left);
        }
        List<Component> lines = List.of(Component.literal(TreeRecipes.info(TreeStats.handlerOf(recipeId)).title() + " (x" + ReadableNumber.wide(multiplier) + ")"));
        List<TreeDraw.IconLine> icons = new ArrayList<>();
        addIcons(icons, "§a" + tr("items.results"), results, payloads);
        addIcons(icons, "§e" + tr("items.ingredients"), ingredients, payloads);
        if (!missing.equals(ingredients)) addIcons(icons, "§c" + tr("items.ingredients_needed"), missing, payloads);
        show(g, mx, my, lines, icons, null);
    }

    private static void addIcons(List<TreeDraw.IconLine> icons, String label, Map<String, Long> amounts, Map<String, Object> payloads) {
        if (amounts.isEmpty()) return;
        List<ItemStack> stacks = new ArrayList<>();
        List<String> texts = new ArrayList<>();
        for (Map.Entry<String, Long> e : amounts.entrySet()) {
            stacks.add(stackOf(e.getKey(), payloads.get(e.getKey())));
            texts.add(e.getValue() > 1 ? ReadableNumber.amount(e.getValue()) : "");
        }
        icons.add(new TreeDraw.IconLine(label, stacks, texts, -1));
    }

    // ---------------------------------------------------------------- search

    private void onSearchTextChanged() {
        if (search == null) return;
        String text = search.getValue();
        if (ui.searchStatsResult != null) ui.searchStatsResult = null;
        if (!text.isEmpty()) {
            ui.searchItemResult = new SearchResult(text);
            updateSearchItemMatches();
            searchGoTo(ui.searchItemResult, true);
        } else {
            ui.searchItemResult = null;
        }
    }

    private static String lastQuery;
    private static neiaddon.search.SearchQuery.Node lastNode;

    /** NEI's search language (see {@link neiaddon.search.SearchQuery}): and, or, not, groups, quotes, regex, @mod #tooltip $tag &id. */
    static boolean matches(String query, ItemStack stack) {
        if (stack.isEmpty()) return false;
        if (!query.equals(lastQuery)) {
            lastQuery = query;
            lastNode = neiaddon.search.SearchQuery.parse(query);
        }
        return lastNode.matches(SearchItem.of(stack));
    }

    private void updateSearchItemMatches() {
        SearchResult result = ui.searchItemResult;
        if (result == null) return;
        String query = (String) result.guid;
        result.matches.clear();
        result.highlightNode = null;
        for (TreeSlot node : canvas.graph.items) {
            for (TreeSlot.Output output : node.outputs()) {
                if (matches(query, stackOf(output.shown()))) {
                    result.matches.add(node);
                    break;
                }
            }
        }
    }

    private void updateSearchStatsMatches() {
        SearchResult result = ui.searchStatsResult;
        if (result == null) return;
        result.matches.clear();
        result.highlightNode = null;
        boolean byHandler = result.guid instanceof String;
        for (TreeSlot node : canvas.graph.items) {
            boolean match = byHandler;
            if (byHandler) {
                match = node.prefItem != null && result.matchesGuid(TreeStats.handlerOf(node.prefItem.recipeId));
            } else {
                for (TreeSlot.Output output : node.outputs()) {
                    // issue #42: a search matches any of the node's results, not just its main slot
                    if (result.matchesGuid(new ItemGuid(output.shown().item))) {
                        match = true;
                        break;
                    }
                }
            }
            if (match) result.matches.add(node);
        }
    }

    /** NEI's searchGoTo: the next (or previous) match gets the view; its parents are unfolded. */
    private void searchGoTo(SearchResult result, boolean forward) {
        if (result == null || result.matches.isEmpty()) return;
        List<TreeSlot> list = new ArrayList<>(result.matches);
        int index = list.indexOf(result.highlightNode);
        if (forward) {
            if (index >= list.size() - 1) index = -1;
            index++;
        } else {
            if (index <= 0) index = list.size();
            index--;
        }
        TreeSlot target = index == -1 ? null : list.get(index);
        if (target != null) {
            for (TreeSlot parent = canvas.graph.parentOf.get(target); parent != null; parent = canvas.graph.parentOf.get(parent)) {
                ui.collapsedItems.remove(parent.ingrItem);
            }
        }
        relayout(null);
        result.highlightNode = target;
        if (target != null && pointOf(target) != null) {
            ui.camera.centerOn(pointOf(target).x + SLOT / 2f, pointOf(target).y + SLOT / 2f);
        }
    }

    // ---------------------------------------------------------------- actions

    private static void clickSound() {
        Minecraft.getInstance()
            .getSoundManager()
            .play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
    }

    private void runTool(Tool tool) {
        switch (tool) {
            case INVENTORY -> {
                if (toolLocked(tool)) return;
                TreeState.toggleUseInventorySnapshot();
                rebuildTree(null);
            }
            case CRAFTING -> {
                Bookmarks.GroupMeta meta = page.meta(gid);
                meta.crafting = !meta.crafting;
                Bookmarks.save();
                inventoryChanged();
            }
            case FIT -> fitToView();
            case STATS -> {
                TreeState.toggleStatsPanelVisible();
                rebuildStats();
            }
            case SECTIONS -> {
                if (menuOpen) menuOpen = false;
                else openMenu();
            }
            case LINK -> {
                String payload = Bookmarks.shareGroup(page, gid);
                if (payload != null) ShareClient.send(payload);
            }
            case SCREENSHOT -> {
                if (!math.outputRecipes.isEmpty()) TreeShot.start(this, ui.camera, canvas.treeWidth, canvas.treeHeight);
            }
            case COLLAPSE -> {
                TreeState.cycleCollapseMode();
                updateCollapsedItems();
                fitToView();
            }
        }
    }

    /** The item of the node whose recipe the next picked recipe replaces (see {@link TreeRecipes#waitingItemId}), or null. */
    String replacedItemId() {
        TreeSlot node = ui.replaceRecipeNode;
        return node == null ? null : node.shown().item;
    }

    /** A recipe picked in JEI's recipe screen for this tree: NEI's GuiCraftingTree.addRecipe. */
    void addRecipe(RecipeData recipe) {
        TreeSlot replace = ui.replaceRecipeNode;
        ui.replaceRecipeNode = null;
        TreeRecipes.stopWaiting();
        if (recipe != null && Bookmarks.groupExists(page, gid)) {
            boolean changed = false;
            // A chain group of ONE recipe holds every entry under that recipe, so removing the old recipe empties
            // the group and Bookmarks.normalize() drops the group's meta (chain flag, todo flag, ...). Keep the
            // object: the picked recipe re-enters the same group, and without the meta back the chain is off -
            // the tree rebuilds empty ("No crafting chain to display") and the strip goes gray.
            Bookmarks.GroupMeta meta = page.metas.get(gid);
            if (replace != null) {
                Set<String> unused = canvas.graph.collectUnusedRecipes(replace);
                unused.remove(recipe.recipeId);
                for (String id : unused) changed |= Bookmarks.removeRecipe(page, id, gid);
            }
            if (!Bookmarks.existsRecipe(page, recipe.recipeId, gid)) {
                changed |= Bookmarks.addRecipe(page, recipe, 1, gid);
            }
            if (changed) {
                if (meta != null && page.metas.get(gid) == null) page.metas.put(gid, meta);
                page.normalize();
                Bookmarks.save();
                rebuildTree(null);
            }
        }
        McCompat.setScreen(this);
    }

    private void lookup(ItemStack stack, boolean usages) {
        if (stack.isEmpty()) return;
        TreeRecipes.lookup(this, usages ? null : RecipeData.idOf(stack),
            () -> BookmarkPanel.showRecipes(stack, usages ? RecipeIngredientRole.INPUT : RecipeIngredientRole.OUTPUT));
    }

    private void deleteRecipe(TreeSlot target) {
        String recipeId = target.prefItem.recipeId;
        if (target.prefItem.payload instanceof Bookmarks.Entry entry) Bookmarks.removeEntry(page, entry, false);
        if (!Bookmarks.existsRecipe(page, recipeId, gid)) {
            for (String unused : canvas.graph.collectUnusedRecipes(target)) Bookmarks.removeRecipe(page, unused, gid);
        }
        rebuildTree(null);
    }

    // ---------------------------------------------------------------- input

    private boolean overSearch(double mx, double my) {
        return in(mx, my, search.getX(), search.getY(), search.getWidth(), search.getHeight());
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (TreeShot.running(this)) return true;
        double mx = event.x();
        double my = event.y();
        int button = event.button();

        if (overSearch(mx, my)) {
            if (button == 1) {
                search.setValue("");
                onSearchTextChanged();
            } else {
                search.setFocused(true);
                search.mouseClicked(event, doubleClick);
            }
            return true;
        }
        search.setFocused(false);

        if (menuOpen) {
            if (in(mx, my, menuX, menuY, menuW, menuH)) {
                int row = (int) ((my - menuY - MENU_TOP) / MENU_ROW);
                if (my - menuY - MENU_TOP >= 0 && row < StatsSection.values().length) {
                    TreeState.toggleStatsSection(StatsSection.values()[row]);
                    rebuildStats();
                }
                return true;
            }
            if (toolAt(mx, my) != Tool.SECTIONS) menuOpen = false;
        }

        if (button == 0) {
            Tool tool = toolAt(mx, my);
            if (tool != null) {
                if (toolLocked(tool)) return true;
                clickSound();
                runTool(tool);
                return true;
            }
        }

        if (statsVisible) {
            if (handleStatsClick(mx, my, button)) return true;
            if (button == 0 && overBar(mx, my)) {
                float thumb = thumbSize();
                int tx = thumbX();
                statsGrab = mx < tx || mx > tx + thumb ? thumb / 2 : mx - tx;
                barDrag(mx);
                return true;
            }
        }

        if (button == 0 && handleCanvasClick(mx, my)) return true;

        // issue #39: a middle click opens the tag chooser for a node whose ingredient accepts several items. Unlike
        // the wheel it is not gated on prefItem / leftAmount (the reporter's AE2 case), so it works on every such node
        if (button == 2) {
            Bookmarks.Entry entry = optionEntry(nodeAt(mx, my));
            if (entry != null && IngredientDirectory.openSelect(entry, this, mx, my)) return true;
        }

        // what NEI's container does with a click on a hovered item: LMB recipes, RMB usages
        ItemStack stack = hoveredStack();
        if (!stack.isEmpty() && (button == 0 || button == 1)) {
            lookup(stack, button == 1);
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    /** NEI's handleWidgetClick: a click on a stats slot highlights what it stands for in the tree. */
    private boolean handleStatsClick(double mx, double my, int button) {
        TreeStatsLayout.Placed placed = statAt(mx, my);
        if (placed == null) return false;
        Object guid = guidOf(rowOf(placed));

        if (button == 1 && ui.searchStatsResult != null && ui.searchStatsResult.matchesGuid(guid)) {
            clickSound();
            ui.searchStatsResult = null;
            onSearchTextChanged();
            return true;
        }
        if (button == 0) {
            clickSound();
            if (ui.searchStatsResult == null || !ui.searchStatsResult.matchesGuid(guid)) {
                ui.searchItemResult = null;
                ui.searchStatsResult = new SearchResult(guid);
                updateSearchStatsMatches();
            }
            searchGoTo(ui.searchStatsResult, !Minecraft.getInstance().hasShiftDown());
            return true;
        }
        return false;
    }

    private boolean handleCanvasClick(double mx, double my) {
        if (!ui.camera.isInWidget(mx, my)) return false;
        TreeSlot deleteTarget = badgeAt(mx, my);
        TreeSlot node = deleteTarget != null ? null : nodeAt(mx, my);

        if (node != null) {
            if (Minecraft.getInstance().hasAltDown() && node.prefItem != null) {
                clickSound();
                Bookmarks.toggleCollapsedRecipe(page, gid, node.prefItem.recipeId);
                rebuildTree(node);
                return true;
            } else if (canvas.graph.childrenOf.containsKey(node)) {
                ui.replaceRecipeNode = node;
            }
        } else if (deleteTarget != null) {
            clickSound();
            deleteRecipe(deleteTarget);
            return true;
        } else {
            TreeSlot arrow = arrowAt(mx, my);
            if (arrow != null) {
                clickSound();
                if (!ui.collapsedItems.remove(arrow.ingrItem)) ui.collapsedItems.add(arrow.ingrItem);
                relayout(arrow);
                return true;
            }
            ui.camera.startDrag(mx, my);
        }
        return false;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (event.button() != 0) return super.mouseDragged(event, dx, dy);
        if (statsGrab >= 0) {
            barDrag(event.x());
            return true;
        }
        ui.camera.drag(event.x(), event.y());
        return true;
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        statsGrab = -1;
        ui.camera.endDrag();
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double scrollX, double scrollY) {
        int direction = scrollY > 0 ? 1 : scrollY < 0 ? -1 : 0;
        if (direction == 0) return super.mouseScrolled(mx, my, scrollX, scrollY);
        Minecraft mc = Minecraft.getInstance();
        boolean shift = mc.hasShiftDown();
        boolean ctrl = mc.hasControlDown();
        boolean alt = mc.hasAltDown();

        // NEI handleItemWheel: over the stats panel the wheel scrolls it; anywhere outside the tree nothing happens
        if (statsVisible && in(mx, my, statsX, statsY, statsW, statsH)) {
            if (statsCanScroll()) setStatsScroll(statsScroll - direction * 16);
            return true;
        }
        if (!ui.camera.isInWidget(mx, my)) return true;

        if (ctrl) {
            TreeSlot node = nodeAt(mx, my);
            if (node != null && canvas.graph.roots.contains(node)) {
                ChainItem item = node.ingrItem;
                if (item.recipeId != null) {
                    long multiplier = alt ? QuantityField.scrollMultiplier(stackOf(node)) : 1;
                    Bookmarks.shiftRecipeAmount(page, gid, item.recipeId, multiplier * direction);
                    rebuildTree(null);
                    return true;
                }
            }
        }

        if (shift && accepting != null) {
            if (Bookmarks.cycleOption(accepting, direction)) rebuildTree(null);
            return true;
        }

        ui.camera.scroll(mx, my, direction, shift, ctrl || AddonConfig.TREE_ZOOM_WITHOUT_CTRL.get());
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        int code = event.key();
        if (code == GLFW.GLFW_KEY_ESCAPE) {
            onClose();
            return true;
        }
        if (search.isFocused()) {
            if (code == GLFW.GLFW_KEY_ENTER || code == GLFW.GLFW_KEY_KP_ENTER) {
                if (ui.searchItemResult == null) onSearchTextChanged();
                else searchGoTo(ui.searchItemResult, !Minecraft.getInstance().hasShiftDown());
                return true;
            }
            search.keyPressed(event);
            return true;
        }
        InputConstants.Key key = InputConstants.Type.KEYSYM.getOrCreate(code);
        if (McCompat.matches(Minecraft.getInstance().options.keyInventory, key)) {
            onClose();
            return true;
        }
        Minecraft mc = Minecraft.getInstance();
        if (!mc.hasControlDown() && (CalcKeys.isShowRecipe(key) || CalcKeys.isShowUses(key))) {
            // NEI recipe.recipe / recipe.usage over an item of the tree or the stats panel (JEI's show-recipes / show-uses
            // keys); over a crafting slot of the stats panel the show-recipes key opens the recipe's result
            ItemStack stack = hoveredStack();
            if (stack.isEmpty() && hoveredStat != null) {
                TreeStats.Row row = rowOf(hoveredStat);
                if (row.recipeId != null) stack = stackOf(row.item, row.payload);
            }
            if (!stack.isEmpty()) {
                lookup(stack, !CalcKeys.isShowRecipe(key));
                return true;
            }
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        if (search.isFocused()) {
            search.charTyped(event);
            return true;
        }
        return super.charTyped(event);
    }
}

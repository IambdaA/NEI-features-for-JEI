package neiaddon;

import com.mojang.blaze3d.platform.InputConstants;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.gui.IRecipeLayoutDrawable;
import mezz.jei.api.gui.drawable.IDrawable;
import mezz.jei.api.recipe.RecipeIngredientRole;
import neiaddon.chain.ChainDetails;
import neiaddon.chain.ChainItem;
import neiaddon.chain.ChainMath;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import neiaddon.event.EventPriority;
import neiaddon.event.SubscribeEvent;
import neiaddon.event.ScreenEvent;
import org.lwjgl.glfw.GLFW;

/**
 * The bookmark panel, laid out like NEI's: a grid of item slots on the left of the screen with a 7px group strip
 * beside the rows, page buttons at the top (grid pages) and bottom (bookmark pages, "namespaces").
 *
 * <ul>
 * <li>Drag in the strip (left button) over rows to make or extend a group; drag with the right button to remove rows
 * from a group.</li>
 * <li>Slot: left click = recipes, right click = uses, middle click or Delete = remove, Shift + drag = move,
 * Ctrl + scroll = amount (on a recipe item: how often the whole recipe runs; Alt = stack size), Alt + click = collapse
 * the group.</li>
 * <li>Recipes (from the JEI heart button): in todo-list view every recipe is one row, its results first, then its
 * ingredients; in a crafting-chain group the amounts are calculated (see {@code neiaddon.chain}); amounts are drawn at
 * half size, the first result of an output recipe gets an {@code xN} marker.</li>
 * <li>Strip: Ctrl + click = crafting chain on/off (green), Alt + click = collapse, Ctrl + right click = calculator,
 * Shift + right click = remove the group.</li>
 * <li>Top label: click = calculator for ungrouped items, right click = chain on/off, scroll = grid page.</li>
 * <li>Bottom: {@code < n/N >} switch bookmark page, {@code +} adds a page.</li>
 * </ul>
 */
public final class BookmarkPanel {

    private static final int SLOT = 18;
    private static final int STRIP = 7;
    private static final int PAD = 2;
    private static final int BTN = 16;
    private static final int BAR = 18;

    private static final int HIGHLIGHT = 0xEE555555;
    private static final int DRAG_COLOR = 0x66555555;
    private static final int GROUP_NONE = 0xFF666666;
    private static final int GROUP_CHAIN = 0x6645DA75;
    private static final int SHADOW = 0x66222222;
    private static final int RECIPE_BORDER = 0x99A033A0;
    private static final int RESULT_BG = 0x4045DA75;

    private static final int NEW_GROUP = -1;
    private static final int SPLIT_GROUP = -2;

    /**
     * One drawn slot. {@code entry} is the bookmarked item behind it (for a merged slot of a collapsed recipe the first
     * item that went into it), {@code calc} the chain maths of the slot (null outside crafting chains) and
     * {@code recipe} the recipe it stands for: the collapsed recipe for the slots of a collapsed recipe, null for plain items.
     */
    private record Cell(Bookmarks.Entry entry, ChainDetails.Calc calc, String recipe, int slot, int row, int col,
        int groupId, boolean firstOutput) {}

    /** NEI's borderGrid: slot -> id of the box (collapsed group or collapsed recipe) drawn around its slots. */
    private static final Map<Integer, String> borderIds = new HashMap<>();

    /** Where a group (or the ungrouped items) is drawn this frame; JEI drag-and-drop target. */
    public record GroupArea(int groupId, Rect2i rect) {}

    private static final List<Cell> cells = new ArrayList<>();
    private static final Map<Integer, Integer> rowGroup = new HashMap<>();
    private static final List<GroupArea> groupAreas = new ArrayList<>();
    private static int totalRows;
    private static int gridPage;
    private static int px, py, pw, gridTop, cols, rows, footY, band, scrW, scrH;
    private static boolean active;
    private static int lastMx, lastMy;

    // strip drag (grouping)
    private static int dragMode; // 0 none, 1 include, 2 remove
    private static int dragStartRow, dragEndRow;
    private static boolean dragMoved;
    private static int dragButton;
    // item reorder drag (reorderRecipe: the recipe the dragged slot stands for, null for a plain item)
    private static Bookmarks.Entry reorder;
    private static String reorderRecipe;
    // whole-group move (Shift + left drag on the strip)
    private static int moveGroup = -1;

    private BookmarkPanel() {}

    private static final String JEI_RECIPES_GUI = "mezz.jei.gui.recipes.RecipesGui";
    private static boolean hidden;

    /** Container screens and the JEI recipe screen. */
    public static boolean supports(Screen s) {
        return s instanceof AbstractContainerScreen<?> || s.getClass().getName().equals(JEI_RECIPES_GUI);
    }

    private static boolean applies(Screen s) {
        return supports(s);
    }

    public static boolean isHidden() {
        return hidden;
    }

    public static void toggleHidden() {
        hidden = !hidden;
    }

    // ---------------------------------------------------------------- layout

    private static void layout(Screen screen) {
        // the JEI recipe window is centred and about half the screen wide
        int left = screen instanceof AbstractContainerScreen<?> c ? neiaddon.platform.ClientPlatform.get().containerBounds(c)[0] : (int) (screen.width * 0.26);
        int free = left - PAD * 2;
        cols = (free - STRIP) / SLOT;
        px = PAD;
        py = PAD;
        pw = Math.max(0, cols) * SLOT + STRIP;
        gridTop = py + BAR;
        // JEI's lookup history list (when on) sits above its two buttons at the bottom left; leave it room
        scrW = screen.width;
        scrH = screen.height;
        band = JeiButtons.historyBand();
        int bottom = screen.height - band;
        rows = (bottom - PAD * 2 - BAR * 2) / SLOT;
        footY = bottom - PAD - BTN;
        active = !hidden && cols >= 1 && rows >= 1;
    }

    private static boolean isCollapsed(Bookmarks.Page page, int gid) {
        Bookmarks.GroupMeta m = page.metas.get(gid);
        return m != null && m.collapsed;
    }

    private static boolean isTodo(Bookmarks.Page page, int gid) {
        Bookmarks.GroupMeta m = page.metas.get(gid);
        return m != null && m.todo;
    }

    private static boolean isChain(Bookmarks.Page page, int gid) {
        Bookmarks.GroupMeta m = page.metas.get(gid);
        return m != null && m.chain;
    }

    /**
     * NEI's {@code BookmarkGridGenerator.nextSlotIndex}: the next free slot for an item. A new group starts on a new row.
     * In todo-list view a recipe is one row: its result in the first column, its ingredients right after it; a plain
     * item gets a row of its own; ingredients that do not fit continue on the next row from the second column.
     */
    private static int nextSlot(int index, Bookmarks.Entry prev, Bookmarks.Entry e, boolean todo) {
        index = Math.max(0, index);
        for (int guard = 0; guard < cols * 4 + 4; guard++) {
            boolean firstColumn = index % cols == 0;
            if (!firstColumn && (prev == null || prev.groupId != e.groupId)) {
                index++;
            } else if (!todo) {
                return index;
            } else if (firstColumn && (e.recipeId == null || prev == null || prev.groupId != e.groupId
                || e.type != ChainItem.Type.INGREDIENT || !e.recipeId.equals(prev.recipeId))) {
                return index;
            } else if (!firstColumn && e.isRecipe() && prev.isRecipe() && e.groupId == prev.groupId
                && e.recipeId.equals(prev.recipeId)) {
                return index;
            } else {
                index++;
            }
        }
        return index;
    }

    private static Cell cellOf(Bookmarks.Entry item, ChainDetails d, int next, int gid, boolean first) {
        return new Cell(item, d != null ? d.calculated.get(item) : null, item.isRecipe() ? item.recipeId : null, next,
            next / cols, next % cols, gid, first);
    }

    /** What the grid draws for a slot: the calculated amount in a crafting chain, else the stored one. */
    /** Merged slots of a collapsed recipe cannot be edited item by item (tags, reordering). */
    private static boolean single(Cell c) {
        return c.calc() == null || !c.calc().shadow;
    }

    private static long amountOf(Cell c) {
        return c.calc() != null ? c.calc().calculatedAmount() : c.entry().amount;
    }

    /**
     * Lays the page's entries out in slots, like NEI's {@code BookmarkGridGenerator.generate}. A collapsed group shows
     * only its results that fit on one row; a crafting-chain group in normal view shows its results and plain items;
     * todo-list view puts one recipe on each row (see {@link #nextSlot}); a collapsed recipe is one block of merged
     * slots (its results, then what it and the recipes it swallows need); everything else is sequential.
     */
    private static void buildCells(Bookmarks.Page page) {
        cells.clear();
        rowGroup.clear();
        borderIds.clear();
        int slot = -1;
        int prevSlot = -2;
        Bookmarks.Entry prev = null;
        int skipUntil = 0; // entries of a run already placed
        for (int index = 0; index < page.entries.size(); index++) {
            if (index < skipUntil) continue;
            Bookmarks.Entry e = page.entries.get(index);
            int gid = e.groupId;
            Bookmarks.GroupMeta meta = page.metas.get(gid);
            ChainDetails d = Bookmarks.chain(page, gid);
            boolean collapsed = meta != null && meta.collapsed;
            boolean todo = meta != null && meta.todo;
            // NEI: in a crafting chain only the items the maths drew a slot for are shown; a collapsed recipe's own
            // items stand in for all of its recipes
            boolean anchor = d != null && e.recipeId != null && e.type != ChainItem.Type.INGREDIENT
                && meta.collapsedRecipes.contains(e.recipeId);
            if (d != null && !anchor && (!d.calculated.containsKey(e)
                || e.recipeId != null && !e.recipeId.equals(d.itemToRecipe.getOrDefault(e, e.recipeId)))) {
                continue;
            }
            if (collapsed || d != null && !todo) {
                // NEI getGroupResults: the run of consecutive items of the group (the ungrouped items can be several runs)
                List<Bookmarks.Entry> sequence = new ArrayList<>();
                int end = index;
                while (end < page.entries.size() && page.entries.get(end).groupId == gid) {
                    Bookmarks.Entry x = page.entries.get(end++);
                    if (x.type != ChainItem.Type.INGREDIENT && (d == null || d.calculated.containsKey(x))) sequence.add(x);
                }
                skipUntil = end;
                for (int i = 0; i < sequence.size(); i++) {
                    Bookmarks.Entry item = sequence.get(i);
                    int next = nextSlot(slot + 1, prev, item, false);
                    if (collapsed && i > 0 && next % cols == 0) break;
                    Bookmarks.Entry adjacent = prevSlot == next - 1 ? prev : null;
                    boolean first = item.type == ChainItem.Type.RESULT && item.isRecipe()
                        && (adjacent == null || adjacent.type == ChainItem.Type.INGREDIENT
                            || !Objects.equals(adjacent.recipeId, item.recipeId) || adjacent.groupId != item.groupId);
                    cells.add(cellOf(item, d, next, gid, first));
                    rowGroup.put(next / cols, gid);
                    if (collapsed) borderIds.put(next, "group:" + gid);
                    else if (d != null && item.recipeId != null && d.collapsedItems.containsKey(item.recipeId)) {
                        borderIds.put(next, "recipe:" + item.recipeId);
                    }
                    prev = item;
                    prevSlot = next;
                    slot = next;
                }
            } else if (d != null && e.recipeId != null && d.itemToRecipe.containsValue(e.recipeId)) {
                // a collapsed recipe in todo-list view: one block of merged slots
                String recipe = e.recipeId;
                List<ChainDetails.Calc> block = new ArrayList<>(d.collapsedItems.get(recipe));
                block.sort(
                    Comparator.<ChainDetails.Calc>comparingInt(c -> c.type.toInt())
                        .thenComparing(c -> c.item.item));
                for (int i = 0; i < block.size(); i++) {
                    ChainDetails.Calc calc = block.get(i);
                    Bookmarks.Entry item = (Bookmarks.Entry) calc.item.payload;
                    boolean ingredient = i != 0 || calc.type == ChainDetails.Calculated.INGREDIENT;
                    Bookmarks.Entry shape = new Bookmarks.Entry(null, 0, gid);
                    shape.recipeId = recipe;
                    shape.type = ingredient ? ChainItem.Type.INGREDIENT : ChainItem.Type.RESULT;
                    int next = nextSlot(slot + 1, prev, shape, true);
                    Bookmarks.Entry adjacent = prevSlot == next - 1 ? prev : null;
                    boolean first = !calc.shadow && calc.item.type == ChainItem.Type.RESULT && next % cols == 0
                        && (adjacent == null || adjacent.type == ChainItem.Type.INGREDIENT
                            || !Objects.equals(adjacent.recipeId, recipe) || adjacent.groupId != gid);
                    cells.add(new Cell(item, calc, recipe, next, next / cols, next % cols, gid, first));
                    rowGroup.put(next / cols, gid);
                    borderIds.put(next, "recipe:" + recipe);
                    prev = shape;
                    prevSlot = next;
                    slot = next;
                }
                // NEI: the rest of the recipe's items are not slots of their own
                int end = index;
                while (end < page.entries.size() && page.entries.get(end).sameRecipe(recipe, gid)) end++;
                skipUntil = Math.max(end, index + 1);
            } else {
                int next = nextSlot(slot + 1, prev, e, todo);
                Bookmarks.Entry adjacent = prevSlot == next - 1 ? prev : null;
                boolean first = e.type == ChainItem.Type.RESULT && e.isRecipe() && (next % cols == 0 || !todo)
                    && (adjacent == null || adjacent.type == ChainItem.Type.INGREDIENT
                        || !Objects.equals(adjacent.recipeId, e.recipeId) || adjacent.groupId != e.groupId);
                cells.add(cellOf(e, d, next, gid, first));
                rowGroup.put(next / cols, gid);
                prev = e;
                prevSlot = next;
                slot = next;
            }
        }
        totalRows = slot < 0 ? 0 : slot / cols + 1;
        int pages = gridPages();
        gridPage = Math.max(0, Math.min(gridPage, pages - 1));
    }

    private static int gridPages() {
        return Math.max(1, (totalRows + rows - 1) / rows);
    }

    private static int firstRow() {
        return gridPage * rows;
    }

    private static int cellX(Cell c) {
        return px + STRIP + c.col() * SLOT;
    }

    private static int cellY(Cell c) {
        return gridTop + (c.row() - firstRow()) * SLOT;
    }

    private static Cell cellAt(double mx, double my) {
        for (Cell c : cells) {
            if (c.row() < firstRow() || c.row() >= firstRow() + rows) continue;
            int x = cellX(c);
            int y = cellY(c);
            if (mx >= x && mx < x + SLOT && my >= y && my < y + SLOT) return c;
        }
        return null;
    }

    /** Absolute row under the mouse in the group strip, or -1. */
    private static int stripRowAt(double mx, double my) {
        if (mx < px || mx >= px + STRIP || my < gridTop) return -1;
        int rel = (int) ((my - gridTop) / SLOT);
        int row = firstRow() + rel;
        if (rel >= rows || row >= totalRows) return -1;
        return row;
    }

    private static int clampRow(double my) {
        int rel = (int) Math.floor((my - gridTop) / SLOT);
        rel = Math.max(0, Math.min(rel, rows - 1));
        int row = firstRow() + rel;
        return Math.max(0, Math.min(row, totalRows - 1));
    }

    /** The screen area this panel occupies, for JEI's "avoid this area" handler. */
    public static List<Rect2i> extraAreas() {
        Screen s = McCompat.screen();
        if (applies(s)) {
            Screen screen = s;
            layout(screen);
            if (active) return List.of(new Rect2i(px, py, pw, screen.height - band - PAD * 2));
        }
        return List.of();
    }

    /** The bookmarked item under the mouse (for Ctrl+C / Ctrl+X), or empty. */
    public static ItemStack hoveredStack() {
        Screen s = McCompat.screen();
        if (s == null || !applies(s)) return ItemStack.EMPTY;
        layout(s);
        if (!active || !inPanel(lastMx, lastMy)) return ItemStack.EMPTY;
        Cell c = cellAt(lastMx, lastMy);
        return c == null ? ItemStack.EMPTY : c.entry().key.sample;
    }

    public static List<GroupArea> groupAreas() {
        return List.copyOf(groupAreas);
    }

    public static Rect2i panelRect() {
        return new Rect2i(px, py, pw, Math.max(1, footY + BTN - py));
    }

    // ---------------------------------------------------------------- drag preview

    /** NEI's {@code GroupingItem.prepareGroups}: the row->group map as it would look if the drag ended now. */
    private static Map<Integer, Integer> preview() {
        Map<Integer, Integer> eff = new HashMap<>(rowGroup);
        if (dragMode == 0 || !dragMoved) return eff;
        int lo = Math.min(dragStartRow, dragEndRow);
        int hi = Math.max(dragStartRow, dragEndRow);
        int g0 = rowGroup.getOrDefault(dragStartRow, Bookmarks.NO_GROUP);
        int target;
        if (dragMode == 2) {
            target = Bookmarks.NO_GROUP;
            int above = eff.getOrDefault(lo - 1, Bookmarks.NO_GROUP);
            int below = eff.getOrDefault(hi + 1, Bookmarks.NO_GROUP);
            if (above != Bookmarks.NO_GROUP && above == below) {
                for (int r = hi + 1; eff.getOrDefault(r, -99) == above; r++) eff.put(r, SPLIT_GROUP);
            }
        } else if (g0 != Bookmarks.NO_GROUP) {
            target = g0;
            if (dragEndRow < dragStartRow) {
                for (int r = lo - 1; eff.getOrDefault(r, -99) == g0; r--) eff.put(r, Bookmarks.NO_GROUP);
            } else if (dragEndRow > dragStartRow) {
                for (int r = hi + 1; eff.getOrDefault(r, -99) == g0; r++) eff.put(r, Bookmarks.NO_GROUP);
            }
        } else {
            target = NEW_GROUP;
        }
        for (int r = lo; r <= hi; r++) {
            if (eff.containsKey(r)) eff.put(r, target);
        }
        return eff;
    }

    private static void applyDrag(Bookmarks.Page page) {
        Map<Integer, Integer> eff = preview();
        int fresh = page.nextGroupId();
        int newId = fresh;
        int splitId = fresh + 1;
        Set<Bookmarks.Entry> touched = new HashSet<>();
        for (Cell c : cells) {
            int want = eff.getOrDefault(c.row(), c.groupId());
            if (want == c.groupId()) continue;
            int gid = want == NEW_GROUP ? newId : want == SPLIT_GROUP ? splitId : want;
            if (isCollapsed(page, c.groupId())) {
                // the collapsed row stands for the run of consecutive items of the group (the ungrouped items can be several)
                int at = page.entries.indexOf(c.entry());
                int from = at;
                while (from > 0 && page.entries.get(from - 1).groupId == c.groupId()) from--;
                for (int i = from; i < page.entries.size() && page.entries.get(i).groupId == c.groupId(); i++) {
                    if (touched.add(page.entries.get(i))) page.entries.get(i).groupId = gid;
                }
            } else if (touched.add(c.entry())) {
                int from = c.entry().groupId;
                String recipe = c.recipe();
                Set<String> family = recipe != null ? family(page, from, recipe) : Set.of();
                c.entry().groupId = gid;
                if (recipe != null) {
                    // the rest of the recipe (also items not drawn in normal view, and the recipes a collapsed recipe swallows) goes along
                    for (Bookmarks.Entry e : page.entries) {
                        if (e.isRecipe() && e.groupId == from && family.contains(e.recipeId) && touched.add(e)) e.groupId = gid;
                    }
                }
            }
        }
        // a split-off part inherits the chain flag of the group it came from
        if (eff.containsValue(SPLIT_GROUP)) {
            int from = rowGroup.getOrDefault(dragStartRow, Bookmarks.NO_GROUP);
            int above = rowGroup.getOrDefault(Math.min(dragStartRow, dragEndRow) - 1, Bookmarks.NO_GROUP);
            Bookmarks.GroupMeta src = page.metas.get(above != Bookmarks.NO_GROUP ? above : from);
            if (src != null) {
                Bookmarks.GroupMeta m = page.meta(splitId);
                m.chain = src.chain;
                m.collapsed = src.collapsed;
            }
        }
        page.normalize();
        Bookmarks.save();
    }

    // ---------------------------------------------------------------- drawing

    @SubscribeEvent(priority = EventPriority.LOW)
    static void onForeground(ScreenEvent.Render.Post event) {
        Screen screen = event.getScreen();
        if (!applies(screen)) return;
        Bookmarks.ensureLoaded();
        layout(screen);
        if (!active) return;
        GuiGraphics g = event.getGuiGraphics();
        Pose pose = Pose.of(g);
        pose.pushMatrix();
        pose.identity();
        pose.translateZ(PANEL_Z);
        try {
            draw(g, event.getMouseX(), event.getMouseY());
        } finally {
            pose.popMatrix();
        }
    }

    static void button(GuiGraphics g, Font font, int x, int y, int w, int h, String label, boolean over) {
        g.fill(x, y, x + w, y + h, 0xFF000000);
        g.fill(x + 1, y + 1, x + w - 1, y + h - 1, over ? 0xFF7A8CC8 : 0xFF8B8B8B);
        g.fill(x + 1, y + 1, x + w - 1, y + 2, 0xFFFFFFFF);
        g.fill(x + 1, y + 1, x + 2, y + h - 1, 0xFFFFFFFF);
        g.fill(x + 1, y + h - 2, x + w - 1, y + h - 1, 0xFF555555);
        g.fill(x + w - 2, y + 1, x + w - 1, y + h - 1, 0xFF555555);
        g.drawString(font, label, x + (w - font.width(label)) / 2, y + (h - 8) / 2, over ? 0xFFFFFFA0 : 0xFFE0E0E0, true);
    }

    /** 1.21.1 draws with a depth test: the panel sits in front of the container's own items. */
    private static final float PANEL_Z = 100f;

    private static boolean in(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    // top bar: grid page buttons
    private static int hdrPrevX() {
        return px;
    }

    private static int hdrNextX() {
        return px + pw - BTN;
    }

    private static int hdrY() {
        return py;
    }

    // bottom bar: bookmark page ("namespace") buttons
    private static int nsLabelW() {
        return 28;
    }

    /** Keeps the bookmark-page buttons clear of JEI's own toggle buttons in the bottom-left corner. */
    private static int nsCenter() {
        int reserve = band == 0 && pw >= 150 ? 92 : 0;
        int center = px + reserve + (pw - reserve) / 2;
        // leave room on the right for the P button (pull items); narrow panels (big GUI scales) shift the whole row left
        int latest = px + pw - (nsLabelW() / 2 + 2 + BTN + 2 + BTN);
        return Math.max(px + nsLabelW() / 2 + 2 + BTN, Math.min(center, latest));
    }

    private static int nsPrevX() {
        return nsCenter() - nsLabelW() / 2 - 2 - BTN;
    }

    private static int nsNextX() {
        return nsCenter() + nsLabelW() / 2 + 2;
    }

    /** NEI's "P" button: right of the bookmark page arrows. */
    private static int pullX() {
        return nsNextX() + BTN + 2;
    }

    /** NEI shows it only with a chest open and bookmarks to pull. */
    private static boolean pullShown(Bookmarks.Page page) {
        return Pull.available() && !page.entries.isEmpty() && pullX() + BTN <= px + pw;
    }

    private static void draw(GuiGraphics g, int mx, int my) {
        Font font = Minecraft.getInstance().font;
        lastMx = mx;
        lastMy = my;
        Bookmarks.Page page = Bookmarks.page();
        buildCells(page);
        groupAreas.clear();
        List<String> tip = null;
        GroupTip groupTip = null;
        ItemStack tipStack = ItemStack.EMPTY;

        // top bar
        int pages = gridPages();
        boolean overPrev = in(mx, my, hdrPrevX(), hdrY(), BTN, BTN);
        boolean overNext = in(mx, my, hdrNextX(), hdrY(), BTN, BTN);
        int labX = px + BTN;
        int labW = pw - BTN * 2;
        boolean overLabel = in(mx, my, labX, hdrY(), labW, BTN);
        if (pages > 1 || !page.entries.isEmpty()) {
            button(g, font, hdrPrevX(), hdrY(), BTN, BTN, "<", overPrev);
            button(g, font, hdrNextX(), hdrY(), BTN, BTN, ">", overNext);
            String label = isChain(page, Bookmarks.NO_GROUP) ? "§2[§r" + (gridPage + 1) + "/" + pages + "§2]§r"
                : (gridPage + 1) + "/" + pages;
            g.drawString(font, label, labX + (labW - font.width(label)) / 2, hdrY() + 4, 0xFFFFFFFF, true);
        }
        if (overLabel && !page.entries.isEmpty()) {
            groupTip = groupTip(page, Bookmarks.NO_GROUP, true);
        }

        // bottom bar
        boolean overNsPrev = in(mx, my, nsPrevX(), footY, BTN, BTN);
        boolean overNsNext = in(mx, my, nsNextX(), footY, BTN, BTN);
        button(g, font, nsPrevX(), footY, BTN, BTN, "<", overNsPrev);
        button(g, font, nsNextX(), footY, BTN, BTN, ">", overNsNext);
        String ns = (Bookmarks.activePage + 1) + "/" + Bookmarks.pageCount();
        g.drawString(font, ns, nsCenter() - font.width(ns) / 2, footY + 4, 0xFFFFFFFF, true);
        if (overNsPrev || overNsNext) tip = List.of("Bookmark page " + ns, "§7Scroll: switch page");
        boolean overPull = pullShown(page) && in(mx, my, pullX(), footY, BTN, BTN);
        if (pullShown(page)) button(g, font, pullX(), footY, BTN, BTN, "P", overPull);
        if (overPull) tip = List.of("Pull all bookmarked items from storage to inventory", "§7Shift: only the missing items");

        // group strip
        Map<Integer, Integer> eff = preview();
        int first = firstRow();
        int last = Math.min(totalRows, first + rows);
        if (dragMode == 0 && reorder == null && moveGroup < 0) {
            int sr = stripRowAt(mx, my);
            if (sr >= 0) {
                int y = gridTop + (sr - first) * SLOT;
                g.fill(px, y, px + STRIP, y + SLOT, HIGHLIGHT);
                int gid = rowGroup.getOrDefault(sr, Bookmarks.NO_GROUP);
                groupTip = groupTip(page, gid, false);
            }
        } else if (dragMode != 0 && dragMoved) {
            int lo = Math.min(dragStartRow, dragEndRow);
            int hi = Math.max(dragStartRow, dragEndRow);
            for (int r = Math.max(lo, first); r <= Math.min(hi, last - 1); r++) {
                g.fill(px, gridTop + (r - first) * SLOT, px + STRIP, gridTop + (r - first + 1) * SLOT, DRAG_COLOR);
            }
        }
        if (moveGroup > 0) {
            for (int rr = first; rr < last; rr++) {
                if (rowGroup.getOrDefault(rr, Bookmarks.NO_GROUP) == moveGroup) {
                    g.fill(px, gridTop + (rr - first) * SLOT, px + STRIP, gridTop + (rr - first + 1) * SLOT, DRAG_COLOR);
                }
            }
        }
        int r = 0;
        while (r < totalRows) {
            int gid = eff.getOrDefault(r, Bookmarks.NO_GROUP);
            int end = r;
            while (end + 1 < totalRows && eff.getOrDefault(end + 1, Bookmarks.NO_GROUP) == gid) end++;
            int cs = Math.max(r, first);
            int ce = Math.min(end, last - 1);
            if (cs <= ce) {
                int top = gridTop + (cs - first) * SLOT;
                int height = (ce - cs + 1) * SLOT;
                if (gid > 0) {
                    int color = isChain(page, gid) ? GROUP_CHAIN : GROUP_NONE;
                    drawBracket(g, top, height, r >= first, end < last, color);
                    groupAreas.add(new GroupArea(gid, new Rect2i(px, top, pw, height)));
                } else {
                    g.fill(px + 3, top + 2, px + 4, top + height - 2, SHADOW);
                }
            }
            r = end + 1;
        }

        // slots
        Cell hover = reorder == null ? cellAt(mx, my) : null;
        for (Cell c : cells) {
            if (c.row() < first || c.row() >= last) continue;
            int x = cellX(c);
            int y = cellY(c);
            boolean hovered = c == hover || (reorder != null && c.entry() == reorder);
            boolean recipeResult = c.recipe() != null && c.entry().type != ChainItem.Type.INGREDIENT;
            // NEI showRecipeMarkerMode 2: results of a recipe get a tinted background
            if (recipeResult && !hovered && AddonConfig.BM_MARKER_MODE.get() == 2) g.fill(x, y, x + SLOT, y + SLOT, RESULT_BG);
            if (hovered) g.fill(x, y, x + SLOT, y + SLOT, HIGHLIGHT);
            ItemStack stack = c.entry().key.sample;
            FluidStacks.item(g, stack, x + 1, y + 1);
            g.renderItemDecorations(font, stack, x + 1, y + 1, null);
            // NEI: amounts are drawn small (half size) bottom right, only when above 0
            long amount = amountOf(c);
            if (amount > 0) {
                String text = format(amount);
                drawSmall(g, font, text, x + 1 + 16 - font.width(text) * 0.5f, y + 1 + 16 - 9 * 0.5f, 0xFFFFFFFF);
            }
            // NEI: the first result of an output recipe gets an "xN" marker (N = the recipe's real multiplier) top left
            if (c.firstOutput()) {
                ChainDetails.Calc calc = c.calc();
                boolean output = calc != null && Bookmarks.chain(page, c.groupId()).outputRecipes.contains(calc.item.recipeId);
                long real = calc != null ? calc.realMultiplier : c.entry().multiplier();
                if (output || real > 1) drawSmall(g, font, "x" + format(real), x + 1, y + 1, 0xFF000000 | AddonConfig.BM_MARKER_COLOR.get());
            }
            if (recipeResult && (c.firstOutput() || AddonConfig.BM_MARKER_MODE.get() == 1)) drawRecipeMarker(g, font, c, x, y);
            if (c == hover) {
                tipStack = stack;
            }
        }
        drawBorders(g, page, first, last);

        // tooltips
        if (reorder != null) {
            Pose.begin(g, Pose.OVER);
            FluidStacks.item(g, reorder.key.sample, mx - 8, my - 8);
            Pose.end(g);
        } else if (!tipStack.isEmpty() && dragMode == 0) {
            itemTooltip(g, font, tipStack, hover, mx, my);
        } else if (groupTip != null && dragMode == 0 && tip == null) {
            drawGroupTip(g, font, groupTip, mx, my);
        } else if (tip != null && dragMode == 0) {
            List<FormattedCharSequence> lines = new ArrayList<>();
            for (String s : tip) lines.add(Component.literal(s).getVisualOrderText());
            g.renderTooltip(font, lines, mx, my);
        }
    }

    /** NEI's slot borders: a box around the slots of a collapsed group or a collapsed recipe. */
    private static void drawBorders(GuiGraphics g, Bookmarks.Page page, int first, int last) {
        for (Cell c : cells) {
            if (c.row() < first || c.row() >= last) continue;
            String id = borderIds.get(c.slot());
            if (id == null) continue;
            int color = !isChain(page, c.groupId()) ? 0xFF666666 : isCollapsed(page, c.groupId()) ? GROUP_CHAIN : RECIPE_BORDER;
            int x = cellX(c);
            int y = cellY(c);
            if (c.col() == 0 || !id.equals(borderIds.get(c.slot() - 1))) g.fill(x, y, x + 1, y + SLOT, color);
            if (c.col() + 1 == cols || !id.equals(borderIds.get(c.slot() + 1))) g.fill(x + SLOT - 1, y, x + SLOT, y + SLOT, color);
            if (c.row() == first || !id.equals(borderIds.get(c.slot() - cols))) g.fill(x, y, x + SLOT, y + 1, color);
            if (c.row() == last - 1 || !id.equals(borderIds.get(c.slot() + cols))) g.fill(x, y + SLOT - 1, x + SLOT, y + SLOT, color);
        }
    }

    private static void drawBracket(GuiGraphics g, int top, int height, boolean startsHere, boolean endsHere,
        int color) {
        int half = STRIP / 2;
        int pad = SLOT / 4;
        int left = px + STRIP - half - 1;
        int bottom = top + height;
        int lineTop = top;
        int lineBottom = bottom;
        if (startsHere) {
            g.fill(left, top + pad, left + half, top + pad + 1, color);
            lineTop = top + pad + 1;
        }
        if (endsHere) {
            g.fill(left, bottom - pad, left + half, bottom - pad + 1, color);
            lineBottom = bottom - pad;
        }
        g.fill(left, lineTop, left + 1, lineBottom, color);
    }

    // ---------------------------------------------------------------- recipe markers and pictures

    private static Object cacheOwner;
    private static final Map<String, Optional<IDrawable>> icons = new HashMap<>();
    private static final Map<String, IRecipeLayoutDrawable<?>> pictures = new HashMap<>();
    private static final java.util.Set<String> noPicture = new java.util.HashSet<>();

    private static void checkCaches() {
        if (cacheOwner != Calculator.runtime) {
            cacheOwner = Calculator.runtime;
            icons.clear();
            pictures.clear();
            noPicture.clear();
        }
    }

    /** NEI's drawRecipeMarker: the recipe type's icon (when asked for) or an "R", small in the top right corner. */
    private static void drawRecipeMarker(GuiGraphics g, Font font, Cell c, int x, int y) {
        checkCaches();
        IDrawable icon = null;
        if (AddonConfig.BM_HANDLER_ICON.get()) {
            icon = icons.computeIfAbsent(c.recipe(), id -> {
                try {
                    RecipeLookup.Target<?> target = RecipeLookup.findTarget(id, c.entry().key.sample);
                    return Optional.ofNullable(target == null ? null : target.icon());
                } catch (RuntimeException e) {
                    return Optional.empty();
                }
            }).orElse(null);
        }
        if (icon != null && icon.getWidth() > 0 && icon.getHeight() > 0) {
            float f = 6.4f / Math.max(icon.getWidth(), icon.getHeight());
            Pose pose = Pose.of(g);
            pose.pushMatrix();
            pose.translateZ(Pose.OVER);
            pose.translate(x + 17 - icon.getWidth() * f, y + 1);
            pose.scale(f, f);
            icon.draw(g, 0, 0);
            pose.popMatrix();
        } else if (AddonConfig.BM_MARKER_MODE.get() == 1) {
            drawSmall(g, font, "R", x + 17 - font.width("R") * 0.5f, y + 1, 0xFF000000 | AddonConfig.BM_MARKER_COLOR.get());
        }
    }

    /** NEI recipeTooltipsMode: 0 never, 1 default view, 2 todo-list view, 3 always. */
    private static boolean recipePictureWanted(Bookmarks.Page page, Cell cell) {
        int mode = AddonConfig.BM_TOOLTIP_MODE.get();
        Bookmarks.GroupMeta meta = page.meta(cell.groupId());
        boolean todo = meta != null && meta.todo;
        return cell.recipe() != null && (mode == 3 || mode == 1 && !todo || mode == 2 && todo);
    }

    private static IRecipeLayoutDrawable<?> picture(String recipeId, ItemStack hint) {
        checkCaches();
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
            NeiAddon.LOGGER.warn("Could not draw recipe {} in the bookmark tooltip", recipeId, e);
        }
        noPicture.add(recipeId);
        return null;
    }

    /** Text at half size ({@code scale 0.5}) with its top left corner at (x, y), like NEI's {@code drawNEIOverlayText}. */
    private static void drawSmall(GuiGraphics g, Font font, String text, float x, float y, int color) {
        Pose pose = Pose.of(g);
        pose.pushMatrix();
        pose.translateZ(Pose.OVER);
        pose.translate(x, y);
        pose.scale(0.5f, 0.5f);
        g.drawString(font, text, 0, 0, color, true);
        pose.popMatrix();
    }

    private static String format(long amount) {
        if (amount >= 10_000_000) return (amount / 1_000_000) + "M";
        if (amount >= 10_000) return (amount / 1000) + "k";
        return Long.toString(amount);
    }

    /** A tooltip over a group: text lines, then icon sections (what NEI's RecipeChainTooltipLineHandler draws). */
    private record GroupTip(List<String> lines, List<Section> sections) {}

    /**
     * One icon list of a tooltip (NEI's ItemsTooltipLineHandler): {@code amounts} is empty when no amounts are drawn,
     * {@code active} is the highlighted item (-1 for none).
     */
    private record Section(String label, List<ItemStack> stacks, List<Long> amounts, int maxRows, int active, int missing) {}

    /** NEI's tooltip over the group strip / page label: "Bookmarks Group", its hotkeys, then the crafting chain. */
    private static GroupTip groupTip(Bookmarks.Page page, int gid, boolean label) {
        List<String> tip = new ArrayList<>();
        tip.add("Bookmarks Group");
        Map<String, String> k = new HashMap<>();
        String add = HotkeyTips.addKey();
        if (!label) k.put(HotkeyTips.keys("LMB", "Drag"), "Create/Include Group");
        if (gid > 0 || label) {
            k.put(HotkeyTips.keys("CTRL", "Scroll"), "Change Quantity");
            k.put(HotkeyTips.keys("CTRL", "ALT", "Scroll"), "Change Quantity with Multiplier");
            k.put("LMB", "Toggle Group Mode");
            k.put("RMB", "Toggle Crafting Chain");
            k.put(HotkeyTips.keys("SHIFT", add), "Remove Group");
            k.put(HotkeyTips.keys("CTRL", HotkeyTips.chatKey()), "Send Bookmark Group to Chat");
            k.put(HotkeyTips.keys("ALT", "LMB"), "Toggle Collapse/Expand");
            if (isChain(page, gid)) {
                k.put(CalcKeys.TREE.getTranslatedKeyMessage()
                    .getString(), "Open Crafting Tree");
                HotkeyTips.craftKeys(k);
            }
            HotkeyTips.pullKeys(k);
        }
        if (!label && gid > 0) {
            k.put(HotkeyTips.keys("RMB", "Drag"), "Remove/Exclude Group");
            k.put(HotkeyTips.keys("SHIFT", "LMB", "Drag"), "Move Position");
        }
        tip.addAll(HotkeyTips.hotkeyLines(k));
        List<Section> sections = isChain(page, gid) ? chainSections(page, gid) : List.of();
        if (!sections.isEmpty()) tip.add("§bCrafting Chain");
        return new GroupTip(tip, sections);
    }

    /** NEI's RecipeChainTooltipLineHandler.createLines without Shift: what the chain makes and what it needs. */
    private static List<Section> chainSections(Bookmarks.Page page, int gid) {
        if (McCompat.shift()) {
            List<Section> counted = chainSectionsShift(page, gid, McCompat.ctrl());
            if (!counted.isEmpty()) return counted;
        }
        ChainDetails details = page.hasRecipes(gid) ? Bookmarks.chain(page, gid) : null;
        if (details == null) return List.of();
        ChainMath math = details.math;
        Map<String, Object[]> outputs = new LinkedHashMap<>();
        Map<String, Object[]> inputs = new LinkedHashMap<>();
        for (ChainItem item : math.recipeResults) {
            long amount = item.amount() - math.requiredAmount.getOrDefault(item, 0L);
            if (amount > 0 && math.outputRecipes.containsKey(item.recipeId)) sum(outputs, item, amount);
        }
        for (ChainItem item : math.initialItems) {
            long amount = math.requiredAmount.getOrDefault(item, 0L);
            if (amount > 0) sum(inputs, item, amount);
        }
        for (ChainItem item : math.recipeIngredients) {
            long amount = math.requiredAmount.containsKey(math.preferredItems.get(item)) ? 0
                : math.requiredAmount.getOrDefault(item, item.amount());
            if (amount > 0) sum(inputs, item, amount);
        }
        List<Section> out = new ArrayList<>();
        addSection(out, "Results", outputs);
        addSection(out, "Ingredients", inputs);
        return out;
    }

    // ---- Shift: the chain with the inventory counted (NEI's RecipeChainTooltipLineHandler with the Shift key held)

    private record ShiftCache(Bookmarks.Page page, int gid, boolean ctrl, long at, List<Section> sections) {}

    private static ShiftCache shiftCache;

    /** Everything the open menu holds (no crafting result slot), as item id to count. */
    private static Map<String, Long> inventoryCounts() {
        Map<String, Long> have = new LinkedHashMap<>();
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return have;
        for (net.minecraft.world.inventory.Slot slot : mc.player.containerMenu.slots) {
            if (slot.hasItem() && !(slot instanceof net.minecraft.world.inventory.ResultSlot) && slot.mayPickup(mc.player)) {
                have.merge(RecipeData.idOf(slot.getItem()), (long) slot.getItem().getCount(), Long::sum);
            }
        }
        return have;
    }

    private static ItemStack stackFor(ChainItem item) {
        if (item.payload instanceof Bookmarks.Entry e) return e.key.sample;
        return FluidStacks.stackOf(item.item);
    }

    private static void sumInto(Map<String, Object[]> into, ChainItem item, long amount) {
        ItemStack stack = stackFor(item);
        if (stack.isEmpty()) return;
        Object[] slot = into.computeIfAbsent(item.item, k -> new Object[] { stack, 0L });
        slot[1] = (Long) slot[1] + amount;
    }

    private static List<Map.Entry<String, Object[]>> byAmount(Map<String, Object[]> items) {
        List<Map.Entry<String, Object[]>> sorted = new ArrayList<>(items.entrySet());
        sorted.sort((a, b) -> {
            int c = Long.compare((Long) b.getValue()[1], (Long) a.getValue()[1]);
            return c != 0 ? c : a.getKey().compareTo(b.getKey());
        });
        return sorted;
    }

    private static Section sectionOf(String label, List<Map.Entry<String, Object[]>> entries, int missing) {
        List<ItemStack> stacks = new ArrayList<>();
        List<Long> amounts = new ArrayList<>();
        for (Map.Entry<String, Object[]> e : entries) {
            stacks.add((ItemStack) e.getValue()[0]);
            amounts.add((Long) e.getValue()[1]);
        }
        return new Section(label, stacks, amounts, TIP_ROWS, -1, missing);
    }

    /** Recomputed twice a second, not every frame. */
    private static List<Section> chainSectionsShift(Bookmarks.Page page, int gid, boolean ctrl) {
        long now = System.currentTimeMillis();
        ShiftCache c = shiftCache;
        if (c != null && c.page() == page && c.gid() == gid && c.ctrl() == ctrl && now - c.at() < 500) return c.sections();
        List<Section> result;
        try {
            result = computeShift(page, gid, ctrl);
        } catch (RuntimeException e) {
            NeiAddon.LOGGER.warn("Chain tooltip with inventory failed", e);
            result = List.of();
        }
        shiftCache = new ShiftCache(page, gid, ctrl, now, result);
        return result;
    }

    private static List<Section> computeShift(Bookmarks.Page page, int gid, boolean ctrl) {
        ChainMath m = page.hasRecipes(gid) ? Bookmarks.autocraftChain(page, gid) : null;
        if (m == null) return List.of();
        Map<String, Long> have = inventoryCounts();
        m.initialItems.clear();
        if (!ctrl) {
            // without Ctrl: what the inventory already holds is crafted again on top (as Shift+C does)
            for (ChainItem item : m.recipeResults) {
                if (item.emptyFactor() || !m.outputRecipes.containsKey(item.recipeId)) continue;
                long amount = have.getOrDefault(item.item, 0L);
                if (amount < item.amount()) continue;
                long itemAmount = item.amount(m.outputRecipes.get(item.recipeId));
                if (itemAmount > 0) amount += itemAmount - amount % itemAmount;
                m.outputRecipes.put(item.recipeId, Math.max(m.outputRecipes.get(item.recipeId), item.multiplierFromAmount(amount)));
            }
        }
        for (Map.Entry<String, Long> e : have.entrySet()) {
            m.initialItems.add(new ChainItem(e.getKey(), null, ChainItem.Type.ITEM, 1, e.getValue(), null, null));
        }
        m.refresh();

        Map<String, Object[]> available = new LinkedHashMap<>();
        Map<String, Object[]> inputs = new LinkedHashMap<>();
        Map<String, Object[]> outputs = new LinkedHashMap<>();
        Map<String, Object[]> remainder = new LinkedHashMap<>();
        Map<String, Object[]> needed = new LinkedHashMap<>();
        for (ChainItem item : m.initialItems) {
            long amount = m.requiredAmount.getOrDefault(item, 0L);
            if (amount > 0) sumInto(available, item, amount);
        }
        for (ChainItem item : m.recipeIngredients) {
            long amount = m.requiredAmount.containsKey(m.preferredItems.get(item)) ? 0 : m.requiredAmount.getOrDefault(item, item.amount());
            if (amount > 0) sumInto(inputs, item, amount);
        }
        for (ChainItem item : m.recipeResults) {
            long amount = item.amount() - m.requiredAmount.getOrDefault(item, 0L);
            if (amount > 0) sumInto(m.outputRecipes.containsKey(item.recipeId) ? outputs : remainder, item, amount);
        }
        for (Map.Entry<ChainItem, Long> e : m.requiredAmount.entrySet()) {
            if (e.getKey().type == ChainItem.Type.RESULT && e.getValue() != 0) sumInto(needed, e.getKey(), e.getValue());
        }

        // items that are short stay on top of the ingredients, the ones the inventory covers follow
        List<Map.Entry<String, Object[]>> ingredients = new ArrayList<>(byAmount(inputs));
        int missing = ingredients.size();
        for (Map.Entry<String, Object[]> e : byAmount(available)) {
            if (!inputs.containsKey(e.getKey())) ingredients.add(e);
        }
        Map<String, Object[]> results = new LinkedHashMap<>(outputs);
        remainder.forEach(results::putIfAbsent);

        List<Section> out = new ArrayList<>();
        if (!results.isEmpty()) out.add(sectionOf("Results", byAmount(results), 0));
        if (!needed.isEmpty()) out.add(sectionOf("Required Crafts", byAmount(needed), 0));
        if (!ingredients.isEmpty()) out.add(sectionOf("Ingredients", ingredients, missing));
        return out;
    }

    private static void sum(Map<String, Object[]> into, ChainItem item, long amount) {
        Object[] slot = into.computeIfAbsent(item.item, k -> new Object[] { ((Bookmarks.Entry) item.payload).key.sample, 0L });
        slot[1] = (Long) slot[1] + amount;
    }

    /** Biggest amount first, like NEI's comparator (amount descending, then name). */
    private static void addSection(List<Section> out, String label, Map<String, Object[]> items) {
        if (items.isEmpty()) return;
        List<Map.Entry<String, Object[]>> sorted = new ArrayList<>(items.entrySet());
        sorted.sort((a, b) -> {
            int c = Long.compare((Long) b.getValue()[1], (Long) a.getValue()[1]);
            return c != 0 ? c : a.getKey().compareTo(b.getKey());
        });
        List<ItemStack> stacks = new ArrayList<>();
        List<Long> amounts = new ArrayList<>();
        for (Map.Entry<String, Object[]> e : sorted) {
            stacks.add((ItemStack) e.getValue()[0]);
            amounts.add((Long) e.getValue()[1]);
        }
        out.add(new Section(label, stacks, amounts, TIP_ROWS, -1, 0));
    }

    private static final int TIP_SLOT = 18;
    private static final int TIP_COLUMNS = 11;
    private static final int TIP_ROWS = 5;

    /** How a section's icons are laid out: NEI's ItemsTooltipLineHandler.layoutWithNames (no named rows) / layoutAsGrid. */
    private record SectionLayout(int rows, int columns, int count, int shift, int width) {}

    private static SectionLayout layoutOf(Font font, Section sec) {
        int length = sec.stacks().size();
        int labelWidth = font.width(sec.label()) + 15;
        int width = Math.max(labelWidth, Math.min(length, TIP_COLUMNS) * TIP_SLOT);
        int columns = Math.max(1, width / TIP_SLOT);
        int rows = (length + columns - 1) / columns;
        int count = length;
        if (rows > sec.maxRows()) {
            columns = Math.min(TIP_COLUMNS, length);
            rows = Math.min(sec.maxRows(), (length + columns - 1) / columns);
            count = Math.min(length, columns * rows);
            width = Math.max(columns * TIP_SLOT, labelWidth);
            if (count < length) count -= (int) Math.ceil((font.width("+" + (length - count)) - 2) / (float) TIP_SLOT);
        }
        // gridIndexShift: scroll the shown window so the active item stays in view
        int shift = sec.active() < 0 ? 0 : Math.max(0, Math.min(length - count, sec.active() - count + 2));
        return new SectionLayout(rows, columns, count, shift, width);
    }

    /** A group tooltip: its lines are plain strings. */
    private static void drawGroupTip(GuiGraphics g, Font font, GroupTip tip, int mx, int my) {
        List<FormattedCharSequence> lines = new ArrayList<>();
        for (String line : tip.lines()) lines.add(Component.literal(line).getVisualOrderText());
        drawTip(g, font, lines, tip.sections(), mx, my);
    }

    /** Draws the tooltip by hand because it holds item icons. Layout numbers from NEI's ItemsTooltipLineHandler. */
    private static void drawTip(GuiGraphics g, Font font, List<FormattedCharSequence> tipLines, List<Section> sections, int mx, int my) {
        int width = 0;
        for (FormattedCharSequence line : tipLines) width = Math.max(width, font.width(line));
        int height = tipLines.size() * 10 + (tipLines.size() > 1 ? 2 : 0);
        List<SectionLayout> layouts = new ArrayList<>();
        for (Section sec : sections) {
            SectionLayout layout = layoutOf(font, sec);
            layouts.add(layout);
            width = Math.max(width, layout.width());
            height += 2 + 9 + 2 + layout.rows() * TIP_SLOT;
        }
        int x = mx + 12;
        int y = my - 12;
        if (x + width + 6 > scrW) x = mx - 12 - width;
        if (y + height + 6 > scrH) y = scrH - height - 6;
        y = Math.max(y, 6);
        x = Math.max(x, 6);

        Pose.begin(g, Pose.TOOLTIP);
        g.fill(x - 3, y - 4, x + width + 3, y + height + 4, 0xF0100010);
        g.fill(x - 4, y - 3, x + width + 4, y + height + 3, 0xF0100010);
        g.fill(x - 3, y - 3, x + width + 3, y - 2, 0xF0280070);
        g.fill(x - 3, y + height + 2, x + width + 3, y + height + 3, 0xF0280070);
        g.fill(x - 3, y - 3, x - 2, y + height + 3, 0xF0280070);
        g.fill(x + width + 2, y - 3, x + width + 3, y + height + 3, 0xF0280070);
        int ty = y;
        for (int i = 0; i < tipLines.size(); i++) {
            g.drawString(font, tipLines.get(i), x, ty, 0xFFFFFFFF);
            ty += i == 0 ? 12 : 10;
        }
        for (int i = 0; i < sections.size(); i++) {
            Section sec = sections.get(i);
            SectionLayout layout = layouts.get(i);
            ty += 2;
            g.drawString(font, Component.literal("§7" + sec.label() + ":").getVisualOrderText(), x, ty, 0xFFFFFFFF);
            ty += 11;
            for (int n = 0; n < layout.count(); n++) {
                int index = layout.shift() + n;
                int sx = x + (n % layout.columns()) * TIP_SLOT;
                int sy = ty + (n / layout.columns()) * TIP_SLOT;
                if (index == sec.active()) g.fill(sx, sy, sx + TIP_SLOT, sy + TIP_SLOT, 0x66555555);
                FluidStacks.item(g, sec.stacks().get(index), sx + 1, sy + 1);
                if (!sec.amounts().isEmpty()) {
                    String amount = format(sec.amounts().get(index));
                    Pose.begin(g, Pose.OVER);
                    g.drawString(font, amount, sx + 17 - font.width(amount), sy + 9, index < sec.missing() ? 0xFFFF5555 : 0xFFFFFFFF, true);
                    Pose.end(g);
                }
            }
            int hidden = sec.stacks().size() - layout.count();
            if (hidden > 0) {
                String more = "§7+" + hidden;
                g.drawString(font, more, x + layout.width() - font.width(more) - 2, ty + (layout.rows() - 1) * TIP_SLOT + 5, 0xFFFFFFFF, true);
            }
            ty += layout.rows() * TIP_SLOT;
        }
        Pose.end(g);
    }

    private static void itemTooltip(GuiGraphics g, Font font, ItemStack stack, Cell cell, int mx, int my) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        Bookmarks.Page page = Bookmarks.page();
        Bookmarks.Entry entry = cell.entry();
        List<Component> body;
        HotkeyTips.building = true;
        try {
            body = new ArrayList<>(FluidStacks.tooltip(stack, amountOf(cell), Item.TooltipContext.of(mc.level), mc.player, TooltipFlag.Default.NORMAL));
        } finally {
            HotkeyTips.building = false;
        }
        List<Component> tip = new ArrayList<>();
        tip.add(body.isEmpty() ? FluidStacks.name(stack) : body.get(0));
        for (String line : HotkeyTips.hotkeyLines(
            HotkeyTips.panelKeys(page, entry, isCollapsed(page, cell.groupId()), isChain(page, cell.groupId()), single(cell)))) {
            tip.add(Component.literal(line));
        }
        for (int i = 1; i < body.size(); i++) tip.add(body.get(i));
        if (cell.recipe() != null) {
            ChainDetails.Calc calc = cell.calc();
            long multiplier = calc != null ? calc.realMultiplier : entry.multiplier();
            ChainItem.Type type = calc != null ? calc.item.type : entry.type;
            tip.add(Component.literal("§7Recipe: x" + multiplier + (type == ChainItem.Type.RESULT ? " (result)" : " (ingredient)")));
        }
        tip.add(HotkeyTips.modName(stack));
        List<FormattedCharSequence> lines = new ArrayList<>();
        for (Component line : tip) lines.addAll(font.split(line, 260));
        if (recipePictureWanted(page, cell)) {
            IRecipeLayoutDrawable<?> picture = picture(cell.recipe(), stack);
            Screen screen = McCompat.screen();
            if (picture != null && screen != null) {
                Rect2i r = picture.getRect();
                TreeDraw.tooltip(g, font, mx, my, screen.width, screen.height, lines, List.of(), r.getWidth(), r.getHeight(), (g2, px2, py2) -> {
                    picture.setPosition(px2, py2);
                    picture.drawRecipe(g2, -1000, -1000);
                });
                return;
            }
        }
        if (single(cell) && Bookmarks.hasOptions(entry)) {
            // NEI AcceptsFollowingTooltipLineHandler: every item the slot accepts, the chosen one highlighted
            List<ItemStack> accepted = new ArrayList<>();
            int active = 0;
            for (String id : entry.options) {
                ItemStack option = FluidStacks.stackOf(id);
                if (option.isEmpty()) continue;
                if (id.equals(entry.itemId())) active = accepted.size();
                accepted.add(option);
            }
            if (accepted.size() > 1) {
                drawTip(g, font, lines, List.of(new Section("Accepts following", accepted, List.of(), 2, active, 0)), mx, my);
                return;
            }
        }
        g.renderTooltip(font, lines, mx, my);
    }

    // ---------------------------------------------------------------- input

    private static boolean inPanel(double mx, double my) {
        if (!active || !in(mx, my, px, py, pw, footY + BTN - py)) return false;
        // JEI's own button row (bookmarks / history) at the bottom left stays JEI's
        Screen s = McCompat.screen();
        return s == null || !JeiButtons.overButtonRow(mx, my, s.height);
    }

    private static boolean ctrl() {
        return McCompat.ctrl();
    }

    private static boolean alt() {
        return McCompat.alt();
    }

    private static boolean shift() {
        return McCompat.shift();
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    static void onMousePressed(ScreenEvent.MouseButtonPressed.Pre event) {
        Screen screen = event.getScreen();
        if (!applies(screen)) return;
        Bookmarks.ensureLoaded();
        layout(screen);
        double mx = event.getMouseX();
        double my = event.getMouseY();
        if (JeiButtons.overBookmarkButton(mx, my, screen.height)) {
            // JEI's "show bookmarks" button: JEI's own list is replaced by this panel, so it toggles the panel
            if (event.getButton() == 0) {
                toggleHidden();
                event.setCanceled(true);
            }
            return;
        }
        if (!inPanel(mx, my)) return;
        event.setCanceled(true);
        int button = event.getButton();
        Bookmarks.Page page = Bookmarks.page();
        buildCells(page);

        // bottom bar
        if (in(mx, my, nsPrevX(), footY, BTN, BTN)) {
            if (button == 0) Bookmarks.prevPage();
            return;
        }
        if (in(mx, my, nsNextX(), footY, BTN, BTN)) {
            if (button == 0) Bookmarks.nextPage();
            return;
        }
        if (pullShown(page) && in(mx, my, pullX(), footY, BTN, BTN)) {
            if (button == 0) Pull.pull(Bookmarks.pullMath(page, Bookmarks.NO_GROUP), shift());
            return;
        }
        // top bar
        if (in(mx, my, hdrPrevX(), hdrY(), BTN, BTN)) {
            if (button == 0) gridPage = gridPage == 0 ? gridPages() - 1 : gridPage - 1;
            return;
        }
        if (in(mx, my, hdrNextX(), hdrY(), BTN, BTN)) {
            if (button == 0) gridPage = gridPage >= gridPages() - 1 ? 0 : gridPage + 1;
            return;
        }
        if (in(mx, my, px + BTN, hdrY(), pw - BTN * 2, BTN)) {
            // NEI: the page label is the handle of the default (ungrouped) group
            if (button == 0 && alt()) {
                Bookmarks.GroupMeta m = page.meta(Bookmarks.NO_GROUP);
                m.collapsed = !m.collapsed;
                Bookmarks.save();
            } else if (button == 0) {
                Bookmarks.GroupMeta m = page.meta(Bookmarks.NO_GROUP);
                m.todo = !m.todo;
                Bookmarks.save();
            } else if (button == 1) {
                Bookmarks.GroupMeta m = page.meta(Bookmarks.NO_GROUP);
                m.chain = !m.chain;
                Bookmarks.save();
            }
            return;
        }

        // group strip: drag = create/include (left) or exclude (right); a plain click toggles the mode
        int sr = stripRowAt(mx, my);
        if (sr >= 0) {
            int gid = rowGroup.getOrDefault(sr, Bookmarks.NO_GROUP);
            if (button == 0 && shift() && gid > 0) {
                moveGroup = gid;
            } else if (button == 0 || button == 1) {
                dragMode = button == 0 ? 1 : 2;
                dragButton = button;
                dragMoved = false;
                dragStartRow = dragEndRow = sr;
            }
            return;
        }

        // slots
        Cell c = cellAt(mx, my);
        if (c == null) return;
        Bookmarks.Entry e = c.entry();
        if (button == 0 && alt()) {
            // NEI: Alt+click on a slot re-expands a collapsed group, in a crafting chain it collapses/expands the slot's recipe
            if (isCollapsed(page, c.groupId())) {
                page.meta(c.groupId()).collapsed = false;
                Bookmarks.save();
            } else if (isChain(page, c.groupId()) && c.recipe() != null) {
                Bookmarks.toggleCollapsedRecipe(page, c.groupId(), c.recipe());
            }
        } else if (button == 0 && shift()) {
            if (!isCollapsed(page, c.groupId())) {
                reorder = e;
                reorderRecipe = c.recipe();
            }
        } else if (button == 0) {
            showRecipes(e.key.sample, RecipeIngredientRole.OUTPUT);
        } else if (button == 1) {
            showRecipes(e.key.sample, RecipeIngredientRole.INPUT);
        }
    }

    static void showRecipes(ItemStack stack, RecipeIngredientRole role) {
        var rt = Calculator.runtime;
        if (rt == null) return;
        var focus = FluidStacks.focus(rt, role, stack);
        rt.getRecipesGui()
            .show(focus);
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    static void onMouseDragged(ScreenEvent.MouseDragged.Pre event) {
        if (!applies(event.getScreen()) || !active) return;
        if (dragMode != 0) {
            int row = clampRow(event.getMouseY());
            if (row != dragStartRow) dragMoved = true;
            if (dragMoved) dragEndRow = row;
            event.setCanceled(true);
        } else if (reorder != null || moveGroup >= 0) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    static void onMouseReleased(ScreenEvent.MouseButtonReleased.Pre event) {
        if (!applies(event.getScreen()) || !active) return;
        Bookmarks.Page page = Bookmarks.page();
        if (dragMode != 0) {
            buildCells(page);
            if (dragMoved) {
                dragEndRow = clampRow(event.getMouseY());
                applyDrag(page);
            } else {
                // NEI mouseUp on a group row: LMB toggles the group mode, Alt+LMB collapses, RMB toggles the chain
                int gid = rowGroup.getOrDefault(dragStartRow, Bookmarks.NO_GROUP);
                if (gid > 0) {
                    Bookmarks.GroupMeta m = page.meta(gid);
                    if (dragButton == 0 && alt()) m.collapsed = !m.collapsed;
                    else if (dragButton == 0) m.todo = !m.todo;
                    else if (dragButton == 1) m.chain = !m.chain;
                    Bookmarks.save();
                }
            }
            dragMode = 0;
            dragMoved = false;
            event.setCanceled(true);
        } else if (moveGroup >= 0) {
            buildCells(page);
            moveGroupTo(page, moveGroup, clampRow(event.getMouseY()));
            moveGroup = -1;
            event.setCanceled(true);
        } else if (reorder != null) {
            buildCells(page);
            Cell target = cellAt(event.getMouseX(), event.getMouseY());
            if (target != null && target.entry() != reorder && !isCollapsed(page, target.groupId())) {
                if (reorderRecipe != null) {
                    moveRecipeTo(page, reorder, reorderRecipe, target.entry(), target.recipe());
                } else {
                    int to = page.entries.indexOf(target.entry());
                    page.entries.remove(reorder);
                    reorder.groupId = target.groupId();
                    page.entries.add(Math.min(to, page.entries.size()), reorder);
                }
                page.normalize();
                Bookmarks.save();
            }
            reorder = null;
            reorderRecipe = null;
            event.setCanceled(true);
        }
    }

    /** The recipes a slot's recipe moves/is removed with: a collapsed recipe and the ones it swallows, else just itself. */
    private static Set<String> family(Bookmarks.Page page, int groupId, String recipe) {
        Set<String> f = Bookmarks.collapsedFamily(page, groupId, recipe);
        return f.isEmpty() ? Set.of(recipe) : f;
    }

    /**
     * NEI's SortableItem for a recipe item: the whole recipe (with the recipes a collapsed recipe swallows) moves,
     * before or after the recipe it is dropped on.
     */
    private static void moveRecipeTo(Bookmarks.Page page, Bookmarks.Entry dragged, String draggedRecipe,
        Bookmarks.Entry target, String targetRecipe) {
        Set<String> moving = family(page, dragged.groupId, draggedRecipe);
        List<Bookmarks.Entry> block = new ArrayList<>();
        for (Bookmarks.Entry e : page.entries) {
            if (e.isRecipe() && e.groupId == dragged.groupId && moving.contains(e.recipeId)) block.add(e);
        }
        if (block.isEmpty() || block.contains(target)) return;
        int blockStart = page.entries.indexOf(block.get(0));
        boolean down = page.entries.indexOf(target) > blockStart;
        int targetGroup = target.groupId;
        List<Bookmarks.Entry> around = new ArrayList<>();
        if (targetRecipe != null) {
            Set<String> there = family(page, target.groupId, targetRecipe);
            for (Bookmarks.Entry e : page.entries) {
                if (e.isRecipe() && e.groupId == target.groupId && there.contains(e.recipeId)) around.add(e);
            }
        } else {
            around.add(target);
        }
        if (around.isEmpty()) around.add(target);
        page.entries.removeAll(block);
        int at = down ? page.entries.indexOf(around.get(around.size() - 1)) + 1 : page.entries.indexOf(around.get(0));
        for (Bookmarks.Entry e : block) e.groupId = targetGroup;
        page.entries.addAll(Math.max(0, Math.min(at, page.entries.size())), block);
    }

    /** NEI's SortableGroup: moves a whole group to where the dragged row is. */
    private static void moveGroupTo(Bookmarks.Page page, int gid, int targetRow) {
        int fromRow = -1;
        for (Cell c : cells) {
            if (c.groupId() == gid) {
                fromRow = c.row();
                break;
            }
        }
        if (fromRow < 0 || rowGroup.getOrDefault(targetRow, -1) == gid) return;
        boolean down = targetRow > fromRow;
        Bookmarks.Entry anchor = null;
        for (Cell c : cells) {
            if (c.row() == targetRow && (anchor == null || down)) anchor = c.entry();
        }
        if (anchor == null) return;
        List<Bookmarks.Entry> block = new ArrayList<>();
        for (Bookmarks.Entry e : page.entries) if (e.groupId == gid) block.add(e);
        page.entries.removeAll(block);
        int at = page.entries.indexOf(anchor);
        int ag = anchor.groupId;
        if (down) {
            at++;
            while (ag != Bookmarks.NO_GROUP && at < page.entries.size() && page.entries.get(at).groupId == ag) at++;
        } else {
            while (ag != Bookmarks.NO_GROUP && at > 0 && page.entries.get(at - 1).groupId == ag) at--;
        }
        page.entries.addAll(Math.max(0, Math.min(at, page.entries.size())), block);
        page.normalize();
        Bookmarks.save();
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    static void onMouseScrolled(ScreenEvent.MouseScrolled.Pre event) {
        Screen screen = event.getScreen();
        if (!applies(screen)) return;
        layout(screen);
        double mx = event.getMouseX();
        double my = event.getMouseY();
        if (!inPanel(mx, my)) return;
        int dir = (int) Math.signum(event.getScrollDeltaY());
        if (dir == 0) return;
        Bookmarks.Page page = Bookmarks.page();
        buildCells(page);
        // NEI: scrolling over the bookmark-page buttons switches the page
        if (in(mx, my, nsPrevX(), footY, nsNextX() + BTN - nsPrevX(), BTN)) {
            if (dir > 0) Bookmarks.prevPage();
            else Bookmarks.nextPage();
            event.setCanceled(true);
            return;
        }
        if (shift() && !ctrl()) {
            // NEI: Shift + wheel over an ingredient that accepts several items switches the item
            Cell c = cellAt(mx, my);
            if (c != null && single(c) && !isCollapsed(page, c.groupId()) && Bookmarks.cycleOption(c.entry(), dir)) {
                event.setCanceled(true);
                return;
            }
        }
        if (ctrl()) {
            Cell c = cellAt(mx, my);
            if (c != null) {
                if (isCollapsed(page, c.groupId())) {
                    Bookmarks.shiftGroupAmount(page, c.groupId(), dir);
                } else {
                    // Ctrl+Alt uses the item's stack size as the multiplier
                    long step = alt() ? QuantityField.scrollMultiplier(c.entry().key.sample) : 1;
                    if (c.recipe() != null) {
                        // NEI: Ctrl + wheel on a recipe item changes how often the whole recipe runs (a collapsed recipe: the collapsed one)
                        Bookmarks.shiftRecipeAmount(page, c.groupId(), c.recipe(), dir * step);
                    } else {
                        Bookmarks.shiftItemAmount(page, c.entry(), dir * step);
                    }
                }
                event.setCanceled(true);
                return;
            }
            int gid = hoveredGroup(page, mx, my);
            if (gid >= 0) {
                Bookmarks.shiftGroupAmount(page, gid, (alt() ? (QuantityField.quantity() != 0 ? QuantityField.quantity() : 64L) : 1L) * dir);
                event.setCanceled(true);
                return;
            }
        }
        int pages = gridPages();
        gridPage = Math.max(0, Math.min(pages - 1, gridPage - dir));
        if (dragMode != 0 && dragMoved) dragEndRow = clampRow(my);
        event.setCanceled(true);
    }

    /** NEI's getHoveredGroupId(true): a group strip row, or 0 for the page label (default group); -1 otherwise. */
    private static int hoveredGroup(Bookmarks.Page page, double mx, double my) {
        int sr = stripRowAt(mx, my);
        if (sr >= 0) {
            int gid = rowGroup.getOrDefault(sr, Bookmarks.NO_GROUP);
            return gid == Bookmarks.NO_GROUP ? -1 : gid;
        }
        if (!page.entries.isEmpty() && in(mx, my, px + BTN, hdrY(), pw - BTN * 2, BTN)) return Bookmarks.NO_GROUP;
        return -1;
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    static void onKeyPressed(ScreenEvent.KeyPressed.Pre event) {
        Screen screen = event.getScreen();
        if (!applies(screen)) return;
        if (QuantityField.focused() || Calculator.runtime != null && Calculator.runtime.getIngredientListOverlay()
            .hasKeyboardFocus()) return;
        layout(screen);
        if (!active || !inPanel(lastMx, lastMy)) return;
        int code = event.getKeyCode();
        InputConstants.Key key = InputConstants.Type.KEYSYM.getOrCreate(code);
        Bookmarks.Page page = Bookmarks.page();
        buildCells(page);
        Cell c = cellAt(lastMx, lastMy);
        final int gid = hoveredGroup(page, lastMx, lastMy);

        if (ctrl() && McCompat.matches(CalcKeys.CHAT_LINK, key)) {
            // NEI: over a group handle the whole group, over a recipe's result with Shift that recipe, else the item
            String payload = gid >= 0 ? Bookmarks.shareGroup(page, gid)
                : c == null ? null
                : shift() && c.entry().type == ChainItem.Type.RESULT ? Bookmarks.shareRecipe(page, c.entry())
                : Bookmarks.shareItem(c.entry().key.sample, amountOf(c));
            if (ShareClient.send(payload)) event.setCanceled(true);
            return;
        }
        if (McCompat.matches(CalcKeys.PULL, key)) {
            // NEI: over a group handle every item of the group, over an item that item at its amount; Shift: only what is missing
            boolean pulled = gid >= 0 ? Pull.pull(Bookmarks.pullMath(page, gid), shift())
                : c != null && Pull.pullItem(c.entry().key.sample, amountOf(c), shift());
            if (pulled) event.setCanceled(true);
            return;
        }
        if (shift() && McCompat.matches(CalcKeys.CRAFT, key)) {
            // NEI: over a crafting-chain group handle it crafts the whole chain, over a recipe's result just that recipe;
            // Ctrl keeps what the inventory already holds, without it the wanted amount is crafted again
            ChainMath math = gid >= 0 && isChain(page, gid) ? Bookmarks.autocraftChain(page, gid)
                : c != null ? Bookmarks.autocraftRecipe(page, c.entry()) : null;
            NeiAddon.LOGGER.info(
                "Autocrafting key over the panel: group={} chain={} item={} type={} recipe={}",
                gid,
                gid >= 0 && isChain(page, gid),
                c == null ? null : c.entry().itemId(),
                c == null ? null : c.entry().type,
                c == null ? null : c.entry().recipeId);
            if (AutoCraft.start(math, !ctrl())) event.setCanceled(true);
            return;
        }
        if (McCompat.matches(CalcKeys.ADD, key)) {
            if (gid >= 0 && shift()) {
                // NEI bookmark.remove_recipe: Shift + the add key over a group handle removes the group
                Bookmarks.removeGroup(page, gid);
                event.setCanceled(true);
            } else if (c != null) {
                // NEI bookmark.add over a bookmarked item removes it; Shift removes the whole recipe
                Bookmarks.removeSlot(page, c.entry(), c.recipe(), shift());
                event.setCanceled(true);
            }
        } else if (c != null && !ctrl() && (code == GLFW.GLFW_KEY_R || code == GLFW.GLFW_KEY_U)) {
            // NEI recipe.recipe / recipe.usage over a bookmarked item (JEI's own keys only know its own lists)
            showRecipes(c.entry().key.sample, code == GLFW.GLFW_KEY_R ? RecipeIngredientRole.OUTPUT : RecipeIngredientRole.INPUT);
            event.setCanceled(true);
        } else if (McCompat.matches(CalcKeys.TREE, key) && gid >= 0 && isChain(page, gid) && Calculator.available()) {
            // NEI bookmark.open_crafting_tree: only for groups in crafting-chain mode
            CraftingTreeScreen.open(page, gid);
            event.setCanceled(true);
        }
    }
}

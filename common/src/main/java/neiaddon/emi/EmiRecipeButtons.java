package neiaddon.emi;

import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.api.widget.Bounds;
import dev.emi.emi.api.widget.Widget;
import dev.emi.emi.api.widget.WidgetHolder;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import mezz.jei.api.gui.drawable.IDrawable;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import neiaddon.EmiCompat;
import neiaddon.NeiAddon;
import neiaddon.RecipeHearts;
import neiaddon.RecipeShape;
import neiaddon.TreeRecipes;

/**
 * The favorite heart and the crafting tree's "+" next to each recipe on EMI's recipe screen. EMI has recipe decorators
 * too, but EMI draws them only when its developer option {@code show-recipe-decorators} is on, which players do not have.
 * So the buttons are drawn from the addon's own screen events, over EMI's recipe screen (see {@link EmiCompat#recipeScreen}),
 * where EMI shows each recipe: the screen's internal list of recipe groups is read by reflection, and each group's corner
 * is where its recipe is drawn. Once the hearts are on, the keys over recipes work here too: the heart reports the hovered
 * recipe every frame. Failures answer the safe default and log one warning per kind.
 */
final class EmiRecipeButtons implements EmiCompat.RecipeScreenHooks {

    /** The size of a button's cell, and the distance from one cell to the next. */
    private static final int CELL = 12;
    private static final int STEP = 14;
    /** The gap between the recipe and its first column of cells (EMI's own buttons are placed the same way). */
    private static final int GAP = 5;
    private static final int COLUMNS = 6;
    private static final int HOVER = 0x30FFFFFF;

    /** The field of EMI's recipe screen that lists the recipe groups of the page. */
    private static final String CURRENT_PAGE = "currentPage";

    private static boolean warnedBroken;
    private static boolean warnedEntry;
    private static boolean warnedList;
    private static boolean warnedRender;
    private static boolean warnedClick;
    private static boolean warnedTooltip;

    /** Set when EMI's screen cannot be read: no hearts on EMI's screen from then on. */
    private static boolean broken;
    private static Field pageField;
    private static Field groupX;
    private static Field groupY;
    private static Field groupRecipe;

    /** The shared entry of a group with no recipe shape (so it is not tried every frame). */
    private static final Entry NONE = new Entry(null, null, null, null);

    /** Recipe group -> its buttons; weak, and pruned to the groups on the page on every frame (see {@link #render}). */
    private static final Map<Object, Entry> entries = new WeakHashMap<>();

    /** The buttons drawn in the last frame, with their screen rectangles (the clicks and the tooltip use them). */
    private static final List<Hit> hits = new ArrayList<>();

    /** One recipe group's buttons: its view, the heart (null when it has none), the "+" state and the cells. */
    private static final class Entry {

        final EmiRecipeView view;
        final RecipeHearts.Controller heart;
        final RecipeShape shape;
        final Layout layout;
        /** The "+" while a tree waits; made once per wait, dropped when the wait ends. */
        TreeRecipes.Controller plus;
        boolean triedPlus;

        Entry(EmiRecipeView view, RecipeHearts.Controller heart, RecipeShape shape, Layout layout) {
            this.view = view;
            this.heart = heart;
            this.shape = shape;
            this.layout = layout;
        }
    }

    /** One button drawn this frame: its screen rectangle, its group's entry, and whether it is the "+". */
    private record Hit(Rect2i rect, Entry entry, boolean plus) {}

    @Override
    public void render(Screen screen, GuiGraphics g, int mouseX, int mouseY) {
        hits.clear();
        if (broken) return;
        List<?> page = page(screen);
        if (page == null) return;
        entries.keySet()
            .removeIf(group -> !page.contains(group));
        for (Object group : page) {
            if (broken) return;
            try {
                Entry entry = entryOf(group);
                if (entry == NONE) continue;
                int gx = intOf(groupX, group);
                int gy = intOf(groupY, group);
                entry.view.moveTo(gx, gy);
                if (entry.heart != null) drawHeart(g, entry, gx, gy, mouseX, mouseY);
                drawPlus(g, entry, gx, gy, mouseX, mouseY);
            } catch (ReflectiveOperationException e) {
                // the recipe groups' fields can't be read: off on EMI's recipe screen from now on
                hits.clear();
                broke(e);
                return;
            } catch (RuntimeException | LinkageError e) {
                // only this recipe goes without buttons, and it isn't tried again every frame
                entries.put(group, NONE);
                if (!warnedRender) {
                    warnedRender = true;
                    NeiAddon.LOGGER.warn("Could not draw the favorite hearts of an EMI recipe; they are left out there", e);
                }
            }
        }
        drawTooltip(g, mouseX, mouseY);
    }

    /** The heart of a group at its cell, with the mouse position for the keys over recipes. */
    private void drawHeart(GuiGraphics g, Entry entry, int gx, int gy, int mouseX, int mouseY) {
        Bounds cell = entry.layout.heartCell();
        Rect2i rect = screenRect(cell, gx, gy);
        if (inside(rect, mouseX, mouseY)) g.fill(rect.getX(), rect.getY(), rect.getX() + CELL, rect.getY() + CELL, HOVER);
        IDrawable icon = entry.heart.icon();
        icon.draw(g, rect.getX() + (CELL - icon.getWidth()) / 2, rect.getY() + (CELL - icon.getHeight()) / 2);
        entry.heart.frame(rect, mouseX, mouseY);
        hits.add(new Hit(rect, entry, false));
    }

    /** The "+" of a group while a tree waits for a recipe; nothing otherwise. */
    private static void drawPlus(GuiGraphics g, Entry entry, int gx, int gy, int mouseX, int mouseY) {
        if (!TreeRecipes.waiting()) {
            entry.plus = null;
            entry.triedPlus = false;
            return;
        }
        if (!entry.triedPlus) {
            entry.triedPlus = true;
            entry.plus = TreeRecipes.create(entry.shape);
        }
        if (entry.plus == null) return;
        Rect2i rect = screenRect(entry.layout.plusCell(), gx, gy);
        if (inside(rect, mouseX, mouseY)) g.fill(rect.getX(), rect.getY(), rect.getX() + CELL, rect.getY() + CELL, HOVER);
        IDrawable icon = TreeRecipes.PLUS_ICON;
        icon.draw(g, rect.getX() + (CELL - icon.getWidth()) / 2, rect.getY() + (CELL - icon.getHeight()) / 2);
        hits.add(new Hit(rect, entry, true));
    }

    /** The tooltip of the button under the mouse, drawn last so no other drawing covers it. */
    private static void drawTooltip(GuiGraphics g, int mouseX, int mouseY) {
        for (Hit hit : hits) {
            if (!inside(hit.rect(), mouseX, mouseY)) continue;
            try {
                List<Component> lines = hit.plus() ? List.of(hit.entry().plus.tooltip()) : hit.entry().heart.tooltipLines();
                g.renderComponentTooltip(Minecraft.getInstance().font, lines, mouseX, mouseY);
            } catch (RuntimeException | LinkageError e) {
                if (!warnedTooltip) {
                    warnedTooltip = true;
                    NeiAddon.LOGGER.warn("Could not make the tooltip of a button on EMI's recipe screen", e);
                }
            }
            return;
        }
    }

    /** A left click on a heart or a "+" drawn in the last frame: it is used, and the click is not EMI's. */
    @Override
    public boolean click(Screen screen, double mouseX, double mouseY, int button) {
        if (button != 0 || broken) return false;
        try {
            for (Hit hit : hits) {
                if (!inside(hit.rect(), mouseX, mouseY)) continue;
                boolean used = hit.plus() ? hit.entry().plus != null && hit.entry().plus.press(false)
                    : hit.entry().heart != null && hit.entry().heart.press(false);
                if (!used) return false;
                Minecraft.getInstance()
                    .getSoundManager()
                    .play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
                return true;
            }
            return false;
        } catch (RuntimeException | LinkageError e) {
            if (!warnedClick) {
                warnedClick = true;
                NeiAddon.LOGGER.warn("Could not click a button on EMI's recipe screen", e);
            }
            return false;
        }
    }

    /** The recipe groups of EMI's page (its internal list, read by reflection); null when that cannot be read (then no hearts). */
    private static List<?> page(Screen screen) {
        try {
            if (pageField == null) {
                Field f = screen.getClass()
                    .getDeclaredField(CURRENT_PAGE);
                f.setAccessible(true);
                pageField = f;
            }
            return (List<?>) pageField.get(screen);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return broke(e);
        }
    }

    /** Reads the public fields of a recipe group (x, y, recipe), once; false (and no hearts from then on) when they cannot. */
    private static boolean findGroupFields(Object group) {
        if (groupX != null) return true;
        try {
            Class<?> c = group.getClass();
            groupX = c.getField("x");
            groupY = c.getField("y");
            groupRecipe = c.getField("recipe");
            return true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            broke(e);
            return false;
        }
    }

    /** The entry of a recipe group: made on first sight, and shared when the group has no recipe shape. */
    private static Entry entryOf(Object group) throws ReflectiveOperationException {
        Entry entry = entries.get(group);
        if (entry == null) {
            entry = make(group);
            entries.put(group, entry);
        }
        return entry;
    }

    /** The entry of one recipe group: its view, heart and cells; NONE for a group without a recipe or a shape. */
    private static Entry make(Object group) throws ReflectiveOperationException {
        if (!findGroupFields(group)) return NONE;
        Object recipeObject = groupRecipe.get(group);
        // the workstation row of EMI's page is a group without a recipe
        if (!(recipeObject instanceof EmiRecipe recipe)) return NONE;
        try {
            RecipeShape shape = EmiBridge.shapeFor(recipe);
            if (shape == null) return NONE;
            WidgetHolder holder = (WidgetHolder) group;
            EmiRecipeView view = new EmiRecipeView(recipe, shape, holder);
            RecipeHearts.Controller heart = RecipeHearts.create(view);
            boolean hasHeart = heart != null && heart.shown();
            return new Entry(view, hasHeart ? heart : null, shape, new Layout(holder, hasHeart));
        } catch (RuntimeException | LinkageError e) {
            if (!warnedEntry) {
                warnedEntry = true;
                NeiAddon.LOGGER.warn("Could not make the favorite hearts of an EMI recipe; they are left out there", e);
            }
            return NONE;
        }
    }

    private static int intOf(Field field, Object group) throws ReflectiveOperationException {
        return field.getInt(group);
    }

    /** A cell of a group, moved to the screen: the group's corner is where its recipe is drawn. */
    private static Rect2i screenRect(Bounds cell, int gx, int gy) {
        return new Rect2i(gx + cell.x(), gy + cell.y(), CELL, CELL);
    }

    private static boolean inside(Rect2i r, double x, double y) {
        return x >= r.getX() && x < r.getX() + r.getWidth() && y >= r.getY() && y < r.getY() + r.getHeight();
    }

    /** Turns the hearts off for good after EMI's screen could not be read (one warning). */
    private static List<?> broke(Throwable e) {
        broken = true;
        if (!warnedBroken) {
            warnedBroken = true;
            NeiAddon.LOGGER.warn("Could not read EMI's recipe screen; the favorite hearts are off on it", e);
        }
        return null;
    }

    /**
     * The cells of one recipe's buttons. The heart takes the first free cell, the "+" the next one after it; the cells are
     * worked out on first use (on the first frame that needs them).
     */
    private static final class Layout {

        private final WidgetHolder holder;
        private final boolean hasHeart;
        /** The bounds of the other widgets of the recipe (a snapshot taken on first use); null when the list cannot be read. */
        private List<Bounds> others;
        private boolean listRead;
        private Bounds heart;
        private Bounds plus;

        Layout(WidgetHolder holder, boolean hasHeart) {
            this.holder = holder;
            this.hasHeart = hasHeart;
        }

        /** The heart's cell. */
        Bounds heartCell() {
            if (heart == null) heart = find(new ArrayList<>(), 0);
            return heart;
        }

        /** The "+" cell: after the heart's, so the heart's cell counts as taken. */
        Bounds plusCell() {
            if (plus == null) {
                List<Bounds> taken = new ArrayList<>();
                if (hasHeart) taken.add(heartCell());
                plus = find(taken, 1);
            }
            return plus;
        }

        /**
         * The first cell, column by column and top to bottom, that overlaps no taken cell and no other widget right of the
         * recipe. When the widget list cannot be read, the fixed column; when no cell is free, the cell right of the last
         * column.
         */
        private Bounds find(List<Bounds> taken, int fixedColumn) {
            int w = holder.getWidth();
            List<Bounds> others = others();
            if (others == null) return cell(w, fixedColumn, 0);
            int maxY = Math.max(CELL, holder.getHeight() + 4);
            for (int column = 0; column < COLUMNS; column++) {
                for (int row = 0; STEP * row + CELL <= maxY; row++) {
                    Bounds cell = cell(w, column, row);
                    if (free(cell, taken, others, w)) return cell;
                }
            }
            return cell(w, COLUMNS, 0);
        }

        private static boolean free(Bounds cell, List<Bounds> taken, List<Bounds> others, int recipeWidth) {
            for (Bounds t : taken) {
                if (!cell.overlap(t)
                    .empty()) return false;
            }
            for (Bounds o : others) {
                if (o.empty() || o.x() < recipeWidth) continue;
                if (!cell.overlap(o)
                    .empty()) return false;
            }
            return true;
        }

        /** The cell at a column and row, right of a recipe that is {@code w} wide. */
        private static Bounds cell(int w, int column, int row) {
            return new Bounds(w + GAP + STEP * column, STEP * row, CELL, CELL);
        }

        /** The bounds of the recipe's other widgets, read once; null when the widget list cannot be read (one warning). */
        private List<Bounds> others() {
            if (!listRead) {
                listRead = true;
                others = readOthers();
            }
            return others;
        }

        private List<Bounds> readOthers() {
            try {
                Field field = holder.getClass()
                    .getField("widgets");
                Object widgets = field.get(holder);
                List<Bounds> out = new ArrayList<>();
                for (Object widget : (List<?>) widgets) {
                    try {
                        out.add(((Widget) widget).getBounds());
                    } catch (RuntimeException | LinkageError e) {
                        // a widget whose bounds cannot be read is taken as not in the way
                        NeiAddon.LOGGER.debug("Could not read the bounds of an EMI recipe widget", e);
                    }
                }
                return out;
            } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                if (!warnedList) {
                    warnedList = true;
                    NeiAddon.LOGGER.warn("Could not read the widgets of an EMI recipe; its hearts go in fixed cells", e);
                }
                return null;
            }
        }
    }
}

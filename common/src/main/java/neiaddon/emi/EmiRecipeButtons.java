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
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
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
 * The favorite heart next to each recipe on EMI's recipe screen. EMI has recipe decorators too, but EMI draws them only when
 * its developer option {@code show-recipe-decorators} is on, which players do not have. So the buttons are drawn from the
 * addon's own screen events, over EMI's recipe screen (see {@link EmiCompat#recipeScreen}), where EMI shows each recipe:
 * the screen's internal list of recipe groups is read by reflection, and each group's corner is where its recipe is drawn.
 * Once the hearts are on, the keys over recipes work here too: the heart reports the hovered recipe every frame. Where a
 * recipe has EMI's own "Set as default recipe" button, that button is the favorite: EMI draws and clicks it, and no heart is
 * drawn beside it; while a crafting tree waits, a click on that button picks the recipe for the tree (see
 * {@link RecipeHearts.Controller#press}). Failures answer the safe default and log one warning per kind.
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
    /** EMI's "Set as default recipe" button (its class; matched by name, as EMI's class is internal). */
    private static final String DEFAULT_BUTTON = "dev.emi.emi.widget.RecipeDefaultButtonWidget";
    /** EMI's "View recipe tree" button (internal class, matched by name). */
    private static final String TREE_BUTTON = "dev.emi.emi.widget.RecipeTreeButtonWidget";

    private static boolean warnedBroken;
    private static boolean warnedEntry;
    private static boolean warnedList;
    private static boolean warnedRender;
    private static boolean warnedClick;
    private static boolean warnedTooltip;
    private static boolean warnedDefault;
    private static boolean warnedWrap;
    private static boolean warnedButton;

    /** Set when EMI's screen cannot be read: no hearts on EMI's screen from then on. */
    private static boolean broken;
    private static Field pageField;
    private static Field groupX;
    private static Field groupY;
    private static Field groupRecipe;

    /** The shared entry of a group with no recipe shape (so it is not tried every frame). */
    private static final Entry NONE = new Entry(null, null, null, null, false);

    /** Recipe group -> its buttons; weak, and pruned to the groups on the page on every frame (see {@link #render}). */
    private static final Map<Object, Entry> entries = new WeakHashMap<>();

    /** The buttons drawn in the last frame, with their screen rectangles (the clicks and the tooltip use them). */
    private static final List<Hit> hits = new ArrayList<>();

    /**
     * One recipe group's buttons: its view, the heart (null when it has none) and its cell. When the recipe has EMI's default
     * button ({@code emiDefault}), the heart is not drawn: EMI's button is the favorite, and the heart only takes the mouse
     * over it (for the keys).
     */
    private static final class Entry {

        final EmiRecipeView view;
        final RecipeHearts.Controller heart;
        final Layout layout;
        final Widget emiDefault;
        /** EMI's default button was replaced by a {@link FavoriteButton}: EMI then draws the heart's tooltip itself. */
        final boolean wrapped;

        Entry(EmiRecipeView view, RecipeHearts.Controller heart, Layout layout, Widget emiDefault, boolean wrapped) {
            this.view = view;
            this.heart = heart;
            this.layout = layout;
            this.emiDefault = emiDefault;
            this.wrapped = wrapped;
        }
    }

    /**
     * One button that takes clicks, drawn in the last frame: its screen rectangle and its group's entry. {@code emiDefault}:
     * EMI's own default button, which only takes clicks while a tree waits (EMI draws it; its tooltip is EMI's unless a tree
     * waits).
     */
    private record Hit(Rect2i rect, Entry entry, boolean emiDefault) {}

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
                if (entry.heart != null && entry.emiDefault != null) frameDefault(entry, gx, gy, mouseX, mouseY);
                else if (entry.heart != null) drawHeart(g, entry, gx, gy, mouseX, mouseY);
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

    /**
     * EMI's default button is drawn and clicked by EMI (and has EMI's tooltip): the heart is not drawn, only told where the
     * button is, so the keys over a heart work over it too. While a tree waits, the button also takes the click (see
     * {@link #click}).
     */
    private static void frameDefault(Entry entry, int gx, int gy, int mouseX, int mouseY) {
        Bounds b = entry.emiDefault.getBounds();
        Rect2i rect = new Rect2i(gx + b.x(), gy + b.y(), b.width(), b.height());
        entry.heart.frame(rect, mouseX, mouseY);
        if (TreeRecipes.waiting()) hits.add(new Hit(rect, entry, true));
    }

    /** The tooltip of the button under the mouse, drawn last so no other drawing covers it. */
    /** How far below the mouse our tooltip starts over EMI's default button, clear of EMI's own one-line tooltip. */
    private static final int EMI_TOOLTIP_GAP = 24;

    private static void drawTooltip(GuiGraphics g, int mouseX, int mouseY) {
        for (Hit hit : hits) {
            if (!inside(hit.rect(), mouseX, mouseY)) continue;
            // a replaced default button gets the heart's tooltip from EMI itself (see FavoriteButton); otherwise EMI's
            // button shows EMI's own tooltip, except while a tree waits: then ours (the pick line) shows below it
            if (hit.emiDefault() && (hit.entry().wrapped || !TreeRecipes.waiting())) return;
            try {
                List<Component> lines = hit.entry().heart.tooltipLines();
                // EMI draws its own one-line tooltip for its button at the mouse: ours goes just below it
                int y = hit.emiDefault() ? mouseY + EMI_TOOLTIP_GAP : mouseY;
                g.renderComponentTooltip(Minecraft.getInstance().font, lines, mouseX, y);
            } catch (RuntimeException | LinkageError e) {
                if (!warnedTooltip) {
                    warnedTooltip = true;
                    NeiAddon.LOGGER.warn("Could not make the tooltip of a button on EMI's recipe screen", e);
                }
            }
            return;
        }
    }

    /**
     * A left click on a heart drawn in the last frame, or on EMI's default button while a tree waits: it is used, and the
     * click is not EMI's.
     */
    @Override
    public boolean click(Screen screen, double mouseX, double mouseY, int button) {
        if (button != 0 || broken) return false;
        try {
            for (Hit hit : hits) {
                if (!inside(hit.rect(), mouseX, mouseY)) continue;
                boolean used = hit.entry().heart != null && hit.entry().heart.press(false);
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
            Widget emiDefault = defaultButtonOf(holder);
            boolean wrapped = hasHeart && wrapButtons(holder, heart);
            return new Entry(view, hasHeart ? heart : null, new Layout(holder), emiDefault, wrapped);
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

    /** EMI's "Set as default recipe" button among the recipe's widgets, or null when it has none (or they cannot be read). */
    private static Widget defaultButtonOf(WidgetHolder holder) {
        try {
            Object widgets = holder.getClass()
                .getField("widgets")
                .get(holder);
            for (Object widget : (List<?>) widgets) {
                if (widget instanceof FavoriteButton ours) return ours.inner;
                if (widget instanceof Widget w && DEFAULT_BUTTON.equals(w.getClass()
                    .getName())) return w;
            }
            return null;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            // the heart is then drawn as before, over EMI's button if it has one
            if (!warnedDefault) {
                warnedDefault = true;
                NeiAddon.LOGGER.warn("Could not find EMI's default recipe button; the favorite heart is drawn as usual", e);
            }
            return null;
        }
    }

    /**
     * Replaces EMI's default and recipe tree buttons in the recipe's widget list with the addon's (see
     * {@link FavoriteButton}, {@link TreeButton}); a group already done keeps its buttons. True when the default button
     * is the addon's now. A failure leaves EMI's buttons as they are (one warning).
     */
    @SuppressWarnings("unchecked")
    private static boolean wrapButtons(WidgetHolder holder, RecipeHearts.Controller heart) {
        try {
            List<Widget> widgets = (List<Widget>) holder.getClass()
                .getField("widgets")
                .get(holder);
            boolean wrapped = false;
            for (int i = 0; i < widgets.size(); i++) {
                Widget w = widgets.get(i);
                if (w instanceof FavoriteButton) {
                    wrapped = true;
                } else if (w instanceof TreeButton) {
                    // already the addon's
                } else if (DEFAULT_BUTTON.equals(w.getClass()
                    .getName())) {
                    widgets.set(i, new FavoriteButton(w, heart));
                    wrapped = true;
                } else if (TREE_BUTTON.equals(w.getClass()
                    .getName())) {
                    widgets.set(i, new TreeButton(w, heart));
                }
            }
            return wrapped;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            if (!warnedWrap) {
                warnedWrap = true;
                NeiAddon.LOGGER.warn("Could not replace EMI's default and recipe tree buttons; EMI's own stay", e);
            }
            return false;
        }
    }

    /** One warning for a failure inside the addon's buttons on EMI's screen; the button then acts as EMI's. */
    private static void warnButton(Throwable e) {
        if (!warnedButton) {
            warnedButton = true;
            NeiAddon.LOGGER.warn("A button on EMI's recipe screen failed; EMI's own action is used", e);
        }
    }

    /**
     * EMI's "Set as default recipe" button with the heart's tooltip (one tooltip, with the hotkeys): EMI draws it, clicks
     * it (its default is the favorite) and shows its tooltip; only the tooltip's lines are the addon's.
     */
    private static final class FavoriteButton extends Widget {

        final Widget inner;
        private final RecipeHearts.Controller heart;

        FavoriteButton(Widget inner, RecipeHearts.Controller heart) {
            this.inner = inner;
            this.heart = heart;
        }

        @Override
        public Bounds getBounds() {
            return inner.getBounds();
        }

        @Override
        public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
            inner.render(g, mouseX, mouseY, delta);
        }

        @Override
        public List<ClientTooltipComponent> getTooltip(int mouseX, int mouseY) {
            try {
                List<ClientTooltipComponent> out = new ArrayList<>();
                for (Component line : heart.tooltipLines()) out.add(ClientTooltipComponent.create(line.getVisualOrderText()));
                return out;
            } catch (RuntimeException | LinkageError e) {
                warnButton(e);
                return inner.getTooltip(mouseX, mouseY);
            }
        }

        @Override
        public boolean mouseClicked(int mouseX, int mouseY, int button) {
            return inner.mouseClicked(mouseX, mouseY, button);
        }

        @Override
        public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
            return inner.keyPressed(keyCode, scanCode, modifiers);
        }
    }

    /** EMI's "View recipe tree" button, opening the addon's crafting tree for the recipe instead of EMI's tree. */
    private static final class TreeButton extends Widget {

        private final Widget inner;
        private final RecipeHearts.Controller heart;

        TreeButton(Widget inner, RecipeHearts.Controller heart) {
            this.inner = inner;
            this.heart = heart;
        }

        @Override
        public Bounds getBounds() {
            return inner.getBounds();
        }

        @Override
        public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
            inner.render(g, mouseX, mouseY, delta);
        }

        @Override
        public List<ClientTooltipComponent> getTooltip(int mouseX, int mouseY) {
            return inner.getTooltip(mouseX, mouseY);
        }

        @Override
        public boolean mouseClicked(int mouseX, int mouseY, int button) {
            try {
                // while a crafting tree waits for a recipe, the button picks this one, as the heart does
                if (button == 0 && TreeRecipes.waiting() && heart.press(false)) return true;
                if (button == 0 && RecipeHearts.openTree(heart)) {
                    Minecraft.getInstance()
                        .getSoundManager()
                        .play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
                    return true;
                }
            } catch (RuntimeException | LinkageError e) {
                warnButton(e);
            }
            return inner.mouseClicked(mouseX, mouseY, button);
        }

        @Override
        public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
            return inner.keyPressed(keyCode, scanCode, modifiers);
        }
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
     * The cell of one recipe's heart: the first free cell, worked out on first use (on the first frame that needs it).
     */
    private static final class Layout {

        private final WidgetHolder holder;
        /** The bounds of the other widgets of the recipe (a snapshot taken on first use); null when the list cannot be read. */
        private List<Bounds> others;
        private boolean listRead;
        private Bounds heart;

        Layout(WidgetHolder holder) {
            this.holder = holder;
        }

        /** The heart's cell. */
        Bounds heartCell() {
            if (heart == null) heart = find();
            return heart;
        }

        /**
         * The first cell, column by column and top to bottom, that overlaps no other widget right of the recipe. When the
         * widget list cannot be read, the first column; when no cell is free, the cell right of the last column.
         */
        private Bounds find() {
            int w = holder.getWidth();
            List<Bounds> others = others();
            if (others == null) return cell(w, 0, 0);
            int maxY = Math.max(CELL, holder.getHeight() + 4);
            for (int column = 0; column < COLUMNS; column++) {
                for (int row = 0; STEP * row + CELL <= maxY; row++) {
                    Bounds cell = cell(w, column, row);
                    if (free(cell, others, w)) return cell;
                }
            }
            return cell(w, COLUMNS, 0);
        }

        private static boolean free(Bounds cell, List<Bounds> others, int recipeWidth) {
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

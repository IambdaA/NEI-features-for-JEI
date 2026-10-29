package neiaddon;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

/**
 * Window for the crafting calculator. Left: crafting steps (click = next recipe, right click = treat as raw).
 * Right: targets (+/-/remove) and the raw materials total.
 */
public class CalculatorScreen extends Screen {

    private static final int ROW = 20;
    private static final int PAD = 10;

    private record Hit(int x, int y, int w, int h, Runnable left, Runnable right) {

        boolean contains(double mx, double my) {
            return mx >= x && mx < x + w && my >= y && my < y + h;
        }
    }

    private final Map<Calculator.StackKey, Long> items;
    private final String groupName;
    private final List<Hit> hits = new ArrayList<>();
    private Calculator.Plan plan;
    private boolean dirty = true;
    private int scrollSteps;
    private int scrollRaw;
    private ItemStack hoveredStack = ItemStack.EMPTY;

    private final Bookmarks.Group group;

    public CalculatorScreen(Bookmarks.Group group) {
        super(Component.literal("Crafting Calculator"));
        this.group = group;
        this.items = group.items;
        this.groupName = group.name;
    }

    public static void open(Bookmarks.Group group) {
        McCompat.setScreen(new CalculatorScreen(group));
    }

    private void invalidate() {
        dirty = true;
    }

    private void recompute() {
        try {
            plan = Calculator.compute(items);
        } catch (RuntimeException e) {
            NeiAddon.LOGGER.error("Calculator failed", e);
            plan = new Calculator.Plan(List.of(), java.util.Map.of());
        }
        dirty = false;
    }

    private static String qty(ItemStack sample, long amount) {
        int max = Math.max(1, sample.getMaxStackSize());
        if (max > 1 && amount >= max) {
            return amount + " (" + (amount / max) + "x" + max + (amount % max != 0 ? " + " + (amount % max) : "") + ")";
        }
        return Long.toString(amount);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        super.extractRenderState(g, mouseX, mouseY, delta);
        if (dirty) recompute();
        hits.clear();
        hoveredStack = ItemStack.EMPTY;
        Font font = Minecraft.getInstance().font;

        int w = g.guiWidth();
        int h = g.guiHeight();
        int leftW = (int) (w * 0.55) - PAD;
        int rightX = PAD + leftW + PAD;
        int rightW = w - rightX - PAD;
        int top = PAD + 14;

        g.text(font, "Crafting Calculator - " + groupName, PAD, PAD, 0xFFFFFFFF, true);
        String clear = "[Clear all]";
        int clearX = w - PAD - font.width(clear);
        g.text(font, clear, clearX, PAD, 0xFFFF7777, false);
        hits.add(new Hit(clearX, PAD, font.width(clear), 10, () -> {
            items.clear();
            Calculator.resetChoices();
            group.commit();
            invalidate();
        }, null));

        String imp = "[Import JEI bookmarks]";
        int impX = clearX - 12 - font.width(imp);
        g.text(font, imp, impX, PAD, 0xFF77DDFF, false);
        hits.add(new Hit(impX, PAD, font.width(imp), 10, () -> {
            BookmarkImport.importInto(group);
            invalidate();
        }, null));

        if (!Calculator.available()) {
            g.text(font, "JEI is not ready yet.", PAD, top, 0xFFFFAAAA, false);
            return;
        }
        if (items.isEmpty()) {
            g.text(font, "Nothing to calculate. Drag items into this bookmark group, or click 'Import JEI bookmarks'.", PAD, top, 0xFFAAAAAA, false);
            return;
        }

        // ---- steps (left)
        g.text(font, "Steps  (click: other recipe, right click: treat as raw)", PAD, top, 0xFFFFDD66, false);
        int rowsVisible = (h - top - ROW - PAD) / ROW;
        scrollSteps = clampScroll(scrollSteps, plan.steps().size(), rowsVisible);
        int y = top + 14;
        for (int i = scrollSteps; i < Math.min(plan.steps().size(), scrollSteps + rowsVisible); i++) {
            Calculator.Step step = plan.steps().get(i);
            g.fakeItem(step.out.sample, PAD, y);
            String text = "Craft " + step.crafts + "x  -> " + qty(step.out.sample, step.crafts * step.option.outPerCraft()) + "  "
                + step.out.sample.getHoverName().getString() + "  [" + step.option.category() + " " + (step.optionIndex + 1) + "/"
                + step.optionCount + "]";
            g.text(font, font.plainSubstrByWidth(text, leftW - 22), PAD + 22, y + 4, 0xFFFFFFFF, false);
            Calculator.StackKey key = step.out;
            hits.add(new Hit(PAD, y, leftW, ROW, () -> {
                Calculator.cycleRecipe(key);
                invalidate();
            }, () -> {
                Calculator.toggleRaw(key);
                invalidate();
            }));
            if (mouseX >= PAD && mouseX < PAD + 18 && mouseY >= y && mouseY < y + 18) hoveredStack = step.out.sample;
            y += ROW;
        }

        // ---- targets (right top)
        g.text(font, "Targets  (-/+ amount, x remove)", rightX, top, 0xFFFFDD66, false);
        int ty = top + 14;
        for (var entry : new ArrayList<>(items.entrySet())) {
            Calculator.StackKey key = entry.getKey();
            g.fakeItem(key.sample, rightX, ty);
            String label = entry.getValue() + "x " + key.sample.getHoverName().getString();
            g.text(font, font.plainSubstrByWidth(label, rightW - 22 - 36), rightX + 22, ty + 4, 0xFFFFFFFF, false);
            int bx = rightX + rightW - 36;
            g.text(font, "-", bx, ty + 4, 0xFFFF7777, false);
            g.text(font, "+", bx + 12, ty + 4, 0xFF77FF77, false);
            g.text(font, "x", bx + 24, ty + 4, 0xFFFF4444, false);
            hits.add(new Hit(bx - 2, ty, 12, ROW, () -> bump(key, -step()), null));
            hits.add(new Hit(bx + 10, ty, 12, ROW, () -> bump(key, step()), null));
            hits.add(new Hit(bx + 22, ty, 12, ROW, () -> {
                items.remove(key);
                group.commit();
                invalidate();
            }, null));
            if (mouseX >= rightX && mouseX < rightX + 18 && mouseY >= ty && mouseY < ty + 18) hoveredStack = key.sample;
            ty += ROW;
        }

        // ---- raw materials (right bottom)
        int ry = Math.max(ty + 8, top + 14 + 6 * ROW);
        g.text(font, "Raw materials", rightX, ry, 0xFFFFDD66, false);
        ry += 14;
        List<java.util.Map.Entry<Calculator.StackKey, Long>> raw = new ArrayList<>(plan.raw().entrySet());
        int rawRows = Math.max(1, (h - ry - PAD) / ROW);
        scrollRaw = clampScroll(scrollRaw, raw.size(), rawRows);
        for (int i = scrollRaw; i < Math.min(raw.size(), scrollRaw + rawRows); i++) {
            var e = raw.get(i);
            g.fakeItem(e.getKey().sample, rightX, ry);
            String text = qty(e.getKey().sample, e.getValue()) + "  " + e.getKey().sample.getHoverName().getString();
            g.text(font, font.plainSubstrByWidth(text, rightW - 22), rightX + 22, ry + 4, 0xFFFFFFFF, false);
            if (mouseX >= rightX && mouseX < rightX + 18 && mouseY >= ry && mouseY < ry + 18) hoveredStack = e.getKey().sample;
            ry += ROW;
        }

        if (!hoveredStack.isEmpty()) tooltip(g, font, hoveredStack, mouseX, mouseY);
    }

    private static int step() {
        return Minecraft.getInstance().hasShiftDown() ? 64 : 1;
    }

    private void bump(Calculator.StackKey key, long delta) {
        long next = items.getOrDefault(key, 0L) + delta;
        if (next <= 0) items.remove(key);
        else items.put(key, next);
        group.commit();
        invalidate();
    }

    private static int clampScroll(int scroll, int total, int visible) {
        return Math.max(0, Math.min(scroll, Math.max(0, total - visible)));
    }

    private static void tooltip(GuiGraphicsExtractor g, Font font, ItemStack stack, int mx, int my) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        List<FormattedCharSequence> lines = new ArrayList<>();
        for (Component line : stack.getTooltipLines(Item.TooltipContext.of(mc.level), mc.player, TooltipFlag.Default.NORMAL)) {
            lines.addAll(font.split(line, 220));
        }
        g.setTooltipForNextFrame(font, lines, mx, my);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        for (Hit hit : hits) {
            if (hit.contains(event.x(), event.y())) {
                Runnable action = event.button() == 1 ? hit.right() : event.button() == 0 ? hit.left() : null;
                if (action != null) {
                    action.run();
                    return true;
                }
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double amountX, double amountY) {
        int delta = (int) -Math.signum(amountY);
        if (mouseX < width * 0.55) scrollSteps += delta;
        else scrollRaw += delta;
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        return super.keyPressed(event);
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        return super.charTyped(event);
    }
}

package neiaddon;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.inventory.Slot;
import neiaddon.chain.ChainMath;
import neiaddon.chain.ChainPull;
import neiaddon.event.ClientTickEvent;
import neiaddon.event.EventPriority;
import neiaddon.event.ScreenEvent;
import neiaddon.event.SubscribeEvent;
import neiaddon.platform.ClientPlatform;

/**
 * Tints the items the crafting trees still need, in any storage the player opens. Only groups whose crafting chain and
 * crafting mode are both on count (the crafting tree's toolbar button turns crafting mode on); a chain without crafting mode
 * is not tinted. "Needed" is what the maths of Shift + V (without V's limits) takes out of this storage for those
 * groups: {@link ChainPull#plan} with the player's inventory counted, so an item the player already carries is not tinted,
 * and nothing is tinted when the player carries everything. Only on container screens, and only while the bookmark panel is
 * shown there ({@link BookmarkPanel#isActive}).
 *
 * <p>
 * The tint is drawn in the screen's {@link ScreenEvent.Render.Post} at {@link Pose#OVER}: in front of the slot items and
 * behind the tooltips, which are drawn at {@link Pose#TOOLTIP} before this event (the GUI depth test lets the nearer one
 * win, as {@link ItemZoom} relies on). The carried item is drawn by the game before this event as well; that it stays on top
 * of the tint is not yet checked in game. The maths runs at most every
 * {@link #REFRESH_TICKS} client ticks or {@link #REFRESH_NANOS}, and at once when the screen or the page changes; the last
 * result is reused in between.
 */
final class ChainHighlight {

    /** Translucent orange: reads as "still needed", and differs from Highlight Present Item's green. */
    private static final int NEEDED_COLOR = 0x60FF9800;
    /** One item slot: the 16 by 16 area its item is drawn in. */
    private static final int SLOT = 16;
    private static final int REFRESH_TICKS = 10;
    private static final long REFRESH_NANOS = 500_000_000L;

    private static int ticks;
    private static Screen computedFor;
    private static Bookmarks.Page computedPage;
    private static int computedTick;
    private static long computedNanos;
    /** Item ids the chains still need from the open storage (empty: nothing is tinted). */
    private static Set<String> needed = Set.of();
    /** The storage slots holding a needed item, found at the last refresh (so a frame reads no item ids). */
    private static List<Slot> tinted = List.of();
    private static boolean warned;

    private ChainHighlight() {}

    @SubscribeEvent
    static void onTick(ClientTickEvent.Post event) {
        ticks++;
    }

    /** Last of the render handlers, so the bookmark panel has laid itself out for this frame ({@link BookmarkPanel#isActive}). */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    static void onRender(ScreenEvent.Render.Post event) {
        Screen screen = event.getScreen();
        Minecraft mc = Minecraft.getInstance();
        // the creative inventory's grid holds the open tab's items, not storage
        if (!(screen instanceof AbstractContainerScreen<?> container) || screen instanceof CreativeModeInventoryScreen
            || !AddonConfig.HIGHLIGHT_CHAIN.get()
            || BookmarkPanel.isHidden() || !BookmarkPanel.supports(screen) || !BookmarkPanel.isActive() || mc.player == null) {
            forget();
            return;
        }
        long now = System.nanoTime();
        try {
            Bookmarks.Page page = Bookmarks.page();
            if (screen != computedFor || page != computedPage || ticks - computedTick >= REFRESH_TICKS
                || now - computedNanos >= REFRESH_NANOS) {
                computedFor = screen;
                computedPage = page;
                computedTick = ticks;
                computedNanos = now;
                needed = neededItems(container, page, mc);
                tinted = needed.isEmpty() ? List.of() : tintedSlots(container, mc);
            }
        } catch (RuntimeException e) {
            needed = Set.of();
            tinted = List.of();
            warn("Could not work out the items the crafting chains need in this storage", e);
        }
        if (!tinted.isEmpty()) draw(event.getGuiGraphics(), container);
    }

    /**
     * The item ids of every crafting-mode crafting chain on the page (chain and crafting both on) that the maths of Shift + V
     * (without V's limits) takes out of this storage (above zero). A fresh chain maths per chain, since {@link ChainPull#plan}
     * changes the one it is given. Empty on any failure.
     */
    private static Set<String> neededItems(AbstractContainerScreen<?> container, Bookmarks.Page page, Minecraft mc) {
        try {
            Map<String, Long> inventory = new HashMap<>();
            Map<String, Long> stored = new HashMap<>();
            for (Slot slot : container.getMenu().slots) {
                if (!slot.hasItem()) continue;
                if (slot.container instanceof CraftingContainer) continue;
                Map<String, Long> into = slot.container == mc.player.getInventory() ? inventory : stored;
                into.merge(RecipeData.idOf(slot.getItem()), (long) slot.getItem().getCount(), Long::sum);
            }
            Set<String> out = new HashSet<>();
            List<Integer> groups = new ArrayList<>(page.metas.keySet());
            for (int groupId : groups) {
                Bookmarks.GroupMeta meta = page.metas.get(groupId);
                if (meta == null || !meta.chain || !meta.crafting) continue;
                ChainMath math = Bookmarks.pullMath(page, groupId);
                if (math == null) continue;
                for (Map.Entry<String, Long> entry : ChainPull.plan(math, inventory, stored).entrySet()) {
                    if (entry.getValue() > 0) out.add(entry.getKey());
                }
            }
            return out;
        } catch (RuntimeException e) {
            warn("Could not work out the items the crafting chains need in this storage", e);
            return Set.of();
        }
    }

    /**
     * The storage slots holding a needed item: not the player's own slots (their items are the ones already carried), not
     * crafting grids and not result slots.
     */
    private static List<Slot> tintedSlots(AbstractContainerScreen<?> container, Minecraft mc) {
        List<Slot> out = new ArrayList<>();
        for (Slot slot : container.getMenu().slots) {
            if (slot.container == mc.player.getInventory() || slot.container instanceof CraftingContainer || !slot.hasItem()
                || CraftSlots.isResult(slot)) continue;
            if (needed.contains(RecipeData.idOf(slot.getItem()))) out.add(slot);
        }
        return out;
    }

    /** Tints the slots found at the last refresh, in a pose at {@link Pose#OVER}, so tooltips and the carried item stay above. */
    private static void draw(GuiGraphics g, AbstractContainerScreen<?> container) {
        Pose pose = Pose.of(g);
        pose.pushMatrix();
        pose.identity();
        pose.translateZ(Pose.OVER);
        try {
            int[] bounds = ClientPlatform.get().containerBounds(container);
            for (Slot slot : tinted) {
                if (!slot.hasItem()) continue; // taken out since the last refresh
                int x = bounds[0] + slot.x;
                int y = bounds[1] + slot.y;
                g.fill(x, y, x + SLOT, y + SLOT, NEEDED_COLOR);
            }
        } catch (RuntimeException e) {
            // nothing more this refresh; the next refresh tries again (debug log only after the first warning)
            tinted = List.of();
            warn("Could not tint the items the crafting chains need in this storage", e);
        } finally {
            pose.popMatrix();
        }
    }

    /** Not a container screen (or the panel is not shown there): nothing is kept, so the next one starts fresh. */
    private static void forget() {
        computedFor = null;
        computedPage = null;
        needed = Set.of();
        tinted = List.of();
    }

    /** One warning, then debug messages: this runs every few ticks while a storage is open. */
    private static void warn(String message, RuntimeException e) {
        if (warned) {
            NeiAddon.LOGGER.debug(message, e);
            return;
        }
        warned = true;
        NeiAddon.LOGGER.warn(message, e);
    }
}

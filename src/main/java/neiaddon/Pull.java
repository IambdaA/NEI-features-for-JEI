package neiaddon;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import neiaddon.chain.ChainPull;
import neiaddon.chain.ChainMath;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * Pull items (NEI: BookmarkPanel.pullBookmarkItems with DefaultBookmarkContainerHandler and FastTransferManager). Moves
 * the items a bookmark group (or one item) needs from an open chest into the player's inventory.
 *
 * <p>
 * Like NEI it only works in a chest: the last 36 slots of the menu are the player's inventory and every slot before them
 * is storage. Items are matched by registry id.
 */
public final class Pull {

    /** NEI: {@code slots.size() - 4 * 9}. */
    private static final int PLAYER_SLOTS = 36;

    private Pull() {}

    /** NEI: BookmarkContainerInfo.getBookmarkContainerHandler(gui) != null. */
    public static boolean available() {
        Minecraft mc = Minecraft.getInstance();
        return mc.player != null && mc.gameMode != null && mc.gui.screen() instanceof ContainerScreen screen
            && screen.getMenu().slots.size() > PLAYER_SLOTS;
    }

    /**
     * NEI's pullBookmarkItems(math, shift). With {@code shift} what the inventory already holds counts, so only the
     * missing items are pulled. Returns false when there was nothing to do (the key is then not used up).
     */
    public static boolean pull(ChainMath math, boolean shift) {
        if (math == null || !available()) return false;
        Minecraft mc = Minecraft.getInstance();
        AbstractContainerMenu menu = ((ContainerScreen) mc.gui.screen()).getMenu();
        int storage = menu.slots.size() - PLAYER_SLOTS;

        Map<String, Long> inventory = shift ? count(menu, storage, menu.slots.size()) : Map.of();
        Map<String, Long> pull = ChainPull.plan(math, inventory, count(menu, 0, storage));
        if (pull.isEmpty()) return false;

        // NEI counts the free slots once, for everything together
        int free = 0;
        for (int i = storage; i < menu.slots.size(); i++) {
            ItemStack stack = menu.slots.get(i)
                .getItem();
            if (stack.isEmpty() || stack.getCount() < stack.getMaxStackSize() && pull.containsKey(RecipeData.idOf(stack))) free++;
        }
        if (free == 0) return false;

        Map<String, Long> wanted = new LinkedHashMap<>();
        for (Map.Entry<String, Long> entry : pull.entrySet()) {
            ItemStack sample = sample(menu, storage, entry.getKey());
            if (sample.isEmpty()) continue; // nothing of it in this chest
            long amount = Math.min(entry.getValue(), (long) free * sample.getMaxStackSize());
            if (amount > 0) wanted.put(entry.getKey(), amount);
        }
        if (wanted.isEmpty()) return true;

        int held = dropHeld(menu, storage);
        if (menu.getCarried()
            .isEmpty()) {
            NeiAddon.LOGGER.info("Pulling items: {}", wanted);
            for (Map.Entry<String, Long> entry : wanted.entrySet()) take(menu, storage, entry.getKey(), entry.getValue());
            if (held != -1) click(menu, held, 0, ContainerInput.PICKUP);
        }
        return true;
    }

    /** What NEI's pullRecipeItems does over one item: that amount of it. */
    public static boolean pullItem(ItemStack stack, long amount, boolean shift) {
        return pull(Bookmarks.pullMath(RecipeData.idOf(stack), amount), shift);
    }

    private static Map<String, Long> count(AbstractContainerMenu menu, int from, int to) {
        Map<String, Long> counts = new HashMap<>();
        for (int i = from; i < to; i++) {
            ItemStack stack = menu.slots.get(i)
                .getItem();
            if (!stack.isEmpty()) counts.merge(RecipeData.idOf(stack), (long) stack.getCount(), Long::sum);
        }
        return counts;
    }

    private static ItemStack sample(AbstractContainerMenu menu, int storage, String id) {
        for (int i = 0; i < storage; i++) {
            ItemStack stack = menu.slots.get(i)
                .getItem();
            if (!stack.isEmpty() && RecipeData.idOf(stack)
                .equals(id)) return stack;
        }
        return ItemStack.EMPTY;
    }

    /** FastTransferManager.dropHeldItem: puts a held stack into an empty inventory slot; that slot, or -1. */
    private static int dropHeld(AbstractContainerMenu menu, int storage) {
        ItemStack carried = menu.getCarried();
        if (carried.isEmpty()) return -1;
        for (int i = storage; i < menu.slots.size(); i++) {
            Slot slot = menu.slots.get(i);
            if (!slot.hasItem() && slot.mayPlace(carried)) {
                click(menu, i, 0, ContainerInput.PICKUP);
                if (menu.getCarried()
                    .isEmpty()) return i;
            }
        }
        return -1;
    }

    /** DefaultBookmarkContainerHandler: takes {@code amount} of the item out of the storage slots, first slot first. */
    private static void take(AbstractContainerMenu menu, int storage, String id, long amount) {
        long remaining = amount;
        for (int i = 0; i < storage && remaining > 0; i++) {
            ItemStack stack = menu.slots.get(i)
                .getItem();
            if (stack.isEmpty() || !RecipeData.idOf(stack)
                .equals(id)) continue;
            int count = (int) Math.min(remaining, stack.getCount());
            move(menu, storage, i, count);
            remaining -= count;
        }
    }

    /**
     * FastTransferManager.transferItems: {@code count} items of the slot go where a shift-click would put them. A whole
     * stack is one shift-click; a part is picked up and put down one by one (NEI's moveOneItem does it with three clicks
     * per item, this keeps the stack in hand).
     */
    private static void move(AbstractContainerMenu menu, int storage, int from, int count) {
        if (count >= menu.slots.get(from)
            .getItem()
            .getCount()) {
            click(menu, from, 0, ContainerInput.QUICK_MOVE);
            return;
        }
        click(menu, from, 0, ContainerInput.PICKUP);
        ItemStack held = menu.getCarried();
        if (held.isEmpty()) return;
        for (int placed = 0; placed < count; placed++) {
            int to = destination(menu, storage, menu.getCarried());
            if (to == -1) break;
            click(menu, to, 1, ContainerInput.PICKUP);
        }
        click(menu, from, 0, ContainerInput.PICKUP);
    }

    /**
     * Where a shift-click from the chest puts an item: the inventory is filled from its last slot backwards (the hotbar
     * first), onto a stack of the same item with room, else into an empty slot. -1 when it does not fit.
     */
    private static int destination(AbstractContainerMenu menu, int storage, ItemStack held) {
        for (int i = menu.slots.size() - 1; i >= storage; i--) {
            Slot slot = menu.slots.get(i);
            ItemStack stack = slot.getItem();
            if (!stack.isEmpty() && ItemStack.isSameItemSameComponents(stack, held)
                && stack.getCount() < Math.min(slot.getMaxStackSize(held), stack.getMaxStackSize())) return i;
        }
        for (int i = menu.slots.size() - 1; i >= storage; i--) {
            Slot slot = menu.slots.get(i);
            if (!slot.hasItem() && slot.mayPlace(held)) return i;
        }
        return -1;
    }

    private static void click(AbstractContainerMenu menu, int slot, int button, ContainerInput input) {
        Minecraft mc = Minecraft.getInstance();
        mc.gameMode.handleContainerInput(menu.containerId, slot, button, input, mc.player);
    }
}

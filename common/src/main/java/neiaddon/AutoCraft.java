package neiaddon;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import neiaddon.chain.ChainAutocraft;
import neiaddon.chain.ChainItem;
import neiaddon.chain.ChainMath;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.ResultSlot;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import neiaddon.event.SubscribeEvent;
import neiaddon.event.ClientTickEvent;

/**
 * NEI's autocrafting (Shift + C): works through a crafting chain recipe by recipe. For each craft it has JEI's recipe
 * transfer move the ingredients into the open crafting grid, then shift-clicks the result slot. What to craft, and in
 * which order, is decided by {@link ChainAutocraft} (a port of NEI's AutoCraftingManager and RecipeChainIterator).
 *
 * <p>
 * NEI does the clicks in one go on a worker thread. Here the server has to answer between a transfer and the click, so
 * one step is done per client tick and the next waits for the result slot to fill (and later to empty). It stops when
 * the screen closes or another container opens, or when the result cannot be moved (inventory full).
 */
public final class AutoCraft {

    /** Ticks to wait for the server before giving up on a craft. */
    private static final int TIMEOUT = 40;

    private enum Phase {
        NEXT,
        WAIT_RESULT,
        WAIT_CLEAR
    }

    private static ChainAutocraft walker;
    private static ChainMath math;
    private static AbstractContainerMenu menu;
    private static Phase phase = Phase.NEXT;
    private static int waited;

    private AutoCraft() {}

    /** The open screen's menu when it has a crafting result slot (inventory, crafting table...), else null. */
    private static AbstractContainerMenu craftingMenu() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || Calculator.runtime == null || !(McCompat.screen() instanceof AbstractContainerScreen<?> screen)) return null;
        AbstractContainerMenu open = screen.getMenu();
        return resultSlot(open) != null && mc.player.containerMenu == open ? open : null;
    }

    public static boolean available() {
        return AddonConfig.AUTOCRAFTING.get() && craftingMenu() != null;
    }

    public static boolean processing() {
        return walker != null;
    }

    private static Slot resultSlot(AbstractContainerMenu m) {
        for (Slot slot : m.slots) {
            if (slot instanceof ResultSlot) return slot;
        }
        return null;
    }

    /** What the player can craft with: every slot of the open menu except the result. NEI's getInventoryItems. */
    private static Map<String, Long> inventory() {
        Map<String, Long> items = new HashMap<>();
        Player player = Minecraft.getInstance().player;
        for (Slot slot : menu.slots) {
            if (slot.hasItem() && !(slot instanceof ResultSlot) && slot.mayPickup(player)) {
                items.merge(RecipeData.idOf(slot.getItem()), (long) slot.getItem().getCount(), Long::sum);
            }
        }
        return items;
    }

    /**
     * Starts crafting the chain. {@code ignoreInventory} (NEI: no Ctrl) crafts the wanted amount again even if the
     * inventory already holds it; with Ctrl only what is missing is crafted.
     */
    public static boolean start(ChainMath chain, boolean ignoreInventory) {
        AbstractContainerMenu open = AddonConfig.AUTOCRAFTING.get() ? craftingMenu() : null;
        if (chain == null || open == null) {
            NeiAddon.LOGGER.info("Autocrafting not started: chain={} craftingGridOpen={}", chain != null, open != null);
            return false;
        }
        stop();
        menu = open;
        math = chain;
        math.createMasterRoot();
        if (ignoreInventory) ignoreInventory();
        math.createMasterRoot();
        walker = new ChainAutocraft(math, new ChainAutocraft.Host() {

            @Override
            public Map<String, Long> inventory() {
                return AutoCraft.inventory();
            }

            @Override
            public boolean canCraft(String recipeId) {
                RecipeLookup.Target<?> target = target(recipeId);
                boolean can = target != null && target.canTransfer(menu, Minecraft.getInstance().player);
                if (!can) NeiAddon.LOGGER.info("Autocrafting: cannot craft {} now (recipe found: {})", recipeId, target != null);
                return can;
            }
        });
        phase = Phase.NEXT;
        NeiAddon.LOGGER.info("Autocrafting started: {}", math.outputRecipes);
        return true;
    }

    public static void stop() {
        walker = null;
        math = null;
        menu = null;
    }

    /** NEI's autocraftingIgnoreInventory: wanted items the inventory already covers are asked for once more on top. */
    private static void ignoreInventory() {
        Map<String, Long> have = inventory();
        for (int i = 0; i < math.recipeIngredients.size(); i++) {
            ChainItem item = math.recipeIngredients.get(i);
            if (item.amount() > 0 && ChainMath.ROOT_RECIPE_ID.equals(item.recipeId)) {
                long amount = have.getOrDefault(item.item, 0L);
                if (amount >= item.amount()) {
                    math.recipeIngredients.set(
                        i,
                        new ChainItem(
                            item.item,
                            item.recipeId,
                            item.type,
                            amount + item.amount() - amount % item.amount(),
                            1,
                            item.options,
                            item.payload));
                }
            }
        }
    }

    private static RecipeLookup.Target<?> target(String recipeId) {
        ItemStack hint = ItemStack.EMPTY;
        for (ChainItem item : math.recipeResults) {
            if (recipeId.equals(item.recipeId)) {
                Optional<Item> found = BuiltInRegistries.ITEM.getOptional(Identifier.parse(item.item));
                if (found.isPresent()) hint = new ItemStack(found.get());
                break;
            }
        }
        return RecipeLookup.findTarget(recipeId, hint);
    }

    @SubscribeEvent
    static void onTick(ClientTickEvent.Post event) {
        if (walker == null) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.gameMode == null || craftingMenu() != menu) {
            stop();
            return;
        }
        Slot result = resultSlot(menu);
        switch (phase) {
            case NEXT -> {
                String recipeId = walker.next();
                if (recipeId == null) {
                    stop();
                    return;
                }
                RecipeLookup.Target<?> target = target(recipeId);
                if (target == null || !target.transfer(menu, mc.player)) {
                    NeiAddon.LOGGER.info("Autocrafting: transfer of {} was refused", recipeId);
                    walker.abandon();
                    return;
                }
                phase = Phase.WAIT_RESULT;
                waited = 0;
            }
            case WAIT_RESULT -> {
                if (result.hasItem()) {
                    mc.gameMode.handleContainerInput(menu.containerId, result.index, 0, ContainerInput.QUICK_MOVE, mc.player);
                    phase = Phase.WAIT_CLEAR;
                    waited = 0;
                } else if (++waited > TIMEOUT) {
                    NeiAddon.LOGGER.info("Autocrafting: no result after the transfer, giving up on this recipe");
                    walker.abandon();
                    phase = Phase.NEXT;
                }
            }
            case WAIT_CLEAR -> {
                if (!result.hasItem()) {
                    walker.crafted();
                    phase = Phase.NEXT;
                } else if (++waited > TIMEOUT) {
                    NeiAddon.LOGGER.info("Autocrafting stopped: the result did not move (inventory full?)");
                    stop();
                }
            }
        }
    }
}

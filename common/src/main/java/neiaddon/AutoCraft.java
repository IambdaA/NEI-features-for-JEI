package neiaddon;

import neiaddon.autocraft.Runner;
import neiaddon.autocraft.ServerPath;
import neiaddon.chain.ChainAutocraft;
import neiaddon.chain.ChainMath;
import neiaddon.event.SubscribeEvent;
import neiaddon.event.ClientTickEvent;

/**
 * NEI's autocrafting (Shift + C): works through a crafting chain recipe by recipe. For each craft it has JEI's recipe
 * transfer move the ingredients into the open crafting grid, then shift-clicks the result slot. What to craft, and in
 * which order, is decided by {@link ChainAutocraft} (a port of NEI's AutoCraftingManager and RecipeChainIterator).
 *
 * <p>
 * NEI does the clicks in one go on a worker thread. Here the server has to answer between a transfer and the click, so
 * one step is done per client tick and the next waits for the result slot to fill (and later to empty). While more than
 * one craft of a recipe is wanted the grid is filled with as many sets as fit, and every craft of that batch is
 * one click on the result that the server answers and vouches for (#46); when a full grid
 * would make more than is wanted, the rest of that recipe is crafted one set at a time. It stops when the screen
 * closes or another container opens, or when the result cannot be moved (inventory full).
 *
 * <p>
 * The state and the code live in {@code neiaddon.autocraft}; this class is the entry point the rest of the addon calls.
 */
public final class AutoCraft {

    private AutoCraft() {}

    public static boolean available() {
        return Runner.available();
    }

    /**
     * Starts crafting the chain. {@code ignoreInventory} crafts the wanted amount again even if the inventory
     * already holds it; false, the default, crafts only what is missing. (NEI has these
     * two on the opposite modifiers.)
     */
    public static boolean start(ChainMath chain, boolean ignoreInventory) {
        return Runner.start(chain, ignoreInventory);
    }

    public static void stop() {
        Runner.stop();
    }

    @SubscribeEvent
    static void onTick(ClientTickEvent.Post event) {
        Runner.onTick(event);
    }

    /**
     * The loader's receiver hands the server's answer to a craft request over (on the client thread); the tick reads it
     * in the SERVER_CRAFT phase.
     */
    public static void serverCraftResult(Craft.CraftResult result) {
        ServerPath.serverCraftResult(result);
    }

    /** The same for a whole-chain answer (read in the SERVER_CHAIN phase). */
    public static void serverChainResult(Craft.CraftChainResult result) {
        ServerPath.serverChainResult(result);
    }
}

package neiaddon;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * AE2's crafting terminals (the terminal part, and wireless ones whose menu extends AE2's) for autocrafting: the grid
 * fills from the network (AE2's own recipe transfer, through JEI or EMI), each craft refills it from the network, and the
 * network's items count as the player's. All by reflection on AE2 15 (1.20.1) names; any failure turns this off with one
 * warning, and the terminal is then simply not a crafting grid for autocrafting.
 */
final class Ae2Crafting {

    private static final String MENU = "appeng.menu.me.items.CraftingTermMenu";
    private static final String OUTPUT = "appeng.menu.slot.CraftingTermSlot";
    private static final String MATRIX = "appeng.menu.slot.CraftingMatrixSlot";

    private static boolean broken;
    private static Constructor<?> packet, interaction;
    private static Object craftItem, setDown;
    private static Method instance, sendMethod;
    private static Field repo, entries;
    private static Method what, amount, toStack;

    private Ae2Crafting() {}

    private static boolean named(Class<?> c, String name) {
        for (; c != null && c != Object.class; c = c.getSuperclass()) {
            if (c.getName()
                .equals(name)) return true;
        }
        return false;
    }

    /**
     * Is this an AE2 crafting terminal's menu? True also after a failure, so a run that broke still finds its grid and
     * cleans up; {@link #broken()} keeps a new run from starting.
     */
    static boolean isTerminal(AbstractContainerMenu menu) {
        return menu != null && named(menu.getClass(), MENU);
    }

    /** Did an AE2 access fail (autocrafting in terminals is then off)? */
    static boolean broken() {
        return broken;
    }

    /** The terminal's crafting output slot, or null. */
    static Slot output(AbstractContainerMenu menu) {
        if (!isTerminal(menu)) return null;
        for (Slot slot : menu.slots) {
            if (named(slot.getClass(), OUTPUT)) return slot;
        }
        return null;
    }

    /** The crafting terminal's output slot class exactly: AE2's pattern terminal output extends it. */
    static boolean isOutput(Slot slot) {
        return slot.getClass()
            .getName()
            .equals(OUTPUT);
    }

    /** The terminal's 3x3 grid. */
    static List<Slot> grid(AbstractContainerMenu menu) {
        List<Slot> grid = new ArrayList<>();
        if (!isTerminal(menu)) return grid;
        for (Slot slot : menu.slots) {
            if (named(slot.getClass(), MATRIX)) grid.add(slot);
        }
        if (grid.isEmpty()) broke("no crafting grid slots found", null);
        return grid;
    }

    /**
     * Crafts once onto the cursor, as a plain click on the output does (AE2's CRAFT_ITEM action; a click through the screen
     * would read Shift and craft a stack). The server refills the grid from the network afterwards. False when it could not
     * be sent.
     */
    static boolean craftOne(Slot output) {
        try {
            if (packet == null) {
                Class<?> action = Class.forName("appeng.helpers.InventoryAction");
                craftItem = action.getField("CRAFT_ITEM")
                    .get(null);
                packet = Class.forName("appeng.core.sync.packets.InventoryActionPacket")
                    .getConstructor(action, int.class, long.class);
            }
            sendToServer(packet.newInstance(craftItem, output.index, 0L));
            return true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            broke("could not send AE2's craft action", e);
            return false;
        }
    }

    /**
     * Puts the cursor's stack into the network, as the terminal does when the player clicks the empty space of its item grid
     * with a stack held (AE2's {@code MEStorageScreen} sends {@code PICKUP_OR_SET_DOWN} with serial -1): the network takes
     * what it can, the rest stays on the cursor. False when it could not be sent.
     */
    static boolean putCarriedIntoNetwork(AbstractContainerMenu menu) {
        try {
            if (interaction == null) {
                Class<?> action = Class.forName("appeng.helpers.InventoryAction");
                setDown = action.getField("PICKUP_OR_SET_DOWN")
                    .get(null);
                interaction = Class.forName("appeng.core.sync.packets.MEInteractionPacket")
                    .getConstructor(int.class, long.class, action);
            }
            sendToServer(interaction.newInstance(menu.containerId, -1L, setDown));
            return true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            broke("could not send AE2's put-into-network action", e);
            return false;
        }
    }

    /** Sends a packet to the server through AE2's network handler (its methods are looked up once). */
    private static void sendToServer(Object packet) throws ReflectiveOperationException {
        if (instance == null) {
            Class<?> handler = Class.forName("appeng.core.sync.network.NetworkHandler");
            instance = handler.getMethod("instance");
            sendMethod = handler.getMethod("sendToServer", Class.forName("appeng.core.sync.BasePacket"));
        }
        sendMethod.invoke(instance.invoke(null), packet);
    }

    /** Puts the grid back into the network (the terminal's own "clear" action). False when it could not be asked. */
    static boolean clearGrid(AbstractContainerMenu menu) {
        try {
            menu.getClass()
                .getMethod("clearCraftingGrid")
                .invoke(menu);
            return true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            broke("could not clear AE2's crafting grid", e);
            return false;
        }
    }

    /**
     * The network's items as the open terminal screen knows them, keyed like the addon's item ids ({@link RecipeData#idOf});
     * fluids and other kinds are left out. Null when the screen is no AE2 terminal or it cannot be read.
     */
    static Map<String, Long> networkItems(Screen screen) {
        Map<String, Long> out = new HashMap<>();
        if (broken || screen == null) return null;
        try {
            if (repo == null) {
                Class<?> terminal = null;
                for (Class<?> c = screen.getClass(); c != null && c != Object.class && terminal == null; c = c.getSuperclass()) {
                    if (c.getSimpleName()
                        .equals("MEStorageScreen")) terminal = c;
                }
                if (terminal == null) return null;
                repo = JeiAreas.field(terminal, "repo");
            }
            if (!repo.getDeclaringClass()
                .isInstance(screen)) return null;
            Object r = repo.get(screen);
            if (entries == null) entries = JeiAreas.field(r.getClass(), "entries");
            for (Object entry : ((Map<?, ?>) entries.get(r)).values()) {
                if (what == null) {
                    what = entry.getClass()
                        .getMethod("getWhat");
                    amount = entry.getClass()
                        .getMethod("getStoredAmount");
                    toStack = Class.forName("appeng.api.stacks.AEItemKey")
                        .getMethod("toStack");
                }
                Object key = what.invoke(entry);
                long n = (Long) amount.invoke(entry);
                if (n <= 0 || !toStack.getDeclaringClass()
                    .isInstance(key)) continue;
                ItemStack stack = (ItemStack) toStack.invoke(key);
                if (!stack.isEmpty()) out.merge(RecipeData.idOf(stack), n, Long::sum);
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            broke("could not read the AE2 terminal's items", e);
            return null;
        }
        return out;
    }

    private static void broke(String what, Throwable e) {
        if (!broken) {
            broken = true;
            NeiAddon.LOGGER.warn("Autocrafting in AE2 crafting terminals is off: {}", what, e);
        }
    }
}

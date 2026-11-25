package neiaddon;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.NonNullList;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * The AE2 half of server-side crafting, read by name only (AE2 15 names, the same on 1.20.1 Forge and Fabric): the
 * server finds an AE2 crafting terminal, fills its grid with AE2's own packet and takes a craft with AE2's own slot
 * click. {@link CraftServer.Job} does the steps and the counting. Every AE2 call here may throw a reflection error,
 * which the caller turns into an answer.
 */
final class Ae2CraftServer {

    /** The terminal's menu and its crafting result slot, by class name. */
    static final String MENU = "appeng.menu.me.items.CraftingTermMenu";
    private static final String RESULT = "appeng.menu.slot.CraftingTermSlot";

    /** AE2's classes the network helpers read, by name. */
    private static final String MENU_BASE = "appeng.menu.me.common.MEStorageMenu";
    private static final String ITEM_KEY = "appeng.api.stacks.AEItemKey";
    private static final String KEY = "appeng.api.stacks.AEKey";
    private static final String HELPER = "appeng.api.storage.StorageHelper";
    private static final String ACTIONABLE = "appeng.api.config.Actionable";
    private static final String STORAGE = "appeng.api.storage.MEStorage";
    private static final String ENERGY = "appeng.api.networking.energy.IEnergySource";
    private static final String SOURCE = "appeng.api.networking.security.IActionSource";
    private static final String NODE = "appeng.api.networking.IGridNode";
    private static final String GRID = "appeng.api.networking.IGrid";
    private static final String STORAGE_SERVICE = "appeng.api.networking.storage.IStorageService";
    private static final String ACTION_HOST = "appeng.api.networking.security.IActionHost";

    /** The server the one failure warning was logged for (an integrated server starts again in the same game run). */
    private static MinecraftServer warnedFor;
    /** AE2's static members the network helpers call, found on first use (see {@link #api}). */
    private static Api apiMembers;
    /** The terminal classes' network members, found once per menu class (see {@link #network}). */
    private static final Map<Class<?>, Network> NETWORKS = new ConcurrentHashMap<>();

    private Ae2CraftServer() {}

    /** AE2's static members the network helpers call. */
    private static final class Api {

        final Method of;
        final Method insert;
        final Method extract;
        final Method grid;
        final Method service;
        final Method invalidate;
        final Object modulate;
        final Object simulate;

        Api() throws ReflectiveOperationException {
            Class<?> key = Class.forName(KEY);
            Class<?> actionable = Class.forName(ACTIONABLE);
            Class<?> storage = Class.forName(STORAGE);
            Class<?> source = Class.forName(SOURCE);
            of = Class.forName(ITEM_KEY).getMethod("of", ItemStack.class);
            insert = Class.forName(HELPER).getMethod("poweredInsert", Class.forName(ENERGY), storage, key, long.class,
                source, actionable);
            extract = storage.getMethod("extract", key, long.class, actionable, source);
            grid = Class.forName(NODE).getMethod("getGrid");
            service = Class.forName(GRID).getMethod("getStorageService");
            invalidate = Class.forName(STORAGE_SERVICE).getMethod("invalidateCache");
            modulate = actionable.getField("MODULATE").get(null);
            simulate = actionable.getField("SIMULATE").get(null);
        }
    }

    /** One terminal class's network members: the menu's storage and power fields, and its node and action source. */
    private static final class Network {

        final Field storage;
        final Field power;
        final Method node;
        final Method visible;
        final Method source;

        Network(Class<?> type) throws ReflectiveOperationException {
            Class<?> base = Class.forName(MENU_BASE);
            storage = base.getDeclaredField("storage");
            storage.setAccessible(true);
            power = base.getDeclaredField("powerSource");
            power.setAccessible(true);
            node = type.getMethod("getNetworkNode");
            visible = type.getMethod("isKeyVisible", Class.forName(KEY));
            source = type.getMethod("getActionSource");
        }
    }

    /**
     * The members that reach a wireless terminal's network through its host. Looked up apart from {@link Network}, so
     * a missing one only turns off the wireless case and never the placed terminals.
     */
    private static final class HostNode {

        final Method host;
        final Class<?> actionHost;
        final Method node;

        HostNode() throws ReflectiveOperationException {
            host = Class.forName(MENU_BASE).getMethod("getHost");
            actionHost = Class.forName(ACTION_HOST);
            node = actionHost.getMethod("getActionableNode");
        }
    }

    private static HostNode hostNode;
    private static boolean hostNodeTried;
    private static boolean hostNodeWarned;

    /** The node of the menu's host when the host is an AE2 action host (a wireless terminal), else null. */
    private static Object nodeFromHost(AbstractContainerMenu menu) {
        try {
            if (!hostNodeTried) {
                hostNodeTried = true;
                hostNode = new HostNode();
            }
            if (hostNode == null) return null;
            Object host = hostNode.host.invoke(menu);
            return hostNode.actionHost.isInstance(host) ? hostNode.node.invoke(host) : null;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            if (!hostNodeWarned) {
                hostNodeWarned = true;
                NeiAddon.LOGGER.warn("Autocrafting: can't reach a wireless AE2 terminal's network through its host; "
                    + "results there go to the inventory only", e);
            }
            return null;
        }
    }

    private static Api api() throws ReflectiveOperationException {
        if (apiMembers == null) apiMembers = new Api();
        return apiMembers;
    }

    private static Network network(AbstractContainerMenu menu) throws ReflectiveOperationException {
        Network found = NETWORKS.get(menu.getClass());
        if (found == null) {
            found = new Network(menu.getClass());
            NETWORKS.put(menu.getClass(), found);
        }
        return found;
    }

    /** Whether the terminal has a network to put items into: its storage and its power source are both set. */
    static boolean networked(AbstractContainerMenu menu) throws ReflectiveOperationException {
        Network net = network(menu);
        return net.storage.get(menu) != null && net.power.get(menu) != null;
    }

    /**
     * How much of the stack the terminal's network accepts, as AE2's own shift-click into the terminal counts it. Zero
     * when the terminal has no storage or power source, when the stack is empty, or when the view cells hide its item.
     * With {@code simulate} false the network keeps what it accepts (MODULATE). The stack itself does not change.
     */
    static long toNetwork(AbstractContainerMenu menu, ItemStack stack, boolean simulate)
        throws ReflectiveOperationException {
        Network net = network(menu);
        Object storage = net.storage.get(menu);
        Object power = net.power.get(menu);
        if (storage == null || power == null || stack.isEmpty()) return 0;
        Api api = api();
        Object key = api.of.invoke(null, stack);
        if (key == null || !(Boolean) net.visible.invoke(menu, key)) return 0;
        Object mode = simulate ? api.simulate : api.modulate;
        long accepted = (Long) api.insert.invoke(null, power, storage, key, (long) stack.getCount(),
            net.source.invoke(menu), mode);
        return Math.max(0, Math.min(accepted, stack.getCount()));
    }

    /**
     * How much of the stack's item the terminal's live network storage holds, read with a simulated extract of all
     * of it. Zero when there is no storage or no key for the stack. Used to read back a network insert that threw.
     */
    static long stored(AbstractContainerMenu menu, ItemStack stack) throws ReflectiveOperationException {
        Network net = network(menu);
        Object storage = net.storage.get(menu);
        if (storage == null || stack.isEmpty()) return 0;
        Api api = api();
        Object key = api.of.invoke(null, stack);
        if (key == null) return 0;
        return (Long) api.extract.invoke(storage, key, Long.MAX_VALUE, api.simulate, net.source.invoke(menu));
    }

    /**
     * Why the network takes none of a stack, for the log: whether the view cells show the item, how much the storage
     * alone would take (no power counted: 0 means full, by bytes or by item types), and how much energy the network
     * holds. Simulated only; changes nothing.
     */
    static String refusal(AbstractContainerMenu menu, ItemStack stack) throws ReflectiveOperationException {
        Network net = network(menu);
        Object storage = net.storage.get(menu);
        Object power = net.power.get(menu);
        Api api = api();
        Object key = api.of.invoke(null, stack);
        if (storage == null || power == null || key == null) return "no storage, power source or item key";
        boolean visible = (Boolean) net.visible.invoke(menu, key);
        Object source = net.source.invoke(menu);
        long room = (Long) Class.forName(STORAGE)
            .getMethod("insert", Class.forName(KEY), long.class, Class.forName(ACTIONABLE), Class.forName(SOURCE))
            .invoke(storage, key, (long) stack.getCount(), api.simulate, source);
        Class<?> multiplier = Class.forName("appeng.api.config.PowerMultiplier");
        double energy = (Double) Class.forName(ENERGY)
            .getMethod("extractAEPower", double.class, Class.forName(ACTIONABLE), multiplier)
            .invoke(power, 1.0E12, api.simulate, multiplier.getField("ONE").get(null));
        return "view cells show it: " + visible + ", the storage alone would take " + room + " (0: full, by bytes or "
            + "item types), energy in the network: " + Math.round(energy) + " AE";
    }

    /**
     * Drops the cached item view of the terminal's network, so AE2's next fill sees what was stored since the cache was
     * built. A placed terminal's menu has its own node. A wireless one has none, so the node comes from its host,
     * which reaches the network through the access point in range (or AE2WTLib's quantum bridge). False when neither
     * gives a node, grid or storage service (a portable cell, an unlinked or out-of-range wireless terminal), so there
     * is no view to refresh.
     */
    static boolean refreshNetworkView(AbstractContainerMenu menu) throws ReflectiveOperationException {
        Object node = network(menu).node.invoke(menu);
        if (node == null) node = nodeFromHost(menu);
        if (node == null) return false;
        Api api = api();
        Object grid = api.grid.invoke(node);
        if (grid == null) return false;
        Object service = api.service.invoke(grid);
        if (service == null) return false;
        api.invalidate.invoke(service);
        return true;
    }

    /** Whether the menu is an AE2 crafting terminal, or a subclass of one (AE2's wireless one, AE2WTLib's). */
    static boolean isTerminal(AbstractContainerMenu menu) {
        for (Class<?> type = menu.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            if (type.getName().equals(MENU)) return true;
        }
        return false;
    }

    /** Whether the slot is the terminal's crafting result: AE2's CraftingTermSlot, or a subclass of it. */
    static boolean isResult(Slot slot) {
        for (Class<?> type = slot.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            if (type.getName().equals(RESULT)) return true;
        }
        return false;
    }

    /**
     * The slots of the menu for one of AE2's slot semantics ({@code appeng.menu.SlotSemantics}, a field name such as
     * {@code CRAFTING_GRID}), in the menu's own order. A copy, so the caller's list does not change with the menu.
     */
    @SuppressWarnings("unchecked")
    static List<Slot> slots(AbstractContainerMenu menu, String semantic) throws ReflectiveOperationException {
        Object key = Class.forName("appeng.menu.SlotSemantics").getField(semantic).get(null);
        for (Method method : menu.getClass().getMethods()) {
            if (method.getName().equals("getSlots") && method.getParameterCount() == 1
                && method.getParameterTypes()[0].isInstance(key)) {
                return new ArrayList<>((List<Slot>) method.invoke(menu, key));
            }
        }
        throw new NoSuchMethodException("getSlots(" + semantic + ")");
    }

    /**
     * Whether AE2's own fill can work on this terminal: the menu is AE2's crafting packet holder, it uses the real
     * items of the network, and it has a network node. Without these AE2's fill does nothing at all.
     */
    static boolean usable(AbstractContainerMenu menu) throws ReflectiveOperationException {
        Class<?> holder = Class.forName("appeng.helpers.IMenuCraftingPacket");
        if (!holder.isInstance(menu)) return false;
        boolean real = (Boolean) holder.getMethod("useRealItems").invoke(menu);
        return real && holder.getMethod("getNetworkNode").invoke(menu) != null;
    }

    /**
     * AE2's own grid fill for a recipe, on the server (the packet's server code): the recipe is looked up by its id,
     * what does not fit the recipe moves out, and each empty cell gets one item from the displaced items, the network
     * or the player's inventory. Nothing is crafted (craftMissing is false, so AE2 never starts an autocraft job).
     */
    static void fill(ServerPlayer player, ResourceLocation recipe) throws ReflectiveOperationException {
        Class<?> packet = Class.forName("appeng.core.sync.packets.FillCraftingGridFromRecipePacket");
        Object fill = packet.getConstructor(ResourceLocation.class, NonNullList.class, boolean.class)
            .newInstance(recipe, NonNullList.create(), false);
        packet.getMethod("serverPacketData", ServerPlayer.class).invoke(fill, player);
    }

    /**
     * One craft at the terminal, as a click on its result does: AE2's CraftingTermSlot.doClick with the CRAFT_ITEM
     * action. The result goes onto the cursor (the menu's carried stack); AE2 refills the grid from the network itself.
     * {@code clicking} runs once every name is found, right before the click.
     */
    static void craftOnce(Slot result, Player player, Runnable clicking) throws ReflectiveOperationException {
        Class<?> action = Class.forName("appeng.helpers.InventoryAction");
        Object craft = action.getField("CRAFT_ITEM").get(null);
        java.lang.reflect.Method click = result.getClass().getMethod("doClick", action, Player.class);
        // every name is found: from here on the click may have changed something
        clicking.run();
        click.invoke(result, craft, player);
    }

    /**
     * Logs that AE2 autocrafting on the server failed, once per server start: a later failure in the same run is not
     * logged again. {@code cause} may be null.
     */
    static void warnOnce(MinecraftServer server, String what, Throwable cause) {
        if (warnedFor == server) return;
        warnedFor = server;
        String text = "Server-side autocrafting at an AE2 terminal failed: {}; the client clicks instead";
        if (cause == null) NeiAddon.LOGGER.warn(text, what);
        else NeiAddon.LOGGER.warn(text, what, cause);
    }
}

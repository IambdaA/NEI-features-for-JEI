package neiaddon;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Optional;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.runtime.IRecipesGui;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import neiaddon.event.EventPriority;
import neiaddon.event.ScreenEvent;
import neiaddon.event.SubscribeEvent;

/**
 * NEI's Cheat Mode giving: a click on an item of JEI's list or of the bookmark panel gives it, left click the Stack Controls
 * amount (0 = one stack; a bookmark gives its own amount when it has one), right click one. Shift + click on JEI's list
 * shows the recipes instead, and nothing is given while JEI's recipe screen is open, as in NEI. On while JEI's cheat mode
 * is on, or while this addon's Cheat Mode is (NEI's cheat mode gives items too).
 *
 * <p>
 * JEI has no API for giving items, so its own cheat code is used by reflection: JEI's give packet when JEI is on the server
 * (the server then checks that the player may cheat, as for JEI's own clicks), else JEI's own fallback (creative mode on a
 * server without JEI). In creative mode the items are put into the inventory directly instead (see giveCreative). If JEI's internals are not found one warning is logged and clicks work as without cheating.
 */
public final class JeiGive {

    /** At most this many stacks per click (a full inventory); more would only be dropped on the ground. */
    private static final int MAX_STACKS = 36;
    private static boolean failed;

    private JeiGive() {}

    /** Is a click on an item a give (JEI's cheat mode, or this addon's Cheat Mode)? */
    static boolean cheating() {
        if (AddonConfig.CHEAT_MODE.get() == 2) return true;
        if (failed) return false;
        try {
            Object state = JeiAreas.method(internal(), "getClientToggleState")
                .invoke(null);
            return Boolean.TRUE.equals(JeiAreas.invoke(state, "isCheatItemsEnabled"));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            fail(e);
            return false;
        }
    }

    /** Gives {@code count} of the item the way JEI's cheat mode does; false when it cannot (a fluid, JEI not found). */
    static boolean give(ItemStack stack, long count) {
        return give(stack, count, false);
    }

    /** {@code toInventory}: always into the inventory, whatever JEI's give mode (several items given at once). */
    static boolean give(ItemStack stack, long count, boolean toInventory) {
        if (FluidStacks.isFluid(stack) && count > 0) {
            // a fluid is given as filled buckets, as JEI's cheat mode does: the amount is in millibuckets, rounded up
            ItemStack bucket = FluidStacks.bucketOf(stack);
            return !bucket.isEmpty() && give(bucket, Math.max(1, (count + 999) / 1000), toInventory);
        }
        if (stack.isEmpty() || FluidStacks.isMilli(stack) || count <= 0) return false;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && mc.gameMode != null && mc.player.isCreative()) {
            giveCreative(mc, stack, count);
            return true;
        }
        if (failed) return false;
        try {
            Class<?> internal = internal();
            Object connection = JeiAreas.method(internal, "getServerConnection")
                .invoke(null);
            // getClientConfigs(), or getJeiClientConfigs() on older JEI (15.59 in ATM9, 19.25 in ATM10)
            Object config = JeiButtons.clientConfig();
            // JEI 15: getGiveMode() gives the value; JEI 19 and later: giveMode() gives a config value read with get()
            Object mode = JeiAreas.invoke(config, "giveMode", "getGiveMode");
            if (!(mode instanceof Enum<?>)) mode = JeiAreas.invoke(mode, "get");
            if (toInventory && mode instanceof Enum<?> e) mode = inventoryMode(e);
            int max = Math.max(1, stack.getMaxStackSize());
            // JEI's "give to the mouse" mode: the mouse holds one stack at most
            long left = String.valueOf(mode)
                .equals("MOUSE_PICKUP") ? Math.min(count, max) : Math.min(count, (long) max * MAX_STACKS);
            if (Boolean.TRUE.equals(JeiAreas.invoke(connection, "isJeiOnServer"))) {
                Class<?> modeType = Class.forName("mezz.jei.common.config.GiveMode");
                Constructor<?> packet = Class.forName("mezz.jei.common.network.packets.PacketGiveItemStack")
                    .getConstructor(ItemStack.class, modeType);
                Method send = sendMethod(connection);
                // one packet per stack: the packet carries one item stack (1.20.1 writes its count as a byte)
                int chunk = Math.min(max, 64);
                while (left > 0) {
                    int n = (int) Math.min(left, chunk);
                    send.invoke(connection, packet.newInstance(stack.copyWithCount(n), mode));
                    left -= n;
                }
            } else {
                // JEI's own fallback (creative mode without JEI on the server) only knows "one" and "a stack"
                Class<?> amounts = Class.forName("mezz.jei.gui.util.GiveAmount");
                Object util = Class.forName("mezz.jei.gui.util.CommandUtil")
                    .getConstructor(Class.forName("mezz.jei.common.config.IClientConfig"), Class.forName("mezz.jei.common.network.IConnectionToServer"))
                    .newInstance(config, connection);
                Method giveStack = util.getClass()
                    .getMethod("giveStack", ItemStack.class, amounts);
                Object stackAmount = amounts.getField("MAX")
                    .get(null);
                Object one = amounts.getField("ONE")
                    .get(null);
                for (; left >= max; left -= max) giveStack.invoke(util, stack, stackAmount);
                for (; left > 0; left--) giveStack.invoke(util, stack, one);
            }
            return true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            fail(e);
            return false;
        }
    }

    /**
     * Creative mode: the items go straight into the inventory's slots, on this side and with the creative slot packet, the
     * way the creative screen itself puts items in. The server's give (JEI's packet) would not do here: while the creative
     * screen shows another tab than the inventory, the game ignores the server's updates of the main inventory, so the
     * items only showed up after the screen was closed. Stacks of the item are topped up first, then empty slots are used.
     */
    private static void giveCreative(Minecraft mc, ItemStack stack, long count) {
        Inventory inventory = mc.player.getInventory();
        int max = Math.max(1, stack.getMaxStackSize());
        long left = Math.min(count, (long) max * MAX_STACKS);
        for (int pass = 0; pass < 2 && left > 0; pass++) {
            for (int i = 0; i < Inventory.INVENTORY_SIZE && left > 0; i++) {
                ItemStack in = inventory.getItem(i);
                boolean fits = pass == 0 ? !in.isEmpty() && ItemStack.isSameItemSameTags(in, stack) : in.isEmpty();
                int room = max - in.getCount();
                if (!fits || room <= 0) continue;
                int n = (int) Math.min(left, room);
                ItemStack put = stack.copyWithCount(in.getCount() + n);
                inventory.setItem(i, put);
                // the inventory menu numbers the hotbar 36-44 and the rest of the inventory 9-35
                mc.gameMode.handleCreativeModeItemAdd(put.copy(), i < 9 ? i + 36 : i);
                left -= n;
            }
        }
    }

    /** Cheat Mode over JEI's item list: the Stack Controls amount on left click, one on right click. */
    @SubscribeEvent(priority = EventPriority.HIGH)
    static void onMousePressed(ScreenEvent.MouseButtonPressed.Pre event) {
        int button = event.getButton();
        if (button != 0 && button != 1 || Calculator.runtime == null || QuantityField.focused()) return;
        // NEI: the recipe screen never gives, a click there shows recipes as usual
        if (event.getScreen() instanceof IRecipesGui || !cheating()) return;
        Minecraft mc = Minecraft.getInstance();
        // an item on the mouse: JEI's cheat mode deletes it on a click over its list
        if (mc.player == null || !mc.player.containerMenu.getCarried()
            .isEmpty()) return;
        Optional<ITypedIngredient<?>> hovered = Calculator.runtime.getIngredientListOverlay()
            .getIngredientUnderMouse();
        if (hovered.isEmpty()) return;
        Optional<ItemStack> stack = hovered.get()
            .getItemStack();
        if (stack.isEmpty() || stack.get()
            .isEmpty()) return;
        if (McCompat.shift()) {
            // NEI: Shift + click shows the recipes (left) or uses (right) instead of giving
            BookmarkPanel.showRecipes(stack.get(), button == 0 ? RecipeIngredientRole.OUTPUT : RecipeIngredientRole.INPUT);
            event.setCanceled(true);
            return;
        }
        if (give(stack.get(), button == 0 ? QuantityField.scrollMultiplier(stack.get()) : 1)) event.setCanceled(true);
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static Object inventoryMode(Enum<?> mode) {
        try {
            return Enum.valueOf((Class) mode.getDeclaringClass(), "INVENTORY");
        } catch (IllegalArgumentException e) {
            return mode;
        }
    }

    /** JEI's own cheat mode toggle (not this addon's Cheat Mode). */
    static boolean jeiCheating() {
        if (failed) return false;
        try {
            Object state = JeiAreas.method(internal(), "getClientToggleState")
                .invoke(null);
            return Boolean.TRUE.equals(JeiAreas.invoke(state, "isCheatItemsEnabled"));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return false;
        }
    }

    /** Sends one of JEI's packets to the server; false when JEI is not on the server or the packet is not there. */
    private static boolean sendJei(String packetClass, Class<?>[] types, Object... args) {
        try {
            Object connection = JeiAreas.method(internal(), "getServerConnection")
                .invoke(null);
            if (!Boolean.TRUE.equals(JeiAreas.invoke(connection, "isJeiOnServer"))) return false;
            Object packet = Class.forName(packetClass)
                .getConstructor(types)
                .newInstance(args);
            sendMethod(connection).invoke(connection, packet);
            return true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            NeiAddon.LOGGER.warn("Could not send JEI's {}", packetClass, e);
            return false;
        }
    }

    /** Deletes the item on the cursor the way JEI's cheat mode does (the server checks it may). */
    static boolean deleteHeld(ItemStack held) {
        return sendJei("mezz.jei.common.network.packets.PacketDeletePlayerItem", new Class<?>[] { ItemStack.class }, held.copy());
    }

    /** Puts {@code count} of the item on the cursor (JEI's "give to the mouse" mode). */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    static boolean giveToMouse(ItemStack stack, int count) {
        try {
            Class modeType = Class.forName("mezz.jei.common.config.GiveMode");
            return sendJei("mezz.jei.common.network.packets.PacketGiveItemStack", new Class<?>[] { ItemStack.class, modeType },
                stack.copyWithCount(count), Enum.valueOf(modeType, "MOUSE_PICKUP"));
        } catch (ClassNotFoundException | RuntimeException e) {
            return false;
        }
    }

    private static Class<?> internal() throws ClassNotFoundException {
        return Class.forName("mezz.jei.common.Internal");
    }

    private static Method sendMethod(Object connection) throws NoSuchMethodException {
        for (Method m : connection.getClass()
            .getMethods()) {
            if (m.getName()
                .equals("sendPacketToServer") && m.getParameterCount() == 1) {
                // the connection's class may not be public, although the method is
                m.setAccessible(true);
                return m;
            }
        }
        throw new NoSuchMethodException(connection.getClass()
            .getName() + ".sendPacketToServer(packet)");
    }

    private static void fail(Throwable e) {
        if (failed) return;
        failed = true;
        NeiAddon.LOGGER.warn("Could not use JEI's cheat mode for giving items; clicks work as without cheating", e);
    }
}

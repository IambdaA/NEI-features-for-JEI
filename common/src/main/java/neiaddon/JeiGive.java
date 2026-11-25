package neiaddon;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Optional;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.recipe.RecipeIngredientRole;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import neiaddon.event.EventPriority;
import neiaddon.event.InputEvent;
import neiaddon.event.ScreenEvent;
import neiaddon.event.SubscribeEvent;

/**
 * NEI's Cheat Mode giving: a click on an item of JEI's list or of the bookmark panel gives it, left click the Stack Controls
 * amount (0 = one stack; a bookmark gives its own amount when it has one), right click one. Shift + click on JEI's list
 * shows the recipes instead, and nothing is given while JEI's recipe screen is open, as in NEI. On while JEI's cheat mode
 * is on, or while this addon's Cheat Mode is (NEI's cheat mode gives items too). With EMI installed EMI shows the item list,
 * so the clicks on its stacks are Ctrl + click (see cheatingForClicks and onEmiListPressed).
 *
 * <p>
 * JEI has no API for giving items, so its own cheat code is used by reflection: JEI's give packet when JEI is on the server
 * (the server then checks that the player may cheat, as for JEI's own clicks), else JEI's own fallback (creative mode on a
 * server without JEI). With EMI installed and only EMI's cheat mode on, the give instead goes through EMI's own packet (GitHub
 * issue #36, see give). In creative mode the items are put into the inventory directly instead (see giveCreative). If JEI's internals are not found one warning is logged and clicks work as without cheating.
 */
public final class JeiGive {

    /**
     * A safety ceiling, not a limit a real plan reaches: at most this many stacks of one item per give (64,000 of a
     * stackable item; 128 LV Cutters need 11,264 copper). It stops a runaway amount from sending millions of packets in
     * one key press.
     */
    static final int MAX_GIVE_STACKS = 1000;

    /** The amount a give hands out: the count, but at most {@link #MAX_GIVE_STACKS} stacks of the item. */
    public static long ceiling(long count, int max) {
        return Math.min(count, (long) max * MAX_GIVE_STACKS);
    }

    private static boolean failed;
    private static boolean modeWarned;
    private static boolean toggleWarned;

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

    /**
     * Is a click on the addon's or EMI's list a give? Any of the three modes says yes (#36, the user's
     * decision 2026-11-22: if either mod is in cheat mode it works): this addon's Cheat Mode, JEI's own toggle (it
     * counts again even with EMI installed, overriding the 2026-11-17 decision that only EMI's mode counts there),
     * or EMI's cheat mode (its setting: Always, or Creative Only in creative on newer EMI). Under EMI the clicks are
     * still Ctrl + click. Nothing is written into EMI's config.
     */
    static boolean cheatingForClicks() {
        if (!EmiCompat.installed()) return cheating();
        return cheating() || EmiCompat.cheatMode();
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
        // #36 (the user's decision 2026-11-22): each server accepts only its own give, so the packet
        // follows the mode that is on. JEI's toggle is read from the server: while it is on, JEI's server takes
        // JEI's packet below (that also covers this addon's Cheat Mode); while it is off and EMI's cheat mode is
        // on, the give goes through EMI's own packet instead - EMI's server takes it for operators and creative
        // players only (its setting is client side and never reaches the server), exactly what EMI's own
        // Ctrl + click would do there. EMI's packet follows JEI's give mode too (see giveToCursor). If that route
        // is not open either, the JEI way below is kept as before.
        if (EmiCompat.installed() && EmiCompat.cheatMode() && !jeiCheating() && EmiCompat.giveEmi(stack, count, giveToCursor(toInventory))) return true;
        if (failed) return false;
        try {
            Class<?> internal = internal();
            Object connection = JeiAreas.method(internal, "getServerConnection")
                .invoke(null);
            // getClientConfigs(), or getJeiClientConfigs() on older JEI (15.59 in ATM9, 19.25 in ATM10)
            Object config = JeiButtons.clientConfig();
            Object mode = giveModeOf(config, toInventory);
            int max = Math.max(1, stack.getMaxStackSize());
            // JEI's "give to the mouse" mode: the mouse holds one stack at most. Otherwise the whole amount is given
            // up to the safety ceiling (the user's call, 2026-11-25: a crafting plan can need several inventories of
            // one item); what doesn't fit the inventory is dropped at the player's feet by the server
            long left = String.valueOf(mode).equals("MOUSE_PICKUP") ? Math.min(count, max) : ceiling(count, max);
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
     * items only showed up after the screen was closed. Stacks of the item are topped up first, then empty slots are
     * used, and the rest is dropped.
     */
    private static void giveCreative(Minecraft mc, ItemStack stack, long count) {
        Inventory inventory = mc.player.getInventory();
        int max = Math.max(1, stack.getMaxStackSize());
        long left = ceiling(count, max);
        for (int pass = 0; pass < 2 && left > 0; pass++) {
            for (int i = 0; i < Inventory.INVENTORY_SIZE && left > 0; i++) {
                ItemStack in = inventory.getItem(i);
                boolean fits = pass == 0 ? !in.isEmpty() && ItemStack.isSameItemSameComponents(in, stack) : in.isEmpty();
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
        // what doesn't fit is dropped at the player's feet, a stack at a time, as the creative screen drops an item
        while (left > 0) {
            int n = (int) Math.min(left, max);
            mc.gameMode.handleCreativeModeItemDrop(stack.copyWithCount(n));
            left -= n;
        }
    }

    /** Cheat Mode over JEI's item list: the Stack Controls amount on left click, one on right click. */
    @SubscribeEvent(priority = EventPriority.HIGH)
    static void onMousePressed(ScreenEvent.MouseButtonPressed.Pre event) {
        int button = event.getButton();
        if (button != 0 && button != 1 || Calculator.runtime == null || QuantityField.focused()) return;
        // NEI: the recipe screen never gives, a click there shows recipes as usual
        if (RecipeHearts.isRecipesScreen(event.getScreen()) || !cheating()) return;
        // EMI shows its own item list and gives from it in its own cheat mode; the bookmark panel still gives (BookmarkPanel)
        if (EmiCompat.installed()) return;
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

    /**
     * With EMI installed its item list takes the presses on its stacks before any screen sees them, so they are read here first.
     * On a container screen (not a recipe screen, no text typed): with the cursor empty, Ctrl + click gives the stack under the
     * mouse while {@link #cheatingForClicks()} (left: the Stack Controls amount, right: one). With an item on the cursor, only
     * this addon's Cheat Mode deletes it or takes one off (as over the bookmark panel); EMI's own cheat mode does that itself.
     * Everything else is EMI's.
     */
    @SubscribeEvent
    static void onEmiListPressed(InputEvent.MouseButtonPre event) {
        int button = event.getButton();
        if (button != 0 && button != 1 || !EmiCompat.installed()) return;
        Screen screen = McCompat.screen();
        if (!(screen instanceof AbstractContainerScreen<?> container) || RecipeHearts.isRecipesScreen(screen) || CalcKeys.typing()) return;
        ItemStack held = container.getMenu()
            .getCarried();
        if (held.isEmpty()) {
            boolean ctrlOnly = McCompat.ctrl() && !McCompat.shift() && !McCompat.alt();
            if (!ctrlOnly || !cheatingForClicks()) return;
        } else if (AddonConfig.CHEAT_MODE.get() != 2 || EmiCompat.cheatMode()) {
            return;
        }
        ItemStack stack = emiSidebarStackUnderMouse();
        if (stack.isEmpty()) return;
        boolean used = held.isEmpty()
            ? give(stack, button == 0 ? QuantityField.scrollMultiplier(stack) : 1)
            : HeldItemDelete.deleteHeld(screen, container.getMenu(), button);
        if (!used) return;
        event.setCanceled(true);
        // the release of this press is kept from EMI and the screen too (see ReleaseGuard)
        ReleaseGuard.pressUsed(button);
    }

    /** The item of EMI's item list (or favorites) under the mouse, empty elsewhere and without EMI. */
    static ItemStack emiSidebarStackUnderMouse() {
        Minecraft mc = Minecraft.getInstance();
        // the mouse in GUI coordinates, as EMI reads it
        double x = mc.mouseHandler.xpos() * mc.getWindow()
            .getGuiScaledWidth() / mc.getWindow()
                .getScreenWidth();
        double y = mc.mouseHandler.ypos() * mc.getWindow()
            .getGuiScaledHeight() / mc.getWindow()
                .getScreenHeight();
        return EmiCompat.sidebarStackAt((int) x, (int) y);
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static Object inventoryMode(Enum<?> mode) {
        try {
            return Enum.valueOf((Class) mode.getDeclaringClass(), "INVENTORY");
        } catch (IllegalArgumentException e) {
            return mode;
        }
    }

    /**
     * JEI's give mode from its client config, read the same way by both give paths; {@code toInventory} gives the inventory
     * mode whatever JEI's setting is.
     */
    private static Object giveModeOf(Object config, boolean toInventory) throws ReflectiveOperationException {
        // JEI 15: getGiveMode() gives the value; JEI 19 and later: giveMode() gives a config value read with get()
        Object mode = JeiAreas.invoke(config, "giveMode", "getGiveMode");
        if (!(mode instanceof Enum<?>)) mode = JeiAreas.invoke(mode, "get");
        if (toInventory && mode instanceof Enum<?> e) mode = inventoryMode(e);
        return mode;
    }

    /**
     * Whether an EMI give puts one stack on the cursor: JEI's give mode is MOUSE_PICKUP, the give is not forced into the
     * inventory, and the cursor is empty (EMI's mode 1 would replace what is on it). When JEI's config cannot be read the
     * inventory mode is taken, with one warning.
     */
    private static boolean giveToCursor(boolean toInventory) {
        if (toInventory) return false;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || !mc.player.containerMenu.getCarried()
            .isEmpty()) return false;
        try {
            return String.valueOf(giveModeOf(JeiButtons.clientConfig(), false))
                .equals("MOUSE_PICKUP");
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            if (!modeWarned && !jeiAbsent(e)) {
                modeWarned = true;
                NeiAddon.LOGGER.warn("Could not read JEI's give mode; gives under EMI use the inventory mode", e);
            }
            return false;
        }
    }

    /**
     * Whether a failure only means JEI's internals are not there (a pack with another viewer on EMI, such as
     * TooManyRecipeViewers): a missing JEI class. That is normal there, so it is not logged.
     */
    private static boolean jeiAbsent(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof ClassNotFoundException || t instanceof NoClassDefFoundError) {
                String name = String.valueOf(t.getMessage());
                if (name.contains("mezz.jei") || name.contains("mezz/jei")) return true;
            }
            if (t.getCause() == t) break;
        }
        return false;
    }

    /** JEI's own cheat mode toggle (not this addon's Cheat Mode). One warning when it cannot be read; then it counts as off. */
    static boolean jeiCheating() {
        if (failed) return false;
        try {
            Object state = JeiAreas.method(internal(), "getClientToggleState")
                .invoke(null);
            return Boolean.TRUE.equals(JeiAreas.invoke(state, "isCheatItemsEnabled"));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            if (!toggleWarned && !jeiAbsent(e)) {
                toggleWarned = true;
                NeiAddon.LOGGER.warn("Could not read JEI's cheat mode toggle; it counts as off", e);
            }
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

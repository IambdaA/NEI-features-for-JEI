package neiaddon;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import neiaddon.event.SubscribeEvent;
import neiaddon.event.TickEvent;

/**
 * A chat message on joining a world, shown only with EMI installed: the addon works with EMI, and it asks players to
 * report anything missing or off on GitHub. Shown once per game session, a few seconds after joining (after the release
 * note, if that one shows); the "turn off" link (caught in {@link ShareClient}, nothing is sent to the server) or the
 * setting (Inventory > EMI Note in Chat) turns it off.
 */
public final class EmiNote {

    /** The command the "turn off" link carries; it never reaches the server. */
    static final String COMMAND = "neiaddon_hide_emi_note";
    private static final int DELAY_TICKS = 140;

    private static boolean shown;
    private static int inWorldTicks;

    private EmiNote() {}

    @SubscribeEvent
    static void onTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || shown) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            inWorldTicks = 0;
            return;
        }
        if (++inWorldTicks < DELAY_TICKS) return;
        shown = true;
        if (!EmiCompat.installed() || !AddonConfig.EMI_NOTE.get()) return;
        mc.player.sendSystemMessage(message());
    }

    private static Component message() {
        MutableComponent github = Component.literal("GitHub")
            .withStyle(style -> style.withColor(ChatFormatting.AQUA)
                .withUnderlined(true)
                .withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, ReleaseNote.URL))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal(ReleaseNote.URL))));
        MutableComponent hide = Component.literal("[Turn off this message]")
            .withStyle(style -> style.withColor(ChatFormatting.GRAY)
                .withUnderlined(true)
                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/" + COMMAND))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal("Turns off EMI Note in Chat (NEI Features Options > Inventory)"))));
        return Component.literal("[NEI Features] ")
            .withStyle(ChatFormatting.GOLD)
            .append(Component.literal("NEI Features is compatible with EMI. I'm not sure which features you're looking for, "
                + "but if anything is missing or seems off, please report it on ")
                .withStyle(ChatFormatting.YELLOW))
            .append(github)
            .append(Component.literal(". ")
                .withStyle(ChatFormatting.YELLOW))
            .append(hide);
    }

    /** The "turn off" link was clicked. */
    static void dismiss() {
        AddonConfig.EMI_NOTE.set(false);
        AddonConfig.EMI_NOTE.save();
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.sendSystemMessage(Component.literal("[NEI Features] The EMI note is turned off "
                + "(NEI Features Options > Inventory > EMI Note in Chat turns it back on).")
                .withStyle(ChatFormatting.GRAY));
        }
    }
}

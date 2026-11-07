package neiaddon;

import java.net.URI;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import neiaddon.event.SubscribeEvent;
import neiaddon.event.ClientTickEvent;

/**
 * A chat message on joining a world: this release is a big one, so it asks players to report problems on GitHub. Shown
 * once per game session, a few seconds after joining (after the JEI++ note, if that one shows); the "turn off" link
 * (caught in {@link ShareClient}, nothing is sent to the server) or the setting (Inventory > Release Note in Chat) turns
 * it off.
 */
public final class ReleaseNote {

    /** The command the "turn off" link carries; it never reaches the server. */
    static final String COMMAND = "neiaddon_hide_release_note";
    /** The issue tracker; also opened by Settings > Report Issues. */
    static final String URL = "https://github.com/IambdaA/NEI-features-for-JEI/issues/";
    private static final int DELAY_TICKS = 100;

    private static boolean shown;
    private static int inWorldTicks;

    private ReleaseNote() {}

    @SubscribeEvent
    static void onTick(ClientTickEvent.Post event) {
        if (shown) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            inWorldTicks = 0;
            return;
        }
        if (++inWorldTicks < DELAY_TICKS) return;
        shown = true;
        if (!AddonConfig.RELEASE_NOTE.get()) return;
        mc.player.sendSystemMessage(message());
    }

    private static Component message() {
        MutableComponent github = Component.literal("GitHub")
            .withStyle(style -> style.withColor(ChatFormatting.AQUA)
                .withUnderlined(true)
                .withClickEvent(new ClickEvent.OpenUrl(URI.create(URL)))
                .withHoverEvent(new HoverEvent.ShowText(Component.literal(URL))));
        MutableComponent hide = Component.literal("[Turn off this message]")
            .withStyle(style -> style.withColor(ChatFormatting.GRAY)
                .withUnderlined(true)
                .withClickEvent(new ClickEvent.RunCommand(COMMAND))
                .withHoverEvent(new HoverEvent.ShowText(Component.literal("Turns off Release Note in Chat (NEI Features Options > Inventory)"))));
        return Component.literal("[NEI Features] ")
            .withStyle(ChatFormatting.GOLD)
            .append(Component.literal("This release is an ambitious one, with a lot of new features. If you find any issues, "
                + "please report them on ")
                .withStyle(ChatFormatting.YELLOW))
            .append(github)
            .append(Component.literal(". ")
                .withStyle(ChatFormatting.YELLOW))
            .append(hide);
    }

    /** The "turn off" link was clicked. */
    static void dismiss() {
        AddonConfig.RELEASE_NOTE.set(false);
        AddonConfig.RELEASE_NOTE.save();
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.sendSystemMessage(Component.literal("[NEI Features] The release note is turned off "
                + "(NEI Features Options > Inventory > Release Note in Chat turns it back on).")
                .withStyle(ChatFormatting.GRAY));
        }
    }
}

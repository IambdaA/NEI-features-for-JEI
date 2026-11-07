package neiaddon;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import neiaddon.event.SubscribeEvent;
import neiaddon.event.ClientTickEvent;
import neiaddon.platform.Platform;

/**
 * A chat message on joining a world while JEI++ is installed: it changes JEI's bookmark list, bookmark overlay and recipe
 * buttons, the same things this addon works with, so the two get in each other's way (buttons on top of each other in the
 * recipe screen, bookmarks ending up in JEI's list). Shown once per game session, a few seconds after joining; the
 * "Don't show again" link (caught in {@link ShareClient}, nothing is sent to the server) or the setting turns it off.
 */
public final class JeiPlusPlusNote {

    /** The command the "Don't show again" link carries; it never reaches the server. */
    static final String COMMAND = "neiaddon_hide_jeipp_note";
    private static final String MOD_ID = "jei_plus_plus";
    private static final int DELAY_TICKS = 60;

    private static boolean shown;
    private static int inWorldTicks;

    private JeiPlusPlusNote() {}

    @SubscribeEvent
    static void onTick(ClientTickEvent.Post event) {
        if (shown) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            inWorldTicks = 0;
            return;
        }
        // after the join messages, so it is not lost among them
        if (++inWorldTicks < DELAY_TICKS) return;
        shown = true;
        if (!AddonConfig.WARN_JEI_PLUS_PLUS.get() || Platform.get()
            .modDisplayName(MOD_ID)
            .isEmpty()) return;
        mc.player.sendSystemMessage(message());
    }

    private static Component message() {
        MutableComponent hide = Component.literal("[Don't show again]")
            .withStyle(style -> style.withColor(ChatFormatting.GRAY)
                .withUnderlined(true)
                .withClickEvent(new ClickEvent.RunCommand(COMMAND))
                .withHoverEvent(new HoverEvent.ShowText(Component.literal("Also in NEI Features Options > Bookmarks Panel"))));
        return Component.literal("[NEI Features] ")
            .withStyle(ChatFormatting.GOLD)
            .append(Component.literal("JEI++ is installed. It changes JEI's bookmarks and recipe buttons in ways that clash with "
                + "NEI Features (buttons on top of each other in the recipe screen, bookmarks landing in JEI's own list). "
                + "NEI Features already has bookmarks, recipe trees and crafting of its own, so removing or disabling "
                + "JEI++ is recommended. ")
                .withStyle(ChatFormatting.YELLOW))
            .append(hide);
    }

    /** The "Don't show again" link was clicked. */
    static void dismiss() {
        AddonConfig.WARN_JEI_PLUS_PLUS.set(false);
        AddonConfig.WARN_JEI_PLUS_PLUS.save();
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.sendSystemMessage(Component.literal("[NEI Features] The JEI++ message will not be shown again.")
                .withStyle(ChatFormatting.GRAY));
        }
    }
}

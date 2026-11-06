package neiaddon;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.player.Player;
import neiaddon.event.EventPriority;
import neiaddon.event.ScreenEvent;
import neiaddon.event.SubscribeEvent;
import org.joml.Matrix3x2fStack;

/**
 * NEI's utility buttons: a row of icon buttons above the bookmark panel (time of day, rain, heal, game mode). They show in
 * Utility Mode (the ones picked in the settings) and in Cheat Mode (all of them), never in Recipe Mode. Each one sends the
 * command that does it, so the server's permissions apply. NEI's delete and magnet buttons need NEI's server side and are
 * not here.
 *
 * <p>
 * Ported from GTNH NotEnoughItems (LGPL-3.0-or-later); the icons are NEI's {@code nei_sprites.png}.
 */
final class UtilityBar {

    /** NEI's sprite sheet: 12 x 12 icons drawn from a 2x texture, so 24 x 24 pixels each. */
    private static final Identifier SPRITES = Identifier.fromNamespaceAndPath(NeiAddon.MODID, "textures/nei_sprites.png");
    private static final int SHEET = 512;
    private static final int ICON = 24;
    private static final int W = 18;
    private static final int H = 18;
    private static final int GAP = 2;

    enum Kind {

        RAIN(240, 24),
        GAMEMODE(264, 24),
        DAWN(264, 48),
        NOON(240, 48),
        DUSK(288, 48),
        MIDNIGHT(312, 48),
        HEAL(336, 48);

        final int u;
        final int v;

        Kind(int u, int v) {
            this.u = u;
            this.v = v;
        }
    }

    /** The game mode icons: creative C and adventure A. */
    static final int[] CREATIVE_ICON = { 264, 24 };
    static final int[] ADVENTURE_ICON = { 336, 24 };

    private record Button(Kind kind, int x, int y) {}

    private static final List<Button> buttons = new ArrayList<>();

    private UtilityBar() {}

    private static boolean wanted(Kind kind) {
        int mode = AddonConfig.CHEAT_MODE.get();
        if (mode == 0) return false;
        boolean all = mode == 2;
        return switch (kind) {
            case RAIN -> all || AddonConfig.UTIL_RAIN.get();
            case HEAL -> all || AddonConfig.UTIL_HEAL.get();
            case GAMEMODE -> (all || AddonConfig.UTIL_GAMEMODE.get()) && cycle().size() > 1;
            default -> all || AddonConfig.UTIL_TIME.get();
        };
    }

    /** Lays the buttons out in rows inside {@code width} pixels from (x, y). Returns the height they take, 0 for none. */
    static int layout(int x, int y, int width) {
        buttons.clear();
        int perRow = (width + GAP) / (W + GAP);
        if (perRow < 1) return 0;
        int n = 0;
        for (Kind kind : Kind.values()) {
            if (!wanted(kind)) continue;
            buttons.add(new Button(kind, x + (n % perRow) * (W + GAP), y + (n / perRow) * (H + GAP)));
            n++;
        }
        return n == 0 ? 0 : ((n - 1) / perRow + 1) * (H + GAP);
    }

    // ---------------------------------------------------------------- game mode

    private static String current() {
        Player player = Minecraft.getInstance().player;
        if (player == null) return "survival";
        if (player.isCreative()) return "creative";
        if (player.isSpectator()) return "spectator";
        return player.getAbilities().mayBuild ? "survival" : "adventure";
    }

    /** The game modes the button goes through: survival, then the ones picked in the settings. */
    private static List<String> cycle() {
        List<String> modes = new ArrayList<>();
        modes.add("survival");
        if (AddonConfig.GM_CREATIVE.get()) modes.add("creative");
        if (AddonConfig.GM_ADVENTURE.get()) modes.add("adventure");
        return modes;
    }

    private static String next() {
        List<String> modes = cycle();
        return modes.get((modes.indexOf(current()) + 1) % modes.size());
    }

    // ---------------------------------------------------------------- state and actions

    private static boolean raining() {
        return Minecraft.getInstance().level != null && Minecraft.getInstance().level.isRaining();
    }

    /** NEI draws a button of an active state (raining, not in survival) like a hovered one. */
    private static boolean active(Kind kind) {
        return switch (kind) {
            case RAIN -> raining();
            case GAMEMODE -> !current().equals("survival");
            default -> false;
        };
    }

    private static void press(Kind kind) {
        switch (kind) {
            case RAIN -> UtilityCommands.run(raining() ? "weather clear" : "weather rain");
            case GAMEMODE -> UtilityCommands.run("gamemode " + next());
            case DAWN -> UtilityCommands.run("time set 0");
            case NOON -> UtilityCommands.run("time set 6000");
            case DUSK -> UtilityCommands.run("time set 12000");
            case MIDNIGHT -> UtilityCommands.run("time set 18000");
            case HEAL -> {
                UtilityCommands.run("effect give @s minecraft:instant_health 1 10 true");
                UtilityCommands.run("effect give @s minecraft:saturation 1 10 true");
            }
        }
    }

    private static List<String> tip(Kind kind) {
        return switch (kind) {
            case RAIN -> List.of(raining() ? "Turn rain off" : "Turn rain on");
            case GAMEMODE -> List.of("Current game mode: " + current(), "§7Click: set game mode to " + next());
            case DAWN -> List.of("Set time to dawn");
            case NOON -> List.of("Set time to noon");
            case DUSK -> List.of("Set time to dusk");
            case MIDNIGHT -> List.of("Set time to midnight");
            case HEAL -> List.of("Heal the player");
        };
    }

    private static int[] iconOf(Kind kind) {
        if (kind == Kind.GAMEMODE) {
            String shown = current().equals("survival") ? next() : current();
            return shown.equals("adventure") ? ADVENTURE_ICON : CREATIVE_ICON;
        }
        return new int[] { kind.u, kind.v };
    }

    // ---------------------------------------------------------------- events

    private static Button at(double mx, double my) {
        for (Button b : buttons) {
            if (mx >= b.x() && mx < b.x() + W && my >= b.y() && my < b.y() + H) return b;
        }
        return null;
    }

    @SubscribeEvent(priority = EventPriority.LOW)
    static void onForeground(ScreenEvent.Render.Foreground event) {
        Screen screen = event.getScreen();
        if (!BookmarkPanel.supports(screen)) return;
        BookmarkPanel.layout(screen);
        if (buttons.isEmpty()) return;
        Font font = Minecraft.getInstance().font;
        GuiGraphicsExtractor g = event.getGuiGraphics();
        g.nextStratum();
        Matrix3x2fStack pose = g.pose();
        pose.pushMatrix();
        pose.identity();
        try {
            Button hover = at(event.getMouseX(), event.getMouseY());
            for (Button b : buttons) {
                BookmarkPanel.button(g, font, b.x(), b.y(), W, H, "", b == hover || active(b.kind()));
                int[] icon = iconOf(b.kind());
                pose.pushMatrix();
                pose.translate(b.x() + (W - ICON / 2) / 2, b.y() + (H - ICON / 2) / 2);
                pose.scale(0.5f, 0.5f);
                g.blit(RenderPipelines.GUI_TEXTURED, SPRITES, 0, 0, (float) icon[0], (float) icon[1], ICON, ICON, SHEET, SHEET);
                pose.popMatrix();
            }
            if (hover != null) {
                List<FormattedCharSequence> lines = new ArrayList<>();
                for (String line : tip(hover.kind())) {
                    lines.add(Component.literal(line)
                        .getVisualOrderText());
                }
                g.setTooltipForNextFrame(font, lines, event.getMouseX(), event.getMouseY());
            }
        } finally {
            pose.popMatrix();
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    static void onMousePressed(ScreenEvent.MouseButtonPressed.Pre event) {
        Screen screen = event.getScreen();
        if (!BookmarkPanel.supports(screen)) return;
        BookmarkPanel.layout(screen);
        Button hit = at(event.getMouseX(), event.getMouseY());
        if (hit == null) return;
        event.setCanceled(true);
        if (event.getButton() == 0) press(hit.kind());
    }
}

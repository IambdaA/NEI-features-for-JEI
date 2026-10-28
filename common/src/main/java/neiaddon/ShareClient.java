package neiaddon;

import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import neiaddon.platform.ClientPlatform;
import neiaddon.event.SubscribeEvent;
import neiaddon.event.ClientChatReceivedEvent;
import neiaddon.event.ScreenEvent;

/**
 * Ctrl + L chat links, the client half: sending (NEIClientUtils.sendChatItemLink), keeping what the server hands out
 * (NEICPH CACHE_BOOKMARK_DATA) and importing when the clicked link's command comes back (NEI's CommandBookmarkAdd).
 */
public final class ShareClient {

    /** The id of the newest payload kept, for the id-less command. */
    private static String latest;

    /** NEI's PAYLOAD_CACHE: the last 64 payloads, oldest dropped first. */
    private static final Map<String, String> CACHE = new LinkedHashMap<>(16, 0.75f, true) {

        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
            return size() > 64;
        }
    };

    private ShareClient() {}

    private static void say(String text) {
        if (Minecraft.getInstance().player != null) Minecraft.getInstance().player.sendSystemMessage(Component.literal(text));
    }

    /** Sends a payload (see {@link Bookmarks#shareGroup}) to the server to be shared in chat. */
    public static boolean send(String payload) {
        if (payload == null) return false;
        ClientPacketListener connection = Minecraft.getInstance().getConnection();
        if (connection == null || !connection.getConnection()
            .isConnected() || !ClientPlatform.get().canSendToServer()) {
            say("The server does not have the NEI addon, so links cannot be shared in chat.");
            return true;
        }
        if (payload.length() > Share.MAX_LENGTH) {
            say("That is too big to share in chat.");
            return true;
        }
        ClientPlatform.get().sendToServer(new Share.ShareRequest(payload));
        return true;
    }

    public static void keep(String id, String payload) {
        NeiAddon.LOGGER.info("Kept shared bookmark {} ({} characters)", id, payload.length());
        synchronized (CACHE) {
            CACHE.put(id, payload);
            latest = id;
        }
    }

    /**
     * Clicking our link in chat: the vanilla click did nothing, so the click is caught here and the import runs directly
     * (the hovered-link lookup is the one JEI uses for its own chat links).
     */
    @SubscribeEvent
    static void onMousePressed(ScreenEvent.MouseButtonPressed.Pre event) {
        if (event.getButton() != 0 || !(event.getScreen() instanceof ChatScreen)) return;
        Style style = McCompat.chat()
            .getClickedComponentStyleAt(event.getMouseX(), event.getMouseY());
        ClickEvent click = style == null ? null : style.getClickEvent();
        if (click == null || click.getAction() != ClickEvent.Action.RUN_COMMAND) return;
        // the click event keeps the leading slash of the command
        String command = click.getValue();
        if (command.startsWith("/")) command = command.substring(1);
        if (command.startsWith(Share.COMMAND + " ")) {
            event.setCanceled(true);
            open(command.substring(Share.COMMAND.length() + 1));
        }
    }

    /** Diagnostic: does the click event survive the trip to this client? */
    @SubscribeEvent
    static void onChatReceived(ClientChatReceivedEvent event) {
        Component message = event.getMessage();
        if (!message.getString()
            .contains("Click to add bookmark")) return;
        message.visit((style, text) -> {
            ClickEvent click = style.getClickEvent();
            if (click != null && click.getAction() == ClickEvent.Action.RUN_COMMAND) {
                NeiAddon.LOGGER.info("Chat link received: \"{}\" runs {}", text, click.getValue());
            }
            return java.util.Optional.empty();
        }, Style.EMPTY);
    }

    /** The player clicked a chat link: import what was kept under the id (NEI's CommandBookmarkAdd). */
    public static void open(String id) {
        NeiAddon.LOGGER.info("Chat link clicked: {}", id);
        String payload;
        synchronized (CACHE) {
            payload = CACHE.get(id.isEmpty() ? latest : id);
        }
        if (payload == null) {
            say("Missing cached payload");
            return;
        }
        NeiAddon.LOGGER.info("Importing: {}", payload.replace("\n", " | ").replace("\t", " "));
        if (!Bookmarks.importShared(payload)) {
            say("Invalid bookmark payload");
            return;
        }
        say("Bookmark added!");
    }
}

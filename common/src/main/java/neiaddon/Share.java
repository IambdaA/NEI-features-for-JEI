package neiaddon;

import java.util.Optional;
import java.util.UUID;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import neiaddon.platform.Platform;

/**
 * Ctrl + L chat links, the networking half (NEI: NEISPH SEND_CHAT_ITEM_LINK, NEIServerUtils.sendChatItemLink and
 * NEICPH CACHE_BOOKMARK_DATA). The sender's client sends the bookmark payload to the server; the server gives it an id,
 * sends payload and id to every client to keep, and broadcasts a chat message whose click runs
 * {@code /neiaddon_bookmark <id>}; that server command answers the clicking player with an import request (see
 * {@link ShareClient}).
 *
 * <p>
 * Common code: it loads on a dedicated server too, so nothing from the client may be touched here. The channel is
 * optional, so players without the addon (or a server without it) can still connect. Each loader carries the three
 * messages (the encode / decode methods and {@link #SHARE_REQUEST} and the other ids below are the wire format), hands
 * them to {@link #broadcast}, {@link ShareClient#keep} and {@link ShareClient#open}, and registers {@link #command()}.
 */
public final class Share {

    public static final String COMMAND = "neiaddon_bookmark";
    /** Longest payload text the channel accepts (a string field holds 32767 characters). */
    public static final int MAX_LENGTH = 30000;

    public static final ResourceLocation SHARE_REQUEST = new ResourceLocation(NeiAddon.MODID, "share_request");
    public static final ResourceLocation SHARED_PAYLOAD = new ResourceLocation(NeiAddon.MODID, "shared_payload");
    public static final ResourceLocation IMPORT_REQUEST = new ResourceLocation(NeiAddon.MODID, "import_request");

    /** Client to server: share this payload. */
    public record ShareRequest(String payload) {

        public static void encode(ShareRequest m, FriendlyByteBuf buf) {
            buf.writeUtf(m.payload, 32767);
        }

        public static ShareRequest decode(FriendlyByteBuf buf) {
            return new ShareRequest(buf.readUtf(32767));
        }
    }

    /** Server to every client: keep this payload under this id until someone clicks the link. */
    public record SharedPayload(String id, String payload) {

        public static void encode(SharedPayload m, FriendlyByteBuf buf) {
            buf.writeUtf(m.id, 32767);
            buf.writeUtf(m.payload, 32767);
        }

        public static SharedPayload decode(FriendlyByteBuf buf) {
            return new SharedPayload(buf.readUtf(32767), buf.readUtf(32767));
        }
    }

    /** Server to one client: import the payload you kept under this id (the player clicked the chat link). */
    public record ImportRequest(String id) {

        public static void encode(ImportRequest m, FriendlyByteBuf buf) {
            buf.writeUtf(m.id, 32767);
        }

        public static ImportRequest decode(FriendlyByteBuf buf) {
            return new ImportRequest(buf.readUtf(32767));
        }
    }

    private Share() {}

    /**
     * What a click on the chat link runs. A server command (the client-side command route did not register), which
     * only tells the clicking client to import what it kept under the id.
     */
    public static LiteralArgumentBuilder<CommandSourceStack> command() {
        NeiAddon.LOGGER.info("Registering the /{} command", COMMAND);
        return Commands.literal(COMMAND)
            .executes(context -> {
                // no id: import the newest link the client kept (a way in when clicking the chat link does not work)
                Platform.get().sendToPlayer(context.getSource().getPlayerOrException(), new ImportRequest(""));
                return 1;
            })
            .then(
                Commands.argument("id", StringArgumentType.word())
                    .executes(context -> {
                        ServerPlayer player = context.getSource()
                            .getPlayerOrException();
                        String id = StringArgumentType.getString(context, "id");
                        NeiAddon.LOGGER.info("Chat link command from {}: {}", player.getName().getString(), id);
                        Platform.get().sendToPlayer(player, new ImportRequest(id));
                        return 1;
                    }));
    }

    /** NEIServerUtils.sendChatItemLink. */
    public static void broadcast(ServerPlayer sender, String payload) {
        if (payload == null || payload.length() > MAX_LENGTH || !payload.startsWith("share\t")) {
            sender.sendSystemMessage(Component.literal("Invalid bookmark payload"));
            return;
        }
        String id = UUID.randomUUID()
            .toString()
            .substring(0, 8);
        String[] lines = payload.split("\n", 3);
        String kind = lines[0].split("\t", -1)[1];
        MutableComponent clickable = null;
        if (lines.length > 1 && lines[1].startsWith("target\t")) {
            try {
                Optional<Item> item = BuiltInRegistries.ITEM.getOptional(new ResourceLocation(lines[1].substring(7)));
                if (item.isPresent()) {
                    // built like JEI's own item chat links: plain text carrying the click and the item hover
                    ItemStack stack = new ItemStack(item.get());
                    clickable = Component.literal("[" + stack.getHoverName().getString() + "]")
                        .withStyle(style -> style.withColor(ChatFormatting.AQUA).withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_ITEM, new HoverEvent.ItemStackInfo(stack))));
                }
            } catch (RuntimeException ignored) {
                // unreadable item id: fall back to the plain label
            }
        }
        if (clickable == null) {
            clickable = Component.literal("[Bookmark " + (kind.equals("group") ? "Group" : kind.equals("recipe") ? "Recipe" : "Item") + "]");
        }
        clickable.withStyle(style -> style.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/" + COMMAND + " " + id)));
        NeiAddon.LOGGER.info("Sharing a {} link from {} as {} ({} characters)", kind, sender.getName().getString(), id, payload.length());
        Component message = Component.literal("<")
            .append(sender.getDisplayName())
            .append("> §6Click to add bookmark: ")
            .append(clickable);

        MinecraftServer server = sender.getServer();
        SharedPayload cache = new SharedPayload(id, payload);
        for (ServerPlayer player : server.getPlayerList()
            .getPlayers()) {
            Platform.get().sendToPlayer(player, cache);
            player.sendSystemMessage(message);
        }
    }
}

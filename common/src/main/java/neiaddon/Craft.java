package neiaddon;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * The messages of server-side autocrafting. The client asks the server to craft one recipe a number of times at the
 * open vanilla crafting grid or Tinkers' Crafting Station (without a side inventory); {@link CraftServer} does it in
 * one server tick. The server answers how many crafts
 * it made and why it stopped. The encode / decode methods and the two ids are the wire format; each loader carries them
 * on a channel of its own, so a server with an older addon (which has the chat link channel but no handler for these)
 * never sees them.
 *
 * <p>
 * A whole chain goes in one request ({@link CraftChainRequest}, its own channel): the server crafts its steps in order
 * in one tick and answers each step it ran. A server without that channel gets the one-recipe requests as before.
 *
 * <p>
 * Common code: it loads on a dedicated server too, so nothing from the client may be touched here.
 */
public final class Craft {

    public static final Identifier CRAFT_REQUEST =
        Identifier.fromNamespaceAndPath(NeiAddon.MODID, "craft_request");
    public static final Identifier CRAFT_RESULT =
        Identifier.fromNamespaceAndPath(NeiAddon.MODID, "craft_result");
    public static final Identifier CHAIN_REQUEST =
        Identifier.fromNamespaceAndPath(NeiAddon.MODID, "craft_chain_request");
    public static final Identifier CHAIN_RESULT =
        Identifier.fromNamespaceAndPath(NeiAddon.MODID, "craft_chain_result");
    /** Most item ids a request may name; a recipe grid has at most 9 inputs, the rest is slack. */
    public static final int MAX_PREFERRED = 64;
    /** Most crafts of one recipe in one step (a step never asks for more than one JEI batch). */
    public static final int MAX_TIMES = 64;
    /** Most steps in one chain request. */
    public static final int MAX_STEPS = 256;
    /** Most crafts in one chain request. */
    public static final int MAX_CHAIN_CRAFTS = 2048;

    /** Why the server stopped. */
    public enum Reason {
        DONE,
        MISSING_INGREDIENT,
        RESULT_CHANGED,
        INVENTORY_FULL,
        UNSUPPORTED_MENU,
        BAD_RECIPE,
        BUSY,
        ERROR
    }

    /**
     * Client to server: craft {@code recipe} {@code times} times in the menu {@code containerId}. {@code preferred}
     * holds the item ids the chain chose for the recipe's ingredients (tools included).
     */
    public record CraftRequest(int requestId, int containerId, Identifier recipe, int times,
        List<Identifier> preferred) implements CustomPacketPayload {

        public static final Type<CraftRequest> TYPE = new Type<>(CRAFT_REQUEST);
        public static final StreamCodec<FriendlyByteBuf, CraftRequest> STREAM_CODEC = StreamCodec
            .of((buf, m) -> encode(m, buf), CraftRequest::decode);

        @Override
        public Type<CraftRequest> type() {
            return TYPE;
        }

        public static void encode(CraftRequest m, FriendlyByteBuf buf) {
            buf.writeVarInt(m.requestId);
            buf.writeVarInt(m.containerId);
            buf.writeIdentifier(m.recipe);
            buf.writeVarInt(m.times);
            int n = Math.min(m.preferred.size(), MAX_PREFERRED);
            buf.writeVarInt(n);
            for (int i = 0; i < n; i++) buf.writeIdentifier(m.preferred.get(i));
        }

        public static CraftRequest decode(FriendlyByteBuf buf) {
            int requestId = buf.readVarInt();
            int containerId = buf.readVarInt();
            Identifier recipe = buf.readIdentifier();
            int times = buf.readVarInt();
            int n = Math.max(0, Math.min(buf.readVarInt(), MAX_PREFERRED));
            List<Identifier> preferred = new ArrayList<>(n);
            for (int i = 0; i < n; i++) preferred.add(buf.readIdentifier());
            return new CraftRequest(requestId, containerId, recipe, times, preferred);
        }
    }

    /** Server to client: how many crafts were made, why the server stopped, and the item that ran out (or null). */
    public record CraftResult(int requestId, int crafted, Reason reason, Identifier missing)
        implements CustomPacketPayload {

        public static final Type<CraftResult> TYPE = new Type<>(CRAFT_RESULT);
        public static final StreamCodec<FriendlyByteBuf, CraftResult> STREAM_CODEC = StreamCodec
            .of((buf, m) -> encode(m, buf), CraftResult::decode);

        @Override
        public Type<CraftResult> type() {
            return TYPE;
        }

        public static void encode(CraftResult m, FriendlyByteBuf buf) {
            buf.writeVarInt(m.requestId);
            buf.writeVarInt(m.crafted);
            buf.writeEnum(m.reason);
            buf.writeBoolean(m.missing != null);
            if (m.missing != null) buf.writeIdentifier(m.missing);
        }

        public static CraftResult decode(FriendlyByteBuf buf) {
            int requestId = buf.readVarInt();
            int crafted = buf.readVarInt();
            Reason reason = buf.readEnum(Reason.class);
            Identifier missing = buf.readBoolean() ? buf.readIdentifier() : null;
            return new CraftResult(requestId, crafted, reason, missing);
        }
    }

    /** One step of a chain: {@code times} crafts of {@code recipe}, with the items chosen for its ingredients. */
    public record ChainStep(Identifier recipe, int times, List<Identifier> preferred) {}

    /**
     * Client to server: the chain's steps in order, crafted in one server tick. The server runs them until one is not
     * fully done. Limits: at most {@link #MAX_STEPS} steps, each of {@link #MAX_TIMES} crafts at most and at least one,
     * and at most {@link #MAX_CHAIN_CRAFTS} crafts in all. A request over a limit decodes to no steps (the server
     * answers ERROR without crafting anything).
     */
    public record CraftChainRequest(int requestId, int containerId, List<ChainStep> steps)
        implements CustomPacketPayload {

        public static final Type<CraftChainRequest> TYPE = new Type<>(CHAIN_REQUEST);
        public static final StreamCodec<FriendlyByteBuf, CraftChainRequest> STREAM_CODEC = StreamCodec
            .of((buf, m) -> encode(m, buf), CraftChainRequest::decode);

        @Override
        public Type<CraftChainRequest> type() {
            return TYPE;
        }

        public static void encode(CraftChainRequest m, FriendlyByteBuf buf) {
            buf.writeVarInt(m.requestId);
            buf.writeVarInt(m.containerId);
            buf.writeVarInt(m.steps.size());
            for (ChainStep step : m.steps) {
                buf.writeIdentifier(step.recipe());
                buf.writeVarInt(step.times());
                int n = Math.min(step.preferred().size(), MAX_PREFERRED);
                buf.writeVarInt(n);
                for (int i = 0; i < n; i++) buf.writeIdentifier(step.preferred().get(i));
            }
        }

        public static CraftChainRequest decode(FriendlyByteBuf buf) {
            int requestId = buf.readVarInt();
            int containerId = buf.readVarInt();
            int n = buf.readVarInt();
            if (n < 1 || n > MAX_STEPS) return new CraftChainRequest(requestId, containerId, List.of());
            List<ChainStep> steps = new ArrayList<>(n);
            long crafts = 0;
            for (int i = 0; i < n; i++) {
                Identifier recipe = buf.readIdentifier();
                int times = buf.readVarInt();
                int k = buf.readVarInt();
                crafts += times;
                if (times < 1 || times > MAX_TIMES || k < 0 || k > MAX_PREFERRED || crafts > MAX_CHAIN_CRAFTS) {
                    return new CraftChainRequest(requestId, containerId, List.of());
                }
                List<Identifier> preferred = new ArrayList<>(k);
                for (int j = 0; j < k; j++) preferred.add(buf.readIdentifier());
                steps.add(new ChainStep(recipe, times, preferred));
            }
            return new CraftChainRequest(requestId, containerId, steps);
        }
    }

    /** One step's answer: the crafts made, why the server stopped there, and the item that ran out (or null). */
    public record ChainAnswer(int crafted, Reason reason, Identifier missing) {

        public static void encode(ChainAnswer m, FriendlyByteBuf buf) {
            buf.writeVarInt(m.crafted);
            buf.writeEnum(m.reason);
            buf.writeBoolean(m.missing != null);
            if (m.missing != null) buf.writeIdentifier(m.missing);
        }

        public static ChainAnswer decode(FriendlyByteBuf buf) {
            int crafted = buf.readVarInt();
            Reason reason = buf.readEnum(Reason.class);
            Identifier missing = buf.readBoolean() ? buf.readIdentifier() : null;
            return new ChainAnswer(crafted, reason, missing);
        }
    }

    /**
     * Server to client: one answer per step the server ran, in order; the last is the step that stopped, or every step
     * was DONE. The answer may also stop early after a DONE step, with fewer entries than steps sent, when the server's
     * time limit ({@link ServerConfig}) is reached; the client sends the rest in its next request. A single BUSY
     * answer refuses the whole request for now. An empty list (or one over the limit) is a refusal too, and the client
     * goes on by clicks.
     */
    public record CraftChainResult(int requestId, List<ChainAnswer> results) implements CustomPacketPayload {

        public static final Type<CraftChainResult> TYPE = new Type<>(CHAIN_RESULT);
        public static final StreamCodec<FriendlyByteBuf, CraftChainResult> STREAM_CODEC = StreamCodec
            .of((buf, m) -> encode(m, buf), CraftChainResult::decode);

        @Override
        public Type<CraftChainResult> type() {
            return TYPE;
        }

        public static void encode(CraftChainResult m, FriendlyByteBuf buf) {
            buf.writeVarInt(m.requestId);
            buf.writeVarInt(m.results.size());
            for (ChainAnswer answer : m.results) ChainAnswer.encode(answer, buf);
        }

        public static CraftChainResult decode(FriendlyByteBuf buf) {
            int requestId = buf.readVarInt();
            int n = buf.readVarInt();
            if (n < 0 || n > MAX_STEPS) return new CraftChainResult(requestId, List.of());
            List<ChainAnswer> results = new ArrayList<>(n);
            for (int i = 0; i < n; i++) results.add(ChainAnswer.decode(buf));
            return new CraftChainResult(requestId, results);
        }
    }

    private Craft() {}
}

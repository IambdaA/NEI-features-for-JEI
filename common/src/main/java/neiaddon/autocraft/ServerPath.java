package neiaddon.autocraft;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import neiaddon.Craft;
import neiaddon.CraftSlots;
import neiaddon.FluidStacks;
import neiaddon.NeiAddon;
import neiaddon.RecipeData;
import neiaddon.Tools;
import neiaddon.chain.ChainItem;
import neiaddon.chain.ChainPredict;
import neiaddon.platform.ClientPlatform;
import neiaddon.platform.Platform;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;

import static neiaddon.autocraft.Counting.*;
import static neiaddon.autocraft.Run.*;
import static neiaddon.autocraft.Visits.*;

/**
 * The path where the server crafts by itself: one recipe's crafts per request, or the run's whole chain in requests of
 * many steps ({@link #chainStep}). The answers are counted here; a chain answer is replayed on the walker.
 */
public final class ServerPath {

    /** Most pieces and crafts one run's prediction keeps. Past them the walker goes on one recipe at a time. */
    static final int CHAIN_MAX_PIECES = 1024;
    static final long CHAIN_MAX_CRAFTS = 16384;

    /**
     * The chat line for a run that cannot go on by clicks because a crafting upgrade refills its grid from the
     * storage. The start's refusal and every fallback to clicks use it, so it names no particular cause.
     */
    static final String REFILL_CHAT = "§6[NEI Features] §eAutocrafting can't go on by clicks while the crafting "
        + "upgrade refills its grid from the storage. Turn off the upgrade's refill option to craft.";

    private static boolean chainWarned;

    /** The game side of the prediction: vanilla recipes at the open grid, tools, and what one use leaves behind. */
    static final ChainPredict.Game GAME = new ChainPredict.Game() {

        @Override
        public boolean serverCraftable(String recipeId) {
            return vanillaCrafting(Minecraft.getInstance(), recipeId) != null;
        }

        @Override
        public boolean isTool(String item) {
            return Tools.isTool(FluidStacks.stackOf(item));
        }

        @Override
        public String remainderOf(String item) {
            ItemStack stack = FluidStacks.stackOf(item);
            if (stack.isEmpty()) return null;
            ItemStack left = Platform.get().craftingRemainder(stack);
            return left.isEmpty() ? null : RecipeData.idOf(left);
        }
    };

    /**
     * The loader's receiver hands the server's answer to a craft request over (on the client thread); the tick reads it
     * in {@link Phase#SERVER_CRAFT}.
     */
    public static void serverCraftResult(Craft.CraftResult result) {
        craftAnswer = result;
    }

    /** The same for a chain request; the tick reads it in {@link Phase#SERVER_CHAIN}. */
    public static void serverChainResult(Craft.CraftChainResult result) {
        chainAnswer = result;
    }

    /** The items the chain chose for the recipe's ingredients (tag choices, tool picks), as ids the server reads. */
    static List<ResourceLocation> preferredFor(String recipeId) {
        List<ResourceLocation> preferred = new ArrayList<>();
        for (ChainItem ingredient : math.recipeIngredients) {
            if (!recipeId.equals(ingredient.recipeId)) continue;
            ResourceLocation id = ResourceLocation.tryParse(plainId(ingredient.item));
            if (id != null && BuiltInRegistries.ITEM.containsKey(id) && !preferred.contains(id)) preferred.add(id);
        }
        return preferred;
    }

    /**
     * Asks the server to craft the visit's crafts of this recipe (at most 64 at once; the walker offers the rest
     * next) and waits for the answer in {@link Phase#SERVER_CRAFT}. {@code preferred} are the items the chain chose
     * for the recipe's ingredients, so tag choices and tool picks are kept.
     */
    static void sendServerCraft(String recipeId, CraftingRecipe vanilla) {
        List<ResourceLocation> preferred = preferredFor(recipeId);
        current = recipeId;
        // Autocrafting Speed: at most what this tick may still ask for (the pick waited for a tick with budget left)
        int times = (int) Math.max(1, Math.min(Math.min(walker.remaining(), Craft.MAX_TIMES),
            Math.max(1, Speed.allowed(Craft.MAX_TIMES))));
        Speed.spend(times);
        craftBusyTries = 0;
        craftBusyWait = 0;
        craftSent = new Craft.CraftRequest(++craftRequestId, menu.containerId, vanilla.getId(), times, preferred);
        craftPending = craftSent.requestId();
        craftAnswer = null;
        craftWaited = 0;
        sendCount++;
        ClientPlatform.get().sendToServer(craftSent);
        NeiAddon.LOGGER.info("Autocrafting: asked the server for {} crafts of {}", times, recipeId);
        phase = Phase.SERVER_CRAFT;
    }

    /** Waits for the server's answer to the request and goes on by its reason. */
    static void serverCraftStep(Minecraft mc) {
        if (craftBusyWait > 0) {
            if (--craftBusyWait == 0) {
                // the server said it was busy: the same request again, under a new id
                craftSent = new Craft.CraftRequest(++craftRequestId, craftSent.containerId(), craftSent.recipe(),
                    craftSent.times(), craftSent.preferred());
                Speed.spend(craftSent.times());
                craftPending = craftSent.requestId();
                craftAnswer = null;
                craftWaited = 0;
                sendCount++;
                ClientPlatform.get().sendToServer(craftSent);
            }
            return;
        }
        Craft.CraftResult answer = craftAnswer;
        if (answer == null || answer.requestId() != craftPending) {
            if (++craftWaited > SERVER_CRAFT_TIMEOUT) {
                NeiAddon.LOGGER.info(
                    "Autocrafting: no answer to the craft request of {}, crafting by clicks from now on", current);
                craftPending = 0;
                clicksAfterServer(mc);
            }
            return;
        }
        craftAnswer = null;
        craftPending = 0;
        Craft.Reason reason = answer.reason();
        if (reason == Craft.Reason.BUSY) {
            if (++craftBusyTries <= SERVER_CRAFT_BUSY_TRIES) {
                craftBusyWait = SERVER_CRAFT_BUSY_WAIT;
                return;
            }
            reason = Craft.Reason.ERROR;
        }
        craftBusyTries = 0;
        answered(mc, answer.crafted(), reason, answer.missing());
    }

    /**
     * Credits what the server made of {@link Run#current} and goes on by the reason it stopped at. The same for a
     * one-recipe answer and for each step of a chain answer.
     */
    static void answered(Minecraft mc, int crafted, Craft.Reason reason, ResourceLocation missing) {
        if (crafted > 0) noteCrafted(crafted);
        NeiAddon.LOGGER.info("Autocrafting count: {} crafted {} on the server ({}{}), {} left", current,
            crafted, reason, missing == null ? "" : ", " + missing, walker.remaining());
        switch (reason) {
            case DONE -> phase = Phase.NEXT;
            case MISSING_INGREDIENT, RESULT_CHANGED -> {
                boolean tool;
                if (missing != null && BuiltInRegistries.ITEM.containsKey(missing)) {
                    tool = Tools.isTool(new ItemStack(BuiltInRegistries.ITEM.get(missing)));
                } else {
                    tool = reason == Craft.Reason.RESULT_CHANGED && recipeHasTool(current);
                }
                String name = missing == null ? "ingredient"
                    : FluidStacks.name(FluidStacks.stackOf(missing.toString())).getString();
                String why = reason == Craft.Reason.RESULT_CHANGED ? "the result changed" : "no " + name;
                if (!deferVisit(current, why)) {
                    // the same chat line the click path gives when a worn-out tool ends a recipe
                    if (tool && givenUp.add(current) && mc.player != null) mc.player.sendSystemMessage(
                        net.minecraft.network.chat.Component.literal("§6[NEI Features] §eAutocrafting gave up on "
                            + recipeName(current) + " for now; the rest of the chain goes on."));
                    abandonVisit(current, why);
                }
                phase = Phase.NEXT;
            }
            case INVENTORY_FULL -> fail("the inventory has no room left for the result of " + recipeName(current)
                + (ae2 ? ", and the AE2 network didn't take it either" : ""));
            default -> {
                NeiAddon.LOGGER.info(
                    "Autocrafting: the server could not craft {} ({}), clicking from now on", current, reason);
                clicksAfterServer(mc);
            }
        }
    }

    /**
     * Goes on by clicks after the server could not craft. One sync comes first: the click path fills from this side's
     * view, so the server's screen is fetched before the next fill. Clicks cannot count the crafts of an upgrade that
     * refills its grid from the storage, so there the run ends after that sync instead, with REFILL_CHAT (the cleanup
     * puts the grid back).
     */
    static void clicksAfterServer(Minecraft mc) {
        serverCraft = false;
        awaiting = false;
        waited = 0;
        if (CraftSlots.refillsGrid(menu)) {
            NeiAddon.LOGGER.info("Autocrafting: clicks cannot count the crafts of a refilling upgrade, stopping");
            if (mc.player != null) {
                mc.player.sendSystemMessage(net.minecraft.network.chat.Component.literal(REFILL_CHAT));
            }
            cleaned.clear();
            resumeAfterSync = Phase.CLEANUP;
        } else {
            resumeAfterSync = Phase.NEXT;
        }
        phase = Phase.SYNC;
    }

    /**
     * The chain of the run, on the server path. The first pick of a run predicts the whole chain (see
     * {@link #planChain}); each pick while pieces are left sends the next request. Returns true when a request went
     * out, which ends the tick. Once the chain is answered or dropped, the walker's own picks go on, one recipe at a
     * time.
     */
    static boolean chainStep() {
        if (chainPlan == null) chainPlan = planChain();
        if (chainAt >= chainPlan.size()) return false;
        // Autocrafting Speed lowered during the run: a piece planned for the old setting is bigger than the new one,
        // so the rest of the plan is dropped and the walker's own picks (sized to the setting) go on
        int limit = Speed.limit();
        if (limit > 0 && chainPlan.get(chainAt).times() > limit) {
            NeiAddon.LOGGER.info("Autocrafting: the speed setting is below the planned chain's steps, the rest goes "
                + "one recipe per request");
            dropChain();
            return false;
        }
        // the next request starts with a whole piece, which waits for a tick that has room for it
        if (!Speed.fits(chainPlan.get(chainAt).times())) return true;
        sendChain();
        return true;
    }

    /**
     * Predicts the run's chain from the live inventory (see {@link ChainPredict}): the planner and the replay host both
     * start from those counts, as the walker and the planner were built from the same inventory. Without the chain
     * channel the run plans nothing. A failure logs one warning and plans nothing.
     */
    static List<ChainPiece> planChain() {
        if (!serverCraft || !ClientPlatform.get().canCraftChainOnServer()) {
            NeiAddon.LOGGER.info("Autocrafting: the server takes one recipe per request (no chain channel)");
            return List.of();
        }
        try {
            Minecraft mc = Minecraft.getInstance();
            Map<String, Long> live = Runner.inventory();
            ChainPredict.SimHost sim = new ChainPredict.SimHost(math, GAME, live);
            // Autocrafting Speed: a piece is one request step, so under a limit no piece asks for more than the limit
            int limit = Speed.limit();
            int pieceSize = limit > 0 ? Math.min(Craft.MAX_TIMES, limit) : Craft.MAX_TIMES;
            ChainPredict.Plan plan = ChainPredict.plan(planner, sim, pieceSize, CHAIN_MAX_PIECES,
                CHAIN_MAX_CRAFTS);
            chainReplayHost = new ChainPredict.SimHost(math, GAME, live);
            List<ChainPiece> pieces = new ArrayList<>();
            long crafts = 0;
            for (ChainPredict.Piece piece : plan.pieces()) {
                CraftingRecipe vanilla = vanillaCrafting(mc, piece.recipeId());
                // the planner stopped before any recipe without a vanilla recipe; a missing one ends the chain here
                if (vanilla == null) break;
                pieces.add(new ChainPiece(piece.recipeId(), vanilla.getId(), (int) piece.crafts(),
                    preferredFor(piece.recipeId())));
                crafts += piece.crafts();
            }
            String end = plan.endOfChain() ? "to the end of the chain"
                : "up to a limit or a recipe the server cannot craft";
            NeiAddon.LOGGER.info("Autocrafting: the chain is planned for the server, {} steps and {} crafts ({})",
                pieces.size(), crafts, end);
            return pieces;
        } catch (RuntimeException | LinkageError e) {
            if (!chainWarned) {
                chainWarned = true;
                NeiAddon.LOGGER.warn("Could not plan the chain for the server, crafting one recipe per request", e);
            }
            return List.of();
        }
    }

    /** Sends the next chain request: the pieces from {@link Run#chainAt} on, up to the request's limits. */
    static void sendChain() {
        List<ChainPiece> sent = new ArrayList<>();
        long crafts = 0;
        // Autocrafting Speed: no more crafts in one request than this tick may ask for, but always the first piece
        long room = Math.min(Craft.MAX_CHAIN_CRAFTS, Speed.allowed(Craft.MAX_CHAIN_CRAFTS));
        for (int i = chainAt; i < chainPlan.size() && sent.size() < Craft.MAX_STEPS; i++) {
            ChainPiece piece = chainPlan.get(i);
            if (!sent.isEmpty() && crafts + piece.times() > room) break;
            if (crafts + piece.times() > Craft.MAX_CHAIN_CRAFTS) break;
            sent.add(piece);
            crafts += piece.times();
        }
        List<Craft.ChainStep> steps = new ArrayList<>(sent.size());
        for (ChainPiece piece : sent) steps.add(new Craft.ChainStep(piece.vanilla(), piece.times(), piece.preferred()));
        chainSent = List.copyOf(sent);
        Speed.spend(crafts);
        craftBusyTries = 0;
        craftBusyWait = 0;
        chainRequest = new Craft.CraftChainRequest(++craftRequestId, menu.containerId, steps);
        craftPending = chainRequest.requestId();
        chainAnswer = null;
        craftWaited = 0;
        sendCount++;
        ClientPlatform.get().sendToServer(chainRequest);
        NeiAddon.LOGGER.info("Autocrafting: asked the server for {} crafts in {} steps", crafts, sent.size());
        phase = Phase.SERVER_CHAIN;
    }

    /** Waits for the chain request's answer (sending it again when the server is busy), then replays it. */
    static void serverChainStep(Minecraft mc) {
        if (craftBusyWait > 0) {
            if (--craftBusyWait == 0) {
                // the server said it was busy: the same request again, under a new id
                chainRequest = new Craft.CraftChainRequest(++craftRequestId, chainRequest.containerId(),
                    chainRequest.steps());
                Speed.spend(chainSent.stream().mapToLong(ChainPiece::times).sum());
                craftPending = chainRequest.requestId();
                chainAnswer = null;
                craftWaited = 0;
                sendCount++;
                ClientPlatform.get().sendToServer(chainRequest);
            }
            return;
        }
        Craft.CraftChainResult answer = chainAnswer;
        if (answer == null || answer.requestId() != craftPending) {
            if (++craftWaited > SERVER_CRAFT_TIMEOUT) {
                NeiAddon.LOGGER.info("Autocrafting: no answer to the chain request, crafting by clicks from now on");
                giveUpChain(mc);
            }
            return;
        }
        chainAnswer = null;
        craftPending = 0;
        List<Craft.ChainAnswer> results = answer.results();
        if (results.size() == 1 && results.get(0).reason() == Craft.Reason.BUSY) {
            if (++craftBusyTries <= SERVER_CRAFT_BUSY_TRIES) {
                craftBusyWait = SERVER_CRAFT_BUSY_WAIT;
                return;
            }
            NeiAddon.LOGGER.info("Autocrafting: the server stayed busy with the chain, crafting by clicks from now on");
            giveUpChain(mc);
            return;
        }
        craftBusyTries = 0;
        if (!wellFormed(results)) {
            NeiAddon.LOGGER.info(
                "Autocrafting: the server's chain answer does not match the request, crafting by clicks from now on");
            giveUpChain(mc);
            return;
        }
        replayChain(mc, results);
    }

    /**
     * Whether the answer fits the request: at least one entry, no more than were sent, and every step but the last is
     * DONE. When the last one is DONE the answer may be shorter than the request (the server's time limit stopped it).
     */
    static boolean wellFormed(List<Craft.ChainAnswer> results) {
        if (results.isEmpty() || results.size() > chainSent.size()) return false;
        int last = results.size() - 1;
        for (int i = 0; i < last; i++) {
            if (results.get(i).reason() != Craft.Reason.DONE) return false;
        }
        return true;
    }

    /**
     * Replays the server's answer on the walker. For each step the server ran, the walker (on
     * {@link Run#chainReplayHost}, which holds the predicted counts up to that step) hands out the recipe, and it is
     * credited the crafts the server made. It must hand out the piece that was sent, or the run stops with a chat
     * line. A step the server did not finish is handled as a one-recipe answer is ({@link #answered}), on the real
     * host; the pieces after it are dropped. When every step was DONE, the walker is back on the real host and its
     * next pick (or the next request) follows.
     */
    static void replayChain(Minecraft mc, List<Craft.ChainAnswer> results) {
        walker.setHost(chainReplayHost);
        try {
            for (int i = 0; i < results.size(); i++) {
                ChainPiece piece = chainSent.get(i);
                Craft.ChainAnswer answer = results.get(i);
                String recipeId = walker.next();
                if (!piece.recipe().equals(recipeId) || answer.crafted() < 0) {
                    walker.setHost(liveHost);
                    current = piece.recipe();
                    dropChain();
                    fail("the server's answer did not match the plan");
                    return;
                }
                // the server never crafts more than it was asked; a larger count is credited as the piece's size
                int crafted = Math.min(answer.crafted(), piece.times());
                Runner.dumpPlanOnce();
                current = recipeId;
                NeiAddon.LOGGER.info("Autocrafting visit: {} still wants {} crafts", current, walker.remaining());
                chainReplayHost.craft(recipeId, crafted);
                boolean stopped = answer.reason() != Craft.Reason.DONE;
                if (stopped) walker.setHost(liveHost);
                answered(mc, crafted, answer.reason(), answer.missing());
                if (stopped) {
                    dropChain();
                    return;
                }
            }
            // every step was DONE: the pieces are answered; the next pick is the next request, or the walker's own.
            // A short answer (the server's time limit) leaves the rest for the next request.
            if (results.size() < chainSent.size()) {
                NeiAddon.LOGGER.info("Autocrafting: the server ran {} of {} steps this tick (its time limit), "
                    + "the rest goes in the next request", results.size(), chainSent.size());
            }
            chainAt += results.size();
            chainSent = List.of();
            chainRequest = null;
        } finally {
            walker.setHost(liveHost);
        }
    }

    /**
     * The chain is over for this run: the pieces not yet sent are dropped, and the run goes on by clicks from the
     * server's screen (the one-recipe fallback, see {@link #serverCraftStep}).
     */
    static void giveUpChain(Minecraft mc) {
        dropChain();
        craftPending = 0;
        clicksAfterServer(mc);
    }

    /** Drops the chain's pieces the server has not answered: the walker's own picks go on one recipe at a time. */
    static void dropChain() {
        chainAt = chainPlan == null ? 0 : chainPlan.size();
        chainSent = List.of();
        chainRequest = null;
        chainAnswer = null;
    }

    /** Clears the chain state at the start and the end of a run. */
    static void resetChain() {
        chainPlan = null;
        chainAt = 0;
        chainSent = List.of();
        chainRequest = null;
        chainAnswer = null;
        chainReplayHost = null;
        planner = null;
        liveHost = null;
    }

    /** Whether one of the chain's ingredients of this recipe is a tool (it wears instead of being used up). */
    static boolean recipeHasTool(String recipeId) {
        for (ChainItem ingredient : math.recipeIngredients) {
            if (recipeId.equals(ingredient.recipeId) && Tools.isTool(FluidStacks.stackOf(ingredient.item))) return true;
        }
        return false;
    }
}

/*
 * Recipe chain maths for the NEI addon for JEI.
 *
 * Predicts the crafts the walker hands out (see ChainAutocraft), so the server can craft a chain in one request. Plain
 * Java: no Minecraft classes (tools/AutoCraftCheck.java checks the walk). Licensed under the GNU Lesser General Public
 * License, version 3 or (at your option) any later version.
 */
package neiaddon.chain;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The client's guess at a server-path run: the crafts the walker would hand out, if every craft worked as predicted.
 * The walk runs a {@link ChainAutocraft} against a {@link SimHost}, whose item counts follow each predicted craft. The
 * game side walks the same pieces again after the server's answer (a replay, with a second SimHost that gets the crafts
 * the server really made), so the real walker sees the same answers in the same order.
 */
public final class ChainPredict {

    /** The game-side facts the prediction needs, so the walk stays plain Java. */
    public interface Game {

        /** Whether the recipe is a vanilla crafting recipe at the open grid, so the server can craft it. */
        boolean serverCraftable(String recipeId);

        /** Whether the item is a tool: it wears instead of being used up. */
        boolean isTool(String item);

        /** The item one use of this item leaves behind (an empty bucket from a milk bucket), or null. */
        String remainderOf(String item);
    }

    /** {@code crafts} crafts of {@code recipeId} in a row, as the walker hands them out (one request step at most). */
    public record Piece(String recipeId, long crafts) {}

    /**
     * The walk: the pieces in order, and whether it reached the end of the chain. False when it stopped at a recipe the
     * server cannot craft, or at a cap.
     */
    public record Plan(List<Piece> pieces, boolean endOfChain) {}

    /**
     * A simulated host: the inventory is a map of predicted counts. {@link #craft} moves the counts the way a craft
     * does (ingredients out, leftovers and results in), and {@link #canCraft} answers from the counts.
     */
    public static final class SimHost implements ChainAutocraft.Host {

        private final ChainMath math;
        private final Game game;
        private final Map<String, Long> counts = new HashMap<>();

        public SimHost(ChainMath math, Game game, Map<String, Long> start) {
            this.math = math;
            this.game = game;
            this.counts.putAll(start);
        }

        @Override
        public Map<String, Long> inventory() {
            return new HashMap<>(counts);
        }

        /**
         * Every ingredient of one craft is in the predicted counts: the chain's own amount per craft, for the item the
         * chain chose. A tool has to be there, once per ingredient. A recipe the server cannot craft is answered the
         * same way; when the walk hands one out, {@link #plan} stops before it.
         */
        @Override
        public boolean canCraft(String recipeId) {
            Map<String, Long> need = new HashMap<>();
            for (ChainItem ingredient : math.recipeIngredients) {
                if (!recipeId.equals(ingredient.recipeId) || ingredient.emptyFactor()) continue;
                need.merge(ingredient.item, game.isTool(ingredient.item) ? 1L : ingredient.factor, Long::sum);
            }
            for (Map.Entry<String, Long> entry : need.entrySet()) {
                if (counts.getOrDefault(entry.getKey(), 0L) < entry.getValue()) return false;
            }
            return true;
        }

        /** Whether the game side can craft the recipe on the server at all. */
        public boolean serverCraftable(String recipeId) {
            return game.serverCraftable(recipeId);
        }

        /**
         * Applies {@code n} crafts of the recipe: the used ingredients leave the counts (tools stay), each one's
         * leftover comes back, and the results go in.
         */
        public void craft(String recipeId, long n) {
            if (n <= 0) return;
            for (ChainItem ingredient : math.recipeIngredients) {
                if (!recipeId.equals(ingredient.recipeId) || ingredient.emptyFactor()) continue;
                if (game.isTool(ingredient.item)) continue;
                long used = ingredient.factor * n;
                counts.merge(ingredient.item, -used, Long::sum);
                String leftover = game.remainderOf(ingredient.item);
                if (leftover != null) counts.merge(leftover, used, Long::sum);
            }
            for (ChainItem result : math.recipeResults) {
                if (recipeId.equals(result.recipeId) && !result.emptyFactor()) {
                    counts.merge(result.item, result.factor * n, Long::sum);
                }
            }
            counts.values().removeIf(count -> count <= 0);
        }
    }

    private ChainPredict() {}

    /**
     * Walks {@code planner} against {@code sim} and returns the pieces it hands out. The walk ends when the chain ends
     * (next() is null), at the first recipe the server cannot craft (that one is left out), or at one of the caps:
     * {@code maxPiece} crafts per piece, {@code maxPieces} pieces, {@code maxCrafts} crafts in all. The planner is not
     * used afterwards. It must be built the way the run's walker is built (same math, same inventory at construction).
     */
    public static Plan plan(ChainAutocraft planner, SimHost sim, long maxPiece, int maxPieces, long maxCrafts) {
        planner.setHost(sim);
        List<Piece> pieces = new ArrayList<>();
        long crafts = 0;
        while (true) {
            String recipeId = planner.next();
            if (recipeId == null) return new Plan(pieces, true);
            if (!sim.serverCraftable(recipeId)) return new Plan(pieces, false);
            long n = Math.min(planner.remaining(), maxPiece);
            if (n <= 0 || pieces.size() >= maxPieces || crafts + n > maxCrafts) return new Plan(pieces, false);
            pieces.add(new Piece(recipeId, n));
            crafts += n;
            sim.craft(recipeId, n);
            planner.crafted(n);
        }
    }
}

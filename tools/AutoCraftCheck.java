import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import neiaddon.chain.ChainAutocraft;
import neiaddon.chain.ChainItem;
import neiaddon.chain.ChainItem.Type;
import neiaddon.chain.ChainMath;
import neiaddon.chain.ChainPredict;
import neiaddon.chain.CraftCount;

/**
 * Simulates autocrafting without Minecraft: a fake inventory and fake recipes, driven the way the game side drives
 * {@link ChainAutocraft}.
 *
 * <pre>
 * javac -d /tmp/acheck common/src/main/java/neiaddon/chain/*.java tools/AutoCraftCheck.java
 * java -cp /tmp/acheck AutoCraftCheck
 * </pre>
 */
public class AutoCraftCheck {

    static int failures;

    record Recipe(Map<String, Long> in, Map<String, Long> out) {}

    static final Map<String, Recipe> RECIPES = new LinkedHashMap<>();

    static void recipe(String id, Map<String, Long> in, Map<String, Long> out) {
        RECIPES.put(id, new Recipe(in, out));
    }

    static ChainItem item(String name, String recipe, Type type, long factor, long multiplier) {
        return new ChainItem(name, recipe, type, factor, multiplier, Set.of(name), null);
    }

    static List<ChainItem> chain(long torchRuns) {
        List<ChainItem> g = new ArrayList<>();
        g.add(item("torch", "T", Type.RESULT, 4, torchRuns));
        g.add(item("coal", "T", Type.INGREDIENT, 1, torchRuns));
        g.add(item("stick", "T", Type.INGREDIENT, 1, torchRuns));
        g.add(item("stick", "S", Type.RESULT, 4, 1));
        g.add(item("plank", "S", Type.INGREDIENT, 2, 1));
        g.add(item("plank", "P", Type.RESULT, 4, 1));
        g.add(item("log", "P", Type.INGREDIENT, 1, 1));
        return g;
    }

    static void check(String what, boolean ok) {
        System.out.println((ok ? "ok   " : "FAIL ") + what);
        if (!ok) failures++;
    }

    /** Runs the walker against the fake world; returns the recipe ids crafted in order. */
    static List<String> run(ChainMath math, Map<String, Long> inv) {
        ChainAutocraft.Host host = new ChainAutocraft.Host() {

            public Map<String, Long> inventory() {
                return new HashMap<>(inv);
            }

            public boolean canCraft(String id) {
                Recipe r = RECIPES.get(id);
                if (r == null) return false;
                for (var e : r.in().entrySet()) if (inv.getOrDefault(e.getKey(), 0L) < e.getValue()) return false;
                return true;
            }
        };
        ChainAutocraft ac = new ChainAutocraft(math, host);
        List<String> log = new ArrayList<>();
        String id;
        int guard = 0;
        while ((id = ac.next()) != null) {
            if (++guard > 1000) throw new IllegalStateException("runaway");
            Recipe r = RECIPES.get(id);
            r.in().forEach((k, v) -> inv.merge(k, -v, Long::sum));
            r.out().forEach((k, v) -> inv.merge(k, v, Long::sum));
            log.add(id);
            ac.crafted();
        }
        return log;
    }

    /**
     * One recipe's batch loop, modelled the way the game side of {@code AutoCraft} drives it (#46). The
     * walker wants {@code wanted} crafts. In every pass JEI's transfer fills the grid, and this side's grid shows
     * {@code clientSets} while the server's grid really holds {@code serverSets}; the one shift-click on the result slot
     * then crafts every set the <em>server's</em> grid holds, while the crafts the walker is told about are counted from
     * the snapshot taken of the grid before the click.
     *
     * <p>
     * Pre-change ({@code fixed} false): that snapshot is this side's guess of the grid, and the count is floored at 1
     * (the old {@code AutoCraft.craftsDone()} rule, written out here on purpose: it documents the bug, so it is NOT
     * {@link CraftCount#single(long)} and does not follow a change to it).
     * <p>
     * With the fix: the screen is fetched from the server before the first craft of the batch, so the sets, the snapshot
     * and the count all come from the server's own grid ({@link CraftCount#batch(long)} of the slot drop, exact: nothing
     * crafted counts as 0); a grid confirmed bigger than what is still wanted is trimmed to one set at a time, the path
     * {@code AutoCraft} already guards. A batch that counts 0 is retried until {@link CraftCount#giveUp(int)} says stop.
     *
     * @return {what the inventory got, what the walker was told, passes}
     */
    static long[] batchLoop(long wanted, long clientSets, long serverSets, boolean fixed) {
        long remaining = wanted, produced = 0, counted = 0;
        int passes = 0, zeroTries = 0;
        while (remaining > 0 && ++passes < 100) {
            if (!fixed) {
                produced += serverSets;                       // vanilla crafts the server's whole grid
                long done = Math.max(1, clientSets);           // the pre-change floor (documents the bug, see above)
                counted += done;
                remaining -= done;
                continue;
            }
            long made = Math.min(serverSets, remaining);      // the confirmed grid, trimmed to what is still wanted
            long done = CraftCount.batch(CraftCount.slotDrop(serverSets, true, serverSets - made));
            if (done == 0) {
                // nothing on the server: the exact count drains nothing, so the recipe is synced once and then given up
                // on (AutoCraft's batch zero-tries guard, CraftCount.giveUp) instead of filling the grid forever
                if (CraftCount.giveUp(++zeroTries)) break;
                continue;
            }
            produced += made;
            counted += done;
            remaining -= done;
        }
        return new long[] {produced, counted, passes};
    }

    /** The walker's host over a world map: the map is the inventory, and a recipe is there when its inputs are. */
    static ChainAutocraft.Host worldHost(Map<String, Long> world) {
        return new ChainAutocraft.Host() {

            public Map<String, Long> inventory() {
                return new HashMap<>(world);
            }

            public boolean canCraft(String id) {
                Recipe r = RECIPES.get(id);
                if (r == null) return false;
                for (var e : r.in().entrySet()) if (world.getOrDefault(e.getKey(), 0L) < e.getValue()) return false;
                return true;
            }
        };
    }

    /** {@code n} crafts of the recipe applied to the world: inputs go, outputs come, empty counts are dropped. */
    static void craftWorld(Map<String, Long> world, String id, long n) {
        Recipe r = RECIPES.get(id);
        r.in().forEach((k, v) -> world.merge(k, -v * n, Long::sum));
        r.out().forEach((k, v) -> world.merge(k, v * n, Long::sum));
        world.values().removeIf(v -> v <= 0);
    }

    /** The prediction's game side over the fake recipes: no tools, no leftovers; {@code notVanilla} is not served. */
    static ChainPredict.Game fakeGame(Set<String> notVanilla) {
        return new ChainPredict.Game() {

            public boolean serverCraftable(String id) {
                return RECIPES.containsKey(id) && !notVanilla.contains(id);
            }

            public boolean isTool(String item) {
                return false;
            }

            public String remainderOf(String item) {
                return null;
            }
        };
    }

    /** One run's record: the steps as text, the world at the end, and what the walker still wanted. */
    record Outcome(List<String> steps, Map<String, Long> world, Map<String, Long> wanted) {}

    static String stepText(String id, long asked, long made) {
        return id + " x" + asked + (made < asked ? " made " + made : "");
    }

    static List<String> pieceStrings(List<ChainPredict.Piece> pieces) {
        List<String> out = new ArrayList<>();
        for (ChainPredict.Piece p : pieces) out.add(p.recipeId() + " x" + p.crafts());
        return out;
    }

    /** A fresh chain: the walker changes the maths it is given, so every walk gets its own. */
    static ChainMath freshMath() {
        ChainMath m = ChainMath.of(chain(2));
        m.createMasterRoot();
        return m;
    }

    static Map<String, Long> chainStart() {
        return new HashMap<>(Map.of("log", 1L, "coal", 2L));
    }

    /**
     * The walker's own picks against the world, to the end: each step asks for min(remaining, 64) and the world gets
     * all of it.
     */
    static void finish(ChainAutocraft walker, Map<String, Long> world, List<String> steps) {
        String id;
        while ((id = walker.next()) != null) {
            if (steps.size() > 1000) throw new IllegalStateException("runaway");
            long asked = Math.min(walker.remaining(), 64);
            steps.add(stepText(id, asked, asked));
            craftWorld(world, id, asked);
            walker.crafted(asked);
        }
    }

    /**
     * The reference: the walker step by step against the world. Step i makes {@code counts.get(i)} crafts when the map
     * holds it, else all it asked for. The first step that makes less stops the visit: deferred when {@code defer},
     * else given up (the game side's handling of a stopped one-recipe answer). A zero is not credited, as in the game.
     */
    static Outcome stepwise(ChainMath math, Map<String, Long> start, Map<Integer, Long> counts, boolean defer) {
        Map<String, Long> world = new HashMap<>(start);
        ChainAutocraft walker = new ChainAutocraft(math, worldHost(world));
        List<String> steps = new ArrayList<>();
        boolean stopped = false;
        int index = 0;
        String id;
        while ((id = walker.next()) != null) {
            if (++index > 1000) throw new IllegalStateException("runaway");
            long asked = Math.min(walker.remaining(), 64);
            long made = stopped ? asked : counts.getOrDefault(index - 1, asked);
            steps.add(stepText(id, asked, made));
            craftWorld(world, id, made);
            if (made > 0) walker.crafted(made);
            if (made < asked) {
                stopped = true;
                if (defer) walker.defer();
                else walker.abandon();
            }
        }
        return new Outcome(steps, world, walker.stillWanted());
    }

    /**
     * The server path: the server runs the planned pieces in order on its own world and stops at the first one it did
     * not finish (counts as in {@link #stepwise}). The client replays the answer on the walker, which hands out the
     * pieces on a replay host that starts from the same counts, and then goes on against the server's world.
     */
    static Outcome replayed(ChainMath math, Map<String, Long> start, List<ChainPredict.Piece> pieces,
        Map<Integer, Long> counts, boolean defer) {
        Map<String, Long> server = new HashMap<>(start);
        List<Long> answers = new ArrayList<>();
        for (int i = 0; i < pieces.size(); i++) {
            ChainPredict.Piece piece = pieces.get(i);
            long made = counts.getOrDefault(i, piece.crafts());
            craftWorld(server, piece.recipeId(), made);
            answers.add(made);
            if (made < piece.crafts()) break;
        }
        ChainAutocraft walker = new ChainAutocraft(math, worldHost(new HashMap<>(start)));
        ChainPredict.SimHost replay = new ChainPredict.SimHost(math, fakeGame(Set.of()), start);
        walker.setHost(replay);
        ChainAutocraft.Host live = worldHost(server);
        List<String> steps = new ArrayList<>();
        for (int i = 0; i < answers.size(); i++) {
            String id = walker.next();
            if (!pieces.get(i).recipeId().equals(id)) {
                throw new IllegalStateException("replay handed " + id + ", planned " + pieces.get(i).recipeId());
            }
            long asked = Math.min(walker.remaining(), 64);
            long made = answers.get(i);
            steps.add(stepText(id, asked, made));
            if (made > 0) walker.crafted(made);
            replay.craft(id, made);
            if (made < asked) {
                walker.setHost(live);
                if (defer) walker.defer();
                else walker.abandon();
                break;
            }
        }
        walker.setHost(live);
        finish(walker, server, steps);
        return new Outcome(steps, server, walker.stillWanted());
    }

    static void chainChecks() {
        System.out.println("--- chain requests: prediction and replay");
        Map<String, Long> start = chainStart();
        Outcome reference = stepwise(freshMath(), start, Map.of(), false);
        System.out.println("step by step: " + reference.steps());

        // the prediction: the walker and its planner are built from the same inventory, before either walks
        ChainMath math = freshMath();
        ChainAutocraft walker = new ChainAutocraft(math, worldHost(new HashMap<>(start)));
        ChainAutocraft planner = new ChainAutocraft(math, worldHost(new HashMap<>(start)));
        ChainPredict.SimHost sim = new ChainPredict.SimHost(math, fakeGame(Set.of()), start);
        ChainPredict.Plan plan = ChainPredict.plan(planner, sim, 64, 1024, 16384);
        List<String> predicted = pieceStrings(plan.pieces());
        check("prediction: the chain ends inside the plan", plan.endOfChain());
        check("prediction: the same (recipe, crafts) sequence as the step-by-step walk " + predicted,
            predicted.equals(reference.steps()));
        check("prediction: the predicted counts end where the crafts leave the world",
            sim.inventory().equals(reference.world()));
        check("prediction: the real walker has not moved (nothing handed out, nothing crafted)",
            walker.remaining() == 0 && !walker.processed());

        // a replay where every step is DONE is the step-by-step walk again
        check("replay, every step DONE: same as the step-by-step walk",
            replayed(freshMath(), start, plan.pieces(), Map.of(), false).equals(reference));

        // the server makes 0 of the second step (MISSING): the replay gives the visit up, like a one-recipe answer
        Map<Integer, Long> partial = Map.of(1, 0L);
        Outcome abandon = replayed(freshMath(), start, plan.pieces(), partial, false);
        check("replay, step 2 made 0 (abandoned): same walker state as the step-by-step walk with those counts "
            + abandon.steps(), abandon.equals(stepwise(freshMath(), start, partial, false)));

        // the same with the visit deferred: its recipe gets another turn later in the run
        Outcome defer = replayed(freshMath(), start, plan.pieces(), partial, true);
        check("replay, step 2 made 0 (deferred): same walker state as the step-by-step walk with those counts",
            defer.equals(stepwise(freshMath(), start, partial, true)));
        String deferredId = plan.pieces().get(1).recipeId();
        check("replay, deferred: the recipe " + deferredId + " comes back later in the run",
            defer.steps().stream().skip(2).anyMatch(s -> s.startsWith(deferredId + " x")));

        // a recipe the server cannot craft ends the prediction before it, and nothing after it is predicted
        ChainMath math2 = freshMath();
        ChainAutocraft walker2 = new ChainAutocraft(math2, worldHost(new HashMap<>(start)));
        ChainAutocraft planner2 = new ChainAutocraft(math2, worldHost(new HashMap<>(start)));
        ChainPredict.SimHost sim2 = new ChainPredict.SimHost(math2, fakeGame(Set.of("S")), start);
        ChainPredict.Plan stopped = ChainPredict.plan(planner2, sim2, 64, 1024, 16384);
        List<String> before = pieceStrings(stopped.pieces());
        check("prediction stops before a recipe the server cannot craft (plan " + before + ")",
            !stopped.endOfChain() && before.size() < reference.steps().size()
            && before.equals(reference.steps().subList(0, before.size()))
            && stopped.pieces().stream().noneMatch(p -> p.recipeId().equals("S")));

        // the caps: at most one piece, then the plan stops short
        ChainMath math3 = freshMath();
        ChainAutocraft planner3 = new ChainAutocraft(math3, worldHost(new HashMap<>(start)));
        ChainPredict.Plan capped = ChainPredict.plan(planner3,
            new ChainPredict.SimHost(math3, fakeGame(Set.of()), start), 64, 1, 16384);
        check("prediction stops at the piece cap", capped.pieces().size() == 1 && !capped.endOfChain());
    }

    public static void main(String[] args) {
        recipe("T", Map.of("coal", 1L, "stick", 1L), Map.of("torch", 4L));
        recipe("S", Map.of("plank", 2L), Map.of("stick", 4L));
        recipe("P", Map.of("log", 1L), Map.of("plank", 4L));

        // 8 torches = 2 runs of T; needs 2 coal + 2 sticks <- 1 S <- 2 planks <- 1 P <- 1 log
        ChainMath math = ChainMath.of(chain(2));
        math.createMasterRoot();
        Map<String, Long> inv = new HashMap<>(Map.of("log", 1L, "coal", 2L));
        List<String> log = run(math, inv);
        System.out.println("crafted: " + log + " inv=" + inv);
        check("makes 8 torches", inv.getOrDefault("torch", 0L) == 8);
        check("crafted P once, S once, T twice", log.stream().filter("P"::equals).count() == 1
            && log.stream().filter("S"::equals).count() == 1 && log.stream().filter("T"::equals).count() == 2);
        check("log and coal used up", inv.getOrDefault("log", 0L) == 0 && inv.getOrDefault("coal", 0L) == 0);

        // missing coal: crafts nothing it cannot finish, never goes negative
        math = ChainMath.of(chain(2));
        math.createMasterRoot();
        inv = new HashMap<>(Map.of("log", 1L));
        log = run(math, inv);
        System.out.println("no coal -> crafted: " + log + " inv=" + inv);
        check("no torches without coal", inv.getOrDefault("torch", 0L) == 0);
        check("nothing negative", inv.values().stream().allMatch(v -> v >= 0));

        // already holds the sticks: skips S and P
        math = ChainMath.of(chain(1));
        math.createMasterRoot();
        inv = new HashMap<>(Map.of("log", 1L, "coal", 1L, "stick", 1L));
        log = run(math, inv);
        System.out.println("has sticks -> crafted: " + log + " inv=" + inv);
        check("one T only", log.equals(List.of("T")));
        check("4 torches", inv.getOrDefault("torch", 0L) == 4);

        // master root twice is harmless
        math = ChainMath.of(chain(2));
        math.createMasterRoot();
        math.createMasterRoot();
        inv = new HashMap<>(Map.of("log", 1L, "coal", 2L));
        run(math, inv);
        check("master root twice still makes 8 torches", inv.getOrDefault("torch", 0L) == 8);

        // #46: one recipe crafted as a batch (one transfer, one shift-click that crafts every set the
        // server's grid holds). JEI's/EMI's grid fill is optimistic, so this side and the server can hold different
        // grids (the reporter's log shows exactly that); the pre-change count came from this side's grid and was floored
        // at 1, so too few crafts reached the walker, "remaining" did not drain and the recipe was crafted again on the
        // next pass: the surplus. This case runs the same divergence through the pre-change rules and the fixed ones.
        System.out.println("--- #46: client/server grid divergence in a batch");
        long wanted = 10, clientSets = 4, serverSets = 7;
        long[] pre = batchLoop(wanted, clientSets, serverSets, false);
        long[] post = batchLoop(wanted, clientSets, serverSets, true);
        System.out.println("asked " + wanted + " crafts, this side sees " + clientSets + " sets, the server holds " + serverSets
            + ": pre-change crafted " + pre[0] + " / counted " + pre[1] + " / " + pre[2] + " passes"
            + "; with the fix crafted " + post[0] + " / counted " + post[1] + " / " + post[2] + " passes");
        check("#46 the pre-change rules undercount (counted " + pre[1] + " < crafted " + pre[0] + ")", pre[1] < pre[0]);
        check("#46 the pre-change rules craft more than was asked (" + pre[0] + " > " + wanted + ")", pre[0] > wanted);
        check("#46 regression: with the server-confirmed snapshot and the exact count nothing extra is crafted",
            post[0] == wanted && post[1] == wanted);
        check("#46 with the fix every craft the walker was told about happened", post[1] == post[0]);

        // the same divergence the other way: the fill never reached the server's grid at all
        long[] pre0 = batchLoop(wanted, clientSets, 0, false);
        long[] post0 = batchLoop(wanted, clientSets, 0, true);
        System.out.println("server holds 0 sets: pre-change crafted " + pre0[0] + " / counted " + pre0[1] + " / " + pre0[2]
            + " passes; with the fix crafted " + post0[0] + " / counted " + post0[1] + " / " + post0[2] + " passes");
        check("#46 the pre-change floor reported " + pre0[1] + " crafts that never happened", pre0[0] == 0 && pre0[1] > 0);
        check("#46 regression: an empty server grid counts as nothing crafted and stops after two tries",
            post0[0] == 0 && post0[1] == 0 && post0[2] == 2);

        // the counting rule itself: the grid of a batch click that crafted nothing must not count as one craft
        check("#46 pre-change: a click that crafted nothing drained 1", CraftCount.single(CraftCount.slotDrop(4, true, 4)) == 1);
        check("#46 regression: it drains nothing", CraftCount.batch(CraftCount.slotDrop(4, true, 4)) == 0);
        check("#46 a full batch of 7 is counted as 7", CraftCount.batch(CraftCount.slotDrop(7, true, 0)) == 7);
        check("#46 a slot whose item changed (bucket left behind) drops all of it", CraftCount.slotDrop(3, false, 3) == 3);
        check("#46 a grid of only tools is one craft, single and batch", CraftCount.single(Long.MAX_VALUE) == 1
            && CraftCount.batch(Long.MAX_VALUE) == 1);
        check("#46 zero-tries: one empty batch retries, the second gives up", !CraftCount.giveUp(1) && CraftCount.giveUp(2));

        chainChecks();

        System.out.println(failures == 0 ? "ALL OK" : failures + " FAILED");
        if (failures != 0) System.exit(1);
    }
}

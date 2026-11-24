import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import neiaddon.chain.ChainAutocraft;
import neiaddon.chain.ChainDetails;
import neiaddon.chain.ChainItem;
import neiaddon.chain.ChainItem.Type;
import neiaddon.chain.ChainMath;

/**
 * Regression test for the chain maths. Plain Java, no Minecraft classes:
 *
 * <pre>
 * javac -d /tmp/chaincheck common/src/main/java/neiaddon/chain/*.java tools/ChainMathCheck.java
 * java -cp /tmp/chaincheck ChainMathCheck
 * </pre>
 *
 * Exits non-zero when a number does not match.
 */
public class ChainMathCheck {

    static int failures;

    static ChainItem item(String name, String recipe, Type type, long factor, long multiplier, String... options) {
        Object payload = new Object() {

            @Override
            public String toString() {
                return recipe + ":" + name + ":" + type;
            }
        };
        return new ChainItem(name, recipe, type, factor, multiplier, Set.of(options), payload);
    }

    static List<ChainItem> sample(long railMultiplier) {
        List<ChainItem> g = new ArrayList<>();
        // rail: rail x6 <- iron x6, stick x2, torch x1
        g.add(item("rail", "rail", Type.RESULT, 6, railMultiplier));
        g.add(item("iron", "rail", Type.INGREDIENT, 6, railMultiplier));
        g.add(item("stick", "rail", Type.INGREDIENT, 2, railMultiplier));
        g.add(item("torch", "rail", Type.INGREDIENT, 1, railMultiplier));
        // smelt: iron x1 <- raw x1
        g.add(item("iron", "smelt", Type.RESULT, 1, 1));
        g.add(item("raw", "smelt", Type.INGREDIENT, 1, 1));
        // torch: torch x1 <- stick x1, redstone x1
        g.add(item("torch", "torch", Type.RESULT, 1, 1));
        g.add(item("stick", "torch", Type.INGREDIENT, 1, 1));
        g.add(item("redstone", "torch", Type.INGREDIENT, 1, 1));
        return g;
    }

    /** Recipes x (tag slot of certus or charged, 1 needed), c (charged from certus), y (1 charged) in one chain. */
    static List<ChainItem> certusChain(String... slotOptions) {
        List<ChainItem> g = new ArrayList<>();
        g.add(item("x_out", "x", Type.RESULT, 1, 1));
        g.add(item("certus", "x", Type.INGREDIENT, 1, 1, slotOptions));
        g.add(item("y_out", "y", Type.RESULT, 1, 1));
        g.add(item("charged", "y", Type.INGREDIENT, 1, 1));
        g.add(item("charged", "c", Type.RESULT, 1, 1));
        g.add(item("certus", "c", Type.INGREDIENT, 1, 1));
        return g;
    }

    static void expect(String what, long expected, long actual) {
        if (expected != actual) {
            failures++;
            System.out.println("FAIL " + what + ": expected " + expected + " got " + actual);
        } else {
            System.out.println("ok   " + what + " = " + actual);
        }
    }

    static void check(List<ChainItem> group, ChainDetails d, String recipe, String name, Type type, long expected) {
        for (ChainItem i : group) {
            if (i.recipeId.equals(recipe) && i.item.equals(name) && i.type == type) {
                ChainDetails.Calc c = d.calculated.get(i.payload);
                expect(recipe + " / " + name, expected, c.calculatedAmount());
                return;
            }
        }
        failures++;
        System.out.println("FAIL missing " + recipe + "/" + name);
    }

    static void railCase(long railMultiplier, long k) {
        System.out.println("--- rail multiplier " + railMultiplier);
        List<ChainItem> g = sample(railMultiplier);
        ChainDetails d = ChainDetails.of(g);

        if (!d.outputRecipes.equals(Set.of("rail"))) {
            failures++;
            System.out.println("FAIL output recipes " + d.outputRecipes);
        }
        if (!d.recipeInMiddle.equals(Set.of("smelt", "torch"))) {
            failures++;
            System.out.println("FAIL middle recipes " + d.recipeInMiddle);
        }

        check(g, d, "rail", "rail", Type.RESULT, 6 * k);
        check(g, d, "rail", "iron", Type.INGREDIENT, 6 * k);
        check(g, d, "rail", "stick", Type.INGREDIENT, 2 * k);
        check(g, d, "rail", "torch", Type.INGREDIENT, 1 * k);
        check(g, d, "smelt", "iron", Type.RESULT, 6 * k);
        check(g, d, "smelt", "raw", Type.INGREDIENT, 6 * k);
        check(g, d, "torch", "torch", Type.RESULT, 1 * k);
        check(g, d, "torch", "stick", Type.INGREDIENT, 1 * k);
        check(g, d, "torch", "redstone", Type.INGREDIENT, 1 * k);
    }

    /**
     * #46, H4 (checked here, deliberately not changed): the reporter bookmarked 48 plates and then
     * autocrafted a heat vent whose rings and screws are made of plates too, so the plate recipe is both an output
     * recipe (the bookmark, 16 runs of 3 plates) and a mid-chain one. The merge at ChainMath.java:118-130 then puts it
     * at {@code max(0, wanted - 1)} runs. The case walks the chain the way the game does and asserts that no kind ends up
     * crafted beyond or short of what the plan asks for: every amount is checked exactly; the whole-multiples surplus
     * of the rings and screws is printed too.
     */
    static void ventCase() {
        System.out.println("--- #46: bookmarked plate 48x, also the mid-chain producer (ring 18 plate, screw 36 plate)");
        List<ChainItem> g = new ArrayList<>();
        // heat vent 1 <- ring x5, rotor x1
        g.add(item("heat_vent", "vent", Type.RESULT, 1, 1));
        g.add(item("ring", "vent", Type.INGREDIENT, 5, 1));
        g.add(item("rotor", "vent", Type.INGREDIENT, 1, 1));
        // ring x2 <- plate x6: 5 rings want 3 runs = 18 plate, one ring over
        g.add(item("ring", "ring", Type.RESULT, 2, 1));
        g.add(item("plate", "ring", Type.INGREDIENT, 6, 1));
        // rotor 1 <- screw x9
        g.add(item("rotor", "rotor", Type.RESULT, 1, 1));
        g.add(item("screw", "rotor", Type.INGREDIENT, 9, 1));
        // screw x4 <- plate x12: 9 screws want 3 runs = 36 plate, three screws over
        g.add(item("screw", "screw", Type.RESULT, 4, 1));
        g.add(item("plate", "screw", Type.INGREDIENT, 12, 1));
        // the bookmark: the plate recipe run 16 times, 3 plates each = the 48 plates asked for
        g.add(item("plate", "plate", Type.RESULT, 3, 16));
        g.add(item("iron_ingot", "plate", Type.INGREDIENT, 1, 16));

        ChainDetails d = ChainDetails.of(g);
        System.out.println("output recipes with their runs: " + d.math.outputRecipes);
        System.out.println("recipes in the middle: " + d.recipeInMiddle);
        // the merge under test: bookmarked and mid-chain at once, so 16 runs become max(0, 16 - 1)
        expect("plate recipe runs after the merge at max(0, wanted - 1)", 15, d.math.outputRecipes.getOrDefault("plate", -1L));
        expect("heat vent stays an output recipe", 1, d.math.outputRecipes.getOrDefault("vent", -1L));

        d.math.createMasterRoot();
        long rootPlate = 0, rootVent = 0;
        for (ChainItem c : d.math.recipeIngredients) {
            if (!ChainMath.ROOT_RECIPE_ID.equals(c.recipeId)) continue;
            if ("plate".equals(c.item)) rootPlate += c.amount();
            if ("heat_vent".equals(c.item)) rootVent += c.amount();
        }
        // at root level the plan must never ask for more than the player bookmarked
        expect("root level ask for plates (16 bookmarked runs minus NEI's one)", 45, rootPlate);
        expect("root level ask for heat vents", 1, rootVent);

        // the same chain as plain craft actions, to watch what the engine is really asked to craft
        Map<String, Map<String, Long>> in = new HashMap<>(), out = new HashMap<>();
        in.put("vent", Map.of("ring", 5L, "rotor", 1L));
        out.put("vent", Map.of("heat_vent", 1L));
        in.put("ring", Map.of("plate", 6L));
        out.put("ring", Map.of("ring", 2L));
        in.put("rotor", Map.of("screw", 9L));
        out.put("rotor", Map.of("rotor", 1L));
        in.put("screw", Map.of("plate", 12L));
        out.put("screw", Map.of("screw", 4L));
        in.put("plate", Map.of("iron_ingot", 1L));
        out.put("plate", Map.of("plate", 3L));

        Map<String, Long> inv = new HashMap<>();
        inv.put("iron_ingot", 10000L);
        Map<String, Long> crafted = new TreeMap<>(), made = new TreeMap<>(), spent = new TreeMap<>();
        ChainAutocraft ac = new ChainAutocraft(d.math, new ChainAutocraft.Host() {

            public Map<String, Long> inventory() {
                return new HashMap<>(inv);
            }

            public boolean canCraft(String id) {
                Map<String, Long> need = in.get(id);
                if (need == null) return false;
                for (var e : need.entrySet()) if (inv.getOrDefault(e.getKey(), 0L) < e.getValue()) return false;
                return true;
            }
        });
        String id;
        int guard = 0;
        while ((id = ac.next()) != null) {
            if (++guard > 2000) {
                failures++;
                System.out.println("FAIL the vent chain does not terminate");
                break;
            }
            for (var e : in.get(id).entrySet()) {
                inv.merge(e.getKey(), -e.getValue(), Long::sum);
                spent.merge(e.getKey(), e.getValue(), Long::sum);
            }
            for (var e : out.get(id).entrySet()) {
                inv.merge(e.getKey(), e.getValue(), Long::sum);
                made.merge(e.getKey(), e.getValue(), Long::sum);
            }
            crafted.merge(id, 1L, Long::sum);
            ac.crafted();
        }
        System.out.println("crafts: " + crafted);
        System.out.println("crafted in total: " + made);
        long plateMade = made.getOrDefault("plate", 0L), plateSpent = spent.getOrDefault("plate", 0L);
        long plateLeft = plateMade - plateSpent;
        System.out.println("plates crafted " + plateMade + ", eaten by the rings and screws " + plateSpent
            + ", left over " + plateLeft + " of the 48 bookmarked");
        System.out.println("whole-multiples surplus: rings " + (made.getOrDefault("ring", 0L) - 5) + " of a batch of 2,"
            + " screws " + (made.getOrDefault("screw", 0L) - 9) + " of a batch of 4");

        // exact amounts, so both crafting too much and crafting too little fail here. The plate recipe is both
        // bookmarked (16 runs) and in the middle, so the chain keeps NEI's rule and asks for one run fewer as output
        // (15 runs = 45 plates left over; without that rule every step of a chain bookmarked at x1 would craft one
        // extra run, see CollapseCheck). The chain's own 54 plates come on top: 99 crafted in total.
        expect("plates crafted (45 left over + 54 for the chain)", 45 + 54, plateMade);
        expect("plates left over (16 bookmarked runs minus NEI's one)", 45, plateLeft);
        expect("heat vents crafted", 1, made.getOrDefault("heat_vent", 0L));
        expect("rotors crafted", 1, made.getOrDefault("rotor", 0L));
        expect("rings crafted (5 wanted, whole multiples of 2)", 6, made.getOrDefault("ring", 0L));
        expect("screws crafted (9 wanted, whole multiples of 4)", 12, made.getOrDefault("screw", 0L));

    }

    public static void main(String[] args) {
        railCase(1, 1);
        railCase(3, 3);
        ventCase();

        // raw materials the chain needs: stick 2+1, raw 6, redstone 1 (per run)
        {
            System.out.println("--- missed items (raw materials)");
            ChainDetails d = ChainDetails.of(sample(1));
            Map<String, Long> missed = d.math.getMissedItems();
            expect("raw", 6, missed.getOrDefault("raw", 0L));
            expect("stick", 3, missed.getOrDefault("stick", 0L));
            expect("redstone", 1, missed.getOrDefault("redstone", 0L));
            expect("iron (not raw)", 0, missed.getOrDefault("iron", 0L));
        }

        // a plain item in the group is spent first: 4 iron ingots in hand -> smelt only needs 2 more
        {
            System.out.println("--- plain items are spent first");
            List<ChainItem> g = sample(1);
            ChainItem plain = new ChainItem("iron", null, Type.ITEM, 1, 4, null, new Object());
            g.add(plain);
            ChainDetails d = ChainDetails.of(g);
            check(g, d, "smelt", "iron", Type.RESULT, 2);
            check(g, d, "smelt", "raw", Type.INGREDIENT, 2);
        }

        // a loop (a needs b, b needs a) must terminate
        {
            System.out.println("--- loop terminates");
            List<ChainItem> g = new ArrayList<>();
            g.add(item("a", "ra", Type.RESULT, 1, 1));
            g.add(item("b", "ra", Type.INGREDIENT, 1, 1));
            g.add(item("b", "rb", Type.RESULT, 1, 1));
            g.add(item("a", "rb", Type.INGREDIENT, 1, 1));
            ChainDetails d = ChainDetails.of(g);
            System.out.println("outputs " + d.outputRecipes + " middle " + d.recipeInMiddle);
        }

        // a tag slot: the ingredient accepts plank_a or plank_b and the group makes plank_b
        {
            System.out.println("--- tag options");
            List<ChainItem> g = new ArrayList<>();
            g.add(item("chest", "chest", Type.RESULT, 1, 1));
            g.add(item("plank_a", "chest", Type.INGREDIENT, 8, 1, "plank_a", "plank_b"));
            g.add(item("plank_b", "planks", Type.RESULT, 4, 1));
            g.add(item("log", "planks", Type.INGREDIENT, 1, 1));
            ChainDetails d = ChainDetails.of(g);
            check(g, d, "chest", "plank_a", Type.INGREDIENT, 8);
            check(g, d, "planks", "plank_b", Type.RESULT, 8);
            check(g, d, "planks", "log", Type.INGREDIENT, 2);
        }

        // #39: a tag slot of recipe X takes certus or charged certus; recipe C makes charged from certus;
        // recipe Y needs one charged. Open, the slot links to C (2 charged, the bug); picked as certus, it does not.
        {
            System.out.println("--- picked tag slot (issue 39)");
            List<ChainItem> open = certusChain("certus", "charged");
            ChainDetails d = ChainDetails.of(open);
            check(open, d, "c", "charged", Type.RESULT, 2);
            // C runs twice (for the slot and for Y): two certus raw
            expect("open slot: certus raw (C runs twice)", 2, d.math.getMissedItems().getOrDefault("certus", 0L));

            List<ChainItem> picked = certusChain("certus");
            d = ChainDetails.of(picked);
            check(picked, d, "c", "charged", Type.RESULT, 1);
            // C runs once, and the slot is raw certus too: one for C's input, one for X
            expect("picked slot: certus raw (C's input and X's slot)", 2, d.math.getMissedItems().getOrDefault("certus", 0L));
        }

        if (failures > 0) {
            System.out.println(failures + " FAILURE(S)");
            System.exit(1);
        }
        System.out.println("ALL OK");
    }
}

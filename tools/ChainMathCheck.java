import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import neiaddon.chain.ChainDetails;
import neiaddon.chain.ChainItem;
import neiaddon.chain.ChainItem.Type;

/**
 * Regression test for the chain maths (brief section 6). Plain Java, no Minecraft classes:
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

    public static void main(String[] args) {
        railCase(1, 1);
        railCase(3, 3);

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

        if (failures > 0) {
            System.out.println(failures + " FAILURE(S)");
            System.exit(1);
        }
        System.out.println("ALL OK");
    }
}

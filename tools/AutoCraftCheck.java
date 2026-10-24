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

/**
 * Simulates autocrafting without Minecraft: a fake inventory and fake recipes, driven the way the game side drives
 * {@link ChainAutocraft}.
 *
 * <pre>
 * javac -d /tmp/acheck src/main/java/neiaddon/chain/*.java tools/AutoCraftCheck.java
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

        System.out.println(failures == 0 ? "ALL OK" : failures + " FAILED");
        if (failures != 0) System.exit(1);
    }
}

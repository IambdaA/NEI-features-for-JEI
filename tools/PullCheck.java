import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import neiaddon.chain.ChainItem;
import neiaddon.chain.ChainItem.Type;
import neiaddon.chain.ChainMath;
import neiaddon.chain.ChainPull;

/**
 * What pulling items takes out of a chest, without Minecraft.
 *
 * <pre>
 * javac -d /tmp/pcheck src/main/java/neiaddon/chain/*.java tools/PullCheck.java
 * java -cp /tmp/pcheck PullCheck
 * </pre>
 */
public class PullCheck {

    static int failures;

    static ChainItem item(String name, String recipe, Type type, long factor, long multiplier) {
        return new ChainItem(name, recipe, type, factor, multiplier, Set.of(name), null);
    }

    static ChainMath torches(long runs) {
        List<ChainItem> g = new ArrayList<>();
        g.add(item("torch", "T", Type.RESULT, 4, runs));
        g.add(item("coal", "T", Type.INGREDIENT, 1, runs));
        g.add(item("stick", "T", Type.INGREDIENT, 1, runs));
        g.add(item("stick", "S", Type.RESULT, 4, 1));
        g.add(item("plank", "S", Type.INGREDIENT, 2, 1));
        g.add(item("plank", "P", Type.RESULT, 4, 1));
        g.add(item("log", "P", Type.INGREDIENT, 1, 1));
        return ChainMath.of(g);
    }

    static void check(String what, Object got, Object want) {
        boolean ok = got.equals(want);
        System.out.println((ok ? "ok   " : "FAIL ") + what + (ok ? "" : " got " + got + " want " + want));
        if (!ok) failures++;
    }

    public static void main(String[] args) {
        // 8 torches: 2 coal and 2 sticks (one run of S, from 2 planks, one run of P, from 1 log)
        check("raw materials of the chain", ChainPull.plan(torches(2), Map.of(), Map.of("coal", 64L, "log", 64L)), Map.of("coal", 2L, "log", 1L));
        check(
            "sticks in the chest are used before making them",
            ChainPull.plan(torches(2), Map.of(), Map.of("coal", 64L, "log", 64L, "stick", 10L)),
            Map.of("coal", 2L, "stick", 2L));
        check(
            "Shift: what the inventory holds is not pulled again",
            ChainPull.plan(torches(2), Map.of("coal", 2L), Map.of("coal", 64L, "log", 64L)),
            Map.of("log", 1L));
        check(
            "the inventory's coal and the chest's coal are different things",
            ChainPull.plan(torches(2), Map.of("coal", 1L), Map.of("coal", 64L, "log", 64L)),
            Map.of("coal", 1L, "log", 1L));
        check("nothing in the chest, nothing to pull", ChainPull.plan(torches(2), Map.of(), Map.of()), Map.of());

        // no recipes: every item at its amount, the chest is not looked at
        ChainMath plain = ChainMath.of(List.of(item("cobble", null, Type.ITEM, 1, 10)));
        check("plain items", ChainPull.plan(plain, Map.of(), Map.of("cobble", 3L)), Map.of("cobble", 10L));
        plain = ChainMath.of(List.of(item("cobble", null, Type.ITEM, 1, 10)));
        check("plain items, Shift", ChainPull.plan(plain, Map.of("cobble", 4L), Map.of("cobble", 3L)), Map.of("cobble", 6L));
        plain = ChainMath.of(List.of(item("cobble", null, Type.ITEM, 1, 10)));
        check("plain items already carried", ChainPull.plan(plain, Map.of("cobble", 20L), Map.of()), Map.of());

        System.out.println(failures == 0 ? "ALL OK" : failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }
}

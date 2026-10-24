import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;

import neiaddon.chain.ChainDetails;
import neiaddon.chain.ChainItem;
import neiaddon.chain.ChainItem.Type;

/**
 * Collapsed recipes (NEI's collapsedRecipes / recipeRelations) without Minecraft.
 *
 * <pre>
 * javac -d /tmp/ccheck src/main/java/neiaddon/chain/*.java tools/CollapseCheck.java
 * java -cp /tmp/ccheck CollapseCheck
 * </pre>
 */
public class CollapseCheck {

    static int failures;

    static ChainItem item(String name, String recipe, Type type, long factor, long multiplier) {
        return new ChainItem(name, recipe, type, factor, multiplier, Set.of(name), name + "@" + recipe + ":" + type);
    }

    /** 8 torches (T twice) <- coal + sticks <- S <- planks <- P <- log. */
    static List<ChainItem> chain() {
        List<ChainItem> g = new ArrayList<>();
        g.add(item("torch", "T", Type.RESULT, 4, 2));
        g.add(item("coal", "T", Type.INGREDIENT, 1, 2));
        g.add(item("stick", "T", Type.INGREDIENT, 1, 2));
        g.add(item("stick", "S", Type.RESULT, 4, 1));
        g.add(item("plank", "S", Type.INGREDIENT, 2, 1));
        g.add(item("plank", "P", Type.RESULT, 4, 1));
        g.add(item("log", "P", Type.INGREDIENT, 1, 1));
        return g;
    }

    static void check(String what, Object got, Object want) {
        boolean ok = got.equals(want);
        System.out.println((ok ? "ok   " : "FAIL ") + what + (ok ? "" : " got " + got + " want " + want));
        if (!ok) failures++;
    }

    /** item -> amount (+ "~" for a shadow) of the slots a collapsed recipe is drawn with. */
    static String slots(ChainDetails d, String recipe) {
        TreeMap<String, String> out = new TreeMap<>();
        for (ChainDetails.Calc c : d.collapsedItems.getOrDefault(recipe, List.of())) {
            out.put(c.item.item, c.calculatedAmount() + (c.shadow ? "~" : "") + " " + c.type);
        }
        return out.toString();
    }

    public static void main(String[] args) {
        ChainDetails plain = ChainDetails.of(chain());
        check("nothing collapsed: no relations", plain.recipeRelations.isEmpty(), true);
        check("nothing collapsed: every item has its own slot", plain.calculated.size(), 7);

        ChainDetails d = ChainDetails.of(chain(), Set.of("T"));
        check("T swallows the recipes it feeds on", d.recipeRelations.get("T"), Set.of("T", "S", "P"));
        check(
            "T is drawn as one block",
            slots(d, "T"),
            "{coal=2~ INGREDIENT, log=1~ INGREDIENT, plank=4~ REMAINDER, stick=4~ REMAINDER, torch=8 RESULT}");
        check("only the result keeps its own slot", d.calculated.size(), 1);

        // collapsing S alone: it swallows P, T stays what it was
        ChainDetails s = ChainDetails.of(chain(), Set.of("S"));
        check("S swallows P", s.recipeRelations.get("S"), Set.of("S", "P"));
        check("S block", slots(s, "S"), "{log=1~ INGREDIENT, plank=4~ REMAINDER, stick=4 RESULT}");

        // what is left over is what the recipes make beyond what the chain uses
        for (ChainDetails.Calc c : d.collapsedItems.get("T")) {
            if (c.item.item.equals("stick")) check("2 of the 4 sticks are left over", c.shiftAmount, 2L);
            if (c.item.item.equals("plank")) check("2 of the 4 planks are left over", c.shiftAmount, 2L);
        }

        System.out.println(failures == 0 ? "ALL OK" : failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }
}

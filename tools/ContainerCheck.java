import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import neiaddon.chain.ChainItem;
import neiaddon.chain.ChainItem.Type;
import neiaddon.chain.ChainMath;
import neiaddon.chain.ContainerOps;
import neiaddon.chain.TreeMath;
import neiaddon.chain.TreeSlot;

/**
 * Regression test for the container item part of the chain maths. Plain Java:
 *
 * <pre>
 * javac -d /tmp/containercheck common/src/main/java/neiaddon/chain/*.java tools/ContainerCheck.java
 * java -cp /tmp/containercheck ContainerCheck
 * </pre>
 *
 * Fake items: a milk bucket leaves a bucket behind; a hammer comes back worn and breaks after 3 uses.
 */
public class ContainerCheck {

    record Fake(String id, int wear) {}

    static final ContainerOps OPS = new ContainerOps() {

        @Override
        public Object stackOf(ChainItem item) {
            return stackOf(item.item);
        }

        @Override
        public Object stackOf(String itemId) {
            return itemId.startsWith("neiaddon:") ? null : new Fake(itemId, 0);
        }

        @Override
        public String idOf(Object stack) {
            return ((Fake) stack).id();
        }

        @Override
        public boolean hasContainerItem(Object stack) {
            return remainder(stack) != null;
        }

        @Override
        public boolean sameForCrafting(Object a, Object b) {
            return ((Fake) a).id().equals(((Fake) b).id());
        }

        @Override
        public boolean sameItem(Object a, Object b) {
            return ((Fake) a).id().equals(((Fake) b).id());
        }

        @Override
        public Object remainder(Object stack) {
            Fake f = (Fake) stack;
            if (f.id().equals("milk_bucket")) return new Fake("bucket", 0);
            if (f.id().equals("hammer")) return f.wear() + 1 >= 3 ? null : new Fake("hammer", f.wear() + 1);
            return null;
        }

        @Override
        public Object copy(Object stack) {
            return stack;
        }
    };

    static int failures;

    static void expect(String what, long expected, long actual) {
        if (expected != actual) {
            failures++;
            System.out.println("FAIL " + what + ": expected " + expected + " got " + actual);
        } else {
            System.out.println("ok   " + what + " = " + actual);
        }
    }

    static ChainItem item(String name, String recipe, Type type, long factor, long multiplier) {
        return new ChainItem(name, recipe, type, factor, multiplier, null, null);
    }

    static long required(ChainMath math, String recipe, String name, Type type) {
        for (ChainItem i : type == Type.INGREDIENT ? math.recipeIngredients : math.recipeResults) {
            if (recipe.equals(i.recipeId) && i.item.equals(name)) return math.requiredAmount.getOrDefault(i, 0L);
        }
        return -1;
    }

    static long held(List<ContainerOps.Held> list, String id) {
        long n = 0;
        for (ContainerOps.Held h : list) {
            if (h.stack != null && OPS.idOf(h.stack).equals(id)) n += h.count;
        }
        return n;
    }

    public static void main(String[] args) {
        // cake x1 <- 3 milk buckets, 2 sugar; made twice
        List<ChainItem> cake = new ArrayList<>();
        cake.add(item("cake", "cake", Type.RESULT, 1, 2));
        cake.add(item("milk_bucket", "cake", Type.INGREDIENT, 3, 2));
        cake.add(item("sugar", "cake", Type.INGREDIENT, 2, 2));

        ChainMath.containerOps = null;
        ChainMath plain = ChainMath.of(cake)
            .refresh();
        expect("no ops: milk buckets", 6, required(plain, "cake", "milk_bucket", Type.INGREDIENT));
        expect("no ops: leftovers", 0, plain.containerItemsCrafting.size());

        ChainMath.containerOps = OPS;
        ChainMath withBuckets = ChainMath.of(cake)
            .refresh();
        expect("buckets: milk buckets", 6, required(withBuckets, "cake", "milk_bucket", Type.INGREDIENT));
        expect("buckets: sugar", 4, required(withBuckets, "cake", "sugar", Type.INGREDIENT));
        expect("buckets: empty buckets left", 6, held(withBuckets.containerItemsCrafting, "bucket"));

        // two recipes that each need a hammer (3 uses in all): one hammer is enough
        List<ChainItem> tools = new ArrayList<>();
        tools.add(item("x", "a", Type.RESULT, 1, 2));
        tools.add(item("hammer", "a", Type.INGREDIENT, 1, 2));
        tools.add(item("y", "b", Type.RESULT, 1, 2));
        tools.add(item("hammer", "b", Type.INGREDIENT, 1, 2));
        ChainMath.containerOps = null;
        ChainMath noTools = ChainMath.of(tools)
            .refresh();
        expect("no ops: hammers a", 2, required(noTools, "a", "hammer", Type.INGREDIENT));
        expect("no ops: hammers b", 2, required(noTools, "b", "hammer", Type.INGREDIENT));

        ChainMath.containerOps = OPS;
        ChainMath withTools = ChainMath.of(tools)
            .refresh();
        expect("tools: hammers a (1 hammer does 2 crafts)", 1, required(withTools, "a", "hammer", Type.INGREDIENT));
        // the hammer has 1 use left; b needs 2: the worn hammer does one, a new hammer the other
        expect("tools: hammers b", 1, required(withTools, "b", "hammer", Type.INGREDIENT));
        // the first hammer is used up (3 uses); the new one has done 1 of its 3
        expect("tools: one worn hammer left over", 1, held(withTools.containerItemsCrafting, "hammer"));

        // the tree marks the hammer as an estimate
        TreeMath tree = new TreeMath(tools, Set.of());
        tree.refresh();
        int marked = 0;
        for (TreeSlot s : tree.items) {
            if (s.ingrItem.item.equals("hammer") && s.isContainerItem) marked++;
        }
        expect("tree: hammer nodes marked", 2, marked);
        int cakeMarked = 0;
        ChainMath.containerOps = OPS;
        TreeMath cakeTree = new TreeMath(cake, Set.of());
        cakeTree.refresh();
        for (TreeSlot s : cakeTree.items) {
            if (s.isContainerItem) cakeMarked++;
        }
        expect("tree: milk bucket (comes back as a bucket) not marked", 0, cakeMarked);

        ChainMath.containerOps = null;
        if (failures > 0) {
            System.out.println(failures + " FAILED");
            System.exit(1);
        }
        System.out.println("all ok");
    }
}

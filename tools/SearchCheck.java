import java.util.List;
import java.util.Map;
import neiaddon.search.SearchQuery;
import neiaddon.search.SearchQuery.Item;

/**
 * Checks the NEI search language without Minecraft.
 *
 * <pre>
 * javac -d /tmp/scheck common/src/main/java/neiaddon/search/*.java tools/SearchCheck.java
 * java -cp /tmp/scheck SearchCheck
 * </pre>
 */
public class SearchCheck {

    static int failures;

    record Thing(String name, String mod, String tags) implements Item {

        @Override
        public boolean test(char prefix, String text, boolean regex) {
            String h = prefix == 0 ? name : prefix == '@' ? mod : prefix == '$' ? tags : prefix == '&' ? mod + ":" + name.replace(' ', '_') : "";
            return SearchQuery.textMatches(h, text, regex);
        }
    }

    static Thing t(String name, String mod, String tags) {
        return new Thing(name, mod, tags);
    }

    static void check(String what, boolean actual, boolean expected) {
        if (actual != expected) {
            failures++;
            System.out.println("FAIL " + what + ": got " + actual + ", expected " + expected);
        }
    }

    static boolean item(String query, Thing t) {
        return SearchQuery.parse(query).matches(t);
    }

    static final Thing IRON = t("Iron Ingot", "minecraft", "c:ingots/iron");
    static final Thing RAIL = t("Powered Rail", "minecraft", "");
    static final Thing GEAR = t("Iron Gear", "create", "c:gears");

    record R(List<List<Item>> in, List<List<Item>> out, List<List<Item>> other) implements SearchQuery.Recipe {

        public List<List<Item>> ingredients() { return in; }
        public List<List<Item>> results() { return out; }
        public List<List<Item>> others() { return other; }
    }

    static boolean recipe(String q, R r) {
        return SearchQuery.matches(SearchQuery.parseRecipe(q), r);
    }

    public static void main(String[] args) {
        check("plain", item("iron", IRON), true);
        check("plain case", item("IRON", IRON), true);
        check("and", item("iron gear", GEAR), true);
        check("and fails", item("iron gear", IRON), false);
        check("or", item("rail|gear", GEAR), true);
        check("or none", item("rail|gear", IRON), false);
        check("negate", item("iron -gear", IRON), true);
        check("negate hit", item("iron -gear", GEAR), false);
        check("group", item("{rail|gear} iron", GEAR), true);
        check("group and", item("{rail|gear} iron", RAIL), false);
        check("mod", item("@create", GEAR), true);
        check("mod no", item("@create", IRON), false);
        check("tag", item("$ingots", IRON), true);
        check("quoted", item("\"iron ingot\"", IRON), true);
        check("quoted no", item("\"iron gear\"", IRON), false);
        check("regex", item("/^iron (ingot|gear)$/", GEAR), true);
        check("regex r", item("r/gear$/", GEAR), true);
        check("bad regex", item("/(/", GEAR), false);
        check("blank", item("", GEAR), true);
        check("prefix + neg", item("@minecraft -rail", IRON), true);
        check("escape", item("\\@create", GEAR), false);

        R rail = new R(List.of(List.of(IRON), List.of(IRON)), List.of(List.of(RAIL)), List.of());
        R gear = new R(List.of(List.of(IRON, RAIL)), List.of(List.of(GEAR)), List.of(List.of(t("Hammer", "create", ""))));
        check("ingredient", recipe("<iron", rail), true);
        check("result", recipe(">rail", rail), true);
        check("result wrong side", recipe(">iron", rail), false);
        check("both", recipe("<iron >rail", rail), true);
        check("both fail", recipe("<iron >gear", rail), false);
        check("alt slot", recipe("<rail", gear), true);
        check("any", recipe("rail", rail), true);
        check("any other", recipe("hammer", gear), true);
        check("others", recipe("^hammer", gear), true);
        check("others none", recipe("^hammer", rail), false);
        check("every", recipe("<&iron", rail), true);
        check("every fails", recipe("<&iron", new R(List.of(List.of(IRON), List.of(RAIL)), List.of(), List.of())), false);
        check("neg in clause", recipe("<iron -rail", rail), true);
        check("neg in clause hit", recipe("<iron -ingot", rail), false);
        check("or in clause", recipe(">rail|gear", gear), true);
        check("mod clause", recipe(">@create", gear), true);
        check("regex clause", recipe("<r/^iron/ >/rail$/", rail), true);
        check("empty", recipe("", rail), true);
        check("quote with arrow", recipe("\"a>b\"", rail), false);

        System.out.println(failures == 0 ? "all search checks passed" : failures + " search check(s) failed");
        if (failures > 0) System.exit(1);
    }
}

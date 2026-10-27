package neiaddon.search;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * NEI's search expression language, without Minecraft (see NEI's SearchExpression grammar):
 * <ul>
 * <li>a space means "and", {@code |} means "or", {@code -} negates the next term, {@code { }} groups;</li>
 * <li>{@code "quoted text"} keeps spaces, {@code /regex/} (or {@code r/regex/}) is a regular expression, a backslash
 * escapes one character;</li>
 * <li>a term may start with a prefix character ({@code @ # $ &}) that picks what it is matched against, see
 * {@link Item#test};</li>
 * <li>recipe queries add clauses: {@code <} ingredients, {@code >} results, {@code ^} other slots (catalysts); a doubled
 * form ({@code <&}) needs every slot to match instead of any, and text without a clause matches any slot.</li>
 * </ul>
 */
public final class SearchQuery {

    /** The prefix characters, in NEI's default order. */
    public static final String PREFIXES = "@#$&";

    /** What an item is asked: does it match this term? {@code prefix} is 0 for a plain name search. */
    public interface Item {

        boolean test(char prefix, String text, boolean regex);
    }

    public interface Node {

        boolean matches(Item item);
    }

    private record Term(char prefix, String text, boolean regex) implements Node {

        @Override
        public boolean matches(Item item) {
            return item.test(prefix, text, regex);
        }
    }

    private record Not(Node inner) implements Node {

        @Override
        public boolean matches(Item item) {
            return !inner.matches(item);
        }
    }

    private record All(List<Node> parts) implements Node {

        @Override
        public boolean matches(Item item) {
            for (Node n : parts) if (!n.matches(item)) return false;
            return true;
        }
    }

    private record Any(List<Node> parts) implements Node {

        @Override
        public boolean matches(Item item) {
            for (Node n : parts) if (n.matches(item)) return true;
            return false;
        }
    }

    private static final Node EVERYTHING = item -> true;

    // ---------------------------------------------------------------- parsing one expression

    private static final class Parser {

        final String s;
        int pos;

        Parser(String s) {
            this.s = s;
        }

        Node parseOr(boolean inGroup) {
            List<Node> alternatives = new ArrayList<>();
            List<Node> sequence = new ArrayList<>();
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if (c == '}') {
                    if (inGroup) break;
                    pos++;
                } else if (c == '|') {
                    pos++;
                    alternatives.add(all(sequence));
                    sequence = new ArrayList<>();
                } else if (c == ' ' || c == '\t' || c == '\r' || c == '\n') {
                    pos++;
                } else {
                    Node n = parseUnary();
                    if (n != null) sequence.add(n);
                }
            }
            alternatives.add(all(sequence));
            return alternatives.size() == 1 ? alternatives.get(0) : new Any(alternatives);
        }

        private static Node all(List<Node> parts) {
            return parts.isEmpty() ? EVERYTHING : parts.size() == 1 ? parts.get(0) : new All(parts);
        }

        Node parseUnary() {
            char c = s.charAt(pos);
            if (c == '-') {
                pos++;
                while (pos < s.length() && s.charAt(pos) == ' ') pos++;
                if (pos >= s.length()) return null;
                Node inner = parseUnary();
                return inner == null ? null : new Not(inner);
            }
            if (c == '{') {
                pos++;
                Node inner = parseOr(true);
                if (pos < s.length() && s.charAt(pos) == '}') pos++;
                return inner;
            }
            char prefix = 0;
            if (PREFIXES.indexOf(c) >= 0) {
                prefix = c;
                pos++;
                if (pos >= s.length()) return null;
            }
            return parseToken(prefix);
        }

        Node parseToken(char prefix) {
            char c = s.charAt(pos);
            if (c == '"') {
                pos++;
                return new Term(prefix, until('"'), false);
            }
            if (c == '/' || c == 'r' && pos + 1 < s.length() && s.charAt(pos + 1) == '/') {
                pos += c == 'r' ? 2 : 1;
                return new Term(prefix, until('/'), true);
            }
            StringBuilder text = new StringBuilder();
            while (pos < s.length()) {
                char d = s.charAt(pos);
                if (d == '\\' && pos + 1 < s.length()) {
                    text.append(s.charAt(pos + 1));
                    pos += 2;
                } else if ("-<>^{}|/ \"".indexOf(d) >= 0 || d == '\t' || d == '\r' || d == '\n') {
                    break;
                } else {
                    text.append(d);
                    pos++;
                }
            }
            if (text.length() == 0) {
                pos++; // a stray symbol: skip it so parsing always moves on
                return null;
            }
            return new Term(prefix, text.toString(), false);
        }

        private String until(char end) {
            StringBuilder text = new StringBuilder();
            while (pos < s.length()) {
                char d = s.charAt(pos++);
                if (d == end) break;
                if (d == '\\' && pos < s.length() && s.charAt(pos) == end) d = s.charAt(pos++);
                text.append(d);
            }
            return text.toString();
        }
    }

    /** An item expression: what a plain search box means. Blank text matches everything. */
    public static Node parse(String text) {
        return new Parser(text == null ? "" : text).parseOr(false);
    }

    // ---------------------------------------------------------------- recipe queries

    public enum Context {
        INGREDIENTS, RESULTS, OTHERS, ANY
    }

    public record Clause(Context context, boolean every, Node node) {}

    /** The slots of a recipe; each slot lists the items it accepts. */
    public interface Recipe {

        List<List<Item>> ingredients();

        List<List<Item>> results();

        List<List<Item>> others();
    }

    /** Splits a recipe query into its clauses; clauses are all required. */
    public static List<Clause> parseRecipe(String text) {
        List<Clause> clauses = new ArrayList<>();
        if (text == null) return clauses;
        Context context = Context.ANY;
        boolean every = false;
        int start = 0;
        int depth = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\\') {
                i++;
            } else if (c == '"') {
                i = skipTo(text, i + 1, '"');
            } else if (c == '/' && (i == 0 || text.charAt(i - 1) != '<' && text.charAt(i - 1) != '>')) {
                i = skipTo(text, i + 1, '/');
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth = Math.max(0, depth - 1);
            } else if (depth == 0 && (c == '<' || c == '>' || c == '^')) {
                addClause(clauses, context, every, text.substring(start, i));
                context = c == '<' ? Context.INGREDIENTS : c == '>' ? Context.RESULTS : Context.OTHERS;
                every = i + 1 < text.length() && text.charAt(i + 1) == '&';
                if (every) i++;
                start = i + 1;
            }
        }
        addClause(clauses, context, every, text.substring(start));
        return clauses;
    }

    private static int skipTo(String text, int from, char end) {
        int i = from;
        while (i < text.length() && text.charAt(i) != end) {
            if (text.charAt(i) == '\\') i++;
            i++;
        }
        return i;
    }

    private static void addClause(List<Clause> clauses, Context context, boolean every, String text) {
        if (text.isBlank() && context == Context.ANY) return;
        clauses.add(new Clause(context, every, parse(text)));
    }

    public static boolean matches(List<Clause> query, Recipe recipe) {
        for (Clause clause : query) {
            if (!clauseMatches(clause, recipe)) return false;
        }
        return true;
    }

    private static boolean clauseMatches(Clause clause, Recipe recipe) {
        return switch (clause.context()) {
            case INGREDIENTS -> slots(recipe.ingredients(), clause.node(), clause.every());
            case RESULTS -> slots(recipe.results(), clause.node(), clause.every());
            case OTHERS -> slots(recipe.others(), clause.node(), clause.every());
            case ANY -> slots(recipe.ingredients(), clause.node(), false) || slots(recipe.results(), clause.node(), false)
                || slots(recipe.others(), clause.node(), false);
        };
    }

    private static boolean slots(List<List<Item>> slots, Node node, boolean every) {
        if (slots.isEmpty()) return false;
        for (List<Item> slot : slots) {
            boolean hit = false;
            for (Item item : slot) {
                if (node.matches(item)) {
                    hit = true;
                    break;
                }
            }
            if (hit && !every) return true;
            if (!hit && every) return false;
        }
        return every;
    }

    // ---------------------------------------------------------------- text matching helper

    /** Plain and regex matching of one term against a text, ignoring case. */
    public static boolean textMatches(String haystack, String term, boolean regex) {
        if (haystack == null) return false;
        if (!regex) return haystack.toLowerCase().contains(term.toLowerCase());
        try {
            return Pattern.compile(term, Pattern.CASE_INSENSITIVE).matcher(haystack).find();
        } catch (PatternSyntaxException e) {
            return false;
        }
    }
}

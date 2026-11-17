package engine;

import java.awt.Color;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** CSS rule parser and cascade for the built-in renderer's supported layout properties. */
public final class CssEngine {
    private static final Pattern RULE_PATTERN = Pattern.compile("(?s)([^{}]+)\\{([^{}]*)}");
    private static final Pattern ATTR_PATTERN = Pattern.compile("\\[([\\w:-]+)(?:\\s*([~|^$*]?=)\\s*(['\\\"]?)(.*?)\\3)?\\]");
    private static final Pattern PSEUDO_PATTERN = Pattern.compile(":(first-child|last-child|nth-child\\(([^)]*)\\)|not\\(([^)]*)\\)|empty|root|checked|disabled|enabled|focus|hover|visited|link)", Pattern.CASE_INSENSITIVE);
    private static final String INHERITED = " color font-family font-size font-style font-weight line-height text-align white-space visibility cursor ";

    public record Style(Map<String, String> values) {
        public String get(String property, String fallback) { return values.getOrDefault(property.toLowerCase(Locale.ROOT), fallback); }
        public int px(String property, int fallback) {
            String value = get(property, "").trim().toLowerCase(Locale.ROOT);
            try {
                double multiplier = 1;
                if (value.endsWith("rem")) { value = value.substring(0, value.length() - 3); multiplier = 16; }
                else if (value.endsWith("em")) { value = value.substring(0, value.length() - 2); multiplier = 16; }
                else if (value.endsWith("pt")) { value = value.substring(0, value.length() - 2); multiplier = 4.0 / 3.0; }
                else if (value.endsWith("px")) value = value.substring(0, value.length() - 2);
                return (int) Math.round(Double.parseDouble(value.trim()) * multiplier);
            } catch (Exception ignored) { return fallback; }
        }
        public Color color(String property, Color fallback) { return parseColor(get(property, ""), fallback); }
    }
    private record Rule(String selector, Map<String, String> declarations, int specificity, int order) {}
    private static final Style EMPTY = new Style(Map.of());
    private final List<Rule> rules = new ArrayList<>();
    private final Map<Dom.Node, Style> cache = new IdentityHashMap<>();

    public CssEngine(String css) {
        if (css == null) return;
        Matcher matcher = RULE_PATTERN.matcher(stripComments(css));
        int order = 0;
        while (matcher.find()) {
            String selectorText = matcher.group(1).trim();
            if (selectorText.isEmpty() || selectorText.startsWith("@")) continue;
            Map<String, String> declarations = parseDeclarations(matcher.group(2));
            for (String selector : splitSelectors(selectorText)) if (!selector.isBlank())
                rules.add(new Rule(selector.trim(), declarations, specificity(selector), order++));
        }
        rules.sort((a, b) -> a.specificity != b.specificity ? Integer.compare(a.specificity, b.specificity) : Integer.compare(a.order, b.order));
    }

    public Style compute(Dom.Node node) {
        if (node == null || node.isText()) return EMPTY;
        Style cached = cache.get(node);
        if (cached != null) return cached;
        Map<String, String> inherited = new HashMap<>();
        if (node.parent != null) {
            Style parent = compute(node.parent);
            for (String property : INHERITED.trim().split("\\s+")) {
                String value = parent.get(property, "");
                if (!value.isEmpty()) inherited.put(property, value);
            }
        }
        Map<String, String> values = new HashMap<>(inherited);
        if (Dom.isBlock(node.tag)) { values.put("display", "block"); values.put("margin", "6px"); }
        if (node.tag.equals("a")) { values.put("color", "#0645ad"); values.put("text-decoration", "underline"); }
        if (node.tag.equals("strong") || node.tag.equals("b")) values.put("font-weight", "bold");
        if (node.tag.equals("em") || node.tag.equals("i")) values.put("font-style", "italic");
        if (node.tag.matches("h[1-6]")) { values.put("font-weight", "bold"); values.put("font-size", node.tag.equals("h1") ? "30px" : node.tag.equals("h2") ? "26px" : "22px"); }
        if (node.tag.equals("pre")) values.put("white-space", "pre");
        for (Rule rule : rules) if (matches(node, rule.selector)) values.putAll(rule.declarations);
        values.putAll(parseDeclarations(node.attr("style")));
        expandBox(values, "margin"); expandBox(values, "padding");
        for (Map.Entry<String, String> entry : values.entrySet()) if (entry.getValue().equalsIgnoreCase("inherit")) {
            String value = inherited.get(entry.getKey()); if (value != null) entry.setValue(value);
        }
        values.putIfAbsent("color", "#222222");
        values.putIfAbsent("font-family", "SansSerif");
        values.putIfAbsent("font-size", "16px");
        Style result = new Style(Map.copyOf(values));
        cache.put(node, result);
        return result;
    }

    private static void expandBox(Map<String, String> values, String name) {
        String raw = values.get(name);
        if (raw == null) return;
        String[] parts = raw.trim().split("\\s+");
        if (parts.length < 1 || parts.length > 4) return;
        String top = parts[0], right = parts.length > 1 ? parts[1] : top;
        String bottom = parts.length > 2 ? parts[2] : top, left = parts.length > 3 ? parts[3] : right;
        values.put(name + "-top", top); values.put(name + "-right", right);
        values.put(name + "-bottom", bottom); values.put(name + "-left", left);
    }
    private static Map<String, String> parseDeclarations(String source) {
        Map<String, String> result = new HashMap<>();
        for (String declaration : splitDeclarations(source)) {
            int colon = declaration.indexOf(':');
            if (colon < 1) continue;
            String key = declaration.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            String value = declaration.substring(colon + 1).trim();
            if (value.toLowerCase(Locale.ROOT).endsWith("!important")) value = value.substring(0, value.length() - 10).trim();
            if (!key.isEmpty() && !value.isEmpty()) result.put(key, value);
        }
        return result;
    }
    private static List<String> splitDeclarations(String text) {
        ArrayList<String> out = new ArrayList<>(); int start = 0, depth = 0; char quote = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quote != 0) { if (c == quote && (i == 0 || text.charAt(i - 1) != '\\')) quote = 0; }
            else if (c == '\'' || c == '"') quote = c;
            else if (c == '(') depth++;
            else if (c == ')') depth = Math.max(0, depth - 1);
            else if (c == ';' && depth == 0) { out.add(text.substring(start, i)); start = i + 1; }
        }
        out.add(text.substring(start)); return out;
    }
    private static List<String> splitSelectors(String text) {
        ArrayList<String> out = new ArrayList<>(); int start = 0, brackets = 0, parens = 0; char quote = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quote != 0) { if (c == quote) quote = 0; }
            else if (c == '\'' || c == '"') quote = c;
            else if (c == '[') brackets++;
            else if (c == ']') brackets--;
            else if (c == '(') parens++;
            else if (c == ')') parens--;
            else if (c == ',' && brackets == 0 && parens == 0) { out.add(text.substring(start, i)); start = i + 1; }
        }
        out.add(text.substring(start)); return out;
    }
    private static boolean matches(Dom.Node node, String selector) {
        String normalized = selector.trim().replace(">", " > ").replace("+", " + ").replace("~", " ~ ").trim().replaceAll("\\s+", " ");
        String[] parts = normalized.split(" ");
        int i = parts.length - 1; Dom.Node current = node;
        if (!matchesCompound(current, parts[i--])) return false;
        while (i >= 0) {
            String combinator = parts[i].equals(">") || parts[i].equals("+") || parts[i].equals("~") ? parts[i--] : " ";
            if (i < 0) return false;
            String wanted = parts[i--];
            if (combinator.equals(">")) { current = current.parent; if (!matchesCompound(current, wanted)) return false; }
            else if (combinator.equals("+") || combinator.equals("~")) {
                if (current.parent == null) return false;
                List<Dom.Node> siblings = current.parent.children; int pos = siblings.indexOf(current); boolean found = false;
                for (int p = pos - 1; p >= 0; p--) {
                    Dom.Node sibling = siblings.get(p); if (sibling.isText()) continue;
                    if (matchesCompound(sibling, wanted)) { current = sibling; found = true; }
                    if (combinator.equals("+")) break;
                }
                if (!found) return false;
            } else {
                current = current.parent;
                while (current != null && !matchesCompound(current, wanted)) current = current.parent;
                if (current == null) return false;
            }
        }
        return true;
    }
    private static boolean matchesCompound(Dom.Node node, String selector) {
        if (node == null || node.isText() || selector.isBlank()) return false;
        String s = selector;
        Matcher attrs = ATTR_PATTERN.matcher(s);
        while (attrs.find()) {
            String name = attrs.group(1), op = attrs.group(2), expected = attrs.group(4);
            if (!node.hasAttribute(name)) return false;
            if (op != null) {
                String actual = node.attr(name);
                boolean ok = switch (op) {
                    case "=" -> actual.equals(expected); case "~=" -> List.of(actual.split("\\s+")).contains(expected);
                    case "|=" -> actual.equals(expected) || actual.startsWith(expected + "-"); case "^=" -> actual.startsWith(expected);
                    case "$=" -> actual.endsWith(expected); case "*=" -> actual.contains(expected); default -> false;
                };
                if (!ok) return false;
            }
        }
        s = ATTR_PATTERN.matcher(s).replaceAll("");
        Matcher pseudo = PSEUDO_PATTERN.matcher(s);
        while (pseudo.find()) {
            String name = pseudo.group(1).toLowerCase(Locale.ROOT);
            switch (name) {
                case "first-child" -> { if (elementIndex(node) != 1) return false; }
                case "last-child" -> { if (elementIndexFromEnd(node) != 1) return false; }
                case "nth-child" -> { if (!matchesNth(elementIndex(node), pseudo.group(2))) return false; }
                case "not" -> { if (matchesCompound(node, pseudo.group(3))) return false; }
                case "empty" -> { if (!node.children.isEmpty()) return false; }
                case "root" -> { if (node.parent == null || !node.parent.tag.equals("document")) return false; }
                case "checked" -> { if (!node.hasAttribute("checked")) return false; }
                case "disabled" -> { if (!node.hasAttribute("disabled")) return false; }
                case "enabled" -> { if (node.hasAttribute("disabled")) return false; }
                case "link" -> { if (!node.tag.equals("a") || node.attr("href").isEmpty()) return false; }
                case "visited", "focus", "hover" -> { /* no interactive state model yet */ }
            }
        }
        String tag = PSEUDO_PATTERN.matcher(s).replaceAll("").replaceAll("#[\\w-]+|\\.[\\w-]+", "").trim();
        Matcher ids = Pattern.compile("#([\\w-]+)").matcher(s);
        while (ids.find()) if (!ids.group(1).equals(node.attr("id"))) return false;
        Matcher classes = Pattern.compile("\\.([\\w-]+)").matcher(s);
        String classNames = " " + node.attr("class").trim().replaceAll("\\s+", " ") + " ";
        while (classes.find()) if (!classNames.contains(" " + classes.group(1) + " ")) return false;
        return tag.isEmpty() || tag.equals("*") || tag.equalsIgnoreCase(node.tag);
    }
    private static int elementIndex(Dom.Node node) {
        if (node.parent == null) return 1; int index = 0;
        for (Dom.Node sibling : node.parent.children) { if (!sibling.isText()) index++; if (sibling == node) return index; }
        return index;
    }
    private static int elementIndexFromEnd(Dom.Node node) {
        if (node.parent == null) return 1; int index = 0; List<Dom.Node> siblings = node.parent.children;
        for (int i = siblings.size() - 1; i >= 0; i--) { Dom.Node sibling = siblings.get(i); if (!sibling.isText()) index++; if (sibling == node) return index; }
        return index;
    }
    private static boolean matchesNth(int index, String expression) {
        String value = expression.trim().toLowerCase(Locale.ROOT);
        if (value.equals("odd")) return index % 2 == 1;
        if (value.equals("even")) return index % 2 == 0;
        try { return index == Integer.parseInt(value); } catch (NumberFormatException ignored) { return false; }
    }
    private static int specificity(String selector) {
        int ids = count(selector, '#');
        int classes = count(selector, '.') + count(selector, '[') + count(selector, ':');
        int tags = 0;
        for (String part : selector.replace(">", " ").replace("+", " ").replace("~", " ").trim().split("\\s+")) {
            String tag = part.replaceAll("#[\\w-]+|\\.[\\w-]+|\\[.*?]|:.*", "");
            if (!tag.isBlank() && !tag.equals("*")) tags++;
        }
        return ids * 100 + classes * 10 + tags;
    }
    private static int count(String text, char ch) { int n = 0; for (int i = 0; i < text.length(); i++) if (text.charAt(i) == ch) n++; return n; }
    private static String stripComments(String css) { return css.replaceAll("(?s)/\\*.*?\\*/", ""); }
    public static Color parseColor(String raw, Color fallback) {
        if (raw == null) return fallback;
        String text = raw.trim().toLowerCase(Locale.ROOT);
        try {
            if (text.startsWith("#")) {
                String hex = text.substring(1);
                if (hex.length() == 3) return new Color(Integer.parseInt("" + hex.charAt(0) + hex.charAt(0), 16), Integer.parseInt("" + hex.charAt(1) + hex.charAt(1), 16), Integer.parseInt("" + hex.charAt(2) + hex.charAt(2), 16));
                if (hex.length() == 6) return new Color(Integer.parseInt(hex.substring(0, 2), 16), Integer.parseInt(hex.substring(2, 4), 16), Integer.parseInt(hex.substring(4, 6), 16));
                if (hex.length() == 8) return new Color((int) Long.parseLong(hex, 16), true);
            }
            Matcher rgb = Pattern.compile("rgba?\\(\\s*(\\d{1,3})\\s*,\\s*(\\d{1,3})\\s*,\\s*(\\d{1,3})(?:\\s*,\\s*([0-9.]+))?\\s*\\)").matcher(text);
            if (rgb.matches()) {
                int alpha = rgb.group(4) == null ? 255 : Math.max(0, Math.min(255, Math.round(Float.parseFloat(rgb.group(4)) * 255)));
                return new Color(clamp(Integer.parseInt(rgb.group(1))), clamp(Integer.parseInt(rgb.group(2))), clamp(Integer.parseInt(rgb.group(3))), alpha);
            }
        } catch (Exception ignored) { return fallback; }
        return switch (text) {
            case "black" -> Color.BLACK; case "white" -> Color.WHITE; case "red" -> Color.RED; case "blue" -> Color.BLUE;
            case "green" -> new Color(0, 128, 0); case "gray", "grey" -> Color.GRAY; case "yellow" -> Color.YELLOW;
            case "orange" -> Color.ORANGE; case "purple" -> new Color(128, 0, 128); case "transparent" -> new Color(0, 0, 0, 0);
            case "navy" -> new Color(0, 0, 128); case "teal" -> new Color(0, 128, 128); case "silver" -> new Color(192, 192, 192);
            default -> fallback;
        };
    }
    private static int clamp(int value) { return Math.max(0, Math.min(255, value)); }
}

package engine;

import java.awt.Color;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A small CSS parser/cascade for the browser's supported HTML subset. */
public final class CssEngine {
    private static final Pattern RULE_PATTERN = Pattern.compile("(?s)([^{}]+)\\{([^{}]*)}");
    private static final Pattern ID_PATTERN = Pattern.compile("#([\\w-]+)");
    private static final Pattern CLASS_PATTERN = Pattern.compile("\\.([\\w-]+)");
    private static final String INHERITED = " color font-family font-size font-style font-weight line-height text-align white-space ";

    public record Style(Map<String, String> values) {
        public String get(String property, String fallback) {
            return values.getOrDefault(property.toLowerCase(Locale.ROOT), fallback);
        }
        public int px(String property, int fallback) {
            String value = get(property, "").trim().toLowerCase(Locale.ROOT);
            try {
                if (value.endsWith("px")) value = value.substring(0, value.length() - 2).trim();
                else if (value.endsWith("pt")) value = Double.toString(Double.parseDouble(value.substring(0, value.length() - 2).trim()) * 4.0 / 3.0);
                else if (value.endsWith("em")) value = Double.toString(Double.parseDouble(value.substring(0, value.length() - 2).trim()) * 16);
                return (int) Math.round(Double.parseDouble(value));
            } catch (Exception ignored) { return fallback; }
        }
        public Color color(String property, Color fallback) { return parseColor(get(property, ""), fallback); }
    }

    private record Rule(String selector, Map<String, String> declarations, int specificity, int order) {}
    private final List<Rule> rules = new ArrayList<>();

    public CssEngine(String css) {
        if (css == null || css.isEmpty()) return;
        String clean = stripComments(css);
        Matcher matcher = RULE_PATTERN.matcher(clean);
        int order = 0;
        while (matcher.find()) {
            String selectorText = matcher.group(1).trim();
            if (selectorText.isEmpty() || selectorText.startsWith("@")) continue;
            Map<String, String> declarations = parseDeclarations(matcher.group(2));
            for (String selector : splitSelectors(selectorText)) {
                if (!selector.isBlank()) rules.add(new Rule(selector.trim(), declarations, specificity(selector), order++));
            }
        }
    }

    /** Computes a node's style, inheriting the standard text properties from its parent. */
    public Style compute(Dom.Node node) {
        if (node == null || node.isText()) return new Style(Map.of());
        Map<String, String> inherited = new HashMap<>();
        Dom.Node parent = node.parent;
        if (parent != null && !parent.isText()) {
            Style parentStyle = compute(parent);
            for (String property : INHERITED.trim().split("\\s+")) {
                String value = parentStyle.get(property, "");
                if (!value.isEmpty()) inherited.put(property, value);
            }
        }
        return compute(node, inherited);
    }

    private Style compute(Dom.Node node, Map<String, String> inherited) {
        Map<String, String> values = new HashMap<>(inherited);
        if (Dom.isBlock(node.tag)) {
            values.put("display", "block");
            values.put("margin", "6px");
        }
        if (node.tag.equals("a")) { values.put("color", "#0645ad"); values.put("text-decoration", "underline"); }
        if (node.tag.equals("strong") || node.tag.equals("b")) values.put("font-weight", "bold");
        if (node.tag.equals("em") || node.tag.equals("i")) values.put("font-style", "italic");
        if (node.tag.matches("h[1-6]")) {
            values.put("font-weight", "bold");
            values.put("font-size", node.tag.equals("h1") ? "30px" : node.tag.equals("h2") ? "26px" : "22px");
        }
        if (node.tag.equals("pre")) values.put("white-space", "pre");

        List<Rule> matching = new ArrayList<>();
        for (Rule rule : rules) if (matches(node, rule.selector)) matching.add(rule);
        matching.sort((a, b) -> a.specificity != b.specificity
                ? Integer.compare(a.specificity, b.specificity) : Integer.compare(a.order, b.order));
        for (Rule rule : matching) values.putAll(rule.declarations);
        values.putAll(parseDeclarations(node.attr("style")));
        expandBoxShorthands(values);

        for (Map.Entry<String, String> entry : values.entrySet()) {
            if ("inherit".equalsIgnoreCase(entry.getValue())) {
                String parentValue = inherited.get(entry.getKey());
                if (parentValue != null) entry.setValue(parentValue);
            }
        }
        values.putIfAbsent("color", "#222222");
        values.putIfAbsent("font-family", "SansSerif");
        values.putIfAbsent("font-size", "16px");
        return new Style(Map.copyOf(values));
    }

    private static void expandBoxShorthands(Map<String, String> values) {
        expandFour(values, "margin");
        expandFour(values, "padding");
    }

    private static void expandFour(Map<String, String> values, String name) {
        String shorthand = values.get(name);
        if (shorthand == null) return;
        String[] parts = shorthand.trim().split("\\s+");
        if (parts.length == 0 || parts.length > 4) return;
        String top = parts[0];
        String right = parts.length > 1 ? parts[1] : top;
        String bottom = parts.length > 2 ? parts[2] : top;
        String left = parts.length > 3 ? parts[3] : right;
        values.put(name + "-top", top);
        values.put(name + "-right", right);
        values.put(name + "-bottom", bottom);
        values.put(name + "-left", left);
    }

    private static Map<String, String> parseDeclarations(String source) {
        Map<String, String> result = new HashMap<>();
        for (String declaration : splitDeclarations(source)) {
            int colon = declaration.indexOf(':');
            if (colon < 1) continue;
            String key = declaration.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            String value = declaration.substring(colon + 1).trim();
            if (value.toLowerCase(Locale.ROOT).endsWith("!important")) {
                value = value.substring(0, value.length() - 10).trim();
            }
            if (!key.isEmpty() && !value.isEmpty()) result.put(key, value);
        }
        return result;
    }

    private static List<String> splitDeclarations(String source) {
        List<String> result = new ArrayList<>();
        int start = 0, depth = 0;
        char quote = 0;
        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i);
            if (quote != 0) { if (c == quote && (i == 0 || source.charAt(i - 1) != '\\')) quote = 0; }
            else if (c == '\'' || c == '"') quote = c;
            else if (c == '(') depth++;
            else if (c == ')') depth = Math.max(0, depth - 1);
            else if (c == ';' && depth == 0) { result.add(source.substring(start, i)); start = i + 1; }
        }
        result.add(source.substring(start));
        return result;
    }

    private static List<String> splitSelectors(String source) {
        List<String> result = new ArrayList<>();
        int start = 0, brackets = 0, parens = 0;
        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '[') brackets++;
            else if (c == ']') brackets--;
            else if (c == '(') parens++;
            else if (c == ')') parens--;
            else if (c == ',' && brackets == 0 && parens == 0) { result.add(source.substring(start, i)); start = i + 1; }
        }
        result.add(source.substring(start));
        return result;
    }

    private static boolean matches(Dom.Node node, String selector) {
        List<String> parts = selector.trim().replace(">", " > ").trim().replaceAll("\\s+", " ").lines().toList();
        String[] tokens = parts.isEmpty() ? new String[0] : parts.get(0).split(" ");
        if (tokens.length == 0) return false;
        int i = tokens.length - 1;
        Dom.Node current = node;
        if (!matchesCompound(current, tokens[i--])) return false;
        while (i >= 0) {
            boolean direct = tokens[i].equals(">");
            if (direct) i--;
            if (i < 0) return false;
            if (direct) {
                current = current.parent;
                if (current == null || !matchesCompound(current, tokens[i--])) return false;
            } else {
                String ancestorSelector = tokens[i--];
                current = current.parent;
                while (current != null && !matchesCompound(current, ancestorSelector)) current = current.parent;
                if (current == null) return false;
            }
        }
        return true;
    }

    private static boolean matchesCompound(Dom.Node node, String selector) {
        if (node == null || node.isText()) return false;
        String s = selector.trim();
        if (s.isEmpty()) return false;
        Matcher ids = ID_PATTERN.matcher(s);
        while (ids.find()) if (!ids.group(1).equals(node.attr("id"))) return false;
        Matcher classes = CLASS_PATTERN.matcher(s);
        String classNames = " " + node.attr("class").trim().replaceAll("\\s+", " ") + " ";
        while (classes.find()) if (!classNames.contains(" " + classes.group(1) + " ")) return false;

        Matcher pseudo = Pattern.compile(":(first-child|last-child|nth-child\\(([^)]*)\\)|not\\(([^)]*)\\))", Pattern.CASE_INSENSITIVE).matcher(s);
        while (pseudo.find()) {
            String name = pseudo.group(1).toLowerCase(Locale.ROOT);
            if (name.equals("first-child") && elementIndex(node) != 1) return false;
            if (name.equals("last-child") && elementIndexFromEnd(node) != 1) return false;
            if (name.startsWith("nth-child") && !matchesNth(elementIndex(node), pseudo.group(2))) return false;
            if (name.startsWith("not") && matchesCompound(node, pseudo.group(3))) return false;
        }

        String tag = s.replaceAll("#[\\w-]+|\\.[\\w-]+|:(first-child|last-child|nth-child\\([^)]*\\)|not\\([^)]*\\))", "");
        return tag.isBlank() || tag.equals("*") || tag.equalsIgnoreCase(node.tag);
    }

    private static int elementIndex(Dom.Node node) {
        if (node.parent == null) return 1;
        int index = 0;
        for (Dom.Node sibling : node.parent.children) {
            if (!sibling.isText()) index++;
            if (sibling == node) return index;
        }
        return index;
    }

    private static int elementIndexFromEnd(Dom.Node node) {
        if (node.parent == null) return 1;
        int index = 0;
        List<Dom.Node> children = node.parent.children;
        for (int i = children.size() - 1; i >= 0; i--) {
            Dom.Node sibling = children.get(i);
            if (!sibling.isText()) index++;
            if (sibling == node) return index;
        }
        return index;
    }

    private static boolean matchesNth(int index, String expression) {
        String value = expression.trim().toLowerCase(Locale.ROOT);
        if (value.equals("odd")) return index % 2 == 1;
        if (value.equals("even")) return index % 2 == 0;
        try { return index == Integer.parseInt(value); }
        catch (NumberFormatException ignored) { return false; }
    }

    private static int specificity(String selector) {
        int ids = count(selector, '#');
        int classes = count(selector, '.') + count(selector, ':');
        int tags = 0;
        for (String part : selector.replace(">", " ").trim().split("\\s+")) {
            String tag = part.replaceAll("#[\\w-]+|\\.[\\w-]+|:.*", "");
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
                if (hex.length() == 8) return new Color(Integer.parseInt(hex.substring(0, 2), 16), Integer.parseInt(hex.substring(2, 4), 16), Integer.parseInt(hex.substring(4, 6), 16), Integer.parseInt(hex.substring(6, 8), 16));
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
            default -> fallback;
        };
    }

    private static int clamp(int value) { return Math.max(0, Math.min(255, value)); }
}

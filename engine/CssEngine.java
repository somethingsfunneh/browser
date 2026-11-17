package engine;

import java.awt.Color;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Basic CSS declarations, selector matching, specificity and inline-style cascade. */
public final class CssEngine {
    public record Style(Map<String, String> values) {
        public String get(String property, String fallback) { return values.getOrDefault(property.toLowerCase(Locale.ROOT), fallback); }
        public int px(String property, int fallback) {
            String value = get(property, "").trim().toLowerCase(Locale.ROOT);
            try {
                if (value.endsWith("px")) value = value.substring(0, value.length() - 2).trim();
                else if (value.endsWith("pt")) value = String.valueOf(Double.parseDouble(value.substring(0, value.length() - 2).trim()) * 4.0 / 3.0);
                return (int) Math.round(Double.parseDouble(value));
            } catch (Exception ignored) { return fallback; }
        }
        public Color color(String property, Color fallback) { return parseColor(get(property, ""), fallback); }
    }

    private record Rule(String selector, Map<String, String> declarations, int specificity, int order) {}
    private final List<Rule> rules = new ArrayList<>();

    public CssEngine(String css) {
        Matcher matcher = Pattern.compile("(?s)([^{}]+)\\{([^{}]*)}").matcher(css == null ? "" : css.replaceAll("(?s)/\\*.*?\\*/", ""));
        int order = 0;
        while (matcher.find()) {
            String selector = matcher.group(1).trim();
            if (selector.isEmpty() || selector.startsWith("@")) continue;
            Map<String, String> declarations = parseDeclarations(matcher.group(2));
            for (String part : selector.split(",")) {
                String value = part.trim();
                if (!value.isEmpty()) rules.add(new Rule(value, declarations, specificity(value), order++));
            }
        }
    }

    public Style compute(Dom.Node node) {
        Map<String, String> values = new HashMap<>();
        if (node == null || node.isText()) return new Style(values);
        if (Dom.isBlock(node.tag)) { values.put("display", "block"); values.put("margin", "6px"); }
        if (node.tag.equals("a")) { values.put("color", "#0645ad"); values.put("text-decoration", "underline"); }
        if (node.tag.equals("strong") || node.tag.equals("b")) values.put("font-weight", "bold");
        if (node.tag.equals("em") || node.tag.equals("i")) values.put("font-style", "italic");
        if (node.tag.matches("h[1-6]")) { values.put("font-weight", "bold"); values.put("font-size", node.tag.equals("h1") ? "30px" : "22px"); }
        if (node.tag.equals("pre")) values.put("white-space", "pre");

        List<Rule> matching = new ArrayList<>();
        for (Rule rule : rules) if (matches(node, rule.selector)) matching.add(rule);
        matching.sort((a, b) -> a.specificity != b.specificity ? Integer.compare(a.specificity, b.specificity) : Integer.compare(a.order, b.order));
        for (Rule rule : matching) values.putAll(rule.declarations);
        values.putAll(parseDeclarations(node.attr("style")));
        values.putIfAbsent("color", "#222222");
        return new Style(values);
    }

    private static Map<String, String> parseDeclarations(String source) {
        Map<String, String> result = new HashMap<>();
        for (String declaration : source.split(";")) {
            int colon = declaration.indexOf(':');
            if (colon < 1) continue;
            String key = declaration.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            String value = declaration.substring(colon + 1).trim();
            if (!key.isEmpty() && !value.isEmpty()) result.put(key, value);
        }
        return result;
    }

    private static boolean matches(Dom.Node node, String selector) {
        String[] parts = selector.trim().split("\\s+");
        if (parts.length == 0 || !matchesSimple(node, parts[parts.length - 1])) return false;
        Dom.Node ancestor = node.parent;
        for (int i = parts.length - 2; i >= 0; i--) {
            while (ancestor != null && !matchesSimple(ancestor, parts[i])) ancestor = ancestor.parent;
            if (ancestor == null) return false;
            ancestor = ancestor.parent;
        }
        return true;
    }

    private static boolean matchesSimple(Dom.Node node, String selector) {
        if (node == null || node.isText()) return false;
        String s = selector.trim();
        Matcher ids = Pattern.compile("#([\\w-]+)").matcher(s);
        while (ids.find()) if (!ids.group(1).equals(node.attr("id"))) return false;
        Matcher classes = Pattern.compile("\\.([\\w-]+)").matcher(s);
        String classNames = " " + node.attr("class").trim().replaceAll("\\s+", " ") + " ";
        while (classes.find()) if (!classNames.contains(" " + classes.group(1) + " ")) return false;
        String tag = s.replaceAll("[#.].*", "");
        return tag.isEmpty() || tag.equals("*") || tag.equalsIgnoreCase(node.tag);
    }

    private static int specificity(String selector) {
        int ids = count(selector, '#'), classes = count(selector, '.');
        int tags = 0;
        for (String item : selector.trim().split("\\s+")) if (!item.startsWith("#") && !item.startsWith(".")) tags++;
        return ids * 100 + classes * 10 + tags;
    }

    private static int count(String text, char ch) { int n = 0; for (int i = 0; i < text.length(); i++) if (text.charAt(i) == ch) n++; return n; }

    public static Color parseColor(String raw, Color fallback) {
        if (raw == null) return fallback;
        String text = raw.trim().toLowerCase(Locale.ROOT);
        try {
            if (text.startsWith("#")) {
                String hex = text.substring(1);
                if (hex.length() == 3) return new Color(Integer.parseInt("" + hex.charAt(0) + hex.charAt(0), 16), Integer.parseInt("" + hex.charAt(1) + hex.charAt(1), 16), Integer.parseInt("" + hex.charAt(2) + hex.charAt(2), 16));
                if (hex.length() == 6) return new Color(Integer.parseInt(hex.substring(0, 2), 16), Integer.parseInt(hex.substring(2, 4), 16), Integer.parseInt(hex.substring(4, 6), 16));
            }
        } catch (Exception ignored) { return fallback; }
        return switch (text) {
            case "black" -> Color.BLACK; case "white" -> Color.WHITE; case "red" -> Color.RED; case "blue" -> Color.BLUE;
            case "green" -> new Color(0, 128, 0); case "gray", "grey" -> Color.GRAY; case "yellow" -> Color.YELLOW;
            case "orange" -> Color.ORANGE; case "purple" -> new Color(128, 0, 128); case "transparent" -> new Color(0, 0, 0, 0);
            default -> fallback;
        };
    }
}

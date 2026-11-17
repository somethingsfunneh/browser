import java.awt.Color;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Basic CSS rules, cascade and selectors (tag, #id, .class and descendants). */
public final class CssEngine {
    private CssEngine() {}
    public record Style(Map<String, String> values) {
        public String get(String key, String fallback) { return values.getOrDefault(key, fallback); }
        public int px(String key, int fallback) { try { return (int) Math.round(Double.parseDouble(get(key, "" ).replace("px", "").trim())); } catch (Exception e) { return fallback; } }
        public Color color(String key, Color fallback) { return parseColor(get(key, ""), fallback); }
    }
    private record Rule(String selector, Map<String, String> declarations, int specificity, int order) {}
    private final List<Rule> rules = new ArrayList<>();
    public CssEngine(String css) {
        Matcher matcher = Pattern.compile("(?s)([^{}]+)\\{([^{}]*)}").matcher(css == null ? "" : css);
        int order = 0;
        while (matcher.find()) {
            String selector = matcher.group(1).trim();
            if (selector.startsWith("@")) continue;
            Map<String, String> declarations = parseDeclarations(matcher.group(2));
            for (String part : selector.split(",")) { String s = part.trim(); if (!s.isEmpty()) rules.add(new Rule(s, declarations, specificity(s), order++)); }
        }
    }
    public Style compute(Dom.Node node) {
        Map<String, String> values = new HashMap<>();
        if (Dom.isBlock(node.tag)) { values.put("display", "block"); values.put("margin", "4px"); }
        if (node.tag.matches("h[1-6]")) { values.put("font-weight", "bold"); values.put("font-size", node.tag.equals("h1") ? "30px" : "22px"); }
        if (node.tag.equals("a")) { values.put("color", "#0645ad"); values.put("text-decoration", "underline"); }
        if (node.tag.equals("strong") || node.tag.equals("b")) values.put("font-weight", "bold");
        if (node.tag.equals("i") || node.tag.equals("em")) values.put("font-style", "italic");
        if (node.tag.equals("pre")) values.put("white-space", "pre");
        List<Rule> matches = new ArrayList<>(); for (Rule rule : rules) if (matches(node, rule.selector)) matches.add(rule);
        matches.sort((a,b) -> { int c = Integer.compare(a.specificity, b.specificity); return c != 0 ? c : Integer.compare(a.order, b.order); });
        for (Rule rule : matches) values.putAll(rule.declarations);
        values.putAll(parseDeclarations(node.attr("style")));
        return new Style(values);
    }
    private static Map<String,String> parseDeclarations(String css) {
        Map<String,String> map = new HashMap<>();
        for (String part : css.split(";")) { int colon = part.indexOf(':'); if (colon < 1) continue; String key = part.substring(0, colon).trim().toLowerCase(Locale.ROOT); String value = part.substring(colon + 1).trim(); if (!key.isEmpty() && !value.isEmpty() && !key.startsWith("--")) map.put(key, value); }
        return map;
    }
    private static boolean matches(Dom.Node node, String selector) {
        String[] parts = selector.trim().split("\\s+"); Dom.Node cursor = node;
        if (!matchesSimple(cursor, parts[parts.length - 1])) return false;
        for (int i = parts.length - 2; i >= 0; i--) { cursor = cursor.parent; while (cursor != null && !matchesSimple(cursor, parts[i])) cursor = cursor.parent; if (cursor == null) return false; }
        return true;
    }
    private static boolean matchesSimple(Dom.Node node, String selector) {
        if (node == null || node.isText()) return false;
        String tag = selector.replaceAll("[.#].*", ""); if (!tag.isEmpty() && !tag.equals("*") && !tag.equalsIgnoreCase(node.tag)) return false;
        Matcher ids = Pattern.compile("#([\\w-]+)").matcher(selector); while (ids.find()) if (!ids.group(1).equals(node.attr("id"))) return false;
        Matcher classes = Pattern.compile("\\.([\\w-]+)").matcher(selector); String classAttr = " " + node.attr("class").trim().replaceAll("\\s+", " ") + " "; while (classes.find()) if (!classAttr.contains(" " + classes.group(1) + " ")) return false;
        return true;
    }
    private static int specificity(String s) { int ids = count(s, '#'), classes = count(s, '.'), tags = s.trim().split("\\s+").length; return ids * 100 + classes * 10 + tags; }
    private static int count(String s, char c) { int n = 0; for (int i=0;i<s.length();i++) if(s.charAt(i)==c)n++; return n; }
    public static Color parseColor(String text, Color fallback) {
        if (text == null) return fallback; String s = text.trim().toLowerCase(Locale.ROOT);
        try { if (s.startsWith("#")) { String h=s.substring(1); if(h.length()==3) return new Color(Integer.parseInt(""+h.charAt(0)+h.charAt(0),16),Integer.parseInt(""+h.charAt(1)+h.charAt(1),16),Integer.parseInt(""+h.charAt(2)+h.charAt(2),16)); return new Color(Integer.parseInt(h.substring(0,2),16),Integer.parseInt(h.substring(2,4),16),Integer.parseInt(h.substring(4,6),16)); } }
        catch (Exception ignored) {}
        return switch(s) { case "black" -> Color.BLACK; case "white" -> Color.WHITE; case "red" -> Color.RED; case "blue" -> Color.BLUE; case "green" -> new Color(0,128,0); case "gray","grey" -> Color.GRAY; case "yellow" -> Color.YELLOW; case "transparent" -> new Color(0,0,0,0); default -> fallback; };
    }
}

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** A small, forgiving HTML tree builder for the custom renderer. */
public final class Dom {
    private Dom() {}

    public static final class Node {
        public final String tag;
        public final Map<String, String> attributes = new LinkedHashMap<>();
        public final List<Node> children = new ArrayList<>();
        public Node parent;
        public String text = "";

        private Node(String tag) { this.tag = tag; }
        public boolean isText() { return "#text".equals(tag); }
        public String attr(String name) { return attributes.getOrDefault(name.toLowerCase(Locale.ROOT), ""); }
        public String textContent() {
            if (isText()) return text;
            StringBuilder out = new StringBuilder();
            for (Node child : children) out.append(child.textContent());
            return out.toString();
        }
        public Node byId(String id) {
            if (id.equals(attr("id"))) return this;
            for (Node child : children) { Node found = child.byId(id); if (found != null) return found; }
            return null;
        }
        public void setTextContent(String value) {
            children.clear();
            Node textNode = new Node("#text"); textNode.text = value; textNode.parent = this; children.add(textNode);
        }
    }

    private static final String VOID = " area base br col embed hr img input link meta param source track wbr ";
    private static final String BLOCK = " address article aside blockquote body div dl fieldset footer form h1 h2 h3 h4 h5 h6 header hr li main nav ol p pre section table tr ul " ;

    public static Node parse(String html) {
        Node root = new Node("document");
        Deque<Node> stack = new ArrayDeque<>(); stack.push(root);
        int i = 0;
        while (i < html.length()) {
            if (html.startsWith("<!--", i)) {
                int end = html.indexOf("-->", i + 4); i = end < 0 ? html.length() : end + 3; continue;
            }
            if (html.regionMatches(true, i, "<script", 0, 7) || html.regionMatches(true, i, "<style", 0, 6)) {
                int start = html.indexOf('>', i);
                if (start < 0) break;
                String tag = html.regionMatches(true, i, "<script", 0, 7) ? "script" : "style";
                int close = indexOfIgnoreCase(html, "</" + tag, start + 1);
                Node element = new Node(tag); element.parent = stack.peek(); stack.peek().children.add(element);
                if (close < 0) { Node t = new Node("#text"); t.text = html.substring(start + 1); t.parent = element; element.children.add(t); break; }
                Node t = new Node("#text"); t.text = html.substring(start + 1, close); t.parent = element; element.children.add(t);
                int end = html.indexOf('>', close); i = end < 0 ? html.length() : end + 1; continue;
            }
            if (html.charAt(i) != '<') {
                int end = html.indexOf('<', i); if (end < 0) end = html.length();
                String value = decodeEntities(html.substring(i, end));
                if (!value.isEmpty()) { Node text = new Node("#text"); text.text = value; text.parent = stack.peek(); stack.peek().children.add(text); }
                i = end; continue;
            }
            int end = tagEnd(html, i + 1);
            if (end < 0) { appendText(stack.peek(), "<"); i++; continue; }
            String token = html.substring(i + 1, end).trim(); i = end + 1;
            if (token.isEmpty() || token.charAt(0) == '!' || token.charAt(0) == '?') continue;
            if (token.charAt(0) == '/') {
                String closing = token.substring(1).trim().split("\\s", 2)[0].toLowerCase(Locale.ROOT);
                while (stack.size() > 1) { Node popped = stack.pop(); if (popped.tag.equals(closing)) break; }
                continue;
            }
            boolean selfClosing = token.endsWith("/");
            if (selfClosing) token = token.substring(0, token.length() - 1).trim();
            int nameEnd = 0; while (nameEnd < token.length() && !Character.isWhitespace(token.charAt(nameEnd))) nameEnd++;
            String name = token.substring(0, nameEnd).toLowerCase(Locale.ROOT);
            if (name.isEmpty()) continue;
            if ((name.equals("li") || name.equals("p")) && stack.peek().tag.equals(name)) stack.pop();
            Node element = new Node(name); element.parent = stack.peek(); stack.peek().children.add(element);
            parseAttributes(token.substring(nameEnd), element.attributes);
            if (!selfClosing && !VOID.contains(" " + name + " ")) stack.push(element);
        }
        return root;
    }

    private static void appendText(Node node, String value) { Node t = new Node("#text"); t.text = value; t.parent = node; node.children.add(t); }
    private static int indexOfIgnoreCase(String value, String search, int from) { return value.toLowerCase(Locale.ROOT).indexOf(search.toLowerCase(Locale.ROOT), from); }
    private static int tagEnd(String html, int start) {
        char quote = 0;
        for (int i = start; i < html.length(); i++) { char c = html.charAt(i); if (quote != 0) { if (c == quote) quote = 0; } else if (c == '\'' || c == '"') quote = c; else if (c == '>') return i; }
        return -1;
    }
    private static void parseAttributes(String source, Map<String, String> out) {
        int i = 0;
        while (i < source.length()) {
            while (i < source.length() && (Character.isWhitespace(source.charAt(i)) || source.charAt(i) == '/')) i++;
            int start = i; while (i < source.length() && !Character.isWhitespace(source.charAt(i)) && source.charAt(i) != '=' && source.charAt(i) != '/') i++;
            if (i == start) { i++; continue; }
            String key = source.substring(start, i).toLowerCase(Locale.ROOT);
            while (i < source.length() && Character.isWhitespace(source.charAt(i))) i++;
            String value = "";
            if (i < source.length() && source.charAt(i) == '=') {
                i++; while (i < source.length() && Character.isWhitespace(source.charAt(i))) i++;
                if (i < source.length() && (source.charAt(i) == '\'' || source.charAt(i) == '"')) { char q = source.charAt(i++); int v = i; while (i < source.length() && source.charAt(i) != q) i++; value = source.substring(v, i); if (i < source.length()) i++; }
                else { int v = i; while (i < source.length() && !Character.isWhitespace(source.charAt(i))) i++; value = source.substring(v, i); }
            }
            out.putIfAbsent(key, decodeEntities(value));
        }
    }
    public static String decodeEntities(String value) {
        return value.replace("&nbsp;", "\u00a0").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'");
    }
    public static boolean isBlock(String tag) { return BLOCK.contains(" " + tag + " "); }
}

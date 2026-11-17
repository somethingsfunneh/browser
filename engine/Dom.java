package engine;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Lightweight forgiving HTML parser and mutable document tree. */
public final class Dom {
    private Dom() {}

    public static final class Node {
        public final String tag;
        public final Map<String, String> attributes = new LinkedHashMap<>();
        public final List<Node> children = new ArrayList<>();
        public Node parent;
        public String text = "";

        public Node(String tag) { this.tag = tag; }
        public boolean isText() { return "#text".equals(tag); }
        public String attr(String name) { return attributes.getOrDefault(name.toLowerCase(Locale.ROOT), ""); }
        public String textContent() {
            if (isText()) return text;
            StringBuilder result = new StringBuilder();
            for (Node child : children) result.append(child.textContent());
            return result.toString();
        }
        public Node byId(String id) {
            if (id.equalsIgnoreCase(attr("id"))) return this;
            for (Node child : children) { Node found = child.byId(id); if (found != null) return found; }
            return null;
        }
        public Node querySelector(String selector) {
            List<Node> all = new ArrayList<>(); collect(this, all);
            for (Node node : all) if (matches(node, selector)) return node;
            return null;
        }
        public void setTextContent(String value) {
            children.clear();
            Node textNode = new Node("#text"); textNode.text = value == null ? "" : value; textNode.parent = this; children.add(textNode);
        }
        public void setInnerHTML(String html) {
            children.clear();
            Node fragment = parse(html == null ? "" : html);
            for (Node child : fragment.children) { child.parent = this; children.add(child); }
        }
        public Node appendChild(String name) {
            Node child = new Node(name); child.parent = this; children.add(child); return child;
        }
        private static void collect(Node node, List<Node> out) {
            if (node == null) return;
            if (!node.isText()) out.add(node);
            for (Node child : node.children) collect(child, out);
        }
        private static boolean matches(Node node, String selector) {
            if (selector == null || selector.isBlank() || node.isText()) return false;
            String s = selector.trim();
            if (s.startsWith("#")) return s.substring(1).equalsIgnoreCase(node.attr("id"));
            if (s.startsWith(".")) return hasClass(node, s.substring(1));
            int hash = s.indexOf('#'), dot = s.indexOf('.');
            int split = hash < 0 ? dot : dot < 0 ? hash : Math.min(hash, dot);
            String tag = split < 0 ? s : s.substring(0, split);
            if (!tag.isEmpty() && !tag.equals("*") && !tag.equalsIgnoreCase(node.tag)) return false;
            if (hash >= 0) {
                int stop = dot > hash ? dot : s.length();
                if (!s.substring(hash + 1, stop).equalsIgnoreCase(node.attr("id"))) return false;
            }
            if (dot >= 0 && !hasClass(node, s.substring(dot + 1))) return false;
            return true;
        }
        private static boolean hasClass(Node node, String name) {
            return (" " + node.attr("class").trim().replaceAll("\\s+", " ") + " ").contains(" " + name.trim() + " ");
        }
    }

    private static final String VOID = " area base br col embed hr img input link meta param source track wbr ";
    public static Node parse(String html) {
        Node root = new Node("document");
        Deque<Node> stack = new ArrayDeque<>(); stack.push(root);
        int i = 0;
        while (i < html.length()) {
            if (html.startsWith("<!--", i)) { int end = html.indexOf("-->", i + 4); if (end < 0) break; i = end + 3; continue; }
            if (html.charAt(i) != '<') {
                int end = html.indexOf('<', i); if (end < 0) end = html.length();
                String value = decodeEntities(html.substring(i, end));
                if (!value.isEmpty()) { Node text = new Node("#text"); text.text = value; text.parent = stack.peek(); stack.peek().children.add(text); }
                i = end; continue;
            }
            if (html.regionMatches(true, i, "<script", 0, 7) || html.regionMatches(true, i, "<style", 0, 6)) {
                String tag = html.regionMatches(true, i, "<script", 0, 7) ? "script" : "style";
                int openEnd = html.indexOf('>', i); if (openEnd < 0) break;
                int close = html.toLowerCase(Locale.ROOT).indexOf("</" + tag, openEnd + 1);
                Node element = new Node(tag); element.parent = stack.peek(); stack.peek().children.add(element);
                if (close < 0) { Node text = new Node("#text"); text.text = html.substring(openEnd + 1); text.parent = element; element.children.add(text); break; }
                Node text = new Node("#text"); text.text = html.substring(openEnd + 1, close); text.parent = element; element.children.add(text);
                int closeEnd = html.indexOf('>', close); i = closeEnd < 0 ? html.length() : closeEnd + 1; continue;
            }
            int end = tagEnd(html, i + 1); if (end < 0) { appendText(stack.peek(), "<"); i++; continue; }
            String token = html.substring(i + 1, end).trim(); i = end + 1;
            if (token.isEmpty() || token.charAt(0) == '!' || token.charAt(0) == '?') continue;
            if (token.charAt(0) == '/') {
                String name = token.substring(1).trim().split("\\s", 2)[0].toLowerCase(Locale.ROOT);
                while (stack.size() > 1) if (stack.pop().tag.equals(name)) break;
                continue;
            }
            boolean selfClosing = token.endsWith("/"); if (selfClosing) token = token.substring(0, token.length() - 1).trim();
            int nameEnd = 0; while (nameEnd < token.length() && !Character.isWhitespace(token.charAt(nameEnd))) nameEnd++;
            String name = token.substring(0, nameEnd).toLowerCase(Locale.ROOT); if (name.isEmpty()) continue;
            if ((name.equals("li") || name.equals("p")) && stack.peek().tag.equals(name)) stack.pop();
            Node element = new Node(name); element.parent = stack.peek(); stack.peek().children.add(element);
            parseAttributes(token.substring(nameEnd), element.attributes);
            if (!selfClosing && !VOID.contains(" " + name + " ")) stack.push(element);
        }
        return root;
    }
    private static void appendText(Node node, String value) { Node text = new Node("#text"); text.text = value; text.parent = node; node.children.add(text); }
    private static int tagEnd(String value, int start) {
        char quote = 0;
        for (int i = start; i < value.length(); i++) { char c = value.charAt(i); if (quote != 0) { if (c == quote) quote = 0; } else if (c == '\'' || c == '"') quote = c; else if (c == '>') return i; }
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
    public static String decodeEntities(String text) {
        return text.replace("&nbsp;", "\u00A0").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'");
    }
    public static boolean isBlock(String tag) {
        return switch (tag) {
            case "address", "article", "aside", "blockquote", "body", "div", "dl", "fieldset", "footer", "form", "h1", "h2", "h3", "h4", "h5", "h6", "header", "hr", "li", "main", "nav", "ol", "p", "pre", "section", "table", "tr", "ul" -> true;
            default -> false;
        };
    }
}

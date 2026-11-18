package engine;

import java.awt.Color;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Core web engine subsystems: HTML5 DOM parser & tree (Dom), CSS Preprocessor & Cascade (CssPreprocessor,
 * CssEngine, BrowserStyles), Accessibility & Element classification (AriaSupport, ElementSupport),
 * and the TinyJs interpreter.
 */
public final class WebCore {
    private WebCore() {}

    // =========================================================================
    // Accessibility & ARIA Support
    // =========================================================================

    public static final class AriaSupport {
        private AriaSupport() {}

        public static String role(Dom.Node node) {
            if (node == null || node.isText()) return "none";
            String role = node.attr("role").trim();
            if (!role.isEmpty()) {
                String firstRole = role.split("\\s+")[0].toLowerCase(Locale.ROOT);
                if (!firstRole.isEmpty()) return firstRole;
            }
            return switch (node.tag) {
                case "button", "summary" -> "button";
                case "a", "area" -> node.hasAttribute("href") ? "link" : "generic";
                case "img", "picture", "svg" -> "img";
                case "nav" -> "navigation";
                case "main" -> "main";
                case "header" -> isSectioningScoped(node) ? "generic" : "banner";
                case "footer" -> isSectioningScoped(node) ? "generic" : "contentinfo";
                case "aside" -> "complementary";
                case "section" -> hasAccessibleLabel(node) ? "region" : "generic";
                case "article" -> "article";
                case "form" -> "form";
                case "search" -> "search";
                case "dialog" -> "dialog";
                case "details" -> "group";
                case "fieldset" -> "group";
                case "figure" -> "figure";
                case "hr" -> "separator";
                case "ul", "ol", "menu", "dir" -> "list";
                case "li" -> "listitem";
                case "dl" -> "list";
                case "dt" -> "term";
                case "dd" -> "definition";
                case " math" -> "math";
                case "h1", "h2", "h3", "h4", "h5", "h6" -> "heading";
                case "table" -> "table";
                case "thead", "tbody", "tfoot" -> "rowgroup";
                case "tr" -> "row";
                case "th" -> "columnheader";
                case "td" -> "cell";
                case "textarea" -> "textbox";
                case "select" -> node.hasAttribute("multiple") || parseInt(node.attr("size"), 1) > 1 ? "listbox" : "combobox";
                case "option" -> "option";
                case "optgroup" -> "group";
                case "progress" -> "progressbar";
                case "meter" -> "meter";
                case "output" -> "status";
                case "dfn" -> "term";
                case "blockquote" -> "blockquote";
                case "code" -> "code";
                case "del", "s", "strike" -> "deletion";
                case "ins" -> "insertion";
                case "em" -> "emphasis";
                case "strong" -> "strong";
                case "sub" -> "subscript";
                case "sup" -> "superscript";
                case "time" -> "time";
                case "p" -> "paragraph";
                case "input" -> switch (node.attr("type").toLowerCase(Locale.ROOT)) {
                    case "checkbox" -> "checkbox";
                    case "radio" -> "radio";
                    case "range" -> "slider";
                    case "number" -> "spinbutton";
                    case "search" -> "searchbox";
                    case "button", "submit", "reset", "image" -> "button";
                    case "hidden" -> "none";
                    default -> "textbox";
                };
                default -> "generic";
            };
        }

        private static boolean isSectioningScoped(Dom.Node node) {
            for (Dom.Node p = node.parent; p != null; p = p.parent) {
                switch (p.tag) {
                    case "article", "aside", "main", "nav", "section" -> { return true; }
                }
            }
            return false;
        }

        private static boolean hasAccessibleLabel(Dom.Node node) {
            return !node.attr("aria-label").isBlank()
                    || !node.attr("aria-labelledby").isBlank()
                    || !node.attr("title").isBlank();
        }

        public static boolean hidden(Dom.Node node) {
            if (node == null) return true;
            for (Dom.Node cur = node; cur != null; cur = cur.parent) {
                if (cur.hasAttribute("hidden")
                        || cur.attr("aria-hidden").equalsIgnoreCase("true")
                        || cur.attr("hidden").equalsIgnoreCase("until-found")
                        || ("hidden".equalsIgnoreCase(cur.attr("type")) && "input".equals(cur.tag))) {
                    return true;
                }
                String inlineStyle = cur.attr("style").toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
                if (inlineStyle.contains("display:none") || inlineStyle.contains("visibility:hidden")) {
                    return true;
                }
            }
            return false;
        }

        public static boolean disabled(Dom.Node node) {
            if (node == null) return false;
            if (node.hasAttribute("disabled") || node.attr("aria-disabled").equalsIgnoreCase("true")) {
                return true;
            }
            for (Dom.Node p = node.parent; p != null; p = p.parent) {
                if ("fieldset".equals(p.tag) && p.hasAttribute("disabled")) {
                    return true;
                }
            }
            return false;
        }

        public static boolean focusable(Dom.Node node) {
            if (node == null || node.isText() || hidden(node) || disabled(node)) return false;
            String tabIndex = node.attr("tabindex").trim();
            if (!tabIndex.isEmpty()) {
                try {
                    return Integer.parseInt(tabIndex) >= 0;
                } catch (NumberFormatException ignored) {}
            }
            if ("true".equalsIgnoreCase(node.attr("contenteditable"))) return true;
            return switch (node.tag) {
                case "a", "area" -> !node.attr("href").isBlank();
                case "button", "input", "select", "textarea", "summary" -> true;
                case "iframe", "audio", "video" -> node.hasAttribute("controls") || "iframe".equals(node.tag);
                default -> false;
            };
        }

        public static int tabIndex(Dom.Node node) {
            if (node == null) return -1;
            String raw = node.attr("tabindex").trim();
            if (!raw.isEmpty()) {
                try {
                    return Integer.parseInt(raw);
                } catch (NumberFormatException ignored) {}
            }
            return focusable(node) ? 0 : -1;
        }

        public static String accessibleName(Dom.Node node) {
            if (node == null || hidden(node)) return "";
            String ariaLabel = node.attr("aria-label").trim();
            if (!ariaLabel.isEmpty()) return ariaLabel;

            String labelledBy = node.attr("aria-labelledby").trim();
            if (!labelledBy.isEmpty()) {
                Dom.Node root = node.ownerDocument();
                StringBuilder sb = new StringBuilder();
                for (String id : labelledBy.split("\\s+")) {
                    Dom.Node ref = root.byId(id);
                    if (ref != null) {
                        if (!sb.isEmpty()) sb.append(' ');
                        sb.append(ref.textContent().trim());
                    }
                }
                if (!sb.isEmpty()) return sb.toString();
            }

            switch (node.tag) {
                case "img", "area" -> {
                    String alt = node.attr("alt").trim();
                    if (!alt.isEmpty()) return alt;
                }
                case "input" -> {
                    String type = node.attr("type").toLowerCase(Locale.ROOT);
                    if (type.equals("button") || type.equals("submit") || type.equals("reset")) {
                        String val = node.attr("value").trim();
                        if (!val.isEmpty()) return val;
                        return type;
                    }
                    String label = findAssociatedLabelText(node);
                    if (!label.isEmpty()) return label;
                    String placeholder = node.attr("placeholder").trim();
                    if (!placeholder.isEmpty()) return placeholder;
                }
                case "textarea", "select", "meter", "progress", "output" -> {
                    String label = findAssociatedLabelText(node);
                    if (!label.isEmpty()) return label;
                }
                case "fieldset" -> {
                    for (Dom.Node child : node.children) {
                        if ("legend".equals(child.tag)) return child.textContent().trim();
                    }
                }
                case "figure" -> {
                    for (Dom.Node child : node.children) {
                        if ("figcaption".equals(child.tag)) return child.textContent().trim();
                    }
                }
                case "table" -> {
                    for (Dom.Node child : node.children) {
                        if ("caption".equals(child.tag)) return child.textContent().trim();
                    }
                }
            }

            String text = node.textContent().replaceAll("\\s+", " ").trim();
            if (!text.isEmpty()) return text;
            return node.attr("title").trim();
        }

        private static String findAssociatedLabelText(Dom.Node control) {
            for (Dom.Node p = control.parent; p != null; p = p.parent) {
                if ("label".equals(p.tag)) {
                    return p.textContent().replaceAll("\\s+", " ").trim();
                }
            }
            String id = control.attr("id").trim();
            if (!id.isEmpty()) {
                Dom.Node root = control.ownerDocument();
                for (Dom.Node label : root.getElementsByTagName("label")) {
                    if (id.equals(label.attr("for").trim())) {
                        return label.textContent().replaceAll("\\s+", " ").trim();
                    }
                }
            }
            return "";
        }

        public static int headingLevel(Dom.Node node) {
            if (node == null) return 0;
            String ariaLevel = node.attr("aria-level").trim();
            if (!ariaLevel.isEmpty()) {
                int parsed = parseInt(ariaLevel, 0);
                if (parsed > 0) return parsed;
            }
            return switch (node.tag) {
                case "h1" -> 1;
                case "h2" -> 2;
                case "h3" -> 3;
                case "h4" -> 4;
                case "h5" -> 5;
                case "h6" -> 6;
                default -> 0;
            };
        }

        private static int parseInt(String s, int fallback) {
            try {
                return Integer.parseInt(s.trim());
            } catch (Exception e) {
                return fallback;
            }
        }
    }

    // =========================================================================
    // HTML Element Classification Support
    // =========================================================================

    public static final class ElementSupport {
        private ElementSupport() {}

        public static boolean isVoidElement(String tag) {
            if (tag == null) return false;
            return switch (tag.toLowerCase(Locale.ROOT)) {
                case "area", "base", "basefont", "bgsound", "br", "col", "command",
                     "embed", "frame", "hr", "image", "img", "input", "isindex",
                     "keygen", "link", "menuitem", "meta", "nextid", "param",
                     "source", "track", "wbr" -> true;
                default -> false;
            };
        }

        public static boolean isBlockElement(String tag) {
            if (tag == null) return false;
            return switch (tag.toLowerCase(Locale.ROOT)) {
                case "address", "article", "aside", "blockquote", "body", "br", "canvas",
                     "caption", "center", "col", "colgroup", "dd", "details", "dialog",
                     "dir", "div", "dl", "dt", "fieldset", "figcaption", "figure",
                     "footer", "form", "h1", "h2", "h3", "h4", "h5", "h6", "header",
                     "hgroup", "hr", "html", "legend", "li", "listing", "main", "marquee",
                     "menu", "multicol", "nav", "noframes", "noscript", "ol", "optgroup",
                     "option", "p", "plaintext", "pre", "search", "section", "summary",
                     "table", "tbody", "td", "tfoot", "th", "thead", "tr", "ul", "xmp" -> true;
                default -> false;
            };
        }

        public static boolean isInlineElement(String tag) {
            if (tag == null) return false;
            return switch (tag.toLowerCase(Locale.ROOT)) {
                case "a", "abbr", "acronym", "audio", "b", "bdi", "bdo", "big", "blink",
                     "button", "cite", "code", "data", "datalist", "del", "dfn", "em",
                     "embed", "font", "i", "iframe", "img", "input", "ins", "kbd", "label",
                     "map", "mark", "meter", "nobr", "object", "output", "picture",
                     "progress", "q", "rb", "rp", "rt", "rtc", "ruby", "s", "samp",
                     "select", "slot", "small", "span", "strike", "strong", "sub", "sup",
                     "svg", "textarea", "time", "tt", "u", "var", "video", "wbr" -> true;
                default -> false;
            };
        }

        public static boolean isFormattingElement(String tag) {
            if (tag == null) return false;
            return switch (tag.toLowerCase(Locale.ROOT)) {
                case "a", "b", "big", "code", "em", "font", "i", "nobr", "s", "small",
                     "strike", "strong", "tt", "u" -> true;
                default -> false;
            };
        }

        public static boolean isFormAssociated(String tag) {
            if (tag == null) return false;
            return switch (tag.toLowerCase(Locale.ROOT)) {
                case "button", "fieldset", "input", "label", "meter", "object",
                     "output", "progress", "select", "textarea" -> true;
                default -> false;
            };
        }

        public static boolean isHeadingElement(String tag) {
            if (tag == null) return false;
            return switch (tag.toLowerCase(Locale.ROOT)) {
                case "h1", "h2", "h3", "h4", "h5", "h6" -> true;
                default -> false;
            };
        }

        public static boolean isTableSection(String tag) {
            if (tag == null) return false;
            return switch (tag.toLowerCase(Locale.ROOT)) {
                case "thead", "tbody", "tfoot" -> true;
                default -> false;
            };
        }

        public static boolean isInteractiveElement(String tag) {
            if (tag == null) return false;
            return switch (tag.toLowerCase(Locale.ROOT)) {
                case "a", "audio", "button", "details", "dialog", "embed", "iframe",
                     "img", "input", "label", " object", "select", "summary", "textarea", "video" -> true;
                default -> false;
            };
        }

        public static boolean isRawTextElement(String tag) {
            if (tag == null) return false;
            return switch (tag.toLowerCase(Locale.ROOT)) {
                case "script", "style", "textarea", "title", "xmp", "plaintext", "iframe", "noembed", "noframes" -> true;
                default -> false;
            };
        }

        public static boolean isObsoleteSupported(String tag) {
            if (tag == null) return false;
            return switch (tag.toLowerCase(Locale.ROOT)) {
                case "acronym", "applet", "basefont", "bgsound", "big", "blink", "center",
                     "dir", "font", "frame", "frameset", "hgroup", "isindex", "keygen",
                     "listing", "marquee", "menuitem", "multicol", "nextid", "nobr",
                     "noembed", "noframes", "param", "plaintext", "rb", "rtc", "s",
                     "spacer", "strike", "tt", "xmp" -> true;
                default -> false;
            };
        }
    }

    // =========================================================================
    // Default User-Agent Stylesheet
    // =========================================================================

    public static final class BrowserStyles {
        private BrowserStyles() {}

        public static String css() {
            return """
                    html, body { color:#202124; background-color:#ffffff; font-family:SansSerif; font-size:16px; line-height:1.4; }
                    body { display:block; margin:12px; }
                    div,main,article,section,nav,header,footer,aside,address,blockquote,form,fieldset,figure,details,dialog,search,menu,center,hgroup { display:block; }
                    span,a,strong,b,em,i,small,label,code,q,abbr,acronym,sub,sup,cite,dfn,kbd,samp,var,time,mark,bdi,bdo,ruby,rt,rp,data,output { display:inline; }
                    h1 { display:block; font-size:32px; font-weight:bold; margin:16px 0 10px 0; }
                    h2 { display:block; font-size:24px; font-weight:bold; margin:14px 0 8px 0; }
                    h3 { display:block; font-size:19px; font-weight:bold; margin:12px 0 6px 0; }
                    h4 { display:block; font-size:16px; font-weight:bold; margin:10px 0 6px 0; }
                    h5 { display:block; font-size:14px; font-weight:bold; margin:8px 0 4px 0; }
                    h6 { display:block; font-size:12px; font-weight:bold; margin:8px 0 4px 0; }
                    p { display:block; margin:10px 0; }
                    center { display:block; text-align:center; }
                    a { color:#1a0dab; text-decoration:underline; cursor:pointer; }
                    abbr[title],acronym[title] { text-decoration:underline dotted; cursor:help; }
                    strong,b { font-weight:bold; } em,i,cite,dfn,var,address { font-style:italic; }
                    small { font-size:13px; } big { font-size:19px; }
                    mark { color:#202124; background-color:#fff2a8; padding:1px 3px; }
                    del,s,strike { text-decoration:line-through; } ins,u { text-decoration:underline; }
                    sub { vertical-align:sub; font-size:12px; } sup { vertical-align:super; font-size:12px; }
                    nobr { white-space:nowrap; }
                    ul,menu,dir { display:block; list-style-type:disc; margin:10px 0; padding-left:32px; }
                    ol { display:block; list-style-type:decimal; margin:10px 0; padding-left:32px; }
                    ul ul, ol ul { list-style-type:circle; margin:4px 0; }
                    ul ul ul, ol ol ul { list-style-type:square; }
                    li { display:list-item; margin:4px 0; }
                    dl { display:block; margin:10px 0; } dt { display:block; font-weight:bold; margin-top:6px; } dd { display:block; margin-left:28px; }
                    blockquote { margin:12px 24px; border-left:4px solid #dadce0; padding:6px 14px; color:#5f6368; background-color:#f8f9fa; }
                    q { quotes:'"' '"' ''' '''; }
                    pre,xmp,plaintext,listing { display:block; white-space:pre; font-family:Monospaced; font-size:13px; margin:10px 0; background-color:#f1f3f4; padding:10px; border:1px solid #dadce0; border-radius:6px; }
                    code,kbd,samp,tt { font-family:Monospaced; font-size:13px; background-color:#f1f3f4; padding:1px 4px; border-radius:4px; }
                    kbd { border:1px solid #cbd5e1; background-color:#f8f9fa; }
                    hr { display:block; color:#dadce0; margin:12px 0; border:1px solid #dadce0; height:0; }
                    figure { margin:12px 24px; } figcaption { display:block; color:#5f6368; font-size:14px; text-align:center; margin-top:4px; }
                    fieldset { display:block; border:1px solid #dadce0; border-radius:6px; padding:10px 14px; margin:8px 0; }
                    legend { display:block; padding:0 6px; font-weight:bold; }
                    details { display:block; margin:6px 0; } summary { display:block; font-weight:bold; cursor:pointer; }
                    dialog { display:none; border:1px solid #dadce0; border-radius:8px; padding:16px; background-color:#ffffff; }
                    dialog[open] { display:block; }
                    table { display:table; border-collapse:collapse; border-spacing:0; margin:8px 0; }
                    caption { display:table-caption; font-weight:bold; text-align:center; padding:4px; }
                    thead { display:table-header-group; } tbody { display:table-row-group; } tfoot { display:table-footer-group; }
                    tr { display:table-row; }
                    th { display:table-cell; font-weight:bold; text-align:left; padding:6px 10px; background-color:#f1f3f4; }
                    td { display:table-cell; padding:6px 10px; }
                    colgroup,col { display:table-column; }
                    img,picture,svg,canvas,video,audio,iframe,object,embed { display:inline-block; max-width:100%; }
                    iframe { border:1px solid #dadce0; background-color:#ffffff; }
                    picture,svg { display:inline-block; }
                    canvas { background-color:#ffffff; }
                    audio,video { background-color:#000000; }
                    label { cursor:pointer; } output { display:inline; }
                    meter,progress { display:inline-block; height:16px; width:140px; }
                    button,input,select,textarea { font-family:SansSerif; font-size:14px; box-sizing:border-box; }
                    button,input[type="button"],input[type="submit"],input[type="reset"] { display:inline-block; border:1px solid #dadce0; border-radius:6px; padding:6px 14px; background-color:#f8f9fa; color:#202124; cursor:pointer; text-align:center; }
                    input { display:inline-block; border:1px solid #dadce0; border-radius:6px; padding:6px 10px; background-color:#ffffff; color:#202124; }
                    input[type="checkbox"],input[type="radio"] { width:16px; height:16px; padding:0; vertical-align:middle; cursor:pointer; }
                    input[type="hidden"] { display:none; }
                    textarea { display:inline-block; border:1px solid #dadce0; border-radius:6px; padding:6px 10px; background-color:#ffffff; color:#202124; white-space:pre-wrap; }
                    select { display:inline-block; border:1px solid #dadce0; border-radius:6px; background-color:#ffffff; color:#202124; padding:6px 10px; cursor:pointer; }
                    button[disabled],input[disabled],select[disabled],textarea[disabled] { opacity:0.55; cursor:default; background-color:#f1f3f4; color:#5f6368; }
                    option,optgroup { display:block; }
                    [hidden],template,noscript,head,style,script,meta,link,title,base,param,source,track,datalist { display:none; }
                    """;
        }
    }

    // =========================================================================
    // Mutable DOM Tree & HTML Parser
    // =========================================================================

    public static final class Dom {
        private Dom() {}

        public record Event(
                String type,
                Node target,
                Node currentTarget,
                boolean bubbles,
                boolean cancelable,
                Map<String, Object> detail,
                boolean[] state // [0]=defaultPrevented, [1]=propagationStopped, [2]=immediateStopped
        ) {
            public Event(String type, Node target) {
                this(type, target, target, true, true, new HashMap<>(), new boolean[3]);
            }

            public void preventDefault() {
                if (cancelable) state[0] = true;
            }

            public boolean defaultPrevented() {
                return state[0];
            }

            public void stopPropagation() {
                state[1] = true;
            }

            public void stopImmediatePropagation() {
                state[1] = true;
                state[2] = true;
            }
        }

        public static final class ClassList {
            private final Node node;

            private ClassList(Node node) {
                this.node = node;
            }

            private Set<String> readTokens() {
                Set<String> set = new LinkedHashSet<>();
                String raw = node.attr("class").trim();
                if (!raw.isEmpty()) {
                    for (String part : raw.split("\\s+")) {
                        if (!part.isEmpty()) set.add(part);
                    }
                }
                return set;
            }

            private void writeTokens(Set<String> tokens) {
                if (tokens.isEmpty()) {
                    node.removeAttribute("class");
                } else {
                    node.setAttribute("class", String.join(" ", tokens));
                }
            }

            public void add(String... tokens) {
                Set<String> current = readTokens();
                for (String t : tokens) {
                    if (t != null && !t.isBlank()) current.add(t.trim());
                }
                writeTokens(current);
            }

            public void remove(String... tokens) {
                Set<String> current = readTokens();
                for (String t : tokens) {
                    if (t != null) current.remove(t.trim());
                }
                writeTokens(current);
            }

            public boolean contains(String token) {
                return token != null && readTokens().contains(token.trim());
            }

            public boolean toggle(String token) {
                if (token == null || token.isBlank()) return false;
                String clean = token.trim();
                Set<String> current = readTokens();
                boolean added;
                if (current.contains(clean)) {
                    current.remove(clean);
                    added = false;
                } else {
                    current.add(clean);
                    added = true;
                }
                writeTokens(current);
                return added;
            }

            public boolean toggle(String token, boolean force) {
                if (token == null || token.isBlank()) return false;
                if (force) add(token);
                else remove(token);
                return force;
            }

            public void replace(String oldToken, String newToken) {
                if (oldToken == null || newToken == null) return;
                Set<String> current = readTokens();
                if (current.remove(oldToken.trim()) && !newToken.isBlank()) {
                    current.add(newToken.trim());
                    writeTokens(current);
                }
            }

            public int length() {
                return readTokens().size();
            }

            @Override
            public String toString() {
                return node.attr("class");
            }
        }

        public static final class Node {
            public final String tag;
            public final Map<String, String> attributes = new LinkedHashMap<>();
            public final List<Node> children = new ArrayList<>();
            public Node parent;
            public String text = "";
            private final Map<String, List<Consumer<Event>>> listeners = new HashMap<>();
            private ClassList classListInstance;

            public Node(String tag) {
                this.tag = tag == null ? "div" : tag.toLowerCase(Locale.ROOT);
            }

            public boolean isText() {
                return "#text".equals(tag);
            }

            public boolean isComment() {
                return "#comment".equals(tag);
            }

            public boolean isElement() {
                return !isText() && !isComment() && !"document".equals(tag) && !"#document-fragment".equals(tag);
            }

            public String id() {
                return attr("id");
            }

            public String className() {
                return attr("class");
            }

            public ClassList classList() {
                if (classListInstance == null) classListInstance = new ClassList(this);
                return classListInstance;
            }

            public Map<String, String> dataset() {
                Map<String, String> data = new LinkedHashMap<>();
                for (Map.Entry<String, String> entry : attributes.entrySet()) {
                    String k = entry.getKey();
                    if (k.startsWith("data-") && k.length() > 5) {
                        data.put(kebabToCamel(k.substring(5)), entry.getValue());
                    }
                }
                return data;
            }

            public void setDataset(String camelOrKebabKey, String value) {
                if (camelOrKebabKey == null || camelOrKebabKey.isBlank()) return;
                String attrName = "data-" + camelToKebab(camelOrKebabKey);
                setAttribute(attrName, value);
            }

            public String attr(String name) {
                if (name == null) return "";
                return attributes.getOrDefault(name.toLowerCase(Locale.ROOT), "");
            }

            public boolean hasAttribute(String name) {
                return name != null && attributes.containsKey(name.toLowerCase(Locale.ROOT));
            }

            public void setAttribute(String name, String value) {
                if (name != null && !name.isBlank()) {
                    attributes.put(name.toLowerCase(Locale.ROOT), value == null ? "" : value);
                }
            }

            public void removeAttribute(String name) {
                if (name != null) attributes.remove(name.toLowerCase(Locale.ROOT));
            }

            public void toggleAttribute(String name) {
                if (name == null || name.isBlank()) return;
                if (hasAttribute(name)) removeAttribute(name);
                else setAttribute(name, "");
            }

            public Map<String, String> inlineStyleMap() {
                return CssEngine.parseDeclarations(attr("style"));
            }

            public void setInlineStyle(String property, String value) {
                if (property == null || property.isBlank()) return;
                String prop = camelToKebab(property.trim()).toLowerCase(Locale.ROOT);
                Map<String, String> map = new LinkedHashMap<>();
                String current = attr("style");
                if (!current.isBlank()) {
                    for (String decl : CssEngine.splitDeclarations(current)) {
                        int colon = decl.indexOf(':');
                        if (colon > 0) {
                            String k = decl.substring(0, colon).trim().toLowerCase(Locale.ROOT);
                            String v = decl.substring(colon + 1).trim();
                            if (!k.isEmpty() && !v.isEmpty()) map.put(k, v);
                        }
                    }
                }
                if (value == null || value.isBlank()) {
                    map.remove(prop);
                } else {
                    map.put(prop, value.trim());
                }
                if (map.isEmpty()) {
                    removeAttribute("style");
                } else {
                    StringBuilder sb = new StringBuilder();
                    for (Map.Entry<String, String> e : map.entrySet()) {
                        if (!sb.isEmpty()) sb.append("; ");
                        sb.append(e.getKey()).append(": ").append(e.getValue());
                    }
                    setAttribute("style", sb.toString());
                }
            }

            public String getInlineStyle(String property) {
                if (property == null || property.isBlank()) return "";
                String prop = camelToKebab(property.trim()).toLowerCase(Locale.ROOT);
                return inlineStyleMap().getOrDefault(prop, "");
            }

            public String textContent() {
                if (isComment()) return "";
                if (isText()) return text;
                StringBuilder result = new StringBuilder();
                for (Node child : children) result.append(child.textContent());
                return result.toString();
            }

            public String innerText() {
                if (isComment()) return "";
                if (isText()) return text;
                if (AriaSupport.hidden(this)) return "";
                return switch (tag) {
                    case "head", "style", "script", "noscript", "template", "title" -> "";
                    case "br" -> "\n";
                    default -> {
                        StringBuilder sb = new StringBuilder();
                        for (Node child : children) {
                            String part = child.innerText();
                            if (!part.isEmpty()) {
                                if (!sb.isEmpty() && ElementSupport.isBlockElement(child.tag)
                                        && sb.charAt(sb.length() - 1) != '\n') {
                                    sb.append('\n');
                                }
                                sb.append(part);
                            }
                        }
                        yield sb.toString();
                    }
                };
            }

            public String innerHTML() {
                StringBuilder result = new StringBuilder();
                for (Node child : children) serialize(child, result);
                return result.toString();
            }

            public String outerHTML() {
                StringBuilder result = new StringBuilder();
                serialize(this, result);
                return result.toString();
            }

            public void setOuterHTML(String html) {
                if (parent == null) return;
                Node fragment = parse(html == null ? "" : html);
                int idx = parent.children.indexOf(this);
                if (idx < 0) return;
                for (Node child : new ArrayList<>(fragment.children)) {
                    child.parent = parent;
                    parent.children.add(idx++, child);
                }
                parent.removeChild(this);
            }

            public Node ownerDocument() {
                Node cur = this;
                while (cur.parent != null) cur = cur.parent;
                return cur;
            }

            public List<Node> elementChildren() {
                List<Node> list = new ArrayList<>();
                for (Node c : children) {
                    if (!c.isText() && !c.isComment()) list.add(c);
                }
                return list;
            }

            public Node firstElementChild() {
                for (Node c : children) {
                    if (!c.isText() && !c.isComment()) return c;
                }
                return null;
            }

            public Node lastElementChild() {
                for (int i = children.size() - 1; i >= 0; i--) {
                    Node c = children.get(i);
                    if (!c.isText() && !c.isComment()) return c;
                }
                return null;
            }

            public Node previousElementSibling() {
                if (parent == null) return null;
                int idx = parent.children.indexOf(this);
                for (int i = idx - 1; i >= 0; i--) {
                    Node s = parent.children.get(i);
                    if (!s.isText() && !s.isComment()) return s;
                }
                return null;
            }

            public Node nextElementSibling() {
                if (parent == null) return null;
                int idx = parent.children.indexOf(this);
                if (idx < 0) return null;
                for (int i = idx + 1; i < parent.children.size(); i++) {
                    Node s = parent.children.get(i);
                    if (!s.isText() && !s.isComment()) return s;
                }
                return null;
            }

            public Node previousSibling() {
                if (parent == null) return null;
                int idx = parent.children.indexOf(this);
                return idx > 0 ? parent.children.get(idx - 1) : null;
            }

            public Node nextSibling() {
                if (parent == null) return null;
                int idx = parent.children.indexOf(this);
                return idx >= 0 && idx + 1 < parent.children.size() ? parent.children.get(idx + 1) : null;
            }

            public Node byId(String id) {
                if (id == null || id.isEmpty()) return null;
                if (id.equals(attr("id"))) return this;
                for (Node child : children) {
                    Node found = child.byId(id);
                    if (found != null) return found;
                }
                return null;
            }

            public List<Node> getElementsByTagName(String tagName) {
                List<Node> result = new ArrayList<>();
                if (tagName == null || tagName.isBlank()) return result;
                String target = tagName.trim().toLowerCase(Locale.ROOT);
                boolean wildcard = "*".equals(target);
                List<Node> all = new ArrayList<>();
                collect(this, all);
                for (Node candidate : all) {
                    if (candidate != this && (wildcard || candidate.tag.equals(target))) {
                        result.add(candidate);
                    }
                }
                return result;
            }

            public List<Node> getElementsByClassName(String classNames) {
                List<Node> result = new ArrayList<>();
                if (classNames == null || classNames.isBlank()) return result;
                String[] required = classNames.trim().split("\\s+");
                List<Node> all = new ArrayList<>();
                collect(this, all);
                for (Node candidate : all) {
                    if (candidate == this) continue;
                    ClassList cl = candidate.classList();
                    boolean match = true;
                    for (String req : required) {
                        if (!cl.contains(req)) {
                            match = false;
                            break;
                        }
                    }
                    if (match) result.add(candidate);
                }
                return result;
            }

            public boolean matches(String selector) {
                if (selector == null || selector.isBlank() || isText() || isComment()) return false;
                for (String part : CssEngine.splitSelectors(selector)) {
                    if (CssEngine.matches(this, part.trim())) return true;
                }
                return false;
            }

            public Node closest(String selector) {
                if (selector == null || selector.isBlank()) return null;
                for (Node cur = this; cur != null && !cur.tag.equals("document"); cur = cur.parent) {
                    if (cur.matches(selector)) return cur;
                }
                return null;
            }

            public boolean contains(Node other) {
                for (Node cur = other; cur != null; cur = cur.parent) {
                    if (cur == this) return true;
                }
                return false;
            }

            public Node querySelector(String selector) {
                List<Node> found = querySelectorAll(selector);
                return found.isEmpty() ? null : found.get(0);
            }

            public List<Node> querySelectorAll(String selector) {
                ArrayList<Node> candidates = new ArrayList<>();
                collect(this, candidates);
                ArrayList<Node> result = new ArrayList<>();
                if (selector == null || selector.isBlank()) return result;
                List<String> parts = CssEngine.splitSelectors(selector);
                for (Node candidate : candidates) {
                    for (String part : parts) {
                        if (CssEngine.matches(candidate, part.trim())) {
                            result.add(candidate);
                            break;
                        }
                    }
                }
                return result;
            }

            public void setTextContent(String value) {
                clearChildren();
                if (value != null && !value.isEmpty()) {
                    Node textNode = new Node("#text");
                    textNode.text = value;
                    textNode.parent = this;
                    children.add(textNode);
                }
            }

            public void setInnerHTML(String html) {
                clearChildren();
                Node fragment = parse(html == null ? "" : html);
                for (Node child : new ArrayList<>(fragment.children)) appendChild(child);
            }

            public void insertAdjacentHTML(String position, String html) {
                if (position == null || html == null) return;
                Node fragment = parse(html);
                List<Node> nodes = new ArrayList<>(fragment.children);
                switch (position.trim().toLowerCase(Locale.ROOT)) {
                    case "beforebegin" -> {
                        if (parent == null) return;
                        for (Node n : nodes) parent.insertBefore(n, this);
                    }
                    case "afterbegin" -> {
                        Node first = children.isEmpty() ? null : children.get(0);
                        for (Node n : nodes) insertBefore(n, first);
                    }
                    case "beforeend" -> {
                        for (Node n : nodes) appendChild(n);
                    }
                    case "afterend" -> {
                        if (parent == null) return;
                        Node next = nextSibling();
                        for (Node n : nodes) parent.insertBefore(n, next);
                    }
                }
            }

            public Node appendChild(String name) {
                return appendChild(new Node(name));
            }

            public Node appendChild(Node child) {
                if (child == null || child == this) return child;
                if ("#document-fragment".equals(child.tag) || "document".equals(child.tag)) {
                    for (Node grandChild : new ArrayList<>(child.children)) {
                        appendChild(grandChild);
                    }
                    return child;
                }
                for (Node current = this; current != null; current = current.parent) {
                    if (current == child) return child;
                }
                if (child.parent != null) child.parent.removeChild(child);
                child.parent = this;
                children.add(child);
                return child;
            }

            public Node prepend(Node child) {
                return insertBefore(child, children.isEmpty() ? null : children.get(0));
            }

            public Node insertBefore(Node newNode, Node referenceNode) {
                if (newNode == null || newNode == this) return newNode;
                if (referenceNode == null) return appendChild(newNode);
                int idx = children.indexOf(referenceNode);
                if (idx < 0) return appendChild(newNode);
                for (Node current = this; current != null; current = current.parent) {
                    if (current == newNode) return newNode;
                }
                if (newNode.parent != null) newNode.parent.removeChild(newNode);
                newNode.parent = this;
                children.add(idx, newNode);
                return newNode;
            }

            public Node replaceChild(Node newChild, Node oldChild) {
                if (newChild == null || oldChild == null) return oldChild;
                int idx = children.indexOf(oldChild);
                if (idx < 0) return oldChild;
                insertBefore(newChild, oldChild);
                removeChild(oldChild);
                return oldChild;
            }

            public void replaceWith(Node... nodes) {
                if (parent == null || nodes == null) return;
                for (Node n : nodes) {
                    if (n != null) parent.insertBefore(n, this);
                }
                remove();
            }

            public Node cloneNode(boolean deep) {
                Node copy = new Node(tag);
                copy.text = text;
                copy.attributes.putAll(attributes);
                if (deep) {
                    for (Node child : children) {
                        copy.appendChild(child.cloneNode(true));
                    }
                }
                return copy;
            }

            public Node removeChild(Node child) {
                if (children.remove(child) && child != null) child.parent = null;
                return child;
            }

            public void remove() {
                if (parent != null) parent.removeChild(this);
            }

            public void addEventListener(String type, Consumer<Event> listener) {
                if (type == null || type.isBlank() || listener == null) return;
                listeners.computeIfAbsent(type.toLowerCase(Locale.ROOT), k -> new ArrayList<>()).add(listener);
            }

            public void removeEventListener(String type, Consumer<Event> listener) {
                if (type == null || listener == null) return;
                List<Consumer<Event>> list = listeners.get(type.toLowerCase(Locale.ROOT));
                if (list != null) list.remove(listener);
            }

            public boolean dispatchEvent(String type) {
                return dispatchEvent(new Event(type, this));
            }

            public boolean dispatchEvent(Event event) {
                if (event == null || event.type == null) return true;
                String key = event.type.toLowerCase(Locale.ROOT);
                List<Node> path = new ArrayList<>();
                for (Node cur = this; cur != null; cur = cur.parent) {
                    path.add(cur);
                    if (!event.bubbles) break;
                }
                for (Node current : path) {
                    List<Consumer<Event>> list = current.listeners.get(key);
                    if (list != null && !list.isEmpty()) {
                        Event currentEvent = new Event(
                                event.type, event.target, current,
                                event.bubbles, event.cancelable, event.detail, event.state);
                        for (Consumer<Event> listener : new ArrayList<>(list)) {
                            listener.accept(currentEvent);
                            if (event.state[2]) break;
                        }
                    }
                    if (event.state[1]) break;
                }
                return !event.defaultPrevented();
            }

            private void clearChildren() {
                for (Node child : children) child.parent = null;
                children.clear();
            }

            private static void serialize(Node node, StringBuilder out) {
                if (node.isComment()) {
                    out.append("<!--").append(node.text).append("-->");
                    return;
                }
                if (node.isText()) {
                    boolean rawParent = node.parent != null && ElementSupport.isRawTextElement(node.parent.tag)
                            && !node.parent.tag.equals("title") && !node.parent.tag.equals("textarea");
                    out.append(rawParent ? node.text : escapeText(node.text));
                    return;
                }
                if ("document".equals(node.tag) || "#document-fragment".equals(node.tag)) {
                    for (Node child : node.children) serialize(child, out);
                    return;
                }
                out.append('<').append(node.tag);
                for (Map.Entry<String, String> entry : node.attributes.entrySet()) {
                    out.append(' ').append(entry.getKey()).append("=\"")
                            .append(escapeText(entry.getValue()).replace("\"", "&quot;")).append('"');
                }
                out.append('>');
                if (!ElementSupport.isVoidElement(node.tag)) {
                    for (Node child : node.children) serialize(child, out);
                    out.append("</").append(node.tag).append('>');
                }
            }

            private static String escapeText(String value) {
                return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
            }

            private static void collect(Node node, List<Node> out) {
                if (node == null) return;
                if (!node.isText() && !node.isComment()) out.add(node);
                for (Node child : node.children) collect(child, out);
            }

            private static String camelToKebab(String input) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < input.length(); i++) {
                    char c = input.charAt(i);
                    if (Character.isUpperCase(c)) {
                        if (i > 0 && input.charAt(i - 1) != '-') sb.append('-');
                        sb.append(Character.toLowerCase(c));
                    } else {
                        sb.append(c);
                    }
                }
                return sb.toString();
            }

            private static String kebabToCamel(String input) {
                StringBuilder sb = new StringBuilder();
                boolean upperNext = false;
                for (int i = 0; i < input.length(); i++) {
                    char c = input.charAt(i);
                    if (c == '-') {
                        upperNext = true;
                    } else if (upperNext) {
                        sb.append(Character.toUpperCase(c));
                        upperNext = false;
                    } else {
                        sb.append(c);
                    }
                }
                return sb.toString();
            }
        }

        public static Node parse(String html) {
            Node root = new Node("document");
            if (html == null || html.isEmpty()) return root;
            Deque<Node> stack = new ArrayDeque<>();
            stack.push(root);
            int i = 0;
            while (i < html.length()) {
                if (html.startsWith("<!--", i)) {
                    int end = html.indexOf("-->", i + 4);
                    if (end < 0) break;
                    i = end + 3;
                    continue;
                }
                if (html.startsWith("<![CDATA[", i)) {
                    int end = html.indexOf("]]>", i + 9);
                    if (end < 0) {
                        appendText(stack.peek(), html.substring(i + 9));
                        break;
                    }
                    appendText(stack.peek(), html.substring(i + 9, end));
                    i = end + 3;
                    continue;
                }
                if (html.charAt(i) != '<') {
                    int end = html.indexOf('<', i);
                    if (end < 0) end = html.length();
                    appendText(stack.peek(), decodeEntities(html.substring(i, end)));
                    i = end;
                    continue;
                }
                int end = tagEnd(html, i + 1);
                if (end < 0) {
                    appendText(stack.peek(), "<");
                    i++;
                    continue;
                }
                String token = html.substring(i + 1, end).trim();
                i = end + 1;
                if (token.isEmpty() || token.charAt(0) == '!' || token.charAt(0) == '?') continue;
                if (token.charAt(0) == '/') {
                    String name = token.substring(1).trim().split("\\s", 2)[0].toLowerCase(Locale.ROOT);
                    if (name.endsWith("/")) name = name.substring(0, name.length() - 1).trim();
                    for (Node s : stack) {
                        if (s.tag.equals(name)) {
                            while (stack.size() > 1) {
                                if (stack.pop().tag.equals(name)) break;
                            }
                            break;
                        }
                    }
                    continue;
                }
                boolean selfClosing = token.endsWith("/");
                if (selfClosing) token = token.substring(0, token.length() - 1).trim();
                int nameEnd = 0;
                while (nameEnd < token.length() && !Character.isWhitespace(token.charAt(nameEnd)) && token.charAt(nameEnd) != '/') {
                    nameEnd++;
                }
                String name = token.substring(0, nameEnd).toLowerCase(Locale.ROOT);
                if (name.isEmpty()) continue;
                if ("image".equals(name)) name = "img";

                autoCloseIfNeeded(stack, name);
                Node element = stack.peek().appendChild(new Node(name));
                parseAttributes(token.substring(nameEnd), element.attributes);

                if (!selfClosing && ElementSupport.isRawTextElement(name)) {
                    if ("plaintext".equals(name)) {
                        appendText(element, html.substring(i));
                        break;
                    }
                    int close = findClosingRawTag(html, i, name);
                    if ("textarea".equals(name)) {
                        int nextTag = html.indexOf('<', i);
                        if (close < 0 || (nextTag >= 0 && nextTag < close)) {
                            String raw = nextTag >= 0 ? html.substring(i, nextTag) : html.substring(i);
                            appendText(element, decodeEntities(raw));
                            if (nextTag >= 0 && html.regionMatches(true, nextTag, "</textarea", 0, 10)) {
                                int closeEnd = html.indexOf('>', nextTag);
                                i = closeEnd < 0 ? html.length() : closeEnd + 1;
                            } else {
                                i = nextTag >= 0 ? nextTag : html.length();
                            }
                            continue;
                        }
                    }
                    if (close < 0) {
                        String raw = html.substring(i);
                        appendText(element, name.equals("title") || name.equals("textarea") ? decodeEntities(raw) : raw);
                        break;
                    }
                    String raw = html.substring(i, close);
                    appendText(element, name.equals("title") || name.equals("textarea") ? decodeEntities(raw) : raw);
                    int closeEnd = html.indexOf('>', close);
                    i = closeEnd < 0 ? html.length() : closeEnd + 1;
                    continue;
                }

                if (!selfClosing && !ElementSupport.isVoidElement(name)) {
                    stack.push(element);
                }
            }
            return root;
        }

        private static void autoCloseIfNeeded(Deque<Node> stack, String nextTag) {
            if (stack.size() <= 1) return;
            String top = stack.peek().tag;
            if ((nextTag.equals("li") && top.equals("li"))
                    || (nextTag.equals("p") && top.equals("p"))
                    || (top.equals("p") && closesParagraph(nextTag))
                    || ((nextTag.equals("dt") || nextTag.equals("dd")) && (top.equals("dt") || top.equals("dd")))
                    || ((nextTag.equals("td") || nextTag.equals("th")) && (top.equals("td") || top.equals("th")))
                    || (nextTag.equals("tr") && (top.equals("tr") || top.equals("td") || top.equals("th")))
                    || (ElementSupport.isTableSection(nextTag) && (top.equals("tr") || top.equals("td") || top.equals("th") || ElementSupport.isTableSection(top)))
                    || (ElementSupport.isHeadingElement(nextTag) && ElementSupport.isHeadingElement(top))
                    || (nextTag.equals("option") && top.equals("option"))
                    || (nextTag.equals("optgroup") && (top.equals("option") || top.equals("optgroup")))) {
                stack.pop();
                if (stack.size() > 1 && nextTag.equals("tr") && stack.peek().tag.equals("tr")) {
                    stack.pop();
                } else if (stack.size() > 1 && ElementSupport.isTableSection(nextTag)) {
                    while (stack.size() > 1 && (stack.peek().tag.equals("tr") || !stack.peek().tag.equals("table") && ElementSupport.isTableSection(stack.peek().tag))) {
                        stack.pop();
                    }
                }
            }
        }

        private static boolean closesParagraph(String tag) {
            return switch (tag) {
                case "address", "article", "aside", "blockquote", "details", "dialog",
                     "dir", "div", "dl", "fieldset", "figcaption", "figure", "footer",
                     "form", "h1", "h2", "h3", "h4", "h5", "h6", "header", "hgroup",
                     "hr", "main", "menu", "nav", "ol", "p", "pre", "search", "section",
                     "table", "ul" -> true;
                default -> false;
            };
        }

        private static int findClosingRawTag(String html, int from, String tag) {
            String needle = "</" + tag;
            int idx = from;
            while (idx < html.length()) {
                int pos = html.indexOf("</", idx);
                if (pos < 0) return -1;
                if (html.regionMatches(true, pos, needle, 0, needle.length())) {
                    int after = pos + needle.length();
                    if (after >= html.length()
                            || Character.isWhitespace(html.charAt(after))
                            || html.charAt(after) == '>'
                            || html.charAt(after) == '/') {
                        return pos;
                    }
                }
                idx = pos + 2;
            }
            return -1;
        }

        private static void appendText(Node node, String value) {
            if (value == null || value.isEmpty()) return;
            if (!node.children.isEmpty()) {
                Node last = node.children.get(node.children.size() - 1);
                if (last.isText()) {
                    last.text += value;
                    return;
                }
            }
            Node text = new Node("#text");
            text.text = value;
            text.parent = node;
            node.children.add(text);
        }

        private static int tagEnd(String value, int start) {
            char quote = 0;
            for (int i = start; i < value.length(); i++) {
                char c = value.charAt(i);
                if (quote != 0) {
                    if (c == quote) quote = 0;
                } else if (c == '\'' || c == '"') {
                    quote = c;
                } else if (c == '>') {
                    return i;
                }
            }
            return -1;
        }

        private static void parseAttributes(String source, Map<String, String> out) {
            int i = 0;
            while (i < source.length()) {
                while (i < source.length() && (Character.isWhitespace(source.charAt(i)) || source.charAt(i) == '/')) i++;
                int start = i;
                while (i < source.length() && !Character.isWhitespace(source.charAt(i))
                        && source.charAt(i) != '=' && source.charAt(i) != '/') i++;
                if (i == start) {
                    i++;
                    continue;
                }
                String key = source.substring(start, i).toLowerCase(Locale.ROOT);
                while (i < source.length() && Character.isWhitespace(source.charAt(i))) i++;
                String value = "";
                if (i < source.length() && source.charAt(i) == '=') {
                    i++;
                    while (i < source.length() && Character.isWhitespace(source.charAt(i))) i++;
                    if (i < source.length() && (source.charAt(i) == '\'' || source.charAt(i) == '"')) {
                        char quote = source.charAt(i++);
                        int valueStart = i;
                        while (i < source.length() && source.charAt(i) != quote) i++;
                        value = source.substring(valueStart, i);
                        if (i < source.length()) i++;
                    } else {
                        int valueStart = i;
                        while (i < source.length() && !Character.isWhitespace(source.charAt(i))) i++;
                        value = source.substring(valueStart, i);
                    }
                }
                out.putIfAbsent(key, decodeEntities(value));
            }
        }

        private static final Map<String, String> NAMED_ENTITIES = Map.ofEntries(
                Map.entry("&nbsp;", "\u00a0"),
                Map.entry("&amp;", "&"),
                Map.entry("&lt;", "<"),
                Map.entry("&gt;", ">"),
                Map.entry("&quot;", "\""),
                Map.entry("&#39;", "'"),
                Map.entry("&apos;", "'"),
                Map.entry("&copy;", "\u00a9"),
                Map.entry("&reg;", "\u00ae"),
                Map.entry("&trade;", "\u2122"),
                Map.entry("&mdash;", "\u2014"),
                Map.entry("&ndash;", "\u2013"),
                Map.entry("&bull;", "\u2022"),
                Map.entry("&hellip;", "\u2026"),
                Map.entry("&lsquo;", "\u2018"),
                Map.entry("&rsquo;", "\u2019"),
                Map.entry("&ldquo;", "\u201c"),
                Map.entry("&rdquo;", "\u201d"),
                Map.entry("&laquo;", "\u00ab"),
                Map.entry("&raquo;", "\u00bb"),
                Map.entry("&lsaquo;", "\u2039"),
                Map.entry("&rsaquo;", "\u203a"),
                Map.entry("&euro;", "\u20ac"),
                Map.entry("&pound;", "\u00a3"),
                Map.entry("&yen;", "\u00a5"),
                Map.entry("&cent;", "\u00a2"),
                Map.entry("&sect;", "\u00a7"),
                Map.entry("&para;", "\u00b6"),
                Map.entry("&deg;", "\u00b0"),
                Map.entry("&plusmn;", "\u00b1"),
                Map.entry("&times;", "\u00d7"),
                Map.entry("&divide;", "\u00f7"),
                Map.entry("&frac12;", "\u00bd"),
                Map.entry("&frac14;", "\u00bc"),
                Map.entry("&frac34;", "\u00be"),
                Map.entry("&middot;", "\u00b7"),
                Map.entry("&larr;", "\u2190"),
                Map.entry("&uarr;", "\u2191"),
                Map.entry("&rarr;", "\u2192"),
                Map.entry("&darr;", "\u2193"),
                Map.entry("&harr;", "\u2194"),
                Map.entry("&hearts;", "\u2665"),
                Map.entry("&spades;", "\u2660"),
                Map.entry("&clubs;", "\u2663"),
                Map.entry("&diams;", "\u2666"),
                Map.entry("&check;", "\u2713"),
                Map.entry("&cross;", "\u2717"),
                Map.entry("&star;", "\u2606"),
                Map.entry("&starf;", "\u2605"),
                Map.entry("&infin;", "\u221e"),
                Map.entry("&ne;", "\u2260"),
                Map.entry("&le;", "\u2264"),
                Map.entry("&ge;", "\u2265"),
                Map.entry("&asymp;", "\u2248"),
                Map.entry("&alpha;", "\u03b1"),
                Map.entry("&beta;", "\u03b2"),
                Map.entry("&gamma;", "\u03b3"),
                Map.entry("&delta;", "\u03b4"),
                Map.entry("&pi;", "\u03c0"),
                Map.entry("&omega;", "\u03c9")
        );

        private static final Pattern ENTITY_PATTERN = Pattern.compile("&(#x?[0-9a-fA-F]+|[a-zA-Z0-9]+);");

        public static String decodeEntities(String text) {
            if (text == null || text.indexOf('&') < 0) return text == null ? "" : text;
            Matcher m = ENTITY_PATTERN.matcher(text);
            StringBuilder sb = new StringBuilder(text.length());
            while (m.find()) {
                String full = m.group(0);
                String body = m.group(1);
                String replacement = null;
                if (body.charAt(0) == '#') {
                    try {
                        String raw = body.substring(1);
                        int codePoint = (raw.startsWith("x") || raw.startsWith("X"))
                                ? Integer.parseInt(raw.substring(1), 16)
                                : Integer.parseInt(raw, 10);
                        if (Character.isValidCodePoint(codePoint)) {
                            replacement = new String(Character.toChars(codePoint));
                        }
                    } catch (Exception ignored) {}
                } else {
                    replacement = NAMED_ENTITIES.get(full);
                    if (replacement == null) {
                        replacement = NAMED_ENTITIES.get(full.toLowerCase(Locale.ROOT));
                    }
                }
                m.appendReplacement(sb, Matcher.quoteReplacement(replacement != null ? replacement : full));
            }
            m.appendTail(sb);
            return sb.toString();
        }

        public static boolean isBlock(String tag) {
            return ElementSupport.isBlockElement(tag);
        }
    }

    // =========================================================================
    // CSS Preprocessor (Custom Properties & Conditional At-Rules)
    // =========================================================================

    public static final class CssPreprocessor {
        private static final Pattern VARIABLE_NAME = Pattern.compile("--[\\w-]+");

        private CssPreprocessor() {}

        public static String resolve(String css) {
            return resolve(css, 1024);
        }

        public static String resolve(String css, int viewportWidth) {
            return resolve(css, viewportWidth, 768);
        }

        public static String resolve(String css, int viewportWidth, int viewportHeight) {
            if (css == null) css = "";
            String flattened = flatten(BrowserStyles.css() + "\n" + css, viewportWidth, viewportHeight);
            Map<String, String> variables = new LinkedHashMap<>();
            Matcher rules = Pattern.compile("(?s)([^{}]+)\\{([^{}]*)}").matcher(flattened);
            while (rules.find()) {
                boolean rootRule = false;
                for (String selector : rules.group(1).split(",")) {
                    String normalized = selector.trim().toLowerCase(Locale.ROOT);
                    if (normalized.equals(":root") || normalized.equals("html")
                            || normalized.equals("body") || normalized.equals("*")) {
                        rootRule = true;
                    }
                }
                if (!rootRule) continue;
                for (String declaration : CssEngine.splitDeclarations(rules.group(2))) {
                    int colon = declaration.indexOf(':');
                    if (colon < 1) continue;
                    String name = declaration.substring(0, colon).trim();
                    if (VARIABLE_NAME.matcher(name).matches()) {
                        variables.put(name, declaration.substring(colon + 1).trim());
                    }
                }
            }
            return substitute(flattened, variables, 0);
        }

        private static String flatten(String css, int viewportWidth, int viewportHeight) {
            String stripped = stripComments(css);
            StringBuilder output = new StringBuilder(stripped.length());
            int cursor = 0;
            while (cursor < stripped.length()) {
                int open = findNext(stripped, '{', cursor);
                if (open < 0) break;
                String header = stripped.substring(cursor, open).trim();
                int semi = findTopLevelSemicolon(header);
                while (semi >= 0 && header.startsWith("@")) {
                    header = header.substring(semi + 1).trim();
                    semi = findTopLevelSemicolon(header);
                }
                int close = matchingBrace(stripped, open);
                if (close < 0) break;
                String body = stripped.substring(open + 1, close);
                if (header.startsWith("@")) {
                    String atRule = header.toLowerCase(Locale.ROOT);
                    if ((atRule.startsWith("@media") && mediaMatches(atRule, viewportWidth, viewportHeight))
                            || (atRule.startsWith("@container") && containerMatches(atRule, viewportWidth))
                            || atRule.startsWith("@supports")
                            || atRule.startsWith("@layer")
                            || atRule.startsWith("@scope")) {
                        output.append(flatten(body, viewportWidth, viewportHeight));
                    }
                } else if (!header.isEmpty() && !header.startsWith("@")) {
                    output.append(header).append('{').append(body).append('}');
                }
                cursor = close + 1;
            }
            return output.toString();
        }

        private static int findTopLevelSemicolon(String text) {
            char quote = 0;
            int parens = 0;
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (quote != 0) {
                    if (c == quote) quote = 0;
                } else if (c == '\'' || c == '"') {
                    quote = c;
                } else if (c == '(') {
                    parens++;
                } else if (c == ')') {
                    parens = Math.max(0, parens - 1);
                } else if (c == ';' && parens == 0) {
                    return i;
                }
            }
            return -1;
        }

        private static int findNext(String text, char sought, int start) {
            char quote = 0;
            boolean comment = false;
            for (int i = start; i < text.length(); i++) {
                char c = text.charAt(i);
                if (comment) {
                    if (c == '*' && i + 1 < text.length() && text.charAt(i + 1) == '/') {
                        comment = false;
                        i++;
                    }
                    continue;
                }
                if (quote != 0) {
                    if (c == quote && (i == 0 || text.charAt(i - 1) != '\\')) quote = 0;
                    continue;
                }
                if (c == '/' && i + 1 < text.length() && text.charAt(i + 1) == '*') {
                    comment = true;
                    i++;
                } else if (c == '\'' || c == '"') {
                    quote = c;
                } else if (c == sought) {
                    return i;
                }
            }
            return -1;
        }

        private static int matchingBrace(String text, int open) {
            int depth = 1;
            char quote = 0;
            for (int i = open + 1; i < text.length(); i++) {
                char c = text.charAt(i);
                if (quote != 0) {
                    if (c == quote && text.charAt(i - 1) != '\\') quote = 0;
                    continue;
                }
                if (c == '\'' || c == '"') quote = c;
                else if (c == '{') depth++;
                else if (c == '}' && --depth == 0) return i;
            }
            return -1;
        }

        private static boolean mediaMatches(String query, int width, int height) {
            String media = query.replaceFirst("^@media\\s*", "").trim();
            if (media.isEmpty()) return true;
            for (String branch : media.split(",")) {
                if (singleMediaMatches(branch.trim(), width, height)) return true;
            }
            return false;
        }

        private static boolean singleMediaMatches(String media, int width, int height) {
            if (media.contains("print") || media.contains("speech")) return false;
            if (media.contains("prefers-color-scheme") && media.contains("dark")) return false;
            if (media.contains("prefers-reduced-motion") && media.contains("reduce")) return false;
            if (!media.contains("screen") && !media.contains("all")
                    && !media.startsWith("(") && !media.startsWith("only ")) return false;

            Matcher minW = Pattern.compile("min-width\\s*:\\s*([0-9.]+)\\s*(px|em|rem)").matcher(media);
            while (minW.find()) {
                double val = Double.parseDouble(minW.group(1)) * (minW.group(2).equals("px") ? 1 : 16);
                if (width < val) return false;
            }
            Matcher maxW = Pattern.compile("max-width\\s*:\\s*([0-9.]+)\\s*(px|em|rem)").matcher(media);
            while (maxW.find()) {
                double val = Double.parseDouble(maxW.group(1)) * (maxW.group(2).equals("px") ? 1 : 16);
                if (width > val) return false;
            }
            Matcher minH = Pattern.compile("min-height\\s*:\\s*([0-9.]+)\\s*(px|em|rem)").matcher(media);
            while (minH.find()) {
                double val = Double.parseDouble(minH.group(1)) * (minH.group(2).equals("px") ? 1 : 16);
                if (height < val) return false;
            }
            Matcher maxH = Pattern.compile("max-height\\s*:\\s*([0-9.]+)\\s*(px|em|rem)").matcher(media);
            while (maxH.find()) {
                double val = Double.parseDouble(maxH.group(1)) * (maxH.group(2).equals("px") ? 1 : 16);
                if (height > val) return false;
            }
            Matcher rangeW = Pattern.compile("width\\s*(<=|>=|<|>)\\s*([0-9.]+)\\s*(px|em|rem)").matcher(media);
            while (rangeW.find()) {
                String op = rangeW.group(1);
                double val = Double.parseDouble(rangeW.group(2)) * (rangeW.group(3).equals("px") ? 1 : 16);
                boolean ok = switch (op) {
                    case "<" -> width < val;
                    case "<=" -> width <= val;
                    case ">" -> width > val;
                    case ">=" -> width >= val;
                    default -> true;
                };
                if (!ok) return false;
            }
            if (media.contains("orientation")) {
                if (media.contains("portrait") && width > height) return false;
                if (media.contains("landscape") && width < height) return false;
            }
            return true;
        }

        private static boolean containerMatches(String query, int width) {
            Matcher minW = Pattern.compile("min-width\\s*:\\s*([0-9.]+)\\s*(px|em|rem)").matcher(query);
            while (minW.find()) {
                double val = Double.parseDouble(minW.group(1)) * (minW.group(2).equals("px") ? 1 : 16);
                if (width < val) return false;
            }
            Matcher maxW = Pattern.compile("max-width\\s*:\\s*([0-9.]+)\\s*(px|em|rem)").matcher(query);
            while (maxW.find()) {
                double val = Double.parseDouble(maxW.group(1)) * (maxW.group(2).equals("px") ? 1 : 16);
                if (width > val) return false;
            }
            return true;
        }

        private static String stripComments(String source) {
            return source.replaceAll("(?s)/\\*.*?\\*/", "");
        }

        private static String substitute(String source, Map<String, String> variables, int depth) {
            if (depth >= 16) return source;
            StringBuilder result = new StringBuilder(source.length());
            int cursor = 0;
            while (cursor < source.length()) {
                int start = source.indexOf("var(", cursor);
                if (start < 0) {
                    result.append(source, cursor, source.length());
                    break;
                }
                result.append(source, cursor, start);
                int end = closingParen(source, start + 3);
                if (end < 0) {
                    result.append(source.substring(start));
                    break;
                }
                String expression = source.substring(start + 4, end);
                int comma = topLevelComma(expression);
                String name = (comma < 0 ? expression : expression.substring(0, comma)).trim();
                String replacement = variables.get(name);
                if (replacement == null && comma >= 0) replacement = expression.substring(comma + 1).trim();
                if (replacement == null || !VARIABLE_NAME.matcher(name).matches()) {
                    result.append(source, start, end + 1);
                } else {
                    result.append(substitute(replacement, variables, depth + 1));
                }
                cursor = end + 1;
            }
            return result.toString();
        }

        static int closingParen(String source, int open) {
            int depth = 1;
            for (int i = open + 1; i < source.length(); i++) {
                if (source.charAt(i) == '(') depth++;
                else if (source.charAt(i) == ')' && --depth == 0) return i;
            }
            return -1;
        }

        static int topLevelComma(String expression) {
            int depth = 0;
            for (int i = 0; i < expression.length(); i++) {
                char c = expression.charAt(i);
                if (c == '(') depth++;
                else if (c == ')') depth--;
                else if (c == ',' && depth == 0) return i;
            }
            return -1;
        }
    }

    // =========================================================================
    // CSS Rule Parser & Cascade
    // =========================================================================

    public static final class CssEngine {
        private static final Pattern RULE_PATTERN = Pattern.compile("(?s)([^{}]+)\\{([^{}]*)}");
        private static final Pattern ATTR_PATTERN = Pattern.compile("\\[([\\w:-]+)(?:\\s*([~|^$*]?=)\\s*(['\\\"]?)(.*?)\\3)?(?:\\s+[iIsS])?\\]");
        private static final Pattern PSEUDO_PATTERN = Pattern.compile(
                ":(first-child|last-child|only-child|first-of-type|last-of-type|only-of-type" +
                "|nth-child\\(([^)]*)\\)|nth-last-child\\(([^)]*)\\)|nth-of-type\\(([^)]*)\\)|nth-last-of-type\\(([^)]*)\\)" +
                "|not\\(([^)]*)\\)|is\\(([^)]*)\\)|where\\(([^)]*)\\)|has\\(([^)]*)\\)" +
                "|empty|root|checked|disabled|enabled|required|optional|read-only|read-write" +
                "|focus|hover|visited|link|any-link|active|focus-within|focus-visible|target|open)",
                Pattern.CASE_INSENSITIVE);
        private static final String INHERITED = " color font-family font-size font-style font-weight font-variant " +
                "line-height letter-spacing word-spacing text-align text-indent text-transform white-space " +
                "visibility cursor list-style-type list-style-position quotes direction border-collapse border-spacing ";

        public record Style(Map<String, String> values) {
            public String get(String property, String fallback) {
                return values.getOrDefault(property.toLowerCase(Locale.ROOT), fallback);
            }

            public int px(String property, int fallback) {
                return px(property, fallback, 0);
            }

            public int px(String property, int fallback, int referenceSize) {
                String value = get(property, "").trim().toLowerCase(Locale.ROOT);
                if (value.isEmpty() || value.equals("auto") || value.equals("none") || value.equals("normal")) {
                    return fallback;
                }
                int baseFontPx = parseRawPx(get("font-size", "16px"), 16, 16);
                return evaluateLength(value, fallback, referenceSize, baseFontPx);
            }

            public double number(String property, double fallback) {
                String value = get(property, "").trim();
                if (value.isEmpty()) return fallback;
                try {
                    if (value.endsWith("%")) {
                        return Double.parseDouble(value.substring(0, value.length() - 1).trim()) / 100.0;
                    }
                    return Double.parseDouble(value);
                } catch (Exception e) {
                    return fallback;
                }
            }

            public boolean isAuto(String property) {
                return "auto".equalsIgnoreCase(get(property, "").trim());
            }

            public boolean isNone(String property) {
                return "none".equalsIgnoreCase(get(property, "").trim());
            }

            public Color color(String property, Color fallback) {
                String raw = get(property, "");
                if (raw.isEmpty() && property.equals("background-color")) {
                    raw = get("background", "");
                }
                if ("currentcolor".equalsIgnoreCase(raw.trim()) && !"color".equalsIgnoreCase(property)) {
                    raw = get("color", "");
                }
                return parseColor(raw, fallback);
            }
        }

        private static int evaluateLength(String expr, int fallback, int referenceSize, int baseFontPx) {
            expr = expr.trim();
            if (expr.startsWith("calc(") && expr.endsWith(")")) {
                String inner = expr.substring(5, expr.length() - 1).trim();
                return evaluateCalc(inner, fallback, referenceSize, baseFontPx);
            }
            if ((expr.startsWith("min(") || expr.startsWith("max(") || expr.startsWith("clamp(")) && expr.endsWith(")")) {
                String fn = expr.substring(0, expr.indexOf('('));
                String inner = expr.substring(fn.length() + 1, expr.length() - 1);
                List<String> args = splitTopLevelArgs(inner);
                if (fn.equals("min") && !args.isEmpty()) {
                    int best = Integer.MAX_VALUE;
                    for (String a : args) best = Math.min(best, evaluateLength(a, fallback, referenceSize, baseFontPx));
                    return best;
                }
                if (fn.equals("max") && !args.isEmpty()) {
                    int best = Integer.MIN_VALUE;
                    for (String a : args) best = Math.max(best, evaluateLength(a, fallback, referenceSize, baseFontPx));
                    return best;
                }
                if (fn.equals("clamp") && args.size() == 3) {
                    int min = evaluateLength(args.get(0), fallback, referenceSize, baseFontPx);
                    int val = evaluateLength(args.get(1), fallback, referenceSize, baseFontPx);
                    int max = evaluateLength(args.get(2), fallback, referenceSize, baseFontPx);
                    return Math.max(min, Math.min(max, val));
                }
            }
            return parseRawPx(expr, fallback, referenceSize, baseFontPx);
        }

        private static int evaluateCalc(String expr, int fallback, int referenceSize, int baseFontPx) {
            try {
                int depth = 0;
                for (int i = expr.length() - 1; i >= 0; i--) {
                    char c = expr.charAt(i);
                    if (c == ')') depth++;
                    else if (c == '(') depth--;
                    else if (depth == 0 && (c == '+' || c == '-') && i > 0 && Character.isWhitespace(expr.charAt(i - 1))) {
                        int left = evaluateCalc(expr.substring(0, i).trim(), 0, referenceSize, baseFontPx);
                        int right = evaluateCalc(expr.substring(i + 1).trim(), 0, referenceSize, baseFontPx);
                        return c == '+' ? left + right : left - right;
                    }
                }
                for (int i = expr.length() - 1; i >= 0; i--) {
                    char c = expr.charAt(i);
                    if (c == ')') depth++;
                    else if (c == '(') depth--;
                    else if (depth == 0 && (c == '*' || c == '/')) {
                        String leftStr = expr.substring(0, i).trim();
                        String rightStr = expr.substring(i + 1).trim();
                        if (c == '*') {
                            if (isPureNumber(rightStr)) {
                                return (int) Math.round(evaluateCalc(leftStr, 0, referenceSize, baseFontPx) * Double.parseDouble(rightStr));
                            }
                            if (isPureNumber(leftStr)) {
                                return (int) Math.round(Double.parseDouble(leftStr) * evaluateCalc(rightStr, 0, referenceSize, baseFontPx));
                            }
                        } else {
                            double div = Double.parseDouble(rightStr);
                            return div == 0 ? fallback : (int) Math.round(evaluateCalc(leftStr, 0, referenceSize, baseFontPx) / div);
                        }
                    }
                }
                if (expr.startsWith("(") && expr.endsWith(")")) {
                    return evaluateCalc(expr.substring(1, expr.length() - 1).trim(), fallback, referenceSize, baseFontPx);
                }
                return parseRawPx(expr, fallback, referenceSize, baseFontPx);
            } catch (Exception e) {
                return fallback;
            }
        }

        private static boolean isPureNumber(String s) {
            try {
                Double.parseDouble(s);
                return true;
            } catch (Exception e) {
                return false;
            }
        }

        private static List<String> splitTopLevelArgs(String expr) {
            List<String> out = new ArrayList<>();
            int start = 0, depth = 0;
            for (int i = 0; i < expr.length(); i++) {
                char c = expr.charAt(i);
                if (c == '(') depth++;
                else if (c == ')') depth--;
                else if (c == ',' && depth == 0) {
                    out.add(expr.substring(start, i).trim());
                    start = i + 1;
                }
            }
            out.add(expr.substring(start).trim());
            return out;
        }

        private static int parseRawPx(String value, int fallback, int referenceSize) {
            return parseRawPx(value, fallback, referenceSize, 16);
        }

        private static int parseRawPx(String value, int fallback, int referenceSize, int emBase) {
            value = value.trim().toLowerCase(Locale.ROOT);
            if (value.isEmpty()) return fallback;
            return switch (value) {
                case "thin" -> 1;
                case "medium" -> 3;
                case "thick" -> 5;
                case "xx-small" -> 9;
                case "x-small" -> 10;
                case "small" -> 13;
                case "large" -> 18;
                case "x-large" -> 24;
                case "xx-large" -> 32;
                default -> {
                    try {
                        if (value.endsWith("%")) {
                            double pct = Double.parseDouble(value.substring(0, value.length() - 1).trim());
                            yield referenceSize > 0 ? (int) Math.round(referenceSize * (pct / 100.0)) : fallback;
                        }
                        double multiplier = 1;
                        if (value.endsWith("rem")) {
                            value = value.substring(0, value.length() - 3);
                            multiplier = 16;
                        } else if (value.endsWith("em") || value.endsWith("ch")) {
                            value = value.substring(0, value.length() - 2);
                            multiplier = emBase > 0 ? emBase : 16;
                        } else if (value.endsWith("ex")) {
                            value = value.substring(0, value.length() - 2);
                            multiplier = (emBase > 0 ? emBase : 16) * 0.5;
                        } else if (value.endsWith("vh") || value.endsWith("svh") || value.endsWith("dvh") || value.endsWith("lvh")) {
                            value = value.replaceAll("[sdl]?vh$", "");
                            multiplier = 7.0;
                        } else if (value.endsWith("vw") || value.endsWith("svw") || value.endsWith("dvw") || value.endsWith("lvw")) {
                            value = value.replaceAll("[sdl]?vw$", "");
                            multiplier = referenceSize > 0 ? referenceSize / 100.0 : 10.0;
                        } else if (value.endsWith("vmin") || value.endsWith("vmax")) {
                            value = value.substring(0, value.length() - 4);
                            multiplier = 7.0;
                        } else if (value.endsWith("pt")) {
                            value = value.substring(0, value.length() - 2);
                            multiplier = 4.0 / 3.0;
                        } else if (value.endsWith("pc")) {
                            value = value.substring(0, value.length() - 2);
                            multiplier = 16.0;
                        } else if (value.endsWith("in")) {
                            value = value.substring(0, value.length() - 2);
                            multiplier = 96.0;
                        } else if (value.endsWith("cm")) {
                            value = value.substring(0, value.length() - 2);
                            multiplier = 96.0 / 2.54;
                        } else if (value.endsWith("mm")) {
                            value = value.substring(0, value.length() - 2);
                            multiplier = 96.0 / 25.4;
                        } else if (value.endsWith("px")) {
                            value = value.substring(0, value.length() - 2);
                        }
                        yield (int) Math.round(Double.parseDouble(value.trim()) * multiplier);
                    } catch (Exception ignored) {
                        yield fallback;
                    }
                }
            };
        }

        private record Rule(
                String selector,
                Map<String, String> normalDeclarations,
                Map<String, String> importantDeclarations,
                int specificity,
                int order
        ) {}

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
                Map<String, String> normal = new LinkedHashMap<>();
                Map<String, String> important = new LinkedHashMap<>();
                parseDeclarationsWithImportance(matcher.group(2), normal, important);
                for (String selector : splitSelectors(selectorText)) {
                    if (!selector.isBlank()) {
                        rules.add(new Rule(selector.trim(), normal, important, specificity(selector), order++));
                    }
                }
            }
            rules.sort((a, b) -> a.specificity != b.specificity
                    ? Integer.compare(a.specificity, b.specificity)
                    : Integer.compare(a.order, b.order));
        }

        public void invalidateCache() {
            cache.clear();
        }

        public Style compute(Dom.Node node) {
            if (node == null || node.isText() || node.isComment()) return EMPTY;
            Style cached = cache.get(node);
            if (cached != null) return cached;
            Map<String, String> inherited = new HashMap<>();
            Map<String, String> inheritedVars = new HashMap<>();
            if (node.parent != null) {
                Style parent = compute(node.parent);
                for (String property : INHERITED.trim().split("\\s+")) {
                    String value = parent.get(property, "");
                    if (!value.isEmpty()) inherited.put(property, value);
                }
                for (Map.Entry<String, String> e : parent.values().entrySet()) {
                    if (e.getKey().startsWith("--")) inheritedVars.put(e.getKey(), e.getValue());
                }
            }
            Map<String, String> values = new HashMap<>(inherited);
            values.putAll(inheritedVars);

            if (Dom.isBlock(node.tag)) {
                values.put("display", "block");
            }
            if (node.tag.equals("center")) {
                values.put("text-align", "center");
            }
            if (node.tag.equals("font")) {
                if (!node.attr("color").isBlank()) values.put("color", node.attr("color"));
                if (!node.attr("face").isBlank()) values.put("font-family", node.attr("face"));
                if (!node.attr("size").isBlank()) values.put("font-size", mapFontSizeAttr(node.attr("size")));
            }
            if (!node.attr("bgcolor").isBlank()) {
                values.put("background-color", node.attr("bgcolor"));
            }
            if (!node.attr("align").isBlank()) {
                values.put("text-align", node.attr("align").toLowerCase(Locale.ROOT));
            }
            if (!node.attr("width").isBlank() && !values.containsKey("width")) {
                String w = node.attr("width").trim();
                values.put("width", w.matches("\\d+") ? w + "px" : w);
            }
            if (!node.attr("height").isBlank() && !values.containsKey("height")) {
                String h = node.attr("height").trim();
                values.put("height", h.matches("\\d+") ? h + "px" : h);
            }

            List<Map<String, String>> matchedImportant = new ArrayList<>();
            for (Rule rule : rules) {
                if (matches(node, rule.selector)) {
                    values.putAll(rule.normalDeclarations);
                    if (!rule.importantDeclarations.isEmpty()) {
                        matchedImportant.add(rule.importantDeclarations);
                    }
                }
            }
            Map<String, String> inlineNormal = new LinkedHashMap<>();
            Map<String, String> inlineImportant = new LinkedHashMap<>();
            parseDeclarationsWithImportance(node.attr("style"), inlineNormal, inlineImportant);
            values.putAll(inlineNormal);
            for (Map<String, String> imp : matchedImportant) {
                values.putAll(imp);
            }
            values.putAll(inlineImportant);

            resolveScopedVariables(values);
            expandShorthands(values);

            for (Map.Entry<String, String> entry : new ArrayList<>(values.entrySet())) {
                String val = entry.getValue();
                if (val.equalsIgnoreCase("inherit")) {
                    String inh = inherited.get(entry.getKey());
                    if (inh != null) values.put(entry.getKey(), inh);
                } else if (val.equalsIgnoreCase("initial") || val.equalsIgnoreCase("unset")) {
                    if (val.equalsIgnoreCase("unset") && inherited.containsKey(entry.getKey())) {
                        values.put(entry.getKey(), inherited.get(entry.getKey()));
                    } else {
                        values.remove(entry.getKey());
                    }
                }
            }
            values.putIfAbsent("color", "#202124");
            values.putIfAbsent("font-family", "SansSerif");
            values.putIfAbsent("font-size", "16px");
            Style result = new Style(Map.copyOf(values));
            cache.put(node, result);
            return result;
        }

        private static String mapFontSizeAttr(String sizeAttr) {
            return switch (sizeAttr.trim()) {
                case "1" -> "10px";
                case "2" -> "13px";
                case "3" -> "16px";
                case "4" -> "18px";
                case "5" -> "24px";
                case "6" -> "32px";
                case "7" -> "48px";
                default -> "16px";
            };
        }

        private static void resolveScopedVariables(Map<String, String> values) {
            Map<String, String> vars = new HashMap<>();
            for (Map.Entry<String, String> e : values.entrySet()) {
                if (e.getKey().startsWith("--")) vars.put(e.getKey(), e.getValue());
            }
            if (vars.isEmpty()) return;
            for (Map.Entry<String, String> e : values.entrySet()) {
                if (e.getValue().contains("var(")) {
                    e.setValue(CssPreprocessor.substitute(e.getValue(), vars, 0));
                }
            }
        }

        private static void expandShorthands(Map<String, String> values) {
            expandBox(values, "margin");
            expandBox(values, "padding");
            expandInset(values);
            expandBorder(values);
            expandBorderRadius(values);
            expandBackground(values);
            expandFont(values);
            expandFlex(values);
            expandGap(values);
            expandOverflow(values);
        }

        private static void expandBackground(Map<String, String> values) {
            if (values.containsKey("background-color")) return;
            String bg = values.get("background");
            if (bg == null || bg.isBlank()) return;
            Color parsed = parseColor(bg, null);
            if (parsed != null) {
                values.put("background-color", bg);
            }
        }

        private static void expandBox(Map<String, String> values, String name) {
            String raw = values.get(name);
            if (raw != null && !raw.isBlank()) {
                String[] parts = raw.trim().split("\\s+");
                if (parts.length >= 1 && parts.length <= 4) {
                    String top = parts[0], right = parts.length > 1 ? parts[1] : top;
                    String bottom = parts.length > 2 ? parts[2] : top, left = parts.length > 3 ? parts[3] : right;
                    values.putIfAbsent(name + "-top", top);
                    values.putIfAbsent(name + "-right", right);
                    values.putIfAbsent(name + "-bottom", bottom);
                    values.putIfAbsent(name + "-left", left);
                }
            }
            String inline = values.get(name + "-inline");
            if (inline != null && !inline.isBlank()) {
                String[] parts = inline.trim().split("\\s+");
                values.putIfAbsent(name + "-left", parts[0]);
                values.putIfAbsent(name + "-right", parts.length > 1 ? parts[1] : parts[0]);
            }
            String block = values.get(name + "-block");
            if (block != null && !block.isBlank()) {
                String[] parts = block.trim().split("\\s+");
                values.putIfAbsent(name + "-top", parts[0]);
                values.putIfAbsent(name + "-bottom", parts.length > 1 ? parts[1] : parts[0]);
            }
        }

        private static void expandInset(Map<String, String> values) {
            String raw = values.get("inset");
            if (raw == null || raw.isBlank()) return;
            String[] parts = raw.trim().split("\\s+");
            if (parts.length < 1 || parts.length > 4) return;
            String top = parts[0], right = parts.length > 1 ? parts[1] : top;
            String bottom = parts.length > 2 ? parts[2] : top, left = parts.length > 3 ? parts[3] : right;
            values.putIfAbsent("top", top);
            values.putIfAbsent("right", right);
            values.putIfAbsent("bottom", bottom);
            values.putIfAbsent("left", left);
        }

        private static void expandBorder(Map<String, String> values) {
            String border = values.get("border");
            if (border != null && !border.isBlank()) {
                parseBorderParts(border, "", values);
                for (String side : List.of("top", "right", "bottom", "left")) {
                    values.putIfAbsent("border-" + side, border);
                    parseBorderParts(border, "-" + side, values);
                }
            }
            for (String side : List.of("top", "right", "bottom", "left")) {
                String sideVal = values.get("border-" + side);
                if (sideVal != null && !sideVal.isBlank()) {
                    parseBorderParts(sideVal, "-" + side, values);
                }
            }
            String bw = values.get("border-width");
            if (bw != null && !bw.isBlank()) {
                String[] p = bw.trim().split("\\s+");
                if (p.length >= 1 && p.length <= 4) {
                    values.putIfAbsent("border-top-width", p[0]);
                    values.putIfAbsent("border-right-width", p.length > 1 ? p[1] : p[0]);
                    values.putIfAbsent("border-bottom-width", p.length > 2 ? p[2] : p[0]);
                    values.putIfAbsent("border-left-width", p.length > 3 ? p[3] : (p.length > 1 ? p[1] : p[0]));
                }
            }
        }

        private static void parseBorderParts(String raw, String sideSuffix, Map<String, String> values) {
            for (String token : raw.trim().split("\\s+")) {
                String lower = token.toLowerCase(Locale.ROOT);
                if (lower.matches("none|hidden|dotted|dashed|solid|double|groove|ridge|inset|outset")) {
                    values.putIfAbsent("border" + sideSuffix + "-style", lower);
                    if (lower.equals("none") || lower.equals("hidden")) {
                        values.putIfAbsent("border" + sideSuffix + "-width", "0px");
                    }
                } else if (lower.matches("\\d+(\\.\\d+)?(px|em|rem|pt)?|thin|medium|thick")) {
                    values.putIfAbsent("border" + sideSuffix + "-width", lower);
                } else if (parseColor(token, null) != null) {
                    values.putIfAbsent("border" + sideSuffix + "-color", token);
                }
            }
        }

        private static void expandBorderRadius(Map<String, String> values) {
            String raw = values.get("border-radius");
            if (raw == null || raw.isBlank()) return;
            String[] parts = raw.split("/")[0].trim().split("\\s+");
            if (parts.length < 1 || parts.length > 4) return;
            String tl = parts[0];
            String tr = parts.length > 1 ? parts[1] : tl;
            String br = parts.length > 2 ? parts[2] : tl;
            String bl = parts.length > 3 ? parts[3] : tr;
            values.putIfAbsent("border-top-left-radius", tl);
            values.putIfAbsent("border-top-right-radius", tr);
            values.putIfAbsent("border-bottom-right-radius", br);
            values.putIfAbsent("border-bottom-left-radius", bl);
        }

        private static void expandFont(Map<String, String> values) {
            String font = values.get("font");
            if (font == null || font.isBlank()) return;
            Matcher sizeMatcher = Pattern.compile("(\\d+(?:\\.\\d+)?(?:px|pt|em|rem|%))(?:/(\\d+(?:\\.\\d+)?(?:px|pt|em|rem|%)?))?\\s+(.+)$").matcher(font.trim());
            if (sizeMatcher.find()) {
                values.putIfAbsent("font-size", sizeMatcher.group(1));
                if (sizeMatcher.group(2) != null) values.putIfAbsent("line-height", sizeMatcher.group(2));
                values.putIfAbsent("font-family", sizeMatcher.group(3).trim());
                String prefix = font.substring(0, sizeMatcher.start()).toLowerCase(Locale.ROOT);
                if (prefix.contains("bold") || prefix.matches(".*\\b[6-9]00\\b.*")) {
                    values.putIfAbsent("font-weight", "bold");
                }
                if (prefix.contains("italic") || prefix.contains("oblique")) {
                    values.putIfAbsent("font-style", "italic");
                }
            }
        }

        private static void expandFlex(Map<String, String> values) {
            String flex = values.get("flex");
            if (flex == null || flex.isBlank()) return;
            String clean = flex.trim().toLowerCase(Locale.ROOT);
            if (clean.equals("none")) {
                values.putIfAbsent("flex-grow", "0");
                values.putIfAbsent("flex-shrink", "0");
                values.putIfAbsent("flex-basis", "auto");
            } else if (clean.equals("auto")) {
                values.putIfAbsent("flex-grow", "1");
                values.putIfAbsent("flex-shrink", "1");
                values.putIfAbsent("flex-basis", "auto");
            } else {
                String[] parts = clean.split("\\s+");
                if (parts.length >= 1 && isPureNumber(parts[0])) {
                    values.putIfAbsent("flex-grow", parts[0]);
                    if (parts.length >= 2 && isPureNumber(parts[1])) {
                        values.putIfAbsent("flex-shrink", parts[1]);
                        if (parts.length >= 3) values.putIfAbsent("flex-basis", parts[2]);
                    } else if (parts.length == 2) {
                        values.putIfAbsent("flex-basis", parts[1]);
                    }
                }
            }
        }

        private static void expandGap(Map<String, String> values) {
            String gap = values.get("gap");
            if (gap == null || gap.isBlank()) return;
            String[] parts = gap.trim().split("\\s+");
            values.putIfAbsent("row-gap", parts[0]);
            values.putIfAbsent("column-gap", parts.length > 1 ? parts[1] : parts[0]);
        }

        private static void expandOverflow(Map<String, String> values) {
            String ov = values.get("overflow");
            if (ov == null || ov.isBlank()) return;
            String[] parts = ov.trim().split("\\s+");
            values.putIfAbsent("overflow-x", parts[0]);
            values.putIfAbsent("overflow-y", parts.length > 1 ? parts[1] : parts[0]);
        }

        static Map<String, String> parseDeclarations(String source) {
            Map<String, String> normal = new LinkedHashMap<>();
            Map<String, String> important = new LinkedHashMap<>();
            parseDeclarationsWithImportance(source, normal, important);
            normal.putAll(important);
            return normal;
        }

        private static void parseDeclarationsWithImportance(
                String source, Map<String, String> normal, Map<String, String> important) {
            if (source == null || source.isBlank()) return;
            for (String declaration : splitDeclarations(source)) {
                int colon = declaration.indexOf(':');
                if (colon < 1) continue;
                String key = declaration.substring(0, colon).trim();
                if (!key.startsWith("--")) key = key.toLowerCase(Locale.ROOT);
                String value = declaration.substring(colon + 1).trim();
                boolean isImportant = false;
                if (value.toLowerCase(Locale.ROOT).endsWith("!important")) {
                    value = value.substring(0, value.length() - 10).trim();
                    isImportant = true;
                }
                if (!key.isEmpty() && !value.isEmpty()) {
                    if (isImportant) important.put(key, value);
                    else normal.put(key, value);
                }
            }
        }

        static List<String> splitDeclarations(String text) {
            ArrayList<String> out = new ArrayList<>();
            int start = 0, depth = 0;
            char quote = 0;
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (quote != 0) {
                    if (c == quote && (i == 0 || text.charAt(i - 1) != '\\')) quote = 0;
                } else if (c == '\'' || c == '"') {
                    quote = c;
                } else if (c == '(') {
                    depth++;
                } else if (c == ')') {
                    depth = Math.max(0, depth - 1);
                } else if (c == ';' && depth == 0) {
                    out.add(text.substring(start, i));
                    start = i + 1;
                }
            }
            out.add(text.substring(start));
            return out;
        }

        static List<String> splitSelectors(String text) {
            ArrayList<String> out = new ArrayList<>();
            int start = 0, brackets = 0, parens = 0;
            char quote = 0;
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (quote != 0) {
                    if (c == quote) quote = 0;
                } else if (c == '\'' || c == '"') {
                    quote = c;
                } else if (c == '[') {
                    brackets++;
                } else if (c == ']') {
                    brackets--;
                } else if (c == '(') {
                    parens++;
                } else if (c == ')') {
                    parens--;
                } else if (c == ',' && brackets == 0 && parens == 0) {
                    out.add(text.substring(start, i));
                    start = i + 1;
                }
            }
            out.add(text.substring(start));
            return out;
        }

        public static boolean matches(Dom.Node node, String selector) {
            if (node == null || node.isText() || node.isComment() || selector == null || selector.isBlank()) {
                return false;
            }
            List<String> parts = tokenizeSelectorCombinators(selector.trim());
            if (parts.isEmpty()) return false;
            return matchChain(node, parts, parts.size() - 1);
        }

        private static boolean matchChain(Dom.Node current, List<String> parts, int idx) {
            if (current == null || current.isText() || current.isComment()) return false;
            if (!matchesCompound(current, parts.get(idx))) return false;
            if (idx == 0) return true;

            String prev = parts.get(idx - 1);
            boolean hasCombinator = prev.equals(">") || prev.equals("+") || prev.equals("~");
            String combinator = hasCombinator ? prev : " ";
            int targetIdx = hasCombinator ? idx - 2 : idx - 1;
            if (targetIdx < 0) return false;

            return switch (combinator) {
                case ">" -> matchChain(current.parent, parts, targetIdx);
                case "+" -> {
                    Dom.Node prevSib = current.previousElementSibling();
                    yield prevSib != null && matchChain(prevSib, parts, targetIdx);
                }
                case "~" -> {
                    for (Dom.Node sib = current.previousElementSibling(); sib != null; sib = sib.previousElementSibling()) {
                        if (matchChain(sib, parts, targetIdx)) yield true;
                    }
                    yield false;
                }
                default -> {
                    for (Dom.Node anc = current.parent; anc != null; anc = anc.parent) {
                        if (matchChain(anc, parts, targetIdx)) yield true;
                    }
                    yield false;
                }
            };
        }

        private static List<String> tokenizeSelectorCombinators(String selector) {
            List<String> tokens = new ArrayList<>();
            StringBuilder current = new StringBuilder();
            int brackets = 0, parens = 0;
            char quote = 0;
            for (int i = 0; i < selector.length(); i++) {
                char c = selector.charAt(i);
                if (quote != 0) {
                    current.append(c);
                    if (c == quote && selector.charAt(i - 1) != '\\') quote = 0;
                } else if (c == '\'' || c == '"') {
                    quote = c;
                    current.append(c);
                } else if (c == '[') {
                    brackets++;
                    current.append(c);
                } else if (c == ']') {
                    brackets = Math.max(0, brackets - 1);
                    current.append(c);
                } else if (c == '(') {
                    parens++;
                    current.append(c);
                } else if (c == ')') {
                    parens = Math.max(0, parens - 1);
                    current.append(c);
                } else if (brackets == 0 && parens == 0 && (c == '>' || c == '+' || c == '~')) {
                    if (!current.isEmpty()) {
                        tokens.add(current.toString());
                        current.setLength(0);
                    }
                    tokens.add(String.valueOf(c));
                } else if (brackets == 0 && parens == 0 && Character.isWhitespace(c)) {
                    if (!current.isEmpty()) {
                        tokens.add(current.toString());
                        current.setLength(0);
                    }
                } else {
                    current.append(c);
                }
            }
            if (!current.isEmpty()) tokens.add(current.toString());
            return tokens;
        }

        private static boolean matchesCompound(Dom.Node node, String selector) {
            if (node == null || node.isText() || node.isComment() || selector.isBlank()) return false;
            if (selector.contains("::") || selector.endsWith(":before") || selector.endsWith(":after")) return false;
            String s = selector;
            Matcher attrs = ATTR_PATTERN.matcher(s);
            while (attrs.find()) {
                String fullMatch = attrs.group(0);
                boolean ignoreCase = fullMatch.matches("(?s).*\\s+[iI]\\]$");
                String name = attrs.group(1), op = attrs.group(2), expected = attrs.group(4);
                if (!node.hasAttribute(name)) return false;
                if (op != null) {
                    String actual = node.attr(name);
                    if (ignoreCase) {
                        actual = actual.toLowerCase(Locale.ROOT);
                        expected = expected.toLowerCase(Locale.ROOT);
                    }
                    final String exp = expected;
                    boolean ok = switch (op) {
                        case "=" -> actual.equals(exp);
                        case "~=" -> !exp.isEmpty() && List.of(actual.trim().split("\\s+")).contains(exp);
                        case "|=" -> actual.equals(exp) || actual.startsWith(exp + "-");
                        case "^=" -> !exp.isEmpty() && actual.startsWith(exp);
                        case "$=" -> !exp.isEmpty() && actual.endsWith(exp);
                        case "*=" -> !exp.isEmpty() && actual.contains(exp);
                        default -> false;
                    };
                    if (!ok) return false;
                }
            }
            s = ATTR_PATTERN.matcher(s).replaceAll("");
            Matcher pseudo = PSEUDO_PATTERN.matcher(s);
            while (pseudo.find()) {
                String raw = pseudo.group(1);
                String lower = raw.toLowerCase(Locale.ROOT);
                String name = lower.contains("(") ? lower.substring(0, lower.indexOf('(')) : lower;
                switch (name) {
                    case "first-child" -> { if (elementIndex(node) != 1) return false; }
                    case "last-child" -> { if (elementIndexFromEnd(node) != 1) return false; }
                    case "only-child" -> { if (elementIndex(node) != 1 || elementIndexFromEnd(node) != 1) return false; }
                    case "first-of-type" -> { if (elementTypeIndex(node) != 1) return false; }
                    case "last-of-type" -> { if (elementTypeIndexFromEnd(node) != 1) return false; }
                    case "only-of-type" -> { if (elementTypeIndex(node) != 1 || elementTypeIndexFromEnd(node) != 1) return false; }
                    case "nth-child" -> { if (!matchesNth(elementIndex(node), pseudo.group(2))) return false; }
                    case "nth-last-child" -> { if (!matchesNth(elementIndexFromEnd(node), pseudo.group(3))) return false; }
                    case "nth-of-type" -> { if (!matchesNth(elementTypeIndex(node), pseudo.group(4))) return false; }
                    case "nth-last-of-type" -> { if (!matchesNth(elementTypeIndexFromEnd(node), pseudo.group(5))) return false; }
                    case "not" -> {
                        String arg = pseudo.group(6);
                        if (arg != null && !arg.contains(":has(") && !arg.contains(":not(")) {
                            for (String sub : splitSelectors(arg)) {
                                if (matchesCompound(node, sub.trim())) return false;
                            }
                        }
                    }
                    case "is", "where" -> {
                        String arg = name.equals("is") ? pseudo.group(7) : pseudo.group(8);
                        if (arg != null && !arg.contains(":has(")) {
                            boolean any = false;
                            for (String sub : splitSelectors(arg)) {
                                if (matchesCompound(node, sub.trim())) {
                                    any = true;
                                    break;
                                }
                            }
                            if (!any) return false;
                        }
                    }
                    case "has" -> {
                        String arg = pseudo.group(9);
                        if (arg == null) return false;
                        arg = arg.trim();
                        if (arg.startsWith(">")) {
                            String childSel = arg.substring(1).trim();
                            boolean any = false;
                            for (Dom.Node c : node.elementChildren()) {
                                if (matchesCompound(c, childSel)) { any = true; break; }
                            }
                            if (!any) return false;
                        } else {
                            boolean any = false;
                            for (Dom.Node c : node.elementChildren()) {
                                if (matchesCompound(c, arg)) { any = true; break; }
                            }
                            if (!any) return false;
                        }
                    }
                    case "empty" -> {
                        for (Dom.Node c : node.children) {
                            if (!c.isComment() && (!c.isText() || !c.text.isEmpty())) return false;
                        }
                    }
                    case "root" -> { if (node.parent == null || (!node.tag.equals("html") && !node.parent.tag.equals("document"))) return false; }
                    case "checked" -> { if (!node.hasAttribute("checked") && !node.hasAttribute("selected")) return false; }
                    case "disabled" -> { if (!AriaSupport.disabled(node)) return false; }
                    case "enabled" -> { if (AriaSupport.disabled(node)) return false; }
                    case "required" -> { if (!node.hasAttribute("required")) return false; }
                    case "optional" -> { if (node.hasAttribute("required")) return false; }
                    case "read-only" -> { if (!node.hasAttribute("readonly") && !AriaSupport.disabled(node)) return false; }
                    case "read-write" -> { if (node.hasAttribute("readonly") || AriaSupport.disabled(node)) return false; }
                    case "open" -> { if (!node.hasAttribute("open")) return false; }
                    case "link", "any-link" -> { if ((!node.tag.equals("a") && !node.tag.equals("area")) || node.attr("href").isEmpty()) return false; }
                    case "visited", "focus", "hover", "active", "focus-within", "focus-visible", "target" -> { return false; }
                }
            }
            String tag = PSEUDO_PATTERN.matcher(s).replaceAll("").replaceAll("#[\\w-]+|\\.[\\w-]+", "").trim();
            if (tag.contains(":")) return false;
            Matcher ids = Pattern.compile("#([\\w-]+)").matcher(s);
            while (ids.find()) if (!ids.group(1).equals(node.attr("id"))) return false;
            Matcher classes = Pattern.compile("\\.([\\w-]+)").matcher(s);
            String classNames = " " + node.attr("class").trim().replaceAll("\\s+", " ") + " ";
            while (classes.find()) if (!classNames.contains(" " + classes.group(1) + " ")) return false;
            return tag.isEmpty() || tag.equals("*") || tag.equalsIgnoreCase(node.tag);
        }

        private static int elementIndex(Dom.Node node) {
            if (node.parent == null) return 1;
            int index = 0;
            for (Dom.Node sibling : node.parent.children) {
                if (!sibling.isText() && !sibling.isComment()) index++;
                if (sibling == node) return index;
            }
            return index;
        }

        private static int elementIndexFromEnd(Dom.Node node) {
            if (node.parent == null) return 1;
            int index = 0;
            List<Dom.Node> siblings = node.parent.children;
            for (int i = siblings.size() - 1; i >= 0; i--) {
                Dom.Node sibling = siblings.get(i);
                if (!sibling.isText() && !sibling.isComment()) index++;
                if (sibling == node) return index;
            }
            return index;
        }

        private static int elementTypeIndex(Dom.Node node) {
            if (node.parent == null) return 1;
            int index = 0;
            for (Dom.Node sibling : node.parent.children) {
                if (sibling.tag.equals(node.tag)) index++;
                if (sibling == node) return index;
            }
            return index;
        }

        private static int elementTypeIndexFromEnd(Dom.Node node) {
            if (node.parent == null) return 1;
            int index = 0;
            List<Dom.Node> siblings = node.parent.children;
            for (int i = siblings.size() - 1; i >= 0; i--) {
                Dom.Node sibling = siblings.get(i);
                if (sibling.tag.equals(node.tag)) index++;
                if (sibling == node) return index;
            }
            return index;
        }

        private static boolean matchesNth(int index, String expression) {
            if (expression == null) return false;
            String value = expression.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
            if (value.equals("odd")) return index % 2 == 1;
            if (value.equals("even")) return index % 2 == 0;
            int nPos = value.indexOf('n');
            if (nPos >= 0) {
                try {
                    String aPart = value.substring(0, nPos);
                    int a = aPart.isEmpty() || aPart.equals("+") ? 1 : (aPart.equals("-") ? -1 : Integer.parseInt(aPart));
                    String bPart = value.substring(nPos + 1);
                    int b = bPart.isEmpty() ? 0 : Integer.parseInt(bPart);
                    if (a == 0) return index == b;
                    int diff = index - b;
                    return (diff * a >= 0) && (diff % a == 0);
                } catch (NumberFormatException ignored) {
                    return false;
                }
            }
            try {
                return index == Integer.parseInt(value);
            } catch (NumberFormatException ignored) {
                return false;
            }
        }

        private static int specificity(String selector) {
            String cleaned = selector.replaceAll("(?i):where\\([^)]*\\)", "");
            int ids = count(cleaned, '#');
            int classes = count(cleaned, '.') + count(cleaned, '[') + count(cleaned, ':');
            int tags = 0;
            for (String part : cleaned.replace(">", " ").replace("+", " ").replace("~", " ").trim().split("\\s+")) {
                String tag = part.replaceAll("#[\\w-]+|\\.[\\w-]+|\\[.*?]|:.*", "");
                if (!tag.isBlank() && !tag.equals("*")) tags++;
            }
            return ids * 100 + classes * 10 + tags;
        }

        private static int count(String text, char ch) {
            int n = 0;
            for (int i = 0; i < text.length(); i++) if (text.charAt(i) == ch) n++;
            return n;
        }

        private static String stripComments(String css) {
            return css.replaceAll("(?s)/\\*.*?\\*/", "");
        }

        public static Color parseColor(String raw, Color fallback) {
            if (raw == null || raw.isBlank()) return fallback;
            String text = raw.trim().toLowerCase(Locale.ROOT);
            if (text.equals("none") || text.equals("initial") || text.equals("unset") || text.equals("currentcolor")) {
                return fallback;
            }
            try {
                Matcher rgb = Pattern.compile("rgba?\\(\\s*([0-9.]+%?)\\s*[,\\s]\\s*([0-9.]+%?)\\s*[,\\s]\\s*([0-9.]+%?)(?:\\s*[,/]\\s*([0-9.]+%?))?\\s*\\)").matcher(text);
                if (rgb.find()) {
                    int r = parseChannel(rgb.group(1));
                    int g = parseChannel(rgb.group(2));
                    int b = parseChannel(rgb.group(3));
                    int a = parseAlpha(rgb.group(4));
                    return new Color(r, g, b, a);
                }
                Matcher hsl = Pattern.compile("hsla?\\(\\s*([0-9.]+)(?:deg)?\\s*[,\\s]\\s*([0-9.]+)%\\s*[,\\s]\\s*([0-9.]+)%(?:\\s*[,/]\\s*([0-9.]+%?))?\\s*\\)").matcher(text);
                if (hsl.find()) {
                    float h = (Float.parseFloat(hsl.group(1)) % 360f) / 360f;
                    if (h < 0) h += 1f;
                    float s = Math.max(0f, Math.min(1f, Float.parseFloat(hsl.group(2)) / 100f));
                    float l = Math.max(0f, Math.min(1f, Float.parseFloat(hsl.group(3)) / 100f));
                    int a = parseAlpha(hsl.group(4));
                    return hslToColor(h, s, l, a);
                }
                Matcher hexMatcher = Pattern.compile("#([0-9a-f]{8}|[0-9a-f]{6}|[0-9a-f]{4}|[0-9a-f]{3})\\b").matcher(text);
                if (hexMatcher.find()) {
                    String hex = hexMatcher.group(1);
                    if (hex.length() == 3) {
                        return new Color(
                                Integer.parseInt("" + hex.charAt(0) + hex.charAt(0), 16),
                                Integer.parseInt("" + hex.charAt(1) + hex.charAt(1), 16),
                                Integer.parseInt("" + hex.charAt(2) + hex.charAt(2), 16));
                    }
                    if (hex.length() == 4) {
                        return new Color(
                                Integer.parseInt("" + hex.charAt(0) + hex.charAt(0), 16),
                                Integer.parseInt("" + hex.charAt(1) + hex.charAt(1), 16),
                                Integer.parseInt("" + hex.charAt(2) + hex.charAt(2), 16),
                                Integer.parseInt("" + hex.charAt(3) + hex.charAt(3), 16));
                    }
                    if (hex.length() == 6) {
                        return new Color(
                                Integer.parseInt(hex.substring(0, 2), 16),
                                Integer.parseInt(hex.substring(2, 4), 16),
                                Integer.parseInt(hex.substring(4, 6), 16));
                    }
                    if (hex.length() == 8) {
                        int r = Integer.parseInt(hex.substring(0, 2), 16);
                        int g = Integer.parseInt(hex.substring(2, 4), 16);
                        int b = Integer.parseInt(hex.substring(4, 6), 16);
                        int a = Integer.parseInt(hex.substring(6, 8), 16);
                        return new Color(r, g, b, a);
                    }
                }
            } catch (Exception ignored) {
                return fallback;
            }
            for (String token : text.split("\\s+")) {
                Color named = switch (token) {
                    case "black" -> Color.BLACK;
                    case "white" -> Color.WHITE;
                    case "red" -> new Color(217, 48, 37);
                    case "blue" -> new Color(26, 115, 232);
                    case "green" -> new Color(24, 128, 56);
                    case "gray", "grey" -> Color.GRAY;
                    case "darkgray", "darkgrey" -> Color.DARK_GRAY;
                    case "lightgray", "lightgrey" -> new Color(218, 220, 224);
                    case "dimgray", "dimgrey" -> new Color(105, 105, 105);
                    case "slategray", "slategrey" -> new Color(112, 128, 144);
                    case "yellow" -> new Color(253, 214, 99);
                    case "gold" -> new Color(255, 215, 0);
                    case "orange" -> Color.ORANGE;
                    case "darkorange" -> new Color(255, 140, 0);
                    case "orangered" -> new Color(255, 69, 0);
                    case "coral" -> new Color(255, 127, 80);
                    case "tomato" -> new Color(255, 99, 71);
                    case "salmon" -> new Color(250, 128, 114);
                    case "crimson" -> new Color(220, 20, 60);
                    case "firebrick" -> new Color(178, 34, 34);
                    case "darkred" -> new Color(139, 0, 0);
                    case "pink" -> new Color(255, 192, 203);
                    case "hotpink" -> new Color(255, 105, 180);
                    case "deeppink" -> new Color(255, 20, 147);
                    case "purple" -> new Color(128, 0, 128);
                    case "rebeccapurple" -> new Color(102, 51, 153);
                    case "indigo" -> new Color(75, 0, 130);
                    case "violet" -> new Color(238, 130, 238);
                    case "plum" -> new Color(221, 160, 221);
                    case "lavender" -> new Color(230, 230, 250);
                    case "transparent" -> new Color(0, 0, 0, 0);
                    case "navy" -> new Color(0, 0, 128);
                    case "darkblue" -> new Color(0, 0, 139);
                    case "royalblue" -> new Color(65, 105, 225);
                    case "steelblue" -> new Color(70, 130, 180);
                    case "dodgerblue" -> new Color(30, 144, 255);
                    case "deepskyblue" -> new Color(0, 191, 255);
                    case "skyblue" -> new Color(135, 206, 235);
                    case "lightblue" -> new Color(173, 216, 230);
                    case "teal" -> new Color(0, 128, 128);
                    case "darkcyan" -> new Color(0, 139, 139);
                    case "turquoise" -> new Color(64, 224, 208);
                    case "aquamarine" -> new Color(127, 255, 212);
                    case "darkgreen" -> new Color(0, 100, 0);
                    case "forestgreen" -> new Color(34, 139, 34);
                    case "seagreen" -> new Color(46, 139, 87);
                    case "limegreen" -> new Color(50, 205, 50);
                    case "lime" -> new Color(0, 255, 0);
                    case "lightgreen" -> new Color(144, 238, 144);
                    case "silver" -> new Color(192, 192, 192);
                    case "maroon" -> new Color(128, 0, 0);
                    case "brown" -> new Color(165, 42, 42);
                    case "saddlebrown" -> new Color(139, 69, 19);
                    case "chocolate" -> new Color(210, 105, 30);
                    case "tan" -> new Color(210, 180, 140);
                    case "wheat" -> new Color(245, 222, 179);
                    case "beige" -> new Color(245, 245, 220);
                    case "khaki" -> new Color(240, 230, 140);
                    case "olive" -> new Color(128, 128, 0);
                    case "aqua", "cyan" -> Color.CYAN;
                    case "fuchsia", "magenta" -> Color.MAGENTA;
                    case "whitesmoke" -> new Color(245, 245, 245);
                    case "ghostwhite" -> new Color(248, 248, 255);
                    case "snow" -> new Color(255, 250, 250);
                    case "aliceblue" -> new Color(240, 248, 255);
                    case "azure" -> new Color(240, 255, 255);
                    case "mintcream" -> new Color(245, 255, 250);
                    case "honeydew" -> new Color(240, 255, 240);
                    case "ivory" -> new Color(255, 255, 240);
                    case "linen" -> new Color(250, 240, 230);
                    case "seashell" -> new Color(255, 245, 238);
                    case "cornsilk" -> new Color(255, 248, 220);
                    default -> null;
                };
                if (named != null) return named;
            }
            return fallback;
        }

        private static int parseChannel(String s) {
            if (s.endsWith("%")) {
                float pct = Float.parseFloat(s.substring(0, s.length() - 1));
                return clamp(Math.round(pct * 2.55f));
            }
            return clamp(Math.round(Float.parseFloat(s)));
        }

        private static int parseAlpha(String s) {
            if (s == null || s.isEmpty()) return 255;
            if (s.endsWith("%")) {
                float pct = Float.parseFloat(s.substring(0, s.length() - 1));
                return clamp(Math.round(pct * 2.55f));
            }
            return clamp(Math.round(Float.parseFloat(s) * 255f));
        }

        private static Color hslToColor(float h, float s, float l, int alpha) {
            float r, g, b;
            if (s == 0f) {
                r = g = b = l;
            } else {
                float q = l < 0.5f ? l * (1f + s) : l + s - l * s;
                float p = 2f * l - q;
                r = hueToRgb(p, q, h + 1f / 3f);
                g = hueToRgb(p, q, h);
                b = hueToRgb(p, q, h - 1f / 3f);
            }
            return new Color(clamp(Math.round(r * 255)), clamp(Math.round(g * 255)), clamp(Math.round(b * 255)), alpha);
        }

        private static float hueToRgb(float p, float q, float t) {
            if (t < 0f) t += 1f;
            if (t > 1f) t -= 1f;
            if (t < 1f / 6f) return p + (q - p) * 6f * t;
            if (t < 1f / 2f) return q;
            if (t < 2f / 3f) return p + (q - p) * (2f / 3f - t) * 6f;
            return p;
        }

        private static int clamp(int value) {
            return Math.max(0, Math.min(255, value));
        }
    }

    // =========================================================================
    // TinyJS Interpreter for DOM Manipulation
    // =========================================================================

    public static final class TinyJs {
        private static final int MAX_STEPS = 20_000;
        private static final int MAX_SOURCE_LENGTH = 256_000;
        private static final Object UNDEFINED = new Object() {
            @Override public String toString() { return "undefined"; }
        };

        private record JsFunction(List<String> params, int bodyStart, int bodyEnd, Map<String, Object> closure) {}
        private record StyleProxy(Dom.Node node) {}
        private record DatasetProxy(Dom.Node node) {}
        private static final class ReturnSignal extends RuntimeException {
            final Object value;
            ReturnSignal(Object value) {
                super(null, null, false, false);
                this.value = value;
            }
        }
        private static final class BreakSignal extends RuntimeException {
            BreakSignal() { super(null, null, false, false); }
        }
        private static final class ContinueSignal extends RuntimeException {
            ContinueSignal() { super(null, null, false, false); }
        }

        private final Dom.Node document;
        private final Map<String, Object> variables = new HashMap<>();
        private final List<String> logs = new ArrayList<>();
        private List<Token> tokens = List.of();
        private int cursor;
        private int steps;

        public TinyJs(Dom.Node document) {
            this.document = document;
        }

        public List<String> logs() {
            return Collections.unmodifiableList(logs);
        }

        public Object getVariable(String name) {
            return variables.get(name);
        }

        public void setVariable(String name, Object value) {
            if (name != null) variables.put(name, value);
        }

        public void execute(String source) {
            if (source == null || source.isBlank() || source.length() > MAX_SOURCE_LENGTH) return;
            tokens = tokenize(source);
            cursor = 0;
            steps = 0;
            while (!atEnd() && steps++ < MAX_STEPS) {
                int before = cursor;
                try {
                    statement(variables);
                } catch (ReturnSignal ignored) {
                    break;
                } catch (RuntimeException ignored) {
                    synchronize();
                }
                if (cursor == before) cursor++;
            }
        }

        private void tick() {
            if (++steps > MAX_STEPS) throw new IllegalStateException("Execution step limit exceeded");
        }

        private void statement(Map<String, Object> env) {
            tick();
            if (match(";")) return;
            if (match("{")) {
                while (!atEnd() && !check("}")) {
                    statement(env);
                }
                consume("}", "Expected '}'");
                return;
            }
            if (match("var", "let", "const")) {
                do {
                    String name = consumeType(Type.IDENT, "Expected variable name").text;
                    Object value = match("=") ? expression(env) : null;
                    env.put(name, value);
                } while (match(","));
                match(";");
                return;
            }
            if (match("function")) {
                String name = consumeType(Type.IDENT, "Expected function name").text;
                JsFunction fn = parseFunctionTail(env);
                env.put(name, fn);
                return;
            }
            if (match("return")) {
                Object val = (check(";") || check("}") || atEnd()) ? null : expression(env);
                match(";");
                throw new ReturnSignal(val);
            }
            if (match("break")) {
                match(";");
                throw new BreakSignal();
            }
            if (match("continue")) {
                match(";");
                throw new ContinueSignal();
            }
            if (match("if")) {
                consume("(", "Expected '(' after if");
                boolean cond = truthy(expression(env));
                consume(")", "Expected ')' after condition");
                int thenStart = cursor;
                int thenEnd = skipStatementOrBlock(thenStart);
                int elseStart = -1, elseEnd = -1;
                cursor = thenEnd;
                if (match("else")) {
                    elseStart = cursor;
                    elseEnd = skipStatementOrBlock(elseStart);
                }
                if (cond) {
                    executeRange(thenStart, thenEnd, env);
                } else if (elseStart >= 0) {
                    executeRange(elseStart, elseEnd, env);
                }
                cursor = elseEnd >= 0 ? elseEnd : thenEnd;
                return;
            }
            if (match("while")) {
                consume("(", "Expected '(' after while");
                int condStart = cursor;
                int condEnd = findMatchingParen(condStart - 1);
                int bodyStart = condEnd + 1;
                int bodyEnd = skipStatementOrBlock(bodyStart);
                while (steps < MAX_STEPS) {
                    tick();
                    cursor = condStart;
                    if (!truthy(expression(env))) break;
                    try {
                        executeRange(bodyStart, bodyEnd, env);
                    } catch (BreakSignal b) {
                        break;
                    } catch (ContinueSignal ignored) {}
                }
                cursor = bodyEnd;
                return;
            }
            if (match("for")) {
                consume("(", "Expected '(' after for");
                Map<String, Object> loopEnv = env;
                if (check("let") || check("var") || check("const")) {
                    String decl = advance().text;
                    String varName = consumeType(Type.IDENT, "Expected loop variable").text;
                    if (match("of", "in")) {
                        String kind = previous().text;
                        Object iterable = expression(loopEnv);
                        consume(")", "Expected ')' after for-of");
                        int bodyStart = cursor;
                        int bodyEnd = skipStatementOrBlock(bodyStart);
                        Iterable<?> items = toIterable(iterable, kind.equals("in"));
                        for (Object item : items) {
                            tick();
                            loopEnv.put(varName, item);
                            try {
                                executeRange(bodyStart, bodyEnd, loopEnv);
                            } catch (BreakSignal b) {
                                break;
                            } catch (ContinueSignal ignored) {}
                        }
                        cursor = bodyEnd;
                        return;
                    }
                    Object initVal = match("=") ? expression(loopEnv) : null;
                    loopEnv.put(varName, initVal);
                    while (match(",")) {
                        String nextVar = consumeType(Type.IDENT, "Expected variable").text;
                        loopEnv.put(nextVar, match("=") ? expression(loopEnv) : null);
                    }
                    consume(";", "Expected ';' in for loop");
                } else if (!match(";")) {
                    expression(loopEnv);
                    consume(";", "Expected ';' in for loop");
                }
                int condStart = cursor;
                while (!atEnd() && !check(";")) advance();
                int condEnd = cursor;
                consume(";", "Expected ';' after for condition");
                int updateStart = cursor;
                int closeParen = findMatchingParenFromInner(updateStart);
                int bodyStart = closeParen + 1;
                int bodyEnd = skipStatementOrBlock(bodyStart);

                while (steps < MAX_STEPS) {
                    tick();
                    if (condEnd > condStart) {
                        cursor = condStart;
                        if (!truthy(expression(loopEnv))) break;
                    }
                    try {
                        executeRange(bodyStart, bodyEnd, loopEnv);
                    } catch (BreakSignal b) {
                        break;
                    } catch (ContinueSignal ignored) {}
                    if (closeParen > updateStart) {
                        cursor = updateStart;
                        while (cursor < closeParen) {
                            expression(loopEnv);
                            if (!match(",")) break;
                        }
                    }
                }
                cursor = bodyEnd;
                return;
            }
            if (match("try")) {
                int tryStart = cursor;
                int tryEnd = skipStatementOrBlock(tryStart);
                cursor = tryEnd;
                String catchVar = null;
                int catchStart = -1, catchEnd = -1;
                if (match("catch")) {
                    if (match("(")) {
                        catchVar = consumeType(Type.IDENT, "Expected catch identifier").text;
                        consume(")", "Expected ')'");
                    }
                    catchStart = cursor;
                    catchEnd = skipStatementOrBlock(catchStart);
                    cursor = catchEnd;
                }
                int finallyStart = -1, finallyEnd = -1;
                if (match("finally")) {
                    finallyStart = cursor;
                    finallyEnd = skipStatementOrBlock(finallyStart);
                    cursor = finallyEnd;
                }
                int endAll = cursor;
                try {
                    executeRange(tryStart, tryEnd, env);
                } catch (ReturnSignal | BreakSignal | ContinueSignal flow) {
                    if (finallyStart >= 0) executeRange(finallyStart, finallyEnd, env);
                    throw flow;
                } catch (RuntimeException ex) {
                    if (catchStart >= 0) {
                        if (catchVar != null) env.put(catchVar, ex.getMessage());
                        executeRange(catchStart, catchEnd, env);
                    }
                } finally {
                    if (finallyStart >= 0) executeRange(finallyStart, finallyEnd, env);
                }
                cursor = endAll;
                return;
            }
            expression(env);
            match(";");
        }

        private Iterable<?> toIterable(Object obj, boolean keysOnly) {
            if (obj instanceof List<?> list) {
                if (!keysOnly) return new ArrayList<>(list);
                List<Integer> indices = new ArrayList<>(list.size());
                for (int i = 0; i < list.size(); i++) indices.add(i);
                return indices;
            }
            if (obj instanceof Map<?, ?> map) {
                return keysOnly ? new ArrayList<>(map.keySet()) : new ArrayList<>(map.values());
            }
            if (obj instanceof String s) {
                List<String> chars = new ArrayList<>(s.length());
                for (int i = 0; i < s.length(); i++) chars.add(keysOnly ? String.valueOf(i) : String.valueOf(s.charAt(i)));
                return chars;
            }
            return List.of();
        }

        private void executeRange(int start, int end, Map<String, Object> env) {
            int saved = cursor;
            cursor = start;
            if (match("{")) {
                while (cursor < end - 1 && !atEnd() && steps < MAX_STEPS) {
                    int before = cursor;
                    statement(env);
                    if (cursor == before) cursor++;
                }
            } else {
                statement(env);
            }
            cursor = saved;
        }

        private int skipStatementOrBlock(int start) {
            int i = start;
            if (i < tokens.size() && tokens.get(i).text.equals("{")) {
                int depth = 1;
                i++;
                while (i < tokens.size() && depth > 0) {
                    String t = tokens.get(i).text;
                    if (t.equals("{")) depth++;
                    else if (t.equals("}")) depth--;
                    i++;
                }
                return i;
            }
            int parens = 0, braces = 0;
            while (i < tokens.size()) {
                String t = tokens.get(i).text;
                if (t.equals("(")) parens++;
                else if (t.equals(")")) parens = Math.max(0, parens - 1);
                else if (t.equals("{")) braces++;
                else if (t.equals("}")) {
                    if (braces == 0) return i;
                    braces--;
                } else if (t.equals(";") && parens == 0 && braces == 0) {
                    return i + 1;
                }
                i++;
            }
            return i;
        }

        private int findMatchingParen(int openPos) {
            int depth = 1;
            for (int i = openPos + 1; i < tokens.size(); i++) {
                String t = tokens.get(i).text;
                if (t.equals("(")) depth++;
                else if (t.equals(")") && --depth == 0) return i;
            }
            return tokens.size() - 1;
        }

        private int findMatchingParenFromInner(int pos) {
            int depth = 1;
            for (int i = pos; i < tokens.size(); i++) {
                String t = tokens.get(i).text;
                if (t.equals("(")) depth++;
                else if (t.equals(")") && --depth == 0) return i;
            }
            return tokens.size() - 1;
        }

        private JsFunction parseFunctionTail(Map<String, Object> env) {
            consume("(", "Expected '(' for function parameters");
            List<String> params = new ArrayList<>();
            if (!check(")")) {
                do {
                    params.add(consumeType(Type.IDENT, "Expected parameter name").text);
                } while (match(","));
            }
            consume(")", "Expected ')' after parameters");
            int bodyStart = cursor;
            int bodyEnd = skipStatementOrBlock(bodyStart);
            cursor = bodyEnd;
            return new JsFunction(params, bodyStart, bodyEnd, env);
        }

        private Object invokeFunction(JsFunction fn, List<Object> args) {
            tick();
            Map<String, Object> callEnv = new HashMap<>(fn.closure);
            for (int i = 0; i < fn.params.size(); i++) {
                callEnv.put(fn.params.get(i), i < args.size() ? args.get(i) : null);
            }
            int saved = cursor;
            try {
                cursor = fn.bodyStart;
                if (tokens.get(cursor).text.equals("{")) {
                    executeRange(fn.bodyStart, fn.bodyEnd, callEnv);
                    return null;
                } else {
                    return expression(callEnv);
                }
            } catch (ReturnSignal ret) {
                return ret.value;
            } finally {
                cursor = saved;
                for (Map.Entry<String, Object> e : callEnv.entrySet()) {
                    if (fn.closure.containsKey(e.getKey()) && !fn.params.contains(e.getKey())) {
                        fn.closure.put(e.getKey(), e.getValue());
                    }
                }
            }
        }

        private Object expression(Map<String, Object> env) {
            return assignment(env);
        }

        private Object assignment(Map<String, Object> env) {
            if (checkType(Type.IDENT) && checkNext("=>")) {
                String param = advance().text;
                advance(); // =>
                int bodyStart = cursor;
                int bodyEnd = skipArrowBody(bodyStart);
                cursor = bodyEnd;
                return new JsFunction(List.of(param), bodyStart, bodyEnd, env);
            }
            if (checkType(Type.IDENT) && (checkNext("=") || isCompoundAssignNext())) {
                String name = advance().text;
                String op = advance().text;
                if (!op.equals("=") && check("=")) {
                    op += advance().text;
                }
                Object rhs = assignment(env);
                Object current = env.get(name);
                Object result = applyAssignOp(op, current, rhs);
                env.put(name, result);
                return result;
            }
            return ternary(env);
        }

        private boolean isCompoundAssignNext() {
            if (cursor + 2 >= tokens.size()) return false;
            String n1 = tokens.get(cursor + 1).text;
            String n2 = tokens.get(cursor + 2).text;
            return (n1.equals("+") || n1.equals("-") || n1.equals("*") || n1.equals("/")) && n2.equals("=");
        }

        private Object applyAssignOp(String op, Object left, Object right) {
            return switch (op) {
                case "+=" -> (left instanceof String || right instanceof String)
                        ? stringValue(left) + stringValue(right)
                        : number(left) + number(right);
                case "-=" -> number(left) - number(right);
                case "*=" -> number(left) * number(right);
                case "/=" -> number(left) / number(right);
                default -> right;
            };
        }

        private int skipArrowBody(int start) {
            if (start < tokens.size() && tokens.get(start).text.equals("{")) {
                return skipStatementOrBlock(start);
            }
            int i = start, parens = 0, brackets = 0, braces = 0;
            while (i < tokens.size()) {
                String t = tokens.get(i).text;
                if (t.equals("(")) parens++;
                else if (t.equals(")")) {
                    if (parens == 0) return i;
                    parens--;
                } else if (t.equals("[")) brackets++;
                else if (t.equals("]")) {
                    if (brackets == 0) return i;
                    brackets--;
                } else if (t.equals("{")) braces++;
                else if (t.equals("}")) {
                    if (braces == 0) return i;
                    braces--;
                } else if ((t.equals(",") || t.equals(";")) && parens == 0 && brackets == 0 && braces == 0) {
                    return i;
                }
                i++;
            }
            return i;
        }

        private Object ternary(Map<String, Object> env) {
            Object cond = nullishOrLogicalOr(env);
            if (match("?")) {
                Object ifTrue = assignment(env);
                consume(":", "Expected ':' in ternary operator");
                Object ifFalse = assignment(env);
                return truthy(cond) ? ifTrue : ifFalse;
            }
            return cond;
        }

        private Object nullishOrLogicalOr(Map<String, Object> env) {
            Object left = logicalAnd(env);
            while (true) {
                if (check("|") && checkNext("|")) {
                    advance(); advance();
                    Object right = logicalAnd(env);
                    if (!truthy(left)) left = right;
                } else if (check("?") && checkNext("?")) {
                    advance(); advance();
                    Object right = logicalAnd(env);
                    if (left == null || left == UNDEFINED) left = right;
                } else {
                    break;
                }
            }
            return left;
        }

        private Object logicalAnd(Map<String, Object> env) {
            Object left = equality(env);
            while (check("&") && checkNext("&")) {
                advance(); advance();
                Object right = equality(env);
                if (truthy(left)) left = right;
            }
            return left;
        }

        private Object equality(Map<String, Object> env) {
            Object left = relational(env);
            while (true) {
                if (check("=") && checkNext("=")) {
                    advance(); advance();
                    if (match("=")) {
                        left = isEqual(left, relational(env));
                    } else {
                        left = isEqual(left, relational(env));
                    }
                } else if (check("!") && checkNext("=")) {
                    advance(); advance();
                    match("=");
                    left = !isEqual(left, relational(env));
                } else {
                    break;
                }
            }
            return left;
        }

        private Object relational(Map<String, Object> env) {
            Object left = additive(env);
            while (true) {
                if (match("<")) {
                    boolean eq = match("=");
                    double l = number(left), r = number(additive(env));
                    left = eq ? l <= r : l < r;
                } else if (match(">")) {
                    boolean eq = match("=");
                    double l = number(left), r = number(additive(env));
                    left = eq ? l >= r : l > r;
                } else {
                    break;
                }
            }
            return left;
        }

        private Object additive(Map<String, Object> env) {
            Object left = multiplicative(env);
            while (match("+", "-")) {
                String op = previous().text;
                Object right = multiplicative(env);
                if (op.equals("+") && (left instanceof String || right instanceof String)) {
                    left = stringValue(left) + stringValue(right);
                } else {
                    left = number(left) + (op.equals("+") ? number(right) : -number(right));
                }
            }
            return left;
        }

        private Object multiplicative(Map<String, Object> env) {
            Object left = unary(env);
            while (match("*", "/", "%")) {
                String op = previous().text;
                double right = number(unary(env));
                left = switch (op) {
                    case "*" -> number(left) * right;
                    case "/" -> right == 0 ? Double.NaN : number(left) / right;
                    default -> right == 0 ? Double.NaN : number(left) % right;
                };
            }
            return left;
        }

        private Object unary(Map<String, Object> env) {
            if (match("!")) return !truthy(unary(env));
            if (match("-")) return -number(unary(env));
            if (match("+")) return number(unary(env));
            if (match("typeof")) {
                Object val = unary(env);
                if (val == null) return "object";
                if (val == UNDEFINED) return "undefined";
                if (val instanceof Boolean) return "boolean";
                if (val instanceof Number) return "number";
                if (val instanceof String) return "string";
                if (val instanceof JsFunction) return "function";
                return "object";
            }
            if (check("+") && checkNext("+") && cursor + 2 < tokens.size() && tokens.get(cursor + 2).type == Type.IDENT) {
                advance(); advance();
                String name = advance().text;
                double next = number(env.get(name)) + 1;
                env.put(name, next);
                return next;
            }
            if (check("-") && checkNext("-") && cursor + 2 < tokens.size() && tokens.get(cursor + 2).type == Type.IDENT) {
                advance(); advance();
                String name = advance().text;
                double next = number(env.get(name)) - 1;
                env.put(name, next);
                return next;
            }
            return postfix(env);
        }

        private Object postfix(Map<String, Object> env) {
            Object target = primary(env);
            while (true) {
                if (check("?") && checkNext(".")) {
                    advance(); advance();
                    String member = consumeMemberName();
                    if (target == null || target == UNDEFINED) {
                        if (match("(")) {
                            parseArguments(env);
                        }
                        target = null;
                    } else {
                        target = evaluateMemberOrCall(target, member, env);
                    }
                } else if (match(".")) {
                    String member = consumeMemberName();
                    target = evaluateMemberOrCall(target, member, env);
                } else if (match("[")) {
                    Object key = expression(env);
                    consume("]", "Expected ']'");
                    if (match("=") && !check("=")) {
                        Object val = assignment(env);
                        writeIndexed(target, key, val);
                        target = val;
                    } else {
                        target = readIndexed(target, key);
                    }
                } else if (match("(")) {
                    List<Object> args = parseArguments(env);
                    if (target instanceof JsFunction fn) {
                        target = invokeFunction(fn, args);
                    }
                } else {
                    break;
                }
            }
            return target;
        }

        private String consumeMemberName() {
            if (checkType(Type.IDENT)) return advance().text;
            throw error("Expected property or method name");
        }

        private Object evaluateMemberOrCall(Object target, String member, Map<String, Object> env) {
            if (isCompoundAssignCurrent()) {
                String op = advance().text + advance().text;
                Object rhs = assignment(env);
                Object cur = readProperty(target, member);
                Object res = applyAssignOp(op, cur, rhs);
                writeProperty(target, member, res);
                return res;
            }
            if (match("=") && !check("=")) {
                Object value = assignment(env);
                writeProperty(target, member, value);
                return value;
            }
            if (match("(")) {
                List<Object> args = parseArguments(env);
                return invokeMemberCall(target, member, args);
            }
            return readProperty(target, member);
        }

        private boolean isCompoundAssignCurrent() {
            if (cursor + 1 >= tokens.size()) return false;
            String t1 = tokens.get(cursor).text;
            String t2 = tokens.get(cursor + 1).text;
            return (t1.equals("+") || t1.equals("-") || t1.equals("*") || t1.equals("/")) && t2.equals("=");
        }

        private List<Object> parseArguments(Map<String, Object> env) {
            List<Object> args = new ArrayList<>();
            if (!check(")")) {
                do {
                    if (check(")")) break;
                    args.add(assignment(env));
                } while (match(","));
            }
            consume(")", "Expected ')' after arguments");
            return args;
        }

        private Object primary(Map<String, Object> env) {
            if (match("(")) {
                if (isArrowFunctionAfterOpenParen()) {
                    List<String> params = new ArrayList<>();
                    if (!check(")")) {
                        do {
                            params.add(consumeType(Type.IDENT, "Expected parameter").text);
                        } while (match(","));
                    }
                    consume(")", "Expected ')'");
                    consume("=>", "Expected '=>'");
                    int bodyStart = cursor;
                    int bodyEnd = skipArrowBody(bodyStart);
                    cursor = bodyEnd;
                    return new JsFunction(params, bodyStart, bodyEnd, env);
                }
                Object value = expression(env);
                consume(")", "Expected ')'");
                return value;
            }
            if (match("[")) {
                List<Object> list = new ArrayList<>();
                if (!check("]")) {
                    do {
                        if (check("]")) break;
                        list.add(assignment(env));
                    } while (match(","));
                }
                consume("]", "Expected ']'");
                return list;
            }
            if (match("{")) {
                Map<String, Object> map = new LinkedHashMap<>();
                if (!check("}")) {
                    do {
                        if (check("}")) break;
                        String key;
                        if (matchType(Type.STRING) || matchType(Type.IDENT) || matchType(Type.NUMBER)) {
                            key = previous().text;
                        } else {
                            throw error("Expected object key");
                        }
                        if (match(":")) {
                            map.put(key, assignment(env));
                        } else {
                            map.put(key, env.get(key));
                        }
                    } while (match(","));
                }
                consume("}", "Expected '}'");
                return map;
            }
            if (match("function")) {
                if (checkType(Type.IDENT)) advance();
                return parseFunctionTail(env);
            }
            if (matchType(Type.STRING)) return previous().text;
            if (matchType(Type.NUMBER)) return Double.parseDouble(previous().text);
            if (match("true")) return Boolean.TRUE;
            if (match("false")) return Boolean.FALSE;
            if (match("null")) return null;
            if (match("undefined")) return UNDEFINED;
            if (match("document")) return document;
            if (match("window", "globalThis", "self")) return env;
            if (match("console")) return "console";
            if (match("Math")) return "Math";
            if (match("JSON")) return "JSON";
            if (match("Number")) return "Number";
            if (match("String")) return "String";
            if (match("Array")) return "Array";
            if (match("Object")) return "Object";
            if (matchType(Type.IDENT)) {
                String name = previous().text;
                if (check("(")) {
                    Object fnObj = env.get(name);
                    if (fnObj == null) {
                        return switch (name) {
                            case "parseInt" -> {
                                consume("(", "");
                                List<Object> a = parseArguments(env);
                                yield parseJsInt(a);
                            }
                            case "parseFloat" -> {
                                consume("(", "");
                                List<Object> a = parseArguments(env);
                                yield a.isEmpty() ? Double.NaN : number(a.get(0));
                            }
                            case "isNaN" -> {
                                consume("(", "");
                                List<Object> a = parseArguments(env);
                                yield a.isEmpty() || Double.isNaN(number(a.get(0)));
                            }
                            default -> null;
                        };
                    }
                }
                if (check("+") && checkNext("+")) {
                    advance(); advance();
                    double old = number(env.get(name));
                    env.put(name, old + 1);
                    return old;
                }
                if (check("-") && checkNext("-")) {
                    advance(); advance();
                    double old = number(env.get(name));
                    env.put(name, old - 1);
                    return old;
                }
                return env.get(name);
            }
            throw error("Expected a literal, variable, or DOM lookup");
        }

        private boolean isArrowFunctionAfterOpenParen() {
            int i = cursor;
            while (i < tokens.size()) {
                Token t = tokens.get(i);
                if (t.text.equals(")")) {
                    return i + 1 < tokens.size() && tokens.get(i + 1).text.equals("=>");
                }
                if (t.type != Type.IDENT && !t.text.equals(",")) return false;
                i++;
            }
            return false;
        }

        private static double parseJsInt(List<Object> args) {
            if (args.isEmpty()) return Double.NaN;
            String s = stringValue(args.get(0)).trim();
            int radix = args.size() > 1 ? (int) number(args.get(1)) : 10;
            if (radix == 0) radix = 10;
            try {
                Matcher m = Pattern.compile("^[+-]?[0-9a-zA-Z]+").matcher(s);
                if (m.find()) return Integer.parseInt(m.group(0), radix);
            } catch (Exception ignored) {}
            return Double.NaN;
        }

        private Object invokeMemberCall(Object target, String method, List<Object> args) {
            if (target == document && "document".equals(document.tag)) {
                String arg0 = args.isEmpty() ? "" : stringValue(args.get(0));
                switch (method) {
                    case "getElementById" -> { return document.byId(arg0); }
                    case "querySelector" -> { return document.querySelector(arg0); }
                    case "querySelectorAll" -> { return new ArrayList<>(document.querySelectorAll(arg0)); }
                    case "getElementsByTagName" -> { return new ArrayList<>(document.getElementsByTagName(arg0)); }
                    case "getElementsByClassName" -> { return new ArrayList<>(document.getElementsByClassName(arg0)); }
                    case "createElement" -> { return new Dom.Node(arg0); }
                    case "createTextNode" -> {
                        Dom.Node t = new Dom.Node("#text");
                        t.text = arg0;
                        return t;
                    }
                    case "createDocumentFragment" -> { return new Dom.Node("#document-fragment"); }
                }
            }
            if (target instanceof Dom.Node node) {
                String arg0 = args.isEmpty() ? "" : stringValue(args.get(0));
                return switch (method) {
                    case "appendChild" -> {
                        if (!args.isEmpty() && args.get(0) instanceof Dom.Node child) yield node.appendChild(child);
                        yield null;
                    }
                    case "prepend" -> {
                        for (int i = args.size() - 1; i >= 0; i--) {
                            Object a = args.get(i);
                            if (a instanceof Dom.Node child) node.prepend(child);
                            else {
                                Dom.Node t = new Dom.Node("#text");
                                t.text = stringValue(a);
                                node.prepend(t);
                            }
                        }
                        yield null;
                    }
                    case "append" -> {
                        for (Object a : args) {
                            if (a instanceof Dom.Node child) node.appendChild(child);
                            else {
                                Dom.Node t = new Dom.Node("#text");
                                t.text = stringValue(a);
                                node.appendChild(t);
                            }
                        }
                        yield null;
                    }
                    case "insertBefore" -> {
                        if (args.size() >= 2 && args.get(0) instanceof Dom.Node n) {
                            Dom.Node ref = args.get(1) instanceof Dom.Node r ? r : null;
                            yield node.insertBefore(n, ref);
                        }
                        yield null;
                    }
                    case "replaceChild" -> {
                        if (args.size() >= 2 && args.get(0) instanceof Dom.Node n && args.get(1) instanceof Dom.Node o) {
                            yield node.replaceChild(n, o);
                        }
                        yield null;
                    }
                    case "removeChild" -> {
                        if (!args.isEmpty() && args.get(0) instanceof Dom.Node c) yield node.removeChild(c);
                        yield null;
                    }
                    case "remove" -> {
                        node.remove();
                        yield null;
                    }
                    case "cloneNode" -> node.cloneNode(!args.isEmpty() && truthy(args.get(0)));
                    case "getAttribute" -> node.hasAttribute(arg0) ? node.attr(arg0) : null;
                    case "hasAttribute" -> node.hasAttribute(arg0);
                    case "setAttribute" -> {
                        String val = args.size() > 1 ? stringValue(args.get(1)) : "";
                        node.setAttribute(arg0, val);
                        yield null;
                    }
                    case "removeAttribute" -> {
                        node.removeAttribute(arg0);
                        yield null;
                    }
                    case "toggleAttribute" -> {
                        node.toggleAttribute(arg0);
                        yield node.hasAttribute(arg0);
                    }
                    case "querySelector" -> node.querySelector(arg0);
                    case "querySelectorAll" -> new ArrayList<>(node.querySelectorAll(arg0));
                    case "getElementsByTagName" -> new ArrayList<>(node.getElementsByTagName(arg0));
                    case "getElementsByClassName" -> new ArrayList<>(node.getElementsByClassName(arg0));
                    case "matches" -> node.matches(arg0);
                    case "closest" -> node.closest(arg0);
                    case "contains" -> !args.isEmpty() && args.get(0) instanceof Dom.Node other && node.contains(other);
                    case "insertAdjacentHTML" -> {
                        if (args.size() >= 2) node.insertAdjacentHTML(arg0, stringValue(args.get(1)));
                        yield null;
                    }
                    case "addEventListener" -> {
                        if (args.size() >= 2 && args.get(1) instanceof JsFunction fn) {
                            node.addEventListener(arg0, ev -> invokeFunction(fn, List.of(ev)));
                        }
                        yield null;
                    }
                    case "dispatchEvent" -> node.dispatchEvent(arg0);
                    case "click" -> node.dispatchEvent("click");
                    default -> null;
                };
            }
            if (target instanceof Dom.ClassList cl) {
                String arg0 = args.isEmpty() ? "" : stringValue(args.get(0));
                return switch (method) {
                    case "add" -> {
                        for (Object a : args) cl.add(stringValue(a));
                        yield null;
                    }
                    case "remove" -> {
                        for (Object a : args) cl.remove(stringValue(a));
                        yield null;
                    }
                    case "toggle" -> args.size() > 1 ? cl.toggle(arg0, truthy(args.get(1))) : cl.toggle(arg0);
                    case "contains" -> cl.contains(arg0);
                    case "replace" -> {
                        if (args.size() >= 2) cl.replace(arg0, stringValue(args.get(1)));
                        yield null;
                    }
                    default -> null;
                };
            }
            if (target instanceof StyleProxy(Dom.Node node)) {
                String arg0 = args.isEmpty() ? "" : stringValue(args.get(0));
                return switch (method) {
                    case "setProperty" -> {
                        node.setInlineStyle(arg0, args.size() > 1 ? stringValue(args.get(1)) : "");
                        yield null;
                    }
                    case "getPropertyValue" -> node.getInlineStyle(arg0);
                    case "removeProperty" -> {
                        String old = node.getInlineStyle(arg0);
                        node.setInlineStyle(arg0, "");
                        yield old;
                    }
                    default -> null;
                };
            }
            if ("console".equals(target)) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < args.size(); i++) {
                    if (i > 0) sb.append(' ');
                    sb.append(stringValue(args.get(i)));
                }
                if ("clear".equals(method)) logs.clear();
                else logs.add(sb.toString());
                return null;
            }
            if ("Math".equals(target)) {
                double a = args.isEmpty() ? Double.NaN : number(args.get(0));
                double b = args.size() > 1 ? number(args.get(1)) : Double.NaN;
                return switch (method) {
                    case "abs" -> Math.abs(a);
                    case "floor" -> Math.floor(a);
                    case "ceil" -> Math.ceil(a);
                    case "round" -> (double) Math.round(a);
                    case "trunc" -> a < 0 ? Math.ceil(a) : Math.floor(a);
                    case "sqrt" -> Math.sqrt(a);
                    case "pow" -> Math.pow(a, b);
                    case "sin" -> Math.sin(a);
                    case "cos" -> Math.cos(a);
                    case "tan" -> Math.tan(a);
                    case "log" -> Math.log(a);
                    case "exp" -> Math.exp(a);
                    case "sign" -> Math.signum(a);
                    case "random" -> Math.random();
                    case "min" -> {
                        double m = Double.POSITIVE_INFINITY;
                        for (Object o : args) m = Math.min(m, number(o));
                        yield m;
                    }
                    case "max" -> {
                        double m = Double.NEGATIVE_INFINITY;
                        for (Object o : args) m = Math.max(m, number(o));
                        yield m;
                    }
                    default -> Double.NaN;
                };
            }
            if ("Number".equals(target)) {
                return switch (method) {
                    case "parseInt" -> parseJsInt(args);
                    case "parseFloat" -> args.isEmpty() ? Double.NaN : number(args.get(0));
                    case "isNaN" -> !args.isEmpty() && args.get(0) instanceof Number n && Double.isNaN(n.doubleValue());
                    case "isFinite" -> !args.isEmpty() && args.get(0) instanceof Number n && Double.isFinite(n.doubleValue());
                    case "isInteger" -> !args.isEmpty() && args.get(0) instanceof Number n
                            && Double.isFinite(n.doubleValue()) && n.doubleValue() == Math.rint(n.doubleValue());
                    default -> null;
                };
            }
            if ("Array".equals(target)) {
                if ("isArray".equals(method)) return !args.isEmpty() && args.get(0) instanceof List<?>;
                if ("from".equals(method) && !args.isEmpty()) {
                    List<Object> res = new ArrayList<>();
                    for (Object item : toIterable(args.get(0), false)) res.add(item);
                    return res;
                }
            }
            if ("Object".equals(target) && !args.isEmpty() && args.get(0) instanceof Map<?, ?> m) {
                return switch (method) {
                    case "keys" -> new ArrayList<>(m.keySet());
                    case "values" -> new ArrayList<>(m.values());
                    case "entries" -> {
                        List<Object> entries = new ArrayList<>();
                        for (Map.Entry<?, ?> e : m.entrySet()) {
                            entries.add(new ArrayList<>(List.of(String.valueOf(e.getKey()), e.getValue())));
                        }
                        yield entries;
                    }
                    default -> null;
                };
            }
            if ("JSON".equals(target) && !args.isEmpty()) {
                if ("stringify".equals(method)) return jsonStringify(args.get(0));
            }
            if (target instanceof String s) {
                String arg0 = args.isEmpty() ? "" : stringValue(args.get(0));
                return switch (method) {
                    case "toLowerCase" -> s.toLowerCase(Locale.ROOT);
                    case "toUpperCase" -> s.toUpperCase(Locale.ROOT);
                    case "trim" -> s.trim();
                    case "trimStart" -> s.stripLeading();
                    case "trimEnd" -> s.stripTrailing();
                    case "includes" -> s.contains(arg0);
                    case "startsWith" -> s.startsWith(arg0);
                    case "endsWith" -> s.endsWith(arg0);
                    case "indexOf" -> (double) s.indexOf(arg0);
                    case "lastIndexOf" -> (double) s.lastIndexOf(arg0);
                    case "charAt" -> {
                        int idx = args.isEmpty() ? 0 : (int) number(args.get(0));
                        yield idx >= 0 && idx < s.length() ? String.valueOf(s.charAt(idx)) : "";
                    }
                    case "substring", "slice" -> {
                        int len = s.length();
                        int start = args.isEmpty() ? 0 : (int) number(args.get(0));
                        int end = args.size() > 1 ? (int) number(args.get(1)) : len;
                        if (method.equals("slice")) {
                            if (start < 0) start = Math.max(0, len + start);
                            if (end < 0) end = Math.max(0, len + end);
                        }
                        start = Math.max(0, Math.min(len, start));
                        end = Math.max(start, Math.min(len, end));
                        yield s.substring(start, end);
                    }
                    case "split" -> {
                        if (args.isEmpty()) yield new ArrayList<>(List.of(s));
                        if (arg0.isEmpty()) {
                            List<Object> chars = new ArrayList<>(s.length());
                            for (int i = 0; i < s.length(); i++) chars.add(String.valueOf(s.charAt(i)));
                            yield chars;
                        }
                        yield new ArrayList<Object>(List.of(s.split(Pattern.quote(arg0), -1)));
                    }
                    case "replace" -> args.size() >= 2 ? s.replaceFirst(Pattern.quote(arg0), Matcher.quoteReplacement(stringValue(args.get(1)))) : s;
                    case "replaceAll" -> args.size() >= 2 ? s.replace(arg0, stringValue(args.get(1))) : s;
                    case "repeat" -> s.repeat(Math.max(0, (int) number(args.isEmpty() ? 0 : args.get(0))));
                    case "padStart" -> {
                        int targetLen = args.isEmpty() ? 0 : (int) number(args.get(0));
                        String pad = args.size() > 1 ? stringValue(args.get(1)) : " ";
                        if (pad.isEmpty() || s.length() >= targetLen) yield s;
                        StringBuilder sb = new StringBuilder();
                        while (sb.length() + s.length() < targetLen) sb.append(pad);
                        yield sb.substring(0, targetLen - s.length()) + s;
                    }
                    default -> null;
                };
            }
            if (target instanceof List<?> rawList) {
                @SuppressWarnings("unchecked")
                List<Object> list = (List<Object>) rawList;
                return switch (method) {
                    case "push" -> {
                        list.addAll(args);
                        yield (double) list.size();
                    }
                    case "pop" -> list.isEmpty() ? null : list.remove(list.size() - 1);
                    case "shift" -> list.isEmpty() ? null : list.remove(0);
                    case "unshift" -> {
                        list.addAll(0, args);
                        yield (double) list.size();
                    }
                    case "includes" -> !args.isEmpty() && list.contains(args.get(0));
                    case "indexOf" -> (double) (args.isEmpty() ? -1 : list.indexOf(args.get(0)));
                    case "join" -> {
                        String sep = args.isEmpty() ? "," : stringValue(args.get(0));
                        StringBuilder sb = new StringBuilder();
                        for (int i = 0; i < list.size(); i++) {
                            if (i > 0) sb.append(sep);
                            sb.append(stringValue(list.get(i)));
                        }
                        yield sb.toString();
                    }
                    case "reverse" -> {
                        Collections.reverse(list);
                        yield list;
                    }
                    case "slice" -> {
                        int sz = list.size();
                        int s = args.isEmpty() ? 0 : (int) number(args.get(0));
                        int e = args.size() > 1 ? (int) number(args.get(1)) : sz;
                        if (s < 0) s = Math.max(0, sz + s);
                        if (e < 0) e = Math.max(0, sz + e);
                        s = Math.max(0, Math.min(sz, s));
                        e = Math.max(s, Math.min(sz, e));
                        yield new ArrayList<>(list.subList(s, e));
                    }
                    case "forEach" -> {
                        if (!args.isEmpty() && args.get(0) instanceof JsFunction fn) {
                            for (int i = 0; i < list.size(); i++) {
                                invokeFunction(fn, List.of(list.get(i), (double) i, list));
                            }
                        }
                        yield null;
                    }
                    case "map" -> {
                        List<Object> mapped = new ArrayList<>(list.size());
                        if (!args.isEmpty() && args.get(0) instanceof JsFunction fn) {
                            for (int i = 0; i < list.size(); i++) {
                                mapped.add(invokeFunction(fn, List.of(list.get(i), (double) i, list)));
                            }
                        }
                        yield mapped;
                    }
                    case "filter" -> {
                        List<Object> filtered = new ArrayList<>();
                        if (!args.isEmpty() && args.get(0) instanceof JsFunction fn) {
                            for (int i = 0; i < list.size(); i++) {
                                Object item = list.get(i);
                                if (truthy(invokeFunction(fn, List.of(item, (double) i, list)))) {
                                    filtered.add(item);
                                }
                            }
                        }
                        yield filtered;
                    }
                    case "find" -> {
                        if (!args.isEmpty() && args.get(0) instanceof JsFunction fn) {
                            for (int i = 0; i < list.size(); i++) {
                                Object item = list.get(i);
                                if (truthy(invokeFunction(fn, List.of(item, (double) i, list)))) yield item;
                            }
                        }
                        yield null;
                    }
                    case "some" -> {
                        if (!args.isEmpty() && args.get(0) instanceof JsFunction fn) {
                            for (int i = 0; i < list.size(); i++) {
                                if (truthy(invokeFunction(fn, List.of(list.get(i), (double) i, list)))) yield true;
                            }
                        }
                        yield false;
                    }
                    case "every" -> {
                        if (!args.isEmpty() && args.get(0) instanceof JsFunction fn) {
                            for (int i = 0; i < list.size(); i++) {
                                if (!truthy(invokeFunction(fn, List.of(list.get(i), (double) i, list)))) yield false;
                            }
                        }
                        yield true;
                    }
                    case "reduce" -> {
                        if (args.isEmpty() || !(args.get(0) instanceof JsFunction fn)) yield null;
                        int idx = 0;
                        Object acc = args.size() > 1 ? args.get(1) : (list.isEmpty() ? null : list.get(idx++));
                        for (; idx < list.size(); idx++) {
                            acc = invokeFunction(fn, List.of(acc, list.get(idx), (double) idx, list));
                        }
                        yield acc;
                    }
                    default -> null;
                };
            }
            if (target instanceof Map<?, ?> map) {
                Object fnObj = map.get(method);
                if (fnObj instanceof JsFunction fn) return invokeFunction(fn, args);
            }
            return null;
        }

        private void writeProperty(Object target, String property, Object value) {
            if (target == document && "document".equals(document.tag)) {
                if ("title".equals(property)) {
                    setTitle(stringValue(value));
                    return;
                }
            }
            if (target instanceof StyleProxy(Dom.Node node)) {
                node.setInlineStyle(property, value == null ? "" : stringValue(value));
                return;
            }
            if (target instanceof DatasetProxy(Dom.Node node)) {
                node.setDataset(property, value == null ? "" : stringValue(value));
                return;
            }
            if (target instanceof Map<?, ?> rawMap) {
                @SuppressWarnings("unchecked")
                Map<String, Object> map = (Map<String, Object>) rawMap;
                map.put(property, value);
                return;
            }
            if (!(target instanceof Dom.Node node)) return;
            String text = stringValue(value);
            switch (property) {
                case "textContent", "innerText" -> node.setTextContent(text);
                case "innerHTML" -> node.setInnerHTML(text);
                case "outerHTML" -> node.setOuterHTML(text);
                case "className" -> node.setAttribute("class", text);
                case "id" -> node.setAttribute("id", text);
                case "hidden", "disabled", "checked", "selected", "open", "required", "readOnly" -> {
                    String attr = property.toLowerCase(Locale.ROOT);
                    if (truthy(value)) node.setAttribute(attr, "");
                    else node.removeAttribute(attr);
                }
                default -> node.setAttribute(property, text);
            }
        }

        private Object readProperty(Object target, String property) {
            if (target == null || target == UNDEFINED) return null;
            if (target == document && "document".equals(document.tag)) {
                return switch (property) {
                    case "title" -> findTitle(document) == null ? "" : findTitle(document).textContent();
                    case "body" -> document.querySelector("body") != null ? document.querySelector("body") : document;
                    case "head" -> document.querySelector("head");
                    case "documentElement" -> document.querySelector("html") != null ? document.querySelector("html") : document;
                    default -> document.attr(property);
                };
            }
            if (target instanceof StyleProxy(Dom.Node node)) {
                return node.getInlineStyle(property);
            }
            if (target instanceof DatasetProxy(Dom.Node node)) {
                return node.dataset().getOrDefault(property, "");
            }
            if (target instanceof Dom.ClassList cl) {
                if ("length".equals(property)) return (double) cl.length();
                if ("value".equals(property)) return cl.toString();
                return null;
            }
            if (target instanceof String s) {
                if ("length".equals(property)) return (double) s.length();
                return null;
            }
            if (target instanceof List<?> list) {
                if ("length".equals(property)) return (double) list.size();
                return null;
            }
            if (target instanceof Map<?, ?> map) {
                return map.get(property);
            }
            if ("Math".equals(target)) {
                return switch (property) {
                    case "PI" -> Math.PI;
                    case "E" -> Math.E;
                    case "LN2" -> Math.log(2);
                    case "SQRT2" -> Math.sqrt(2);
                    default -> Double.NaN;
                };
            }
            if (target instanceof Dom.Event ev) {
                return switch (property) {
                    case "type" -> ev.type();
                    case "target" -> ev.target();
                    case "currentTarget" -> ev.currentTarget();
                    case "defaultPrevented" -> ev.defaultPrevented();
                    default -> ev.detail().get(property);
                };
            }
            if (!(target instanceof Dom.Node node)) return null;
            return switch (property) {
                case "textContent" -> node.textContent();
                case "innerText" -> node.innerText();
                case "innerHTML" -> node.innerHTML();
                case "outerHTML" -> node.outerHTML();
                case "className" -> node.attr("class");
                case "classList" -> node.classList();
                case "style" -> new StyleProxy(node);
                case "dataset" -> new DatasetProxy(node);
                case "tagName", "nodeName" -> node.tag.toUpperCase(Locale.ROOT);
                case "parentNode", "parentElement" -> node.parent;
                case "children" -> node.elementChildren();
                case "childNodes" -> new ArrayList<>(node.children);
                case "firstChild" -> node.children.isEmpty() ? null : node.children.get(0);
                case "lastChild" -> node.children.isEmpty() ? null : node.children.get(node.children.size() - 1);
                case "firstElementChild" -> node.firstElementChild();
                case "lastElementChild" -> node.lastElementChild();
                case "nextSibling" -> node.nextSibling();
                case "previousSibling" -> node.previousSibling();
                case "nextElementSibling" -> node.nextElementSibling();
                case "previousElementSibling" -> node.previousElementSibling();
                case "childElementCount" -> (double) node.elementChildren().size();
                case "hidden" -> AriaSupport.hidden(node);
                case "disabled" -> AriaSupport.disabled(node);
                case "checked" -> node.hasAttribute("checked");
                case "selected" -> node.hasAttribute("selected");
                case "open" -> node.hasAttribute("open");
                case "role" -> AriaSupport.role(node);
                default -> node.attr(property);
            };
        }

        private Object readIndexed(Object target, Object key) {
            if (target instanceof List<?> list) {
                int idx = (int) number(key);
                return idx >= 0 && idx < list.size() ? list.get(idx) : null;
            }
            if (target instanceof String s) {
                int idx = (int) number(key);
                return idx >= 0 && idx < s.length() ? String.valueOf(s.charAt(idx)) : null;
            }
            return readProperty(target, stringValue(key));
        }

        private void writeIndexed(Object target, Object key, Object value) {
            if (target instanceof List<?> rawList) {
                @SuppressWarnings("unchecked")
                List<Object> list = (List<Object>) rawList;
                int idx = (int) number(key);
                if (idx >= 0) {
                    while (list.size() <= idx) list.add(null);
                    list.set(idx, value);
                }
                return;
            }
            writeProperty(target, stringValue(key), value);
        }

        private static String jsonStringify(Object obj) {
            if (obj == null || obj == UNDEFINED) return "null";
            if (obj instanceof Boolean || obj instanceof Number) return stringValue(obj);
            if (obj instanceof String s) {
                return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
            }
            if (obj instanceof List<?> list) {
                StringBuilder sb = new StringBuilder("[");
                for (int i = 0; i < list.size(); i++) {
                    if (i > 0) sb.append(',');
                    sb.append(jsonStringify(list.get(i)));
                }
                return sb.append(']').toString();
            }
            if (obj instanceof Map<?, ?> map) {
                StringBuilder sb = new StringBuilder("{");
                boolean first = true;
                for (Map.Entry<?, ?> e : map.entrySet()) {
                    if (!first) sb.append(',');
                    first = false;
                    sb.append(jsonStringify(String.valueOf(e.getKey()))).append(':').append(jsonStringify(e.getValue()));
                }
                return sb.append('}').toString();
            }
            return jsonStringify(stringValue(obj));
        }

        private void setTitle(String value) {
            Dom.Node title = findTitle(document);
            if (title == null) {
                Dom.Node head = document.querySelector("head");
                title = (head != null ? head : document).appendChild("title");
            }
            title.setTextContent(value);
        }

        private static Dom.Node findTitle(Dom.Node node) {
            if (node == null) return null;
            if (node.tag.equals("title")) return node;
            for (Dom.Node child : node.children) {
                Dom.Node found = findTitle(child);
                if (found != null) return found;
            }
            return null;
        }

        private static boolean truthy(Object value) {
            if (value == null || value == UNDEFINED) return false;
            if (value instanceof Boolean b) return b;
            if (value instanceof Number n) return n.doubleValue() != 0.0 && !Double.isNaN(n.doubleValue());
            if (value instanceof String s) return !s.isEmpty();
            return true;
        }

        private static boolean isEqual(Object a, Object b) {
            if (a == b) return true;
            if ((a == null || a == UNDEFINED) && (b == null || b == UNDEFINED)) return true;
            if (a instanceof Number n1 && b instanceof Number n2) {
                return n1.doubleValue() == n2.doubleValue();
            }
            if (a instanceof Number || b instanceof Number) {
                double d1 = number(a), d2 = number(b);
                if (!Double.isNaN(d1) && !Double.isNaN(d2)) return d1 == d2;
            }
            return Objects.equals(stringValue(a), stringValue(b));
        }

        private static double number(Object value) {
            if (value == null) return 0;
            if (value == UNDEFINED) return Double.NaN;
            if (value instanceof Number n) return n.doubleValue();
            if (value instanceof Boolean b) return b ? 1 : 0;
            try {
                String s = String.valueOf(value).trim();
                return s.isEmpty() ? 0 : Double.parseDouble(s);
            } catch (Exception ignored) {
                return Double.NaN;
            }
        }

        private static String stringValue(Object value) {
            if (value == null) return "null";
            if (value == UNDEFINED) return "undefined";
            if (value instanceof Dom.Node node) return node.textContent();
            if (value instanceof Double number && Double.isFinite(number) && number == Math.rint(number)) {
                return Long.toString(number.longValue());
            }
            return String.valueOf(value);
        }

        private RuntimeException error(String message) {
            return new IllegalArgumentException(message + " at token " + cursor);
        }

        private boolean atEnd() {
            return cursor >= tokens.size() || peek().type == Type.END;
        }

        private Token peek() {
            return tokens.get(Math.min(cursor, tokens.size() - 1));
        }

        private Token previous() {
            return tokens.get(cursor - 1);
        }

        private Token advance() {
            if (!atEnd()) cursor++;
            return previous();
        }

        private boolean check(String value) {
            return !atEnd() && peek().text.equals(value);
        }

        private boolean checkNext(String value) {
            return cursor + 1 < tokens.size() && tokens.get(cursor + 1).text.equals(value);
        }

        private boolean checkType(Type type) {
            return !atEnd() && peek().type == type;
        }

        private boolean match(String... values) {
            for (String value : values) {
                if (check(value)) {
                    advance();
                    return true;
                }
            }
            return false;
        }

        private boolean matchType(Type type) {
            if (checkType(type)) {
                advance();
                return true;
            }
            return false;
        }

        private Token consume(String value, String message) {
            if (check(value)) return advance();
            throw error(message);
        }

        private Token consumeType(Type type, String message) {
            if (checkType(type)) return advance();
            throw error(message);
        }

        private void synchronize() {
            while (!atEnd() && !match(";", "}")) advance();
        }

        private enum Type { IDENT, NUMBER, STRING, SYMBOL, END }
        private record Token(Type type, String text) {}

        private static List<Token> tokenize(String source) {
            List<Token> result = new ArrayList<>();
            for (int i = 0; i < source.length();) {
                char c = source.charAt(i);
                if (Character.isWhitespace(c)) {
                    i++;
                    continue;
                }
                if (c == '/' && i + 1 < source.length() && source.charAt(i + 1) == '/') {
                    i += 2;
                    while (i < source.length() && source.charAt(i) != '\n') i++;
                    continue;
                }
                if (c == '/' && i + 1 < source.length() && source.charAt(i + 1) == '*') {
                    int end = source.indexOf("*/", i + 2);
                    i = end < 0 ? source.length() : end + 2;
                    continue;
                }
                if (c == '`') {
                    i = tokenizeTemplateLiteral(source, i + 1, result);
                    continue;
                }
                if (c == '\'' || c == '"') {
                    char quote = c;
                    i++;
                    StringBuilder value = new StringBuilder();
                    while (i < source.length() && source.charAt(i) != quote) {
                        char ch = source.charAt(i++);
                        if (ch == '\\' && i < source.length()) {
                            char e = source.charAt(i++);
                            value.append(switch (e) {
                                case 'n' -> '\n';
                                case 'r' -> '\r';
                                case 't' -> '\t';
                                default -> e;
                            });
                        } else {
                            value.append(ch);
                        }
                    }
                    if (i < source.length()) i++;
                    result.add(new Token(Type.STRING, value.toString()));
                    continue;
                }
                if (Character.isLetter(c) || c == '_' || c == '$') {
                    int start = i++;
                    while (i < source.length() && (Character.isLetterOrDigit(source.charAt(i))
                            || source.charAt(i) == '_' || source.charAt(i) == '$')) i++;
                    result.add(new Token(Type.IDENT, source.substring(start, i)));
                    continue;
                }
                if (Character.isDigit(c)) {
                    int start = i++;
                    if (c == '0' && i < source.length() && (source.charAt(i) == 'x' || source.charAt(i) == 'X')) {
                        i++;
                        while (i < source.length() && Character.digit(source.charAt(i), 16) >= 0) i++;
                        long hexVal = Long.parseLong(source.substring(start + 2, i), 16);
                        result.add(new Token(Type.NUMBER, Long.toString(hexVal)));
                        continue;
                    }
                    while (i < source.length() && (Character.isDigit(source.charAt(i)) || source.charAt(i) == '.' || source.charAt(i) == '_')) i++;
                    result.add(new Token(Type.NUMBER, source.substring(start, i).replace("_", "")));
                    continue;
                }
                if (c == '=' && i + 1 < source.length() && source.charAt(i + 1) == '>') {
                    result.add(new Token(Type.SYMBOL, "=>"));
                    i += 2;
                    continue;
                }
                result.add(new Token(Type.SYMBOL, String.valueOf(c)));
                i++;
            }
            result.add(new Token(Type.END, "<end>"));
            return result;
        }

        private static int tokenizeTemplateLiteral(String source, int i, List<Token> out) {
            out.add(new Token(Type.SYMBOL, "("));
            out.add(new Token(Type.STRING, ""));
            StringBuilder chunk = new StringBuilder();
            while (i < source.length() && source.charAt(i) != '`') {
                if (source.charAt(i) == '$' && i + 1 < source.length() && source.charAt(i + 1) == '{') {
                    if (!chunk.isEmpty()) {
                        out.add(new Token(Type.SYMBOL, "+"));
                        out.add(new Token(Type.STRING, chunk.toString()));
                        chunk.setLength(0);
                    }
                    i += 2;
                    int exprStart = i, depth = 1;
                    while (i < source.length() && depth > 0) {
                        if (source.charAt(i) == '{') depth++;
                        else if (source.charAt(i) == '}') depth--;
                        i++;
                    }
                    String expr = source.substring(exprStart, Math.max(exprStart, i - 1));
                    out.add(new Token(Type.SYMBOL, "+"));
                    out.add(new Token(Type.SYMBOL, "("));
                    List<Token> sub = tokenize(expr);
                    for (int k = 0; k < sub.size() - 1; k++) out.add(sub.get(k));
                    out.add(new Token(Type.SYMBOL, ")"));
                } else {
                    char ch = source.charAt(i++);
                    if (ch == '\\' && i < source.length()) {
                        char e = source.charAt(i++);
                        chunk.append(switch (e) {
                            case 'n' -> '\n';
                            case 'r' -> '\r';
                            case 't' -> '\t';
                            default -> e;
                        });
                    } else {
                        chunk.append(ch);
                    }
                }
            }
            if (!chunk.isEmpty()) {
                out.add(new Token(Type.SYMBOL, "+"));
                out.add(new Token(Type.STRING, chunk.toString()));
            }
            if (i < source.length()) i++; // skip closing `
            out.add(new Token(Type.SYMBOL, ")"));
            return i;
        }
    }
}

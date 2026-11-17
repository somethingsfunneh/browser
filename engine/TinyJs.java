package engine;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A tiny, intentionally isolated interpreter for literal DOM assignments. */
public final class TinyJs {
    private final Dom.Node document;
    public TinyJs(Dom.Node document) { this.document = document; }

    public void execute(String source) {
        if (source == null || source.isBlank()) return;
        Pattern assignment = Pattern.compile(
                "document\\s*\\.\\s*(getElementById|querySelector)\\s*\\(\\s*(['\"])(.*?)\\2\\s*\\)\\s*\\.\\s*(innerHTML|innerText|textContent)\\s*=\\s*(['\"])(.*?)\\5\\s*;",
                Pattern.DOTALL);
        Matcher matcher = assignment.matcher(source);
        while (matcher.find()) {
            String method = matcher.group(1);
            String selector = matcher.group(3);
            String property = matcher.group(4);
            String value = unescape(matcher.group(6));
            Dom.Node target;
            if ("getElementById".equals(method)) target = document.byId(selector);
            else target = document.querySelector(selector);
            if (target == null) continue;
            if ("innerHTML".equals(property)) target.setInnerHTML(value);
            else target.setTextContent(value);
        }

        Pattern titleAssignment = Pattern.compile("document\\s*\\.\\s*title\\s*=\\s*(['\"])(.*?)\\1\\s*;", Pattern.DOTALL);
        Matcher titleMatcher = titleAssignment.matcher(source);
        while (titleMatcher.find()) {
            Dom.Node title = findTitle(document);
            if (title == null) title = document.appendChild("title");
            title.setTextContent(unescape(titleMatcher.group(2)));
        }
    }

    private static Dom.Node findTitle(Dom.Node node) {
        if (node == null) return null;
        if (node.tag.equals("title")) return node;
        for (Dom.Node child : node.children) { Dom.Node found = findTitle(child); if (found != null) return found; }
        return null;
    }

    private static String unescape(String value) {
        return value == null ? "" : value.replace("\\n", "\n").replace("\\r", "\r").replace("\\t", "\t").replace("\\\"", "\"").replace("\\'", "'").replace("\\\\", "\\");
    }
}

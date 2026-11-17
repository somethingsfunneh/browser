import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Deliberately small, isolated script interpreter. It has no Java or network access. */
public final class TinyJs {
    private final Dom.Node document;
    private int steps;
    public TinyJs(Dom.Node document) { this.document = document; }
    public void execute(String source) {
        if (source == null) return;
        Matcher matcher = Pattern.compile("(?s)(?:var|let|const)\\s+([\\w$]+)\\s*=\\s*(['\"])(.*?)\\2\\s*;|document\\s*\\.\\s*(?:getElementById\\s*\\(\\s*(['\"])(.*?)\\4\\s*\\)|querySelector\\s*\\(\\s*(['\"])(.*?)\\6\\s*\\))\\s*\\.\\s*(innerHTML|innerText|textContent)\\s*=\\s*(['\"])(.*?)\\8\\s*;|document\\s*\\.\\s*title\\s*=\\s*(['\"])(.*?)\\10\\s*;", Pattern.DOTALL).matcher(source);
        while (matcher.find() && steps++ < 10_000) {
            if (matcher.group(1) != null) continue; // Variable declarations are parsed but have no global scope yet.
            String selector = matcher.group(5) != null ? matcher.group(5) : matcher.group(7);
            String property = matcher.group(8);
            if (selector != null) {
                Dom.Node target = selector.startsWith("#") ? document.byId(selector.substring(1)) : document.byId(selector);
                if (target != null) target.setTextContent(unescape(matcher.group(9)));
            } else if (matcher.group(11) != null) {
                Dom.Node title = document.byId("__document_title"); if (title != null) title.setTextContent(unescape(matcher.group(11)));
            }
        }
    }
    private static String unescape(String s) { return s.replace("\\n", "\n").replace("\\r", "\r").replace("\\t", "\t").replace("\\\"", "\"").replace("\\'", "'").replace("\\\\", "\\"); }
}

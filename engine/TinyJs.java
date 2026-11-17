import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class TinyJs {
    private final Dom.Node root;
    private final Map<String, String> variables = new HashMap<>();

    public TinyJs(Dom.Node root) {
        this.root = root;
    }

    public void execute(String source) {
        if (source == null || source.isBlank()) return;

        Pattern documentAssignment = Pattern.compile(
                "document\\s*\\.\\s*(getElementById|querySelector)\\s*\\(\\s*(['\"])(.*?)\\2\\s*\\)\\s*\\.\\s*(innerHTML|innerText|textContent)\\s*=\\s*(['\"])(.*?)(?:\\5)\\s*;",
                Pattern.DOTALL
        );
        Matcher match = documentAssignment.matcher(source);
        while (match.find()) {
            String selector = match.group(3);
            String property = match.group(4);
            String value = unescape(match.group(6));
            Dom.Node target = resolveTarget(selector);
            if (target == null) continue;
            if ("innerHTML".equals(property)) {
                target.setInnerHTML(value);
            } else {
                target.setTextContent(value);
            }
        }

        Pattern titleAssignment = Pattern.compile("document\\s*\\.\\s*title\\s*=\\s*(['\"])(.*?)(?:\\1)\\s*;", Pattern.DOTALL);
        Matcher titleMatcher = titleAssignment.matcher(source);
        while (titleMatcher.find()) {
            Dom.Node titleNode = root.byId("__document_title");
            if (titleNode == null) {
                titleNode = root.appendChild("title");
                titleNode.attributes.put("id", "__document_title");
            }
            titleNode.setTextContent(unescape(titleMatcher.group(2)));
        }

        Pattern varAssignment = Pattern.compile("(?:var|let|const)\\s+([A-Za-z_$][A-Za-z0-9_$]*)\\s*=\\s*(['\"])(.*?)(?:\\2)\\s*;", Pattern.DOTALL);
        Matcher varMatcher = varAssignment.matcher(source);
        while (varMatcher.find()) {
            variables.put(varMatcher.group(1), unescape(varMatcher.group(3)));
        }
    }

    private Dom.Node resolveTarget(String selector) {
        if (selector == null || selector.isBlank()) return null;
        if (selector.startsWith("#")) return root.byId(selector.substring(1));
        if (selector.startsWith(".")) {
            for (Dom.Node candidate : flatten(root)) {
                String classes = " " + candidate.attr("class").trim().replaceAll("\\s+", " ") + " ";
                if (classes.contains(" " + selector.substring(1).trim() + " ")) return candidate;
            }
            return null;
        }
        return root.querySelector(selector);
    }

    private List<Dom.Node> flatten(Dom.Node node) {
        List<Dom.Node> out = new ArrayList<>();
        collect(node, out);
        return out;
    }

    private void collect(Dom.Node node, List<Dom.Node> out) {
        if (node == null) return;
        if (!node.isText()) out.add(node);
        for (Dom.Node child : node.children) collect(child, out);
    }

    private static String unescape(String value) {
        if (value == null) return "";
        return value
                .replace("\\n", "\n")
                .replace("\\r", "\r")
                .replace("\\t", "\t")
                .replace("\\\"", "\"")
                .replace("\\'", "'")
                .replace("\\\\", "\\");
    }
}

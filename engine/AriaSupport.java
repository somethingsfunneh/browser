package engine;

import java.util.Locale;

/** Accessibility and ARIA helpers for the built-in DOM model. */
final class AriaSupport {
    static String role(Dom.Node node) {
        String role = node.attr("role");
        if (!role.isBlank()) return role.toLowerCase(Locale.ROOT);
        return switch (node.tag) {
            case "button" -> "button";
            case "a" -> node.attr("href").isBlank() ? "generic" : "link";
            case "img" -> "img";
            case "nav" -> "navigation";
            case "main" -> "main";
            case "header" -> "banner";
            case "footer" -> "contentinfo";
            case "aside" -> "complementary";
            case "form" -> "form";
            case "table" -> "table";
            case "textarea" -> "textbox";
            case "input" -> switch (node.attr("type").toLowerCase(Locale.ROOT)) { case "checkbox" -> "checkbox"; case "radio" -> "radio"; case "range" -> "slider"; case "search" -> "searchbox"; default -> "textbox"; };
            default -> "generic";
        };
    }
    static boolean hidden(Dom.Node node) {
        return node.hasAttribute("hidden") || node.attr("aria-hidden").equalsIgnoreCase("true")
                || node.attr("hidden").equalsIgnoreCase("until-found");
    }
    static boolean disabled(Dom.Node node) {
        return node.hasAttribute("disabled") || node.attr("aria-disabled").equalsIgnoreCase("true");
    }
}

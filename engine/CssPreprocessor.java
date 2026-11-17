package engine;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Resolves the commonly used :root CSS custom properties supported by this renderer. */
final class CssPreprocessor {
    private static final Pattern RULE = Pattern.compile("(?s)([^{}]+)\\{([^{}]*)}");
    private static final Pattern VARIABLE_NAME = Pattern.compile("--[\\w-]+");

    private CssPreprocessor() {}

    static String resolve(String css) {
        if (css == null || css.isEmpty()) return "";
        Map<String, String> variables = new LinkedHashMap<>();
        Matcher rules = RULE.matcher(css);
        while (rules.find()) {
            boolean rootRule = false;
            for (String selector : rules.group(1).split(",")) {
                String normalized = selector.trim().toLowerCase(java.util.Locale.ROOT);
                if (normalized.equals(":root") || normalized.equals("html")) rootRule = true;
            }
            if (!rootRule) continue;
            for (String declaration : rules.group(2).split(";")) {
                int colon = declaration.indexOf(':');
                if (colon < 1) continue;
                String name = declaration.substring(0, colon).trim();
                if (VARIABLE_NAME.matcher(name).matches()) {
                    variables.put(name, declaration.substring(colon + 1).trim());
                }
            }
        }
        return substitute(css, variables, 0);
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

    private static int closingParen(String source, int open) {
        int depth = 1;
        for (int i = open + 1; i < source.length(); i++) {
            if (source.charAt(i) == '(') depth++;
            else if (source.charAt(i) == ')' && --depth == 0) return i;
        }
        return -1;
    }

    private static int topLevelComma(String expression) {
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

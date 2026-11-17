package engine;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Resolves CSS custom properties and flattens supported conditional at-rules. */
final class CssPreprocessor {
    private static final Pattern VARIABLE_NAME = Pattern.compile("--[\\w-]+");

    private CssPreprocessor() {}

    static String resolve(String css) {
        return resolve(css, 1024);
    }

    static String resolve(String css, int viewportWidth) {
        if (css == null) css = "";
        String flattened = flatten(BrowserStyles.css() + "\n" + css, viewportWidth);
        Map<String, String> variables = new LinkedHashMap<>();
        Matcher rules = Pattern.compile("(?s)([^{}]+)\\{([^{}]*)}").matcher(flattened);
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
        return substitute(flattened, variables, 0);
    }

    /** Keeps ordinary style rules and expands screen/all media and supports blocks. */
    private static String flatten(String css, int viewportWidth) {
        StringBuilder output = new StringBuilder(css.length());
        int cursor = 0;
        while (cursor < css.length()) {
            int open = findNext(css, '{', cursor);
            if (open < 0) break;
            String header = css.substring(cursor, open).trim();
            int close = matchingBrace(css, open);
            if (close < 0) break;
            String body = css.substring(open + 1, close);
            if (header.startsWith("@")) {
                String atRule = header.toLowerCase(java.util.Locale.ROOT);
                if (atRule.startsWith("@media") && mediaMatches(atRule, viewportWidth)
                        || atRule.startsWith("@supports")) {
                    output.append(flatten(body, viewportWidth));
                }
            } else if (!header.isEmpty()) {
                output.append(header).append('{').append(body).append('}');
            }
            cursor = close + 1;
        }
        return stripComments(output.toString());
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

    private static boolean mediaMatches(String query, int width) {
        String media = query.replaceFirst("^@media\\s*", "").trim();
        if (media.contains("print")) return false;
        if (!media.contains("screen") && !media.contains("all")
                && !media.startsWith("(") && !media.startsWith("only ")) return false;
        Matcher min = Pattern.compile("min-width\\s*:\\s*(\\d+)\\s*px").matcher(media);
        while (min.find()) if (width < Integer.parseInt(min.group(1))) return false;
        Matcher max = Pattern.compile("max-width\\s*:\\s*(\\d+)\\s*px").matcher(media);
        while (max.find()) if (width > Integer.parseInt(max.group(1))) return false;
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

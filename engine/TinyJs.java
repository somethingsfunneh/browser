package engine;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A small, isolated JavaScript-like interpreter for page scripts.
 * Supported: string/number/boolean/null literals, variables, + - * /,
 * assignments, document.title, and getElementById/querySelector text/HTML writes.
 * It intentionally has no Java, reflection, filesystem, or network bindings.
 */
public final class TinyJs {
    private static final int MAX_STEPS = 10_000;
    private static final int MAX_SOURCE_LENGTH = 256_000;

    private final Dom.Node document;
    private final Map<String, Object> variables = new HashMap<>();
    private List<Token> tokens = List.of();
    private int cursor;
    private int steps;

    public TinyJs(Dom.Node document) { this.document = document; }

    public void execute(String source) {
        if (source == null || source.isBlank()) return;
        if (source.length() > MAX_SOURCE_LENGTH) throw new IllegalArgumentException("Script exceeds the interpreter size limit");
        tokens = tokenize(source);
        cursor = 0;
        steps = 0;
        while (!atEnd() && steps++ < MAX_STEPS) {
            int before = cursor;
            try {
                statement();
            } catch (RuntimeException error) {
                synchronize();
            }
            if (cursor == before) cursor++;
        }
    }

    private void statement() {
        if (match(";")) return;
        if (match("var", "let", "const")) {
            Token name = consumeType(Type.IDENT, "Expected a variable name");
            Object value = null;
            if (match("=")) value = expression();
            variables.put(name.text, value);
            match(";");
            return;
        }
        if (checkType(Type.IDENT) && checkNext("=")) {
            String name = advance().text;
            advance();
            variables.put(name, expression());
            match(";");
            return;
        }
        if (match("document")) {
            documentStatement();
            match(";");
            return;
        }
        expression();
        match(";");
    }

    private void documentStatement() {
        consume(".", "Expected '.' after document");
        if (match("title")) {
            consume("=", "Expected assignment to document.title");
            setTitle(stringValue(expression()));
            return;
        }
        String method = consumeType(Type.IDENT, "Expected a supported document method").text;
        if (!method.equals("getElementById") && !method.equals("querySelector")) throw error("Unsupported document method");
        consume("(", "Expected '(' after document method");
        String selector = stringValue(expression());
        consume(")", "Expected ')' after selector");
        consume(".", "Expected a DOM property");
        String property = consumeType(Type.IDENT, "Expected a DOM property name").text;
        if (!property.equals("textContent") && !property.equals("innerText") && !property.equals("innerHTML")) throw error("Unsupported DOM property");
        consume("=", "Expected assignment to DOM property");
        String value = stringValue(expression());
        Dom.Node target = method.equals("getElementById") ? document.byId(selector) : document.querySelector(selector);
        if (target != null) {
            if (property.equals("innerHTML")) target.setInnerHTML(value);
            else target.setTextContent(value);
        }
    }

    private Object expression() { return additive(); }

    private Object additive() {
        Object left = multiplicative();
        while (match("+", "-")) {
            String op = previous().text;
            Object right = multiplicative();
            if (op.equals("+") && (left instanceof String || right instanceof String)) left = stringValue(left) + stringValue(right);
            else left = number(left) + (op.equals("+") ? number(right) : -number(right));
        }
        return left;
    }

    private Object multiplicative() {
        Object left = primary();
        while (match("*", "/")) {
            String op = previous().text;
            double right = number(primary());
            left = op.equals("*") ? number(left) * right : (right == 0 ? Double.NaN : number(left) / right);
        }
        return left;
    }

    private Object primary() {
        if (match("(")) { Object value = expression(); consume(")", "Expected ')' after expression"); return value; }
        if (matchType(Type.STRING)) return previous().text;
        if (matchType(Type.NUMBER)) return Double.parseDouble(previous().text);
        if (match("true")) return Boolean.TRUE;
        if (match("false")) return Boolean.FALSE;
        if (match("null")) return null;
        if (matchType(Type.IDENT)) return variables.get(previous().text);
        throw error("Expected a literal, variable, or parenthesized expression");
    }

    private void setTitle(String value) {
        Dom.Node title = findTitle(document);
        if (title == null) title = document.appendChild("title");
        title.setTextContent(value);
    }

    private static Dom.Node findTitle(Dom.Node node) {
        if (node == null) return null;
        if (node.tag.equals("title")) return node;
        for (Dom.Node child : node.children) { Dom.Node found = findTitle(child); if (found != null) return found; }
        return null;
    }

    private static double number(Object value) {
        if (value instanceof Number n) return n.doubleValue();
        if (value instanceof Boolean b) return b ? 1 : 0;
        try { return Double.parseDouble(String.valueOf(value)); } catch (Exception ignored) { return Double.NaN; }
    }

    private static String stringValue(Object value) {
        if (value == null) return "null";
        if (value instanceof Double d && d == Math.rint(d)) return Long.toString(d.longValue());
        return String.valueOf(value);
    }

    private RuntimeException error(String message) { return new IllegalArgumentException(message + " at token " + cursor); }
    private boolean atEnd() { return cursor >= tokens.size() || peek().type == Type.END; }
    private Token peek() { return tokens.get(Math.min(cursor, tokens.size() - 1)); }
    private Token previous() { return tokens.get(cursor - 1); }
    private Token advance() { if (!atEnd()) cursor++; return previous(); }
    private boolean check(String text) { return !atEnd() && peek().text.equals(text); }
    private boolean checkNext(String text) { return cursor + 1 < tokens.size() && tokens.get(cursor + 1).text.equals(text); }
    private boolean checkType(Type type) { return !atEnd() && peek().type == type; }
    private boolean match(String... values) { for (String value : values) if (check(value)) { advance(); return true; } return false; }
    private boolean matchType(Type type) { if (checkType(type)) { advance(); return true; } return false; }
    private Token consume(String text, String message) { if (check(text)) return advance(); throw error(message); }
    private Token consumeType(Type type, String message) { if (checkType(type)) return advance(); throw error(message); }
    private void synchronize() { while (!atEnd() && !match(";")) advance(); }

    private enum Type { IDENT, NUMBER, STRING, SYMBOL, END }
    private record Token(Type type, String text) {}

    private static List<Token> tokenize(String source) {
        List<Token> result = new ArrayList<>();
        int i = 0;
        while (i < source.length()) {
            char c = source.charAt(i);
            if (Character.isWhitespace(c)) { i++; continue; }
            if (c == '/' && i + 1 < source.length() && source.charAt(i + 1) == '/') {
                i += 2; while (i < source.length() && source.charAt(i) != '\n') i++; continue;
            }
            if (c == '/' && i + 1 < source.length() && source.charAt(i + 1) == '*') {
                int end = source.indexOf("*/", i + 2); i = end < 0 ? source.length() : end + 2; continue;
            }
            if (c == '\'' || c == '"' || c == '`') {
                char quote = c; i++; StringBuilder value = new StringBuilder();
                while (i < source.length() && source.charAt(i) != quote) {
                    char ch = source.charAt(i++);
                    if (ch == '\\' && i < source.length()) {
                        char escaped = source.charAt(i++);
                        value.append(switch (escaped) { case 'n' -> '\n'; case 'r' -> '\r'; case 't' -> '\t'; default -> escaped; });
                    } else value.append(ch);
                }
                if (i < source.length()) i++;
                result.add(new Token(Type.STRING, value.toString()));
                continue;
            }
            if (Character.isLetter(c) || c == '_' || c == '$') {
                int start = i++;
                while (i < source.length() && (Character.isLetterOrDigit(source.charAt(i)) || source.charAt(i) == '_' || source.charAt(i) == '$')) i++;
                result.add(new Token(Type.IDENT, source.substring(start, i)));
                continue;
            }
            if (Character.isDigit(c)) {
                int start = i++;
                while (i < source.length() && (Character.isDigit(source.charAt(i)) || source.charAt(i) == '.')) i++;
                result.add(new Token(Type.NUMBER, source.substring(start, i)));
                continue;
            }
            if ("=+-*/().;".indexOf(c) >= 0) {
                result.add(new Token(Type.SYMBOL, String.valueOf(c)));
                i++;
                continue;
            }
            // Unsupported syntax is isolated as a token and skipped by statement recovery.
            result.add(new Token(Type.SYMBOL, String.valueOf(c)));
            i++;
        }
        result.add(new Token(Type.END, "<end>"));
        return result;
    }
}

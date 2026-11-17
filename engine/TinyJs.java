package engine;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Bounded interpreter for common inline scripts that read and mutate the document DOM. */
public final class TinyJs {
    private static final int MAX_STEPS = 20_000;
    private static final int MAX_SOURCE_LENGTH = 256_000;
    private final Dom.Node document;
    private final Map<String, Object> variables = new HashMap<>();
    private List<Token> tokens = List.of();
    private int cursor;
    private int steps;

    public TinyJs(Dom.Node document) { this.document = document; }

    public void execute(String source) {
        if (source == null || source.isBlank() || source.length() > MAX_SOURCE_LENGTH) return;
        tokens = tokenize(source);
        cursor = 0;
        steps = 0;
        while (!atEnd() && steps++ < MAX_STEPS) {
            int before = cursor;
            try { statement(); } catch (RuntimeException ignored) { synchronize(); }
            if (cursor == before) cursor++;
        }
    }

    private void statement() {
        if (match(";")) return;
        if (match("var", "let", "const")) {
            String name = consumeType(Type.IDENT, "Expected variable name").text;
            Object value = match("=") ? expression() : null;
            variables.put(name, value);
            match(";");
            return;
        }
        if (check("document") && checkNext(".")) {
            advance(); advance();
            String member = consumeType(Type.IDENT, "Expected document member").text;
            if (member.equals("title") && match("=")) setTitle(stringValue(expression()));
            else if (match(".")) {
                String method = consumeType(Type.IDENT, "Expected document method").text;
                Object target = invokeDocument(method);
                if (match(".")) applyNodeCall(target, consumeType(Type.IDENT, "Expected DOM operation").text);
                else throw error("Expected a document property assignment or DOM operation");
            } else throw error("Unsupported document statement");
            match(";");
            return;
        }
        if (checkType(Type.IDENT) && checkNext(".")) {
            Object target = variables.get(advance().text);
            advance();
            String property = consumeType(Type.IDENT, "Expected element property").text;
            if (match("=")) writeProperty(target, property, expression());
            else if (match(".")) applyNodeCall(target, consumeType(Type.IDENT, "Expected DOM method").text);
            else throw error("Expected a DOM property assignment or method call");
            match(";");
            return;
        }
        if (checkType(Type.IDENT) && checkNext("=")) {
            String name = advance().text; advance(); variables.put(name, expression()); match(";"); return;
        }
        expression();
        match(";");
    }

    private Object invokeDocument(String method) {
        consume("(", "Expected '(' after document method");
        String argument = stringValue(expression());
        consume(")", "Expected ')' after document method");
        return switch (method) {
            case "getElementById" -> document.byId(argument);
            case "querySelector" -> document.querySelector(argument);
            case "createElement" -> new Dom.Node(argument);
            default -> throw error("Unsupported document method: " + method);
        };
    }

    private void applyNodeCall(Object target, String method) {
        if (!(target instanceof Dom.Node node)) throw error("DOM operation target is not an element");
        consume("(", "Expected method arguments");
        switch (method) {
            case "appendChild" -> {
                Object child = expression(); consume(")", "Expected ')' after appendChild");
                if (child instanceof Dom.Node element) node.appendChild(element);
            }
            case "setAttribute" -> {
                String name = stringValue(expression()); consume(",", "Expected attribute value");
                String value = stringValue(expression()); consume(")", "Expected ')' after setAttribute"); node.setAttribute(name, value);
            }
            case "removeAttribute" -> { node.removeAttribute(stringValue(expression())); consume(")", "Expected ')' after removeAttribute"); }
            case "remove" -> { consume(")", "Expected ')' after remove"); node.remove(); }
            default -> throw error("Unsupported DOM method: " + method);
        }
    }

    private Object expression() { return additive(); }
    private Object additive() {
        Object left = multiplicative();
        while (match("+", "-")) {
            String op = previous().text; Object right = multiplicative();
            if (op.equals("+") && (left instanceof String || right instanceof String)) left = stringValue(left) + stringValue(right);
            else left = number(left) + (op.equals("+") ? number(right) : -number(right));
        }
        return left;
    }
    private Object multiplicative() {
        Object left = primary();
        while (match("*", "/")) {
            String op = previous().text; double right = number(primary());
            left = op.equals("*") ? number(left) * right : (right == 0 ? Double.NaN : number(left) / right);
        }
        return left;
    }
    private Object primary() {
        if (match("(")) { Object value = expression(); consume(")", "Expected ')'"); return value; }
        if (matchType(Type.STRING)) return previous().text;
        if (matchType(Type.NUMBER)) return Double.parseDouble(previous().text);
        if (match("true")) return Boolean.TRUE;
        if (match("false")) return Boolean.FALSE;
        if (match("null")) return null;
        if (match("document")) {
            consume(".", "Expected document method");
            String method = consumeType(Type.IDENT, "Expected document method").text;
            Object target = invokeDocument(method);
            if (match(".")) {
                String property = consumeType(Type.IDENT, "Expected element property").text;
                if (match("=")) { Object value = expression(); writeProperty(target, property, value); return value; }
                return readProperty(target, property);
            }
            return target;
        }
        if (matchType(Type.IDENT)) {
            String name = previous().text;
            Object target = variables.get(name);
            if (match(".")) {
                String property = consumeType(Type.IDENT, "Expected element property").text;
                if (match("=")) { Object value = expression(); writeProperty(target, property, value); return value; }
                return readProperty(target, property);
            }
            return target;
        }
        throw error("Expected a literal, variable, or DOM lookup");
    }
    private void writeProperty(Object target, String property, Object value) {
        if (!(target instanceof Dom.Node node)) return;
        String text = stringValue(value);
        switch (property) {
            case "textContent", "innerText" -> node.setTextContent(text);
            case "innerHTML" -> node.setInnerHTML(text);
            case "className" -> node.setAttribute("class", text);
            default -> node.setAttribute(property, text);
        }
    }
    private Object readProperty(Object target, String property) {
        if (!(target instanceof Dom.Node node)) return null;
        return switch (property) {
            case "textContent", "innerText" -> node.textContent();
            case "innerHTML" -> node.innerHTML();
            case "className" -> node.attr("class");
            default -> node.attr(property);
        };
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
        if (value instanceof Dom.Node node) return node.textContent();
        if (value instanceof Double number && number == Math.rint(number)) return Long.toString(number.longValue());
        return String.valueOf(value);
    }
    private RuntimeException error(String message) { return new IllegalArgumentException(message + " at token " + cursor); }
    private boolean atEnd() { return cursor >= tokens.size() || peek().type == Type.END; }
    private Token peek() { return tokens.get(Math.min(cursor, tokens.size() - 1)); }
    private Token previous() { return tokens.get(cursor - 1); }
    private Token advance() { if (!atEnd()) cursor++; return previous(); }
    private boolean check(String value) { return !atEnd() && peek().text.equals(value); }
    private boolean checkNext(String value) { return cursor + 1 < tokens.size() && tokens.get(cursor + 1).text.equals(value); }
    private boolean checkType(Type type) { return !atEnd() && peek().type == type; }
    private boolean match(String... values) { for (String value : values) if (check(value)) { advance(); return true; } return false; }
    private boolean matchType(Type type) { if (checkType(type)) { advance(); return true; } return false; }
    private Token consume(String value, String message) { if (check(value)) return advance(); throw error(message); }
    private Token consumeType(Type type, String message) { if (checkType(type)) return advance(); throw error(message); }
    private void synchronize() { while (!atEnd() && !match(";", "}")) advance(); }

    private enum Type { IDENT, NUMBER, STRING, SYMBOL, END }
    private record Token(Type type, String text) {}
    private static List<Token> tokenize(String source) {
        List<Token> result = new ArrayList<>();
        for (int i = 0; i < source.length();) {
            char c = source.charAt(i);
            if (Character.isWhitespace(c)) { i++; continue; }
            if (c == '/' && i + 1 < source.length() && source.charAt(i + 1) == '/') { i += 2; while (i < source.length() && source.charAt(i) != '\n') i++; continue; }
            if (c == '/' && i + 1 < source.length() && source.charAt(i + 1) == '*') { int end = source.indexOf("*/", i + 2); i = end < 0 ? source.length() : end + 2; continue; }
            if (c == '\'' || c == '"' || c == '`') {
                char quote = c; i++; StringBuilder value = new StringBuilder();
                while (i < source.length() && source.charAt(i) != quote) {
                    char ch = source.charAt(i++);
                    if (ch == '\\' && i < source.length()) { char e = source.charAt(i++); value.append(switch (e) { case 'n' -> '\n'; case 'r' -> '\r'; case 't' -> '\t'; default -> e; }); }
                    else value.append(ch);
                }
                if (i < source.length()) i++;
                result.add(new Token(Type.STRING, value.toString())); continue;
            }
            if (Character.isLetter(c) || c == '_' || c == '$') {
                int start = i++;
                while (i < source.length() && (Character.isLetterOrDigit(source.charAt(i)) || source.charAt(i) == '_' || source.charAt(i) == '$')) i++;
                result.add(new Token(Type.IDENT, source.substring(start, i))); continue;
            }
            if (Character.isDigit(c)) { int start = i++; while (i < source.length() && (Character.isDigit(source.charAt(i)) || source.charAt(i) == '.')) i++; result.add(new Token(Type.NUMBER, source.substring(start, i))); continue; }
            result.add(new Token(Type.SYMBOL, String.valueOf(c))); i++;
        }
        result.add(new Token(Type.END, "<end>"));
        return result;
    }
}

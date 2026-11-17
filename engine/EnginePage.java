package engine;

import javax.swing.JComponent;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Java2D document renderer for block, inline, and common semantic HTML elements. */
public final class EnginePage extends JComponent {
    public interface LinkHandler { void open(String url); }

    private static final int DEFAULT_HEIGHT = 600;
    private static final int MIN_TEXT_WIDTH = 32;
    private final ExecutorService preparer;
    private volatile RenderDocument document;
    private volatile String baseUrl = "";
    private LinkHandler linkHandler = url -> {};
    private final List<LinkHit> linkHits = new ArrayList<>();
    private volatile int contentHeight = DEFAULT_HEIGHT;
    private long pageRevision;

    private record LinkHit(Rectangle bounds, String target) {}
    private record RenderDocument(Dom.Node root, CssEngine css, String title) {}

    public EnginePage() {
        this(Executors.newFixedThreadPool(Math.max(2,
                Math.min(4, Runtime.getRuntime().availableProcessors())), task -> {
                    Thread thread = new Thread(task, "engine-page-preparer");
                    thread.setDaemon(true);
                    return thread;
                }));
    }

    EnginePage(ExecutorService preparer) {
        this.preparer = preparer;
        setOpaque(true);
        setBackground(Color.WHITE);
        addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent event) {
                for (LinkHit hit : linkHits) if (hit.bounds.contains(event.getPoint())) {
                    linkHandler.open(hit.target);
                    return;
                }
            }
        });
    }

    public void setLinkHandler(LinkHandler handler) { linkHandler = handler == null ? url -> {} : handler; }

    /** Parses a page and fetches its style/script resources away from Swing's event thread. */
    public void setPage(String html, String url) {
        baseUrl = url == null ? "" : url;
        long revision = ++pageRevision;
        // Clear the previous document as soon as navigation starts, not after network/resource
        // preparation completes. Guard the EDT update so a stale request cannot clear a newer page.
        Runnable clear = () -> {
            if (revision != pageRevision) return;
            document = null;
            linkHits.clear();
            contentHeight = DEFAULT_HEIGHT;
            revalidate();
            repaint();
        };
        if (SwingUtilities.isEventDispatchThread()) clear.run();
        else SwingUtilities.invokeLater(clear);

        SwingWorker<RenderDocument, Void> worker = new SwingWorker<>() {
            @Override protected RenderDocument doInBackground() {
                Dom.Node root = Dom.parse(html == null ? "" : html);
                ResourceLoader.Budget budget = new ResourceLoader.Budget();
                String effectiveBase = resolveBaseUrl(root, baseUrl);
                StringBuilder styles = new StringBuilder();
                collectStyles(root, styles, effectiveBase, budget);
                int viewportWidth = Math.max(320, getWidth());
                CssEngine css = new CssEngine(CssPreprocessor.resolve(styles.toString(), viewportWidth));
                TinyJs scripts = new TinyJs(root);
                for (Dom.Node script : findAll(root, "script")) {
                    String source = script.textContent();
                    if (!script.attr("src").isBlank()) {
                        try {
                            String external = ResourceLoader.fetch(budget, effectiveBase, script.attr("src"));
                            if (external != null) source = external;
                        } catch (Exception ignored) { continue; }
                    }
                    scripts.execute(source);
                }
                Dom.Node title = findFirst(root, "title");
                return new RenderDocument(root, css, title == null ? "" : title.textContent().trim());
            }
            @Override protected void done() {
                if (revision != pageRevision) return;
                try {
                    document = get();
                    linkHits.clear();
                    revalidate();
                    repaint();
                } catch (Exception ignored) { document = null; repaint(); }
            }
        };
        preparer.execute(worker);
    }

    public void dispose() { ++pageRevision; preparer.shutdownNow(); }
    public String pageTitle() { RenderDocument ready = document; return ready == null ? "" : ready.title; }
    @Override public Dimension getPreferredSize() { return new Dimension(Math.max(600, getWidth()), contentHeight); }

    @Override protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        RenderDocument ready = document;
        if (ready == null) return;
        Graphics2D g = (Graphics2D) graphics.create();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        linkHits.clear();
        Flow flow = new Flow(g, 16, 12, Math.max(100, getWidth() - 32));
        paintChildren(ready.root, ready.css, flow, null);
        contentHeight = Math.max(getHeight(), flow.y + flow.lineHeight + 20);
        g.dispose();
        revalidate();
    }

    private void paintChildren(Dom.Node parent, CssEngine css, Flow flow, String inheritedLink) {
        if (parent == null) return;
        for (Dom.Node node : parent.children) {
            if (node.isText()) { paintText(node.text, flow, inheritedLink); continue; }
            String tag = node.tag;
            if (isNonVisual(tag)) continue;
            CssEngine.Style style = css.compute(node);
            String display = style.get("display", Dom.isBlock(tag) ? "block" : "inline").toLowerCase(Locale.ROOT);
            if (display.equals("none") || style.get("visibility", "visible").equalsIgnoreCase("hidden")) continue;
            if (tag.equals("br")) { flow.newLine(); continue; }
            if (tag.equals("hr")) {
                flow.newLineIfNeeded();
                flow.g.setColor(style.color("border-color", new Color(210, 210, 210)));
                flow.g.drawLine(flow.left, flow.y + 4, flow.left + flow.width, flow.y + 4);
                flow.y += 14;
                continue;
            }
            if (tag.equals("img")) { paintImagePlaceholder(node, flow, style); continue; }
            if (tag.equals("input") || tag.equals("button") || tag.equals("textarea") || tag.equals("select")) {
                paintControl(node, flow, style);
                continue;
            }

            boolean flex = display.equals("flex") || display.equals("inline-flex");
            boolean grid = display.equals("grid") || display.equals("inline-grid");
            boolean block = display.equals("block") || display.equals("list-item") || display.equals("table") || flex || grid;
            if (block && flow.hasText) flow.newLine();
            int marginTop = style.px("margin-top", style.px("margin", block ? 4 : 0));
            int marginBottom = style.px("margin-bottom", style.px("margin", block ? 4 : 0));
            int marginLeft = style.px("margin-left", style.px("margin", 0));
            int marginRight = style.px("margin-right", style.px("margin", 0));
            int paddingTop = style.px("padding-top", style.px("padding", 0));
            int paddingBottom = style.px("padding-bottom", style.px("padding", 0));
            int paddingLeft = style.px("padding-left", style.px("padding", 0));
            int paddingRight = style.px("padding-right", style.px("padding", 0));
            flow.y += marginTop;
            int oldLeft = flow.left, oldWidth = flow.width;
            int boxX = block ? oldLeft + marginLeft : flow.x;
            int available = Math.max(MIN_TEXT_WIDTH, oldWidth - marginLeft - marginRight);
            int specifiedWidth = style.px("width", -1);
            int boxWidth = specifiedWidth >= 0 ? Math.min(available, specifiedWidth + paddingLeft + paddingRight) : available;
            int contentX = boxX + paddingLeft;
            int startY = flow.y;
            Font oldFont = flow.g.getFont();
            Color oldColor = flow.g.getColor();
            flow.g.setFont(fontFor(style, oldFont));
            flow.g.setColor(style.color("color", oldColor));
            String link = tag.equals("a") ? resolve(node.attr("href")) : inheritedLink;
            Color background = style.color("background-color", style.color("background", null));
            int declaredHeight = style.px("height", -1);
            if (background != null && background.getAlpha() > 0 && declaredHeight > 0) {
                flow.g.setColor(background);
                flow.g.fillRect(boxX, startY, boxWidth, declaredHeight + paddingTop + paddingBottom);
            }
            if (block) {
                flow.left = contentX;
                flow.width = Math.max(MIN_TEXT_WIDTH, boxWidth - paddingLeft - paddingRight);
                flow.x = flow.left;
                flow.y += paddingTop;
                if (flex || grid) paintFlexChildren(node, css, flow, link, style);
                else paintChildren(node, css, flow, link);
                if (flow.hasText) flow.newLine();
                int actualHeight = Math.max(0, flow.y - startY - paddingTop);
                int boxHeight = Math.max(actualHeight, declaredHeight);
                flow.y = Math.max(flow.y, startY + paddingTop + boxHeight + paddingBottom);
                paintBoxDecoration(flow.g, style, boxX, startY, boxWidth, boxHeight + paddingTop + paddingBottom);
                flow.left = oldLeft;
                flow.width = oldWidth;
                flow.x = oldLeft;
                flow.y += marginBottom;
            } else {
                if (background != null && background.getAlpha() > 0) {
                    flow.g.setColor(background);
                    flow.g.fillRect(flow.x, flow.y, Math.max(8, style.px("width", 0)), Math.max(flow.lineHeight, style.px("height", 0)));
                }
                paintChildren(node, css, flow, link);
                flow.x = Math.max(flow.x, flow.left);
            }
            flow.g.setFont(oldFont);
            flow.g.setColor(oldColor);
        }
    }

    /** Render flex/grid children in rows by default, or in a column when requested. */
    private void paintFlexChildren(Dom.Node parent, CssEngine css, Flow flow, String link, CssEngine.Style style) {
        String direction = style.get("flex-direction", "row");
        if (direction.contains("column")) { paintChildren(parent, css, flow, link); return; }
        int originalLeft = flow.left, originalWidth = flow.width;
        int gap = Math.max(0, style.px("column-gap", style.px("gap", 0)));
        boolean first = true;
        for (Dom.Node child : parent.children) {
            if (child.isText()) {
                if (!child.text.isBlank()) {
                    if (!first) flow.x += gap;
                    paintText(child.text, flow, link);
                    first = false;
                }
                continue;
            }
            CssEngine.Style childStyle = css.compute(child);
            String childDisplay = childStyle.get("display", Dom.isBlock(child.tag) ? "block" : "inline");
            if (childDisplay.equals("none")) continue;
            if (!first) flow.x += gap;
            Font previousFont = flow.g.getFont();
            Color previousColor = flow.g.getColor();
            flow.g.setFont(fontFor(childStyle, previousFont));
            flow.g.setColor(childStyle.color("color", previousColor));
            Color bg = childStyle.color("background-color", childStyle.color("background", null));
            if (bg != null && bg.getAlpha() > 0) {
                flow.g.setColor(bg);
                flow.g.fillRect(flow.x, flow.y, Math.max(24, childStyle.px("width", 24)), Math.max(flow.lineHeight, childStyle.px("height", 24)));
                flow.g.setColor(childStyle.color("color", previousColor));
            }
            paintChildren(child, css, flow, child.tag.equals("a") ? resolve(child.attr("href")) : link);
            flow.g.setFont(previousFont);
            flow.g.setColor(previousColor);
            first = false;
        }
        flow.left = originalLeft;
        flow.width = originalWidth;
        if (flow.hasText) flow.newLine();
        flow.x = flow.left;
    }

    private void paintText(String raw, Flow flow, String link) {
        if (raw == null || raw.isEmpty()) return;
        FontMetrics metrics = flow.g.getFontMetrics();
        int index = 0;
        int spaceWidth = Math.max(1, metrics.charWidth(' '));
        while (index < raw.length()) {
            while (index < raw.length() && Character.isWhitespace(raw.charAt(index))) index++;
            if (index >= raw.length()) break;
            int start = index;
            while (index < raw.length() && !Character.isWhitespace(raw.charAt(index))) index++;
            String word = raw.substring(start, index);
            int next = index;
            while (next < raw.length() && Character.isWhitespace(raw.charAt(next))) next++;
            boolean hasNext = next < raw.length();
            int wordWidth = metrics.stringWidth(word);
            if (flow.hasText && flow.x + wordWidth > flow.left + flow.width) flow.newLine();
            if (wordWidth > flow.width && word.length() > 1) paintLongWord(word, flow, link, metrics);
            else paintRun(word, flow, link, metrics, wordWidth);
            if (hasNext) flow.x += spaceWidth;
            index = next;
        }
    }

    private void paintLongWord(String word, Flow flow, String link, FontMetrics metrics) {
        StringBuilder part = new StringBuilder();
        for (int i = 0; i < word.length(); i++) {
            char ch = word.charAt(i);
            if (part.length() > 0 && metrics.stringWidth(part.toString() + ch) > flow.width) {
                String text = part.toString(); paintRun(text, flow, link, metrics, metrics.stringWidth(text));
                flow.newLine(); part.setLength(0);
            }
            part.append(ch);
        }
        if (part.length() > 0) paintRun(part.toString(), flow, link, metrics, metrics.stringWidth(part.toString()));
    }

    private void paintRun(String text, Flow flow, String link, FontMetrics metrics, int width) {
        int baseline = flow.y + metrics.getAscent();
        Color previous = flow.g.getColor();
        if (link != null) flow.g.setColor(new Color(26, 115, 232));
        flow.g.drawString(text, flow.x, baseline);
        if (link != null) flow.g.drawLine(flow.x, baseline + 1, flow.x + width, baseline + 1);
        if (link != null) linkHits.add(new LinkHit(new Rectangle(flow.x, flow.y, width, metrics.getHeight()), link));
        flow.g.setColor(previous);
        flow.x += width;
        flow.lineHeight = Math.max(flow.lineHeight, metrics.getHeight());
        flow.hasText = true;
    }

    private void paintControl(Dom.Node node, Flow flow, CssEngine.Style style) {
        if (flow.hasText) flow.newLine();
        int width = Math.max(48, style.px("width", parseInt(node.attr("size"), 180)));
        int height = Math.max(24, style.px("height", node.tag.equals("textarea") ? 72 : 30));
        int x = flow.left, y = flow.y;
        flow.g.setColor(style.color("background-color", Color.WHITE));
        flow.g.fillRoundRect(x, y, width, height, 4, 4);
        flow.g.setColor(style.color("border-color", new Color(140, 140, 140)));
        flow.g.drawRoundRect(x, y, width, height, 4, 4);
        String value = node.attr("value");
        if (value.isBlank()) value = node.textContent();
        if (node.attr("type").equalsIgnoreCase("checkbox") || node.attr("type").equalsIgnoreCase("radio")) {
            width = height = 16;
            flow.g.drawRect(x, y + 4, width, width);
            if (node.hasAttribute("checked")) flow.g.drawLine(x + 3, y + 12, x + 7, y + 16);
        } else {
            flow.g.setColor(style.color("color", Color.DARK_GRAY));
            flow.g.drawString(value.isBlank() ? node.attr("placeholder") : value, x + 7, y + 19);
        }
        flow.y += height + 5;
        flow.x = flow.left;
        flow.hasText = false;
    }

    private void paintImagePlaceholder(Dom.Node node, Flow flow, CssEngine.Style style) {
        int width = Math.max(32, Math.min(640, style.px("width", parseInt(node.attr("width"), 120))));
        int height = Math.max(24, Math.min(480, style.px("height", parseInt(node.attr("height"), 80))));
        flow.newLineIfNeeded();
        flow.g.setColor(new Color(245, 245, 245)); flow.g.fillRect(flow.x, flow.y, width, height);
        flow.g.setColor(new Color(180, 180, 180)); flow.g.drawRect(flow.x, flow.y, width, height);
        if (!node.attr("alt").isBlank()) { flow.g.setColor(Color.DARK_GRAY); flow.g.drawString(node.attr("alt"), flow.x + 6, flow.y + 18); }
        flow.y += height + 4; flow.x = flow.left; flow.hasText = false;
    }

    private static void paintBoxDecoration(Graphics2D g, CssEngine.Style style, int x, int y, int width, int height) {
        String border = style.get("border", "");
        if (!border.isBlank() && !border.equalsIgnoreCase("none")) {
            g.setColor(style.color("border-color", borderColor(border)));
            g.drawRect(x, y, width, Math.max(1, height));
        }
        int radius = style.px("border-radius", 0);
        if (radius > 0 && !border.isBlank()) {
            g.setColor(style.color("border-color", borderColor(border)));
            g.drawRoundRect(x, y, width, Math.max(1, height), radius * 2, radius * 2);
        }
    }
    private static Color borderColor(String border) {
        for (String part : border.trim().split("\\s+")) {
            Color color = CssEngine.parseColor(part, null);
            if (color != null) return color;
        }
        return Color.GRAY;
    }
    private static boolean isNonVisual(String tag) {
        return tag.equals("head") || tag.equals("style") || tag.equals("script") || tag.equals("title")
                || tag.equals("meta") || tag.equals("link") || tag.equals("base") || tag.equals("template");
    }
    private String resolve(String href) {
        if (href == null || href.isBlank() || href.startsWith("#") || href.regionMatches(true, 0, "javascript:", 0, 11)) return null;
        try { return baseUrl.isBlank() ? href : URI.create(baseUrl).resolve(href).toString(); }
        catch (Exception ignored) { return href; }
    }
    private static String resolveBaseUrl(Dom.Node root, String fallback) {
        Dom.Node base = findFirst(root, "base");
        if (base == null || base.attr("href").isBlank()) return fallback;
        try { return URI.create(fallback).resolve(base.attr("href")).toString(); }
        catch (Exception ignored) { return fallback; }
    }
    private static Font fontFor(CssEngine.Style style, Font inherited) {
        int size = Math.max(8, Math.min(72, style.px("font-size", inherited.getSize())));
        String weight = style.get("font-weight", "");
        int flags = "bold".equalsIgnoreCase(weight) || parseInt(weight, 0) >= 600 ? Font.BOLD : Font.PLAIN;
        if ("italic".equalsIgnoreCase(style.get("font-style", ""))) flags |= Font.ITALIC;
        String family = style.get("font-family", inherited.getFamily()).split(",")[0].replace("\"", "").replace("'", "").trim();
        return new Font(family.isBlank() ? inherited.getFamily() : family, flags, size);
    }
    private static int parseInt(String value, int fallback) { try { return Integer.parseInt(value.trim()); } catch (Exception ignored) { return fallback; } }
    private static void collectStyles(Dom.Node node, StringBuilder out, String baseUrl, ResourceLoader.Budget budget) {
        if (node == null) return;
        if (node.tag.equals("style")) out.append(node.textContent()).append('\n');
        else if (node.tag.equals("link") && node.attr("rel").toLowerCase(Locale.ROOT).contains("stylesheet")) {
            try { String css = ResourceLoader.fetch(budget, baseUrl, node.attr("href")); if (css != null) out.append(css).append('\n'); }
            catch (Exception ignored) { /* Optional stylesheet. */ }
        }
        for (Dom.Node child : node.children) collectStyles(child, out, baseUrl, budget);
    }
    private static Dom.Node findFirst(Dom.Node node, String tag) {
        if (node == null) return null;
        if (node.tag.equals(tag)) return node;
        for (Dom.Node child : node.children) { Dom.Node found = findFirst(child, tag); if (found != null) return found; }
        return null;
    }
    private static List<Dom.Node> findAll(Dom.Node node, String tag) {
        ArrayList<Dom.Node> result = new ArrayList<>(); collect(node, tag, result); return result;
    }
    private static void collect(Dom.Node node, String tag, List<Dom.Node> out) {
        if (node == null) return;
        if (node.tag.equals(tag)) out.add(node);
        for (Dom.Node child : node.children) collect(child, tag, out);
    }
    private static final class Flow {
        final Graphics2D g;
        int left, width, x, y, lineHeight = 20;
        boolean hasText;
        Flow(Graphics2D g, int left, int y, int width) { this.g = g; this.left = left; this.width = width; this.x = left; this.y = y; }
        void newLine() { y += lineHeight; x = left; lineHeight = 20; hasText = false; }
        void newLineIfNeeded() { if (hasText) newLine(); }
    }
}

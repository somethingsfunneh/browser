package engine;

import engine.WebCore.AriaSupport;
import engine.WebCore.CssEngine;
import engine.WebCore.CssPreprocessor;
import engine.WebCore.Dom;
import engine.WebCore.TinyJs;

import javax.imageio.ImageIO;
import javax.swing.JComponent;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A small, site-agnostic HTML/CSS renderer. It implements a practical subset of
 * block/inline flow, flexbox, grid, positioned boxes, responsive lengths, images
 * and form controls; it deliberately does not contain website-specific rules.
 */
public final class EnginePage extends JComponent {
    public interface LinkHandler { void open(String url); }
    public interface StatusHandler { void onStatus(String status); }

    private static final Color TEXT = new Color(32, 33, 36);
    private static final Color MUTED = new Color(95, 99, 104);
    private static final Color BLUE = new Color(26, 115, 232);
    private static final int DEFAULT_HEIGHT = 600;
    private final ExecutorService preparer;
    private final Map<String, BufferedImage> images = new ConcurrentHashMap<>();
    private final List<Hit> hits = Collections.synchronizedList(new ArrayList<>());
    private volatile Document document;
    private volatile String baseUrl = "";
    private volatile int contentHeight = DEFAULT_HEIGHT;
    private volatile Dom.Node focusedInput;
    private LinkHandler linkHandler = url -> {};
    private StatusHandler statusHandler = message -> {};
    private long revision;

    private record Document(Dom.Node root, CssEngine css, TinyJs js, String title) {}
    private record Hit(Rectangle bounds, String target, Dom.Node control) {}
    private record Box(int x, int y, int width, int height) {}

    public EnginePage() {
        this(Executors.newFixedThreadPool(Math.max(2, Math.min(4,
                Runtime.getRuntime().availableProcessors())), task -> {
            Thread thread = new Thread(task, "engine-page-preparer");
            thread.setDaemon(true);
            return thread;
        }));
    }

    public EnginePage(ExecutorService preparer) {
        this.preparer = preparer;
        setOpaque(true);
        setBackground(Color.WHITE);
        setFocusable(true);
        addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent event) {
                requestFocusInWindow();
                synchronized (hits) {
                    for (int i = hits.size() - 1; i >= 0; i--) {
                        Hit hit = hits.get(i);
                        if (!hit.bounds.contains(event.getPoint())) continue;
                        if (hit.control != null) {
                            focusedInput = hit.control;
                            if ("submit".equalsIgnoreCase(hit.control.attr("type"))
                                    || "button".equals(hit.control.tag)) submit(hit.control);
                        } else if (hit.target != null && !hit.target.isBlank()) {
                            linkHandler.open(hit.target);
                        }
                        repaint();
                        return;
                    }
                }
                focusedInput = null;
            }
            @Override public void mouseMoved(MouseEvent event) {
                synchronized (hits) {
                    for (int i = hits.size() - 1; i >= 0; i--) {
                        Hit hit = hits.get(i);
                        if (hit.bounds.contains(event.getPoint())) {
                            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
                            statusHandler.onStatus(hit.target == null ? "Interactive control" : hit.target);
                            return;
                        }
                    }
                }
                setCursor(Cursor.getDefaultCursor());
            }
        });
        addMouseMotionListener(new MouseAdapter() {
            @Override public void mouseMoved(MouseEvent event) {
                for (var listener : getMouseListeners()) {
                    if (listener instanceof MouseAdapter adapter && listener != getMouseListeners()[0]) { /* no-op */ }
                }
                synchronized (hits) {
                    for (int i = hits.size() - 1; i >= 0; i--) {
                        Hit hit = hits.get(i);
                        if (hit.bounds.contains(event.getPoint())) {
                            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
                            statusHandler.onStatus(hit.target == null ? "Interactive control" : hit.target);
                            return;
                        }
                    }
                }
                setCursor(Cursor.getDefaultCursor());
            }
        });
        addKeyListener(new KeyAdapter() {
            @Override public void keyTyped(KeyEvent event) {
                Dom.Node input = focusedInput;
                if (input == null || AriaSupport.disabled(input) || input.hasAttribute("readonly")) return;
                String value = input.attr("value");
                char ch = event.getKeyChar();
                if (ch == '\b') {
                    if (!value.isEmpty()) input.setAttribute("value", value.substring(0, value.length() - 1));
                } else if (ch == '\n' || ch == '\r') {
                    submit(input);
                } else if (ch >= 32 && ch != 127) {
                    int max = parseInt(input.attr("maxlength"), Integer.MAX_VALUE);
                    if (value.length() < max) input.setAttribute("value", value + ch);
                }
                repaint();
            }
        });
    }

    public void setLinkHandler(LinkHandler handler) { linkHandler = handler == null ? url -> {} : handler; }
    public void setStatusHandler(StatusHandler handler) { statusHandler = handler == null ? message -> {} : handler; }
    public void setPage(String html, String url) { setPage(html, url, null); }

    public void setPage(String html, String url, Runnable onReady) {
        baseUrl = url == null ? "" : url;
        long pageRevision = ++revision;
        document = null;
        focusedInput = null;
        hits.clear();
        contentHeight = DEFAULT_HEIGHT;
        revalidate(); repaint();
        preparer.execute(new SwingWorker<Document, Void>() {
            @Override protected Document doInBackground() {
                Dom.Node root = Dom.parse(html == null ? "" : html);
                EnginePage.ResourceBudget budget = new EnginePage.ResourceBudget();
                StringBuilder styles = new StringBuilder();
                collectStyles(root, styles, baseUrl, budget);
                int viewportWidth = Math.max(1, getWidth() > 0 ? getWidth() : 1024);
                int viewportHeight = Math.max(1, getHeight() > 0 ? getHeight() : 768);
                String resolvedCss = CssPreprocessor.resolve(styles.toString(), viewportWidth, viewportHeight);
                CssEngine css = new CssEngine(resolvedCss);
                TinyJs js = new TinyJs(root);
                for (Dom.Node script : findAll(root, "script")) {
                    String type = script.attr("type").toLowerCase(Locale.ROOT).trim();
                    if (!type.isEmpty() && !type.contains("javascript") && !type.equals("module")) continue;
                    String source = script.textContent();
                    if (!script.attr("src").isBlank()) {
                        try {
                            String external = ResourceLoader.fetch(budget, baseUrl, script.attr("src"));
                            if (external != null) source = external;
                        } catch (Exception ignored) { continue; }
                    }
                    js.execute(source);
                }
                for (Dom.Node image : findAll(root, "img")) {
                    String src = image.attr("src");
                    if (src.isBlank()) continue;
                    try {
                        byte[] bytes = ResourceLoader.fetchBytes(baseUrl, src, 8 * 1024 * 1024);
                        if (bytes != null) {
                            BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(bytes));
                            if (decoded != null) images.put(src.trim(), decoded);
                        }
                    } catch (Exception ignored) { }
                }
                Dom.Node title = findFirst(root, "title");
                return new Document(root, css, js, title == null ? "" : title.textContent().trim());
            }
            @Override protected void done() {
                if (pageRevision != revision) return;
                try { document = get(); }
                catch (Exception ignored) {
                    Dom.Node root = Dom.parse(html == null ? "" : html);
                    document = new Document(root, new CssEngine(CssPreprocessor.resolve("")), new TinyJs(root), "");
                }
                repaint(); revalidate();
                if (onReady != null) onReady.run();
            }
        });
    }

    public void executeScript(String source) {
        Document ready = document;
        if (ready != null && source != null) {
            ready.js.execute(source);
            ready.css.invalidateCache();
            repaint(); revalidate();
        }
    }
    public List<String> consoleLogs() { return document == null ? List.of() : document.js.logs(); }
    public String pageTitle() {
        Document ready = document;
        Dom.Node title = ready == null ? null : findFirst(ready.root, "title");
        return title == null ? (ready == null ? "" : ready.title) : title.textContent().trim();
    }
    public void disposePage() { revision++; }
    public void dispose() { revision++; preparer.shutdownNow(); }
    @Override public Dimension getPreferredSize() { return new Dimension(Math.max(600, getWidth()), Math.max(DEFAULT_HEIGHT, contentHeight)); }

    @Override protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        Document ready = document;
        if (ready == null) return;
        Graphics2D g = (Graphics2D) graphics.create();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        hits.clear();
        int width = Math.max(1, getWidth()), height = Math.max(1, getHeight());
        Dom.Node body = findFirst(ready.root, "body");
        int bodyColor = 0xffffff;
        Color background = body == null ? null : ready.css.compute(body).color("background-color", null);
        g.setColor(background == null ? Color.WHITE : background);
        g.fillRect(0, 0, width, height);
        Dom.Node root = body == null ? ready.root : body;
        int top = body == null ? 0 : length(ready.css.compute(body), "margin-top", height, width, height, 0);
        int left = body == null ? 0 : length(ready.css.compute(body), "margin-left", width, width, height, 0);
        int right = body == null ? 0 : length(ready.css.compute(body), "margin-right", width, width, height, 0);
        int contentW = Math.max(1, width - left - right);
        int contentH = layoutChildren(root, g, ready.css, left, top, contentW, height, width, height, null, 0, true);
        int minHeight = body == null ? 0 : length(ready.css.compute(body), "min-height", height, width, height, 0);
        contentHeight = Math.max(height, Math.max(contentH + top + 24, minHeight));
        g.dispose();
    }

    private int layoutChildren(Dom.Node parent, Graphics2D g, CssEngine css, int x, int y, int width,
                               int viewportHeight, int viewportWidth, int viewportH, String link, int depth, boolean paint) {
        if (depth > 100 || parent == null) return y;
        CssEngine.Style parentStyle = css.compute(parent);
        List<Dom.Node> children = parent.children;
        int cursorY = y, cursorX = x, lineHeight = 0;
        for (Dom.Node child : children) {
            if (child.isComment() || AriaSupport.hidden(child) || isNonVisual(child.tag)) continue;
            if (child.isText()) {
                Font font = fontFor(parentStyle, g.getFont());
                if (paint) g.setFont(font);
                String text = normalizeWhitespace(child.text, parentStyle.get("white-space", "normal"));
                FontMetrics fm = g.getFontMetrics(font);
                for (String word : text.split("\\s+")) {
                    if (word.isEmpty()) continue;
                    int wordWidth = fm.stringWidth(word);
                    if (cursorX > x && cursorX + wordWidth > x + width) {
                        cursorY += Math.max(lineHeight, fm.getHeight() + 4);
                        cursorX = x; lineHeight = 0;
                    }
                    if (paint) { g.setColor(parentStyle.color("color", TEXT)); g.drawString(word, cursorX, cursorY + fm.getAscent()); }
                    cursorX += wordWidth + fm.charWidth(' ');
                    lineHeight = Math.max(lineHeight, fm.getHeight() + 4);
                }
                continue;
            }
            CssEngine.Style style = css.compute(child);
            String display = style.get("display", Dom.isBlock(child.tag) ? "block" : "inline").toLowerCase(Locale.ROOT);
            if (display.equals("none") || "hidden".equalsIgnoreCase(style.get("visibility", "visible"))) continue;
            String childLink = child.tag.equals("a") ? resolve(child.attr("href")) : link;
            if (isPositioned(style)) {
                Box box = positionedBox(child, style, css, x, y, width, viewportHeight, viewportWidth, viewportH);
                paintBox(child, box, g, css, viewportWidth, viewportH, childLink, depth + 1, paint);
                continue;
            }
            boolean block = display.equals("block") || display.equals("flex") || display.equals("inline-flex")
                    || display.equals("grid") || display.equals("inline-grid") || Dom.isBlock(child.tag);
            if (!block) {
                int inlineWidth = Math.min(width, Math.max(1, length(style, "width", width, viewportWidth, viewportH, 0)));
                int inlineHeight = paintBox(child, new Box(cursorX, cursorY, inlineWidth, viewportHeight), g, css,
                        viewportWidth, viewportH, childLink, depth + 1, paint);
                cursorX += inlineWidth;
                lineHeight = Math.max(lineHeight, inlineHeight);
                if (cursorX >= x + width) { cursorX = x; cursorY += lineHeight; lineHeight = 0; }
            } else {
                if (cursorX != x) { cursorY += lineHeight; cursorX = x; lineHeight = 0; }
                int h = paintBox(child, new Box(x, cursorY, width, viewportHeight), g, css,
                        viewportWidth, viewportH, childLink, depth + 1, paint);
                cursorY += h;
            }
        }
        if (cursorX != x) cursorY += lineHeight;
        return cursorY;
    }

    private int paintBox(Dom.Node node, Box box, Graphics2D g, CssEngine css, int viewportWidth,
                         int viewportHeight, String link, int depth, boolean paint) {
        if (depth > 100 || AriaSupport.hidden(node)) return 0;
        CssEngine.Style style = css.compute(node);
        String display = style.get("display", Dom.isBlock(node.tag) ? "block" : "inline").toLowerCase(Locale.ROOT);
        if (display.equals("none")) return 0;
        int marginTop = length(style, "margin-top", box.height, viewportWidth, viewportHeight, 0);
        int marginBottom = length(style, "margin-bottom", box.height, viewportWidth, viewportHeight, 0);
        int marginLeft = length(style, "margin-left", box.width, viewportWidth, viewportHeight, 0);
        int marginRight = length(style, "margin-right", box.width, viewportWidth, viewportHeight, 0);
        int padTop = length(style, "padding-top", box.height, viewportWidth, viewportHeight, 0);
        int padBottom = length(style, "padding-bottom", box.height, viewportWidth, viewportHeight, 0);
        int padLeft = length(style, "padding-left", box.width, viewportWidth, viewportHeight, 0);
        int padRight = length(style, "padding-right", box.width, viewportWidth, viewportHeight, 0);
        int availableW = Math.max(1, box.width - marginLeft - marginRight);
        int specifiedW = length(style, "width", availableW, viewportWidth, viewportHeight, -1);
        int outerW = specifiedW > 0 ? Math.min(availableW, specifiedW) : availableW;
        if (style.get("box-sizing", "content-box").equals("content-box") && specifiedW > 0) outerW += padLeft + padRight;
        int minW = length(style, "min-width", availableW, viewportWidth, viewportHeight, -1);
        int maxW = length(style, "max-width", availableW, viewportWidth, viewportHeight, -1);
        if (minW > 0) outerW = Math.max(outerW, minW);
        if (maxW > 0) outerW = Math.min(outerW, maxW);
        outerW = Math.max(1, Math.min(availableW, outerW));
        int boxX = box.x + marginLeft;
        if ((style.isAuto("margin-left") || style.isAuto("margin-right")) && outerW < availableW) boxX += (availableW - outerW) / 2;
        int position = style.get("position", "static").toLowerCase(Locale.ROOT).equals("relative") ? 1 : 0;
        boxX += position == 1 ? length(style, "left", viewportWidth, viewportWidth, viewportHeight, 0) - length(style, "right", viewportWidth, viewportWidth, viewportHeight, 0) : 0;
        int boxY = box.y + marginTop + (position == 1 ? length(style, "top", viewportHeight, viewportWidth, viewportHeight, 0) - length(style, "bottom", viewportHeight, viewportWidth, viewportHeight, 0) : 0);
        int contentX = boxX + padLeft, contentY = boxY + padTop;
        int contentW = Math.max(1, outerW - padLeft - padRight);
        int declaredH = length(style, "height", viewportHeight, viewportWidth, viewportHeight, -1);
        if (declaredH < 0) declaredH = length(style, "min-height", viewportHeight, viewportWidth, viewportHeight, 0);
        int childBottom;
        if (node.tag.equals("img")) childBottom = paintImage(node, boxX, boxY, outerW, declaredH, style, g, paint, link);
        else if (node.tag.equals("input") || node.tag.equals("button") || node.tag.equals("textarea") || node.tag.equals("select")) {
            childBottom = paintControl(node, boxX, boxY, outerW, declaredH, style, g, paint);
        } else if (node.tag.equals("svg")) {
            childBottom = Math.max(20, declaredH > 0 ? declaredH : 24);
        } else if (display.equals("flex") || display.equals("inline-flex")) {
            childBottom = layoutFlex(node, g, css, contentX, contentY, contentW, viewportHeight, viewportWidth, link, depth + 1, paint);
        } else if (display.equals("grid") || display.equals("inline-grid")) {
            childBottom = layoutGrid(node, g, css, contentX, contentY, contentW, viewportHeight, viewportWidth, link, depth + 1, paint);
        } else {
            childBottom = layoutChildren(node, g, css, contentX, contentY, contentW, viewportHeight,
                    viewportWidth, viewportHeight, link, depth + 1, paint);
        }
        int naturalH = Math.max(0, childBottom - contentY);
        int contentHeight = Math.max(naturalH, declaredH > 0 ? declaredH : 0);
        int totalHeight = marginTop + padTop + contentHeight + padBottom + marginBottom;
        int visualHeight = padTop + contentHeight + padBottom;
        if (paint && !node.tag.equals("body") && !node.tag.equals("html")) {
            Color bg = style.color("background-color", null);
            if (bg != null && bg.getAlpha() > 0) {
                // Background is painted before children on a separate pass where possible.
                g.setColor(bg); g.fillRect(boxX, boxY, outerW, Math.max(visualHeight, declaredH));
            }
            paintBorder(g, style, boxX, boxY, outerW, Math.max(visualHeight, declaredH));
        }
        return totalHeight;
    }

    private int layoutFlex(Dom.Node parent, Graphics2D g, CssEngine css, int x, int y, int width,
                           int viewportHeight, int viewportWidth, String link, int depth, boolean paint) {
        CssEngine.Style style = css.compute(parent);
        String direction = style.get("flex-direction", "row").toLowerCase(Locale.ROOT);
        boolean row = direction.startsWith("row");
        boolean wrap = style.get("flex-wrap", "nowrap").startsWith("wrap");
        String justify = style.get("justify-content", "flex-start").toLowerCase(Locale.ROOT);
        String align = style.get("align-items", "stretch").toLowerCase(Locale.ROOT);
        int gap = Math.max(0, length(style, row ? "column-gap" : "row-gap", width, viewportWidth, viewportHeight,
                length(style, "gap", width, viewportWidth, viewportHeight, 0)));
        List<Dom.Node> items = elementItems(parent, css);
        if (items.isEmpty()) return y;
        int[] sizes = new int[items.size()];
        int total = gap * Math.max(0, items.size() - 1);
        for (int i = 0; i < items.size(); i++) {
            CssEngine.Style child = css.compute(items.get(i));
            int basis = length(child, "flex-basis", row ? width : viewportHeight, viewportWidth, viewportHeight, -1);
            if (basis < 0) basis = length(child, row ? "width" : "height", row ? width : viewportHeight,
                    viewportWidth, viewportHeight, row ? Math.max(48, width / items.size()) : 28);
            sizes[i] = Math.max(1, basis);
            total += sizes[i];
        }
        int mainSize = row ? width : viewportHeight;
        int extra = mainSize - total;
        if (extra != 0) {
            double growTotal = 0;
            for (Dom.Node item : items) growTotal += Math.max(0, number(css.compute(item).get("flex-grow", "0"), 0));
            if (growTotal > 0 && extra > 0) {
                for (int i = 0; i < items.size(); i++) sizes[i] += (int) Math.round(extra * Math.max(0, number(css.compute(items.get(i)).get("flex-grow", "0"), 0)) / growTotal);
            } else if (extra < 0 && total > 0) {
                for (int i = 0; i < items.size(); i++) sizes[i] = Math.max(24, sizes[i] + (int) Math.floor(extra * (double) sizes[i] / total));
            }
        }
        int used = 0;
        for (int size : sizes) used += size;
        int free = Math.max(0, mainSize - used - gap * Math.max(0, items.size() - 1));
        int offset = switch (justify) {
            case "center" -> free / 2;
            case "flex-end", "end" -> free;
            default -> 0;
        };
        if (justify.equals("space-between") && items.size() > 1) gap += free / (items.size() - 1);
        int cursor = (row ? x : y) + offset;
        int maxCross = 0;
        for (int i = 0; i < items.size(); i++) {
            Dom.Node item = items.get(i);
            int itemMain = sizes[i];
            int cross = length(css.compute(item), row ? "height" : "width", row ? viewportHeight : width,
                    viewportWidth, viewportHeight, -1);
            int itemX = row ? cursor : x;
            int itemY = row ? y : cursor;
            int itemW = row ? itemMain : width;
            int itemH = row ? (cross > 0 ? cross : viewportHeight) : (cross > 0 ? cross : Math.max(28, itemMain));
            int childHeight = paintBox(item, new Box(itemX, itemY, itemW, itemH), g, css, viewportWidth,
                    viewportHeight, item.tag.equals("a") ? resolve(item.attr("href")) : link, depth + 1, paint);
            maxCross = Math.max(maxCross, childHeight);
            cursor += itemMain + gap;
        }
        return row ? y + maxCross : cursor;
    }

    private int layoutGrid(Dom.Node parent, Graphics2D g, CssEngine css, int x, int y, int width,
                           int viewportHeight, int viewportWidth, String link, int depth, boolean paint) {
        CssEngine.Style style = css.compute(parent);
        int gap = Math.max(0, length(style, "column-gap", width, viewportWidth, viewportHeight,
                length(style, "gap", width, viewportWidth, viewportHeight, 0)));
        List<Dom.Node> items = elementItems(parent, css);
        List<Integer> tracks = parseTracks(style.get("grid-template-columns", ""), width, viewportWidth, viewportHeight);
        if (tracks.isEmpty()) {
            int count = Math.max(1, items.size());
            tracks = new ArrayList<>();
            int cols = Math.max(1, (int) Math.floor((width + gap) / 220.0));
            for (int i = 0; i < cols; i++) tracks.add(Math.max(1, (width - gap * (cols - 1)) / cols));
        }
        int cols = Math.max(1, tracks.size());
        int rowGap = Math.max(0, length(style, "row-gap", width, viewportWidth, viewportHeight, gap));
        int cursorY = y;
        for (int start = 0; start < items.size(); start += cols) {
            int rowH = 0;
            for (int col = 0; col < cols && start + col < items.size(); col++) {
                int cellX = x;
                for (int before = 0; before < col; before++) cellX += tracks.get(before) + gap;
                int h = paintBox(items.get(start + col), new Box(cellX, cursorY, tracks.get(col), viewportHeight),
                        g, css, viewportWidth, viewportHeight, link, depth + 1, paint);
                rowH = Math.max(rowH, h);
            }
            cursorY += rowH + rowGap;
        }
        return cursorY;
    }

    private List<Integer> parseTracks(String raw, int width, int viewportWidth, int viewportHeight) {
        ArrayList<Integer> tracks = new ArrayList<>();
        if (raw == null || raw.isBlank() || raw.equals("none")) return tracks;
        String clean = raw.replaceAll("repeat\\(\\s*(\\d+)\\s*,\\s*([^)]*)\\)", "$2 $2 $2 $2 $2 $2 $2 $2");
        String[] parts = clean.trim().split("\\s+");
        double frTotal = 0; int fixed = 0;
        for (String part : parts) if (part.endsWith("fr")) frTotal += number(part.substring(0, part.length() - 2), 1);
            else fixed += lengthValue(part, width, viewportWidth, viewportHeight, 1);
        int remaining = Math.max(1, width - fixed);
        for (String part : parts) {
            if (part.endsWith("fr")) tracks.add(Math.max(1, (int) Math.round(remaining * number(part.substring(0, part.length() - 2), 1) / Math.max(1, frTotal))));
            else if (part.startsWith("minmax(")) {
                String[] ends = part.substring(7, part.length() - 1).split(",");
                tracks.add(Math.max(1, lengthValue(ends[0], width, viewportWidth, viewportHeight, 120)));
            } else tracks.add(lengthValue(part, width, viewportWidth, viewportHeight, 1));
        }
        return tracks;
    }

    private List<Dom.Node> elementItems(Dom.Node parent, CssEngine css) {
        ArrayList<Dom.Node> result = new ArrayList<>();
        for (Dom.Node child : parent.children) {
            if (child.isComment() || child.isText() && child.text.isBlank() || AriaSupport.hidden(child) || isNonVisual(child.tag)) continue;
            if (!child.isText() && !css.compute(child).get("display", "").equals("none")) result.add(child);
        }
        return result;
    }

    private Box positionedBox(Dom.Node node, CssEngine.Style style, CssEngine css, int x, int y, int width,
                              int height, int viewportWidth, int viewportHeight) {
        String position = style.get("position", "absolute");
        int containingX = position.equals("fixed") ? 0 : x;
        int containingY = position.equals("fixed") ? 0 : y;
        int containingW = position.equals("fixed") ? viewportWidth : width;
        int containingH = position.equals("fixed") ? height : height;
        int w = length(style, "width", containingW, viewportWidth, viewportHeight, Math.min(240, containingW));
        int h = length(style, "height", containingH, viewportWidth, viewportHeight, 100);
        int left = length(style, "left", containingW, viewportWidth, viewportHeight, -1);
        int right = length(style, "right", containingW, viewportWidth, viewportHeight, -1);
        int top = length(style, "top", containingH, viewportWidth, viewportHeight, -1);
        int bottom = length(style, "bottom", containingH, viewportWidth, viewportHeight, -1);
        int px = left >= 0 ? containingX + left : right >= 0 ? containingX + containingW - right - w : containingX;
        int py = top >= 0 ? containingY + top : bottom >= 0 ? containingY + containingH - bottom - h : containingY;
        return new Box(px, py, Math.max(1, Math.min(containingW, w)), Math.max(1, h));
    }

    private int paintImage(Dom.Node node, int x, int y, int availableW, int height, CssEngine.Style style,
                           Graphics2D g, boolean paint, String link) {
        BufferedImage image = images.get(node.attr("src").trim());
        if (image == null && node.attr("alt").isBlank()) return 0;
        int w = length(style, "width", availableW, getWidth(), getHeight(), image == null ? Math.min(180, availableW) : image.getWidth());
        int h = height > 0 ? height : length(style, "height", 480, getWidth(), getHeight(), image == null ? 32 : image.getHeight());
        if (w <= 0) w = Math.min(availableW, image == null ? 100 : image.getWidth());
        if (h <= 0) h = Math.max(1, image == null ? 32 : image.getHeight());
        if (image != null && style.get("height", "auto").equals("auto") && style.get("width", "auto").equals("auto")) {
            double ratio = Math.min(1.0, availableW / (double) image.getWidth());
            w = (int) Math.round(image.getWidth() * ratio); h = (int) Math.round(image.getHeight() * ratio);
        }
        if (paint) {
            if (image != null) g.drawImage(image, x, y, w, h, null);
            else { g.setColor(new Color(245,245,245)); g.fillRect(x,y,w,h); g.setColor(MUTED); g.drawString(node.attr("alt"),x+4,y+18); }
            if (link != null) hits.add(new Hit(new Rectangle(x,y,w,h),link,null));
        }
        return h;
    }

    private int paintControl(Dom.Node node, int x, int y, int availableW, int height, CssEngine.Style style,
                             Graphics2D g, boolean paint) {
        String type = node.attr("type").toLowerCase(Locale.ROOT);
        if (type.equals("hidden")) return 0;
        boolean button = node.tag.equals("button") || type.equals("button") || type.equals("submit") || type.equals("reset");
        int w = length(style, "width", availableW, getWidth(), getHeight(), button ? 96 : Math.min(300, availableW));
        int h = height > 0 ? height : length(style, "height", 48, getWidth(), getHeight(), node.tag.equals("textarea") ? 72 : 36);
        w = Math.max(24, Math.min(availableW, w)); h = Math.max(20, h);
        String label = node.attr("value");
        if (label.isBlank()) label = node.attr("placeholder");
        if (label.isBlank() && button) label = node.textContent().trim();
        if (label.isBlank() && button) label = "Submit";
        if (paint) {
            Color bg = style.color("background-color", button ? new Color(248,249,250) : Color.WHITE);
            Color border = style.color("border-color", new Color(218,220,224));
            g.setColor(bg); g.fillRoundRect(x,y,w,h,8,8);
            g.setColor(focusedInput == node ? BLUE : border); g.drawRoundRect(x,y,w,h,8,8);
            g.setFont(fontFor(style, g.getFont())); g.setColor(label.isEmpty() ? MUTED : style.color("color", TEXT));
            FontMetrics fm = g.getFontMetrics();
            g.drawString(label, x + (button ? Math.max(8,(w-fm.stringWidth(label))/2) : 10), y + (h-fm.getHeight())/2 + fm.getAscent());
            if (!AriaSupport.disabled(node)) hits.add(new Hit(new Rectangle(x,y,w,h), null, node));
        }
        return h;
    }

    private void submit(Dom.Node control) {
        Dom.Node form = control.closest("form");
        if (form == null) return;
        String action = form.attr("action").isBlank() ? baseUrl : resolve(form.attr("action"));
        StringBuilder query = new StringBuilder();
        for (Dom.Node field : form.querySelectorAll("input,select,textarea")) {
            String name = field.attr("name");
            if (name.isBlank() || AriaSupport.disabled(field) || "hidden".equalsIgnoreCase(field.attr("type"))) continue;
            if (!query.isEmpty()) query.append('&');
            query.append(URLEncoder.encode(name, StandardCharsets.UTF_8)).append('=')
                    .append(URLEncoder.encode(field.attr("value"), StandardCharsets.UTF_8));
        }
        if (!query.isEmpty()) action += (action.contains("?") ? "&" : "?") + query;
        if (!action.isBlank()) linkHandler.open(action);
    }

    private static void paintBorder(Graphics2D g, CssEngine.Style style, int x, int y, int w, int h) {
        String border = style.get("border", "");
        if (!border.isBlank() && !border.equalsIgnoreCase("none")) {
            Color color = style.color("border-color", new Color(218,220,224));
            int radius = style.px("border-radius", 0);
            g.setColor(color); g.setStroke(new BasicStroke(Math.max(1, style.px("border-width",1))));
            if (radius > 0) g.drawRoundRect(x,y,w,h,radius*2,radius*2); else g.drawRect(x,y,w,h);
        } else {
            for (String side : List.of("top","right","bottom","left")) {
                String raw = style.get("border-" + side, "");
                if (raw.isBlank() || raw.equals("none")) continue;
                g.setColor(style.color("border-" + side + "-color", new Color(218,220,224)));
                int xx = side.equals("right") ? x+w : side.equals("left") ? x : x;
                int yy = side.equals("bottom") ? y+h : side.equals("top") ? y : y;
                if (side.equals("top") || side.equals("bottom")) g.drawLine(x, yy, x+w, yy);
                else g.drawLine(xx, y, xx, y+h);
            }
        }
    }

    private int length(CssEngine.Style style, String property, int reference, int viewportWidth, int viewportHeight, int fallback) {
        String value = style.get(property, "").trim().toLowerCase(Locale.ROOT);
        if (value.endsWith("vw")) return (int)Math.round(number(value.substring(0,value.length()-2),0)*viewportWidth/100.0);
        if (value.endsWith("vh")) return (int)Math.round(number(value.substring(0,value.length()-2),0)*viewportHeight/100.0);
        if (value.equals("100%")) return reference;
        return style.px(property, fallback, reference);
    }
    private int lengthValue(String value, int reference, int viewportWidth, int viewportHeight, int fallback) {
        value = value.trim().toLowerCase(Locale.ROOT);
        if (value.endsWith("fr")) return fallback;
        if (value.endsWith("vw")) return (int)Math.round(number(value.substring(0,value.length()-2),0)*viewportWidth/100.0);
        if (value.endsWith("vh")) return (int)Math.round(number(value.substring(0,value.length()-2),0)*viewportHeight/100.0);
        return new CssEngine.Style(Map.of("font-size","16px","x",value)).px("x",fallback,reference);
    }
    private static double number(String value, double fallback) { try { return Double.parseDouble(value.trim()); } catch (Exception ignored) { return fallback; } }
    private static int parseInt(String value, int fallback) { try { return Integer.parseInt(value.replaceAll("[^0-9-]", "").trim()); } catch (Exception ignored) { return fallback; } }
    private static Font fontFor(CssEngine.Style style, Font inherited) {
        int size = Math.max(8, Math.min(72, style.px("font-size", inherited.getSize())));
        String weight = style.get("font-weight", "");
        int flags = weight.equalsIgnoreCase("bold") || parseInt(weight,0) >= 600 ? Font.BOLD : Font.PLAIN;
        String family = style.get("font-family", inherited.getFamily()).split(",")[0].replace("\"","").replace("'","").trim();
        if (family.equalsIgnoreCase("sans-serif") || family.equalsIgnoreCase("system-ui") || family.equalsIgnoreCase("Arial")) family = "SansSerif";
        if (family.equalsIgnoreCase("serif")) family = "Serif";
        if (family.equalsIgnoreCase("monospace")) family = "Monospaced";
        return new Font(family.isBlank() ? inherited.getFamily() : family, flags, size);
    }
    private static String normalizeWhitespace(String text, String mode) {
        if (text == null) return "";
        return mode != null && mode.contains("pre") ? text.replace('\n',' ') : text.replaceAll("\\s+", " ").trim();
    }
    private static boolean isPositioned(CssEngine.Style style) {
        String position = style.get("position", "static").toLowerCase(Locale.ROOT);
        return position.equals("absolute") || position.equals("fixed");
    }
    private String resolve(String url) {
        try { return baseUrl.isBlank() ? url : URI.create(baseUrl).resolve(url).toString(); }
        catch (Exception ignored) { return url; }
    }
    private static boolean isNonVisual(String tag) {
        return List.of("head","style","script","title","meta","link","base","template","noscript","defs","clippath","lineargradient","filter","source","track").contains(tag);
    }
    private static void collectStyles(Dom.Node node, StringBuilder out, String base, ResourceBudget budget) {
        if (node == null) return;
        if (node.tag.equals("style")) out.append(node.textContent()).append('\n');
        else if (node.tag.equals("link") && node.attr("rel").toLowerCase(Locale.ROOT).contains("stylesheet")) {
            try { String css = ResourceLoader.fetch(budget, base, node.attr("href")); if (css != null) out.append(css).append('\n'); }
            catch (Exception ignored) { }
        }
        for (Dom.Node child : node.children) collectStyles(child, out, base, budget);
    }
    private static Dom.Node findFirst(Dom.Node node, String tag) {
        if (node == null) return null;
        if (node.tag.equals(tag)) return node;
        for (Dom.Node child : node.children) { Dom.Node found = findFirst(child, tag); if (found != null) return found; }
        return null;
    }
    private static List<Dom.Node> findAll(Dom.Node node, String tag) {
        ArrayList<Dom.Node> found = new ArrayList<>();
        if (node == null) return found;
        if (node.tag.equals(tag)) found.add(node);
        for (Dom.Node child : node.children) found.addAll(findAll(child,tag));
        return found;
    }
    private static final class ResourceBudget {
        int count;
        synchronized void startResource() throws java.io.IOException { if (++count > 32) throw new java.io.IOException("Page resource limit reached"); }
    }
}

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
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Composite;
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
import java.awt.Stroke;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.AffineTransform;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLConnection;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Java2D document renderer supporting generic HTML/CSS layouts, flexbox, tables, 
 * and interactive controls without hardcoded site workarounds.
 */
public final class EnginePage extends JComponent {
    public interface LinkHandler { void open(String url); }
    public interface StatusHandler { void onStatus(String status); }

    private static final int DEFAULT_HEIGHT = 600;
    private static final int MIN_TEXT_WIDTH = 32;
    private static final Color CHROME_TEXT = new Color(32, 33, 36);
    private static final Color CHROME_MUTED = new Color(95, 99, 104);
    private static final Color CHROME_BLUE = new Color(26, 115, 232);

    private final ExecutorService preparer;
    private final ImageLoader imageLoader = new ImageLoader();
    private volatile RenderDocument document;
    private volatile String baseUrl = "";
    private LinkHandler linkHandler = url -> {};
    private StatusHandler statusHandler = msg -> {};
    private final List<LinkHit> linkHits = Collections.synchronizedList(new ArrayList<>());
    private final List<ActionHit> actionHits = Collections.synchronizedList(new ArrayList<>());
    private final List<NodeHit> nodeHits = Collections.synchronizedList(new ArrayList<>());
    private volatile Dom.Node focusedInput;
    private volatile int contentHeight = DEFAULT_HEIGHT;
    private long pageRevision;

    private record LinkHit(Rectangle bounds, String target, Dom.Node node) {}
    private record ActionHit(Rectangle bounds, Runnable action, String description) {}
    private record NodeHit(Rectangle bounds, Dom.Node node) {}
    private record RenderDocument(
            Dom.Node root,
            CssEngine css,
            TinyJs runtime,
            String rawStyles,
            String title,
            String effectiveBase
    ) {}

    public EnginePage() {
        this(Executors.newFixedThreadPool(Math.max(2,
                Math.min(4, Runtime.getRuntime().availableProcessors())), task -> {
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

        MouseAdapter mouseAdapter = new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                requestFocusInWindow();
                Point pt = event.getPoint();
                synchronized (actionHits) {
                    for (int i = actionHits.size() - 1; i >= 0; i--) {
                        ActionHit hit = actionHits.get(i);
                        if (hit.bounds.contains(pt)) {
                            hit.action.run();
                            return;
                        }
                    }
                }
                if (focusedInput != null) {
                    focusedInput = null;
                    repaint();
                }
                synchronized (linkHits) {
                    for (int i = linkHits.size() - 1; i >= 0; i--) {
                        LinkHit hit = linkHits.get(i);
                        if (hit.bounds.contains(pt)) {
                            boolean proceed = true;
                            if (hit.node != null) {
                                proceed = fireDomEvent(hit.node, "click");
                            }
                            if (proceed && hit.target != null && !hit.target.isBlank()) {
                                linkHandler.open(hit.target);
                            }
                            return;
                        }
                    }
                }
                synchronized (nodeHits) {
                    for (int i = nodeHits.size() - 1; i >= 0; i--) {
                        NodeHit hit = nodeHits.get(i);
                        if (hit.bounds.contains(pt)) {
                            fireDomEvent(hit.node, "click");
                            return;
                        }
                    }
                }
            }

            @Override
            public void mouseMoved(MouseEvent event) {
                Point pt = event.getPoint();
                synchronized (linkHits) {
                    for (int i = linkHits.size() - 1; i >= 0; i--) {
                        LinkHit hit = linkHits.get(i);
                        if (hit.bounds.contains(pt)) {
                            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
                            statusHandler.onStatus(hit.target);
                            return;
                        }
                    }
                }
                synchronized (actionHits) {
                    for (int i = actionHits.size() - 1; i >= 0; i--) {
                        ActionHit hit = actionHits.get(i);
                        if (hit.bounds.contains(pt)) {
                            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
                            statusHandler.onStatus(hit.description);
                            return;
                        }
                    }
                }
                synchronized (nodeHits) {
                    for (int i = nodeHits.size() - 1; i >= 0; i--) {
                        NodeHit hit = nodeHits.get(i);
                        if (hit.bounds.contains(pt)) {
                            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
                            String label = AriaSupport.accessibleName(hit.node);
                            statusHandler.onStatus(label.isBlank() ? "Interactive element" : label);
                            return;
                        }
                    }
                }
                setCursor(Cursor.getDefaultCursor());
            }
        };
        addMouseListener(mouseAdapter);
        addMouseMotionListener(mouseAdapter);

        addKeyListener(new KeyAdapter() {
            @Override
            public void keyTyped(KeyEvent e) {
                Dom.Node input = focusedInput;
                if (input == null || AriaSupport.disabled(input) || input.hasAttribute("readonly")) return;
                char ch = e.getKeyChar();
                boolean isTextarea = input.tag.equals("textarea");
                boolean isSingleLineSearch = isTextarea && ("q".equalsIgnoreCase(input.attr("name"))
                        || "search".equalsIgnoreCase(input.attr("type"))
                        || "combobox".equalsIgnoreCase(input.attr("role"))
                        || parseInt(input.attr("rows"), 2) <= 1);
                String current = isTextarea && !input.hasAttribute("value") ? input.textContent() : input.attr("value");
                if (ch == '\b') {
                    if (!current.isEmpty()) {
                        String updated = current.substring(0, current.length() - 1);
                        input.setAttribute("value", updated);
                        if (isTextarea) input.setTextContent(updated);
                        fireDomEvent(input, "input");
                        repaint();
                    }
                } else if (ch == '\n' || ch == '\r') {
                    if (isTextarea && !isSingleLineSearch) {
                        String updated = current + "\n";
                        input.setAttribute("value", updated);
                        input.setTextContent(updated);
                        fireDomEvent(input, "input");
                        repaint();
                    } else {
                        fireDomEvent(input, "change");
                        submitEnclosingForm(input);
                    }
                } else if (ch >= 32 && ch != 127) {
                    int maxLen = parseInt(input.attr("maxlength"), -1);
                    if (maxLen < 0 || current.length() < maxLen) {
                        String updated = current + ch;
                        input.setAttribute("value", updated);
                        if (isTextarea) input.setTextContent(updated);
                        fireDomEvent(input, "input");
                        repaint();
                    }
                }
            }
        });
    }

    public void setLinkHandler(LinkHandler handler) {
        linkHandler = handler == null ? url -> {} : handler;
    }

    public void setStatusHandler(StatusHandler handler) {
        statusHandler = handler == null ? msg -> {} : handler;
    }

    public void setPage(String html, String url) {
        setPage(html, url, null);
    }

    public void setPage(String html, String url, Runnable onReady) {
        baseUrl = url == null ? "" : url;
        long revision = ++pageRevision;

        Runnable clear = () -> {
            if (revision != pageRevision) return;
            document = null;
            focusedInput = null;
            linkHits.clear();
            actionHits.clear();
            nodeHits.clear();
            contentHeight = DEFAULT_HEIGHT;
            revalidate();
            repaint();
        };
        if (SwingUtilities.isEventDispatchThread()) clear.run();
        else SwingUtilities.invokeLater(clear);

        SwingWorker<RenderDocument, Void> worker = new SwingWorker<>() {
            @Override
            protected RenderDocument doInBackground() {
                Dom.Node root = Dom.parse(html == null ? "" : html);
                ResourceLoader.Budget budget = new ResourceLoader.Budget();
                String effectiveBase = resolveBaseUrl(root, baseUrl);
                StringBuilder styles = new StringBuilder();
                collectStyles(root, styles, effectiveBase, budget);
                int viewportWidth = Math.max(320, getWidth() > 0 ? getWidth() : 1024);
                int viewportHeight = Math.max(240, getHeight() > 0 ? getHeight() : 768);

                TinyJs scripts = new TinyJs(root);
                for (Dom.Node script : findAll(root, "script")) {
                    String type = script.attr("type").trim().toLowerCase(Locale.ROOT);
                    if (!type.isEmpty() && !type.contains("javascript") && !type.equals("module")) continue;
                    String source = script.textContent();
                    if (!script.attr("src").isBlank()) {
                        try {
                            String external = ResourceLoader.fetch(budget, effectiveBase, script.attr("src"));
                            if (external != null) source = external;
                        } catch (Exception ignored) {
                            continue;
                        }
                    }
                    scripts.execute(source);
                }

                StringBuilder finalStyles = new StringBuilder();
                collectStyles(root, finalStyles, effectiveBase, budget);
                String rawCss = finalStyles.toString();
                CssEngine css = new CssEngine(CssPreprocessor.resolve(rawCss, viewportWidth, viewportHeight));

                ImageLoader.Budget imgBudget = new ImageLoader.Budget();
                for (Dom.Node img : findAll(root, "img")) {
                    String src = img.attr("src");
                    if (!src.isBlank()) {
                        imageLoader.load(effectiveBase, src, imgBudget);
                    }
                }

                Dom.Node title = findFirst(root, "title");
                return new RenderDocument(
                        root, css, scripts, rawCss,
                        title == null ? "" : title.textContent().trim(), effectiveBase);
            }

            @Override
            protected void done() {
                if (revision != pageRevision) return;
                try {
                    document = get();
                } catch (Throwable ex) {
                    Dom.Node fallbackRoot = Dom.parse(html == null ? "" : html);
                    CssEngine fallbackCss = new CssEngine(CssPreprocessor.resolve("", 1024, 768));
                    Dom.Node title = findFirst(fallbackRoot, "title");
                    document = new RenderDocument(
                            fallbackRoot, fallbackCss, new TinyJs(fallbackRoot), "",
                            title == null ? "" : title.textContent().trim(), baseUrl);
                }
                linkHits.clear();
                actionHits.clear();
                nodeHits.clear();
                revalidate();
                repaint();
                if (onReady != null) onReady.run();
            }
        };
        preparer.execute(worker);
    }

    public void executeScript(String jsSource) {
        RenderDocument ready = document;
        if (ready == null || jsSource == null || jsSource.isBlank()) return;
        ready.runtime.execute(jsSource);
        refreshDomState(ready);
    }

    public List<String> consoleLogs() {
        RenderDocument ready = document;
        return ready == null ? List.of() : ready.runtime.logs();
    }

    private boolean fireDomEvent(Dom.Node target, String eventType) {
        RenderDocument ready = document;
        if (ready == null || target == null) return true;
        Dom.Event event = new Dom.Event(eventType, target);
        boolean allowed = target.dispatchEvent(event);
        String inlineAttr = "on" + eventType.toLowerCase(Locale.ROOT);
        for (Dom.Node cur = target; cur != null; cur = cur.parent) {
            String inlineJs = cur.attr(inlineAttr);
            if (!inlineJs.isBlank()) {
                ready.runtime.setVariable("event", event);
                ready.runtime.setVariable("this", cur);
                ready.runtime.execute(inlineJs);
            }
        }
        refreshDomState(ready);
        return allowed && !event.defaultPrevented();
    }

    private void refreshDomState(RenderDocument ready) {
        ready.css.invalidateCache();
        Dom.Node titleNode = findFirst(ready.root, "title");
        String latestTitle = titleNode == null ? ready.title : titleNode.textContent().trim();
        if (!latestTitle.equals(ready.title)) {
            document = new RenderDocument(ready.root, ready.css, ready.runtime, ready.rawStyles, latestTitle, ready.effectiveBase);
        }
        revalidate();
        repaint();
    }

    public void disposePage() {
        ++pageRevision;
    }

    public void dispose() {
        ++pageRevision;
        preparer.shutdownNow();
    }

    public String pageTitle() {
        RenderDocument ready = document;
        if (ready == null) return "";
        Dom.Node titleNode = findFirst(ready.root, "title");
        return titleNode != null ? titleNode.textContent().trim() : ready.title;
    }

    @Override
    public Dimension getPreferredSize() {
        return new Dimension(Math.max(600, getWidth()), contentHeight);
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        RenderDocument ready = document;
        if (ready == null) return;
        Graphics2D g = (Graphics2D) graphics.create();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        linkHits.clear();
        actionHits.clear();
        nodeHits.clear();

        Dom.Node body = findFirst(ready.root, "body");
        Color pageBg = Color.WHITE;
        if (body != null) {
            Color bodyBg = ready.css.compute(body).color("background-color", null);
            if (bodyBg != null && bodyBg.getAlpha() > 0) pageBg = bodyBg;
        }
        g.setColor(pageBg);
        g.fillRect(0, 0, getWidth(), getHeight());

        int viewportPad = 20;
        Flow flow = new Flow(g, viewportPad, 16, Math.max(120, getWidth() - viewportPad * 2), false);
        Dom.Node startNode = body != null ? body : ready.root;
        paintChildren(startNode, ready.css, flow, null, null);
        flow.flushLine();

        int calculatedHeight = Math.max(getHeight(), flow.y + 32);
        if (calculatedHeight != contentHeight) {
            contentHeight = calculatedHeight;
            SwingUtilities.invokeLater(this::revalidate);
        }
        g.dispose();
    }

    private int measureNodeHeight(Dom.Node node, CssEngine css, Graphics2D g, int left, int width, String link, Dom.Node linkNode) {
        Flow dryFlow = new Flow(g, left, 0, width, true);
        dryFlow.align = css.compute(node).get("text-align", "left").toLowerCase(Locale.ROOT);
        String display = css.compute(node).get("display", Dom.isBlock(node.tag) ? "block" : "inline").toLowerCase(Locale.ROOT);
        if (display.equals("flex") || display.equals("inline-flex") || display.equals("grid") || display.equals("inline-grid")) {
            paintFlexChildren(node, css, dryFlow, link, linkNode, css.compute(node));
        } else {
            paintChildren(node, css, dryFlow, link, linkNode);
        }
        dryFlow.flushLine();
        return Math.max(0, dryFlow.y);
    }

    private void paintChildren(Dom.Node parent, CssEngine css, Flow flow, String inheritedLink, Dom.Node inheritedLinkNode) {
        if (parent == null) return;
        CssEngine.Style parentStyle = css.compute(parent);
        int listIndex = parseInt(parent.attr("start"), 1) - 1;
        for (Dom.Node node : parent.children) {
            if (node.isComment()) continue;
            if (node.isText()) {
                String ws = parentStyle.get("white-space", "normal");
                String transformed = applyTextTransform(node.text, parentStyle.get("text-transform", "none"));
                String decoration = parentStyle.get("text-decoration", parentStyle.get("text-decoration-line", "none"));
                if (ws.contains("pre")) {
                    paintPreText(transformed, flow, inheritedLink, inheritedLinkNode, decoration);
                } else {
                    paintText(transformed, flow, inheritedLink, inheritedLinkNode, decoration);
                }
                continue;
            }
            String tag = node.tag;
            if (isNonVisual(tag) || AriaSupport.hidden(node)) continue;
            if (tag.equals("dialog") && !node.hasAttribute("open")) continue;
            CssEngine.Style style = css.compute(node);
            String display = style.get("display", Dom.isBlock(tag) ? "block" : "inline").toLowerCase(Locale.ROOT);
            if (display.equals("none") || style.get("visibility", "visible").equalsIgnoreCase("hidden")) continue;
            if (style.px("height", 10) == 0 || style.px("max-height", 10) == 0 || style.px("width", 10) == 0) continue;

            if (tag.equals("br") || tag.equals("wbr")) {
                if (tag.equals("br")) flow.newLine();
                continue;
            }
            if (tag.equals("hr")) {
                flow.flushLine();
                flow.y += 8;
                if (!flow.dryRun) {
                    flow.g.setColor(style.color("border-color", style.color("color", new Color(218, 220, 224))));
                    flow.g.drawLine(flow.left, flow.y, flow.left + flow.width, flow.y);
                }
                flow.y += 10;
                continue;
            }
            if (tag.equals("img")) {
                paintImage(node, flow, style, inheritedLink, inheritedLinkNode);
                continue;
            }
            if (tag.equals("svg")) {
                paintSvg(node, css, flow, style, inheritedLink, inheritedLinkNode);
                continue;
            }
            if (tag.equals("canvas")) {
                paintCanvas(node, flow, style);
                continue;
            }
            if (tag.equals("progress") || tag.equals("meter")) {
                paintProgressOrMeter(node, flow, style);
                continue;
            }
            if (tag.equals("input") || tag.equals("button") || tag.equals("textarea") || tag.equals("select")) {
                paintControl(node, flow, style);
                continue;
            }
            if (tag.equals("table")) {
                paintTable(node, css, flow);
                continue;
            }
            if (tag.equals("details")) {
                paintDetails(node, css, flow, inheritedLink, inheritedLinkNode, style);
                continue;
            }
            if (tag.equals("q")) {
                paintText("\u201c", flow, inheritedLink, inheritedLinkNode, "none");
                paintChildren(node, css, flow, inheritedLink, inheritedLinkNode);
                paintText("\u201d", flow, inheritedLink, inheritedLinkNode, "none");
                continue;
            }

            boolean flex = display.equals("flex") || display.equals("inline-flex");
            boolean grid = display.equals("grid") || display.equals("inline-grid");
            boolean block = display.equals("block") || display.equals("list-item")
                    || display.equals("table") || flex || grid || Dom.isBlock(tag);

            Font oldFont = flow.g.getFont();
            Color oldColor = flow.g.getColor();
            Composite oldComposite = flow.g.getComposite();
            Font currentFont = fontFor(style, oldFont);
            Color currentColor = style.color("color", oldColor);
            float opacity = (float) Math.max(0.05, Math.min(1.0, style.number("opacity", 1.0)));
            if (!flow.dryRun && opacity < 0.99f) {
                flow.g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, opacity));
            }
            flow.g.setFont(currentFont);
            flow.g.setColor(currentColor);
            String link = tag.equals("a") ? resolve(node.attr("href")) : inheritedLink;
            Dom.Node linkNode = tag.equals("a") ? node : inheritedLinkNode;

            if (block) {
                flow.flushLine();

                int marginTop = style.px("margin-top", style.px("margin", 0), flow.width);
                int marginBottom = style.px("margin-bottom", style.px("margin", 0), flow.width);
                int marginLeft = style.px("margin-left", style.px("margin", 0), flow.width);
                int marginRight = style.px("margin-right", style.px("margin", 0), flow.width);
                int paddingTop = style.px("padding-top", style.px("padding", 0), flow.width);
                int paddingBottom = style.px("padding-bottom", style.px("padding", 0), flow.width);
                int paddingLeft = style.px("padding-left", style.px("padding", 0), flow.width);
                int paddingRight = style.px("padding-right", style.px("padding", 0), flow.width);

                flow.y += marginTop;
                int oldLeft = flow.left, oldWidth = flow.width;
                String oldAlign = flow.align;

                int available = Math.max(MIN_TEXT_WIDTH, oldWidth - marginLeft - marginRight);
                int specifiedWidth = style.px("width", -1, available);
                int minWidth = style.px("min-width", -1, available);
                int maxWidth = style.px("max-width", -1, available);
                int boxWidth = specifiedWidth > 0 ? Math.min(available, specifiedWidth) : available;
                if (minWidth > 0) boxWidth = Math.max(boxWidth, Math.min(available, minWidth));
                if (maxWidth > 0) boxWidth = Math.min(boxWidth, maxWidth);

                int boxX = oldLeft + marginLeft;
                if (boxWidth < available && (style.isAuto("margin-left") || style.isAuto("margin-right")
                        || "center".equalsIgnoreCase(oldAlign) || tag.equals("center"))) {
                    boxX = oldLeft + (oldWidth - boxWidth) / 2;
                }

                int contentX = boxX + paddingLeft;
                int contentWidth = Math.max(MIN_TEXT_WIDTH, boxWidth - paddingLeft - paddingRight);
                int startY = flow.y;
                int declaredHeight = style.px("height", -1, 600);
                int minHeight = style.px("min-height", -1, 600);
                if (minHeight > declaredHeight) declaredHeight = minHeight;

                Color background = style.color("background-color", null);
                boolean hasBoxVisual = !flow.dryRun && !tag.equals("body") && !tag.equals("html")
                        && ((background != null && background.getAlpha() > 0) || hasAnyBorder(style));

                if (hasBoxVisual) {
                    int measuredContentH = measureNodeHeight(node, css, flow.g, contentX, contentWidth, link, linkNode);
                    int preBoxHeight = Math.max(measuredContentH, declaredHeight) + paddingTop + paddingBottom;
                    if (background != null && background.getAlpha() > 0) {
                        flow.g.setColor(background);
                        int radius = style.px("border-top-left-radius", style.px("border-radius", 0, boxWidth), boxWidth);
                        if (radius > 0) {
                            flow.g.fillRoundRect(boxX, startY, boxWidth, preBoxHeight, radius * 2, radius * 2);
                        } else {
                            flow.g.fillRect(boxX, startY, boxWidth, preBoxHeight);
                        }
                    }
                    paintBoxDecoration(flow.g, style, boxX, startY, boxWidth, preBoxHeight);
                    flow.g.setColor(currentColor);
                    flow.g.setFont(currentFont);
                }

                flow.left = contentX;
                flow.width = contentWidth;
                flow.y += paddingTop;
                flow.align = style.get("text-align", oldAlign).toLowerCase(Locale.ROOT);

                if (!flow.dryRun && (tag.equals("li") || display.equals("list-item"))) {
                    listIndex = node.hasAttribute("value") ? parseInt(node.attr("value"), listIndex + 1) : listIndex + 1;
                    String listType = style.get("list-style-type", parent.tag.equals("ol") ? "decimal" : "disc");
                    if (!"none".equalsIgnoreCase(listType)) {
                        String bullet = formatListMarker(listType, parent.tag.equals("ol"), listIndex);
                        FontMetrics fm = flow.g.getFontMetrics();
                        flow.g.drawString(bullet, Math.max(4, flow.left - fm.stringWidth(bullet) - 4), flow.y + fm.getAscent());
                    }
                }

                if (flex || grid) {
                    paintFlexChildren(node, css, flow, link, linkNode, style);
                } else {
                    paintChildren(node, css, flow, link, linkNode);
                }
                flow.flushLine();

                int actualHeight = Math.max(0, flow.y - startY - paddingTop);
                int boxHeight = Math.max(actualHeight, declaredHeight);
                int totalBoxHeight = boxHeight + paddingTop + paddingBottom;

                if (!flow.dryRun && hasClickListener(node)) {
                    nodeHits.add(new NodeHit(new Rectangle(boxX, startY, boxWidth, totalBoxHeight), node));
                }

                flow.y = Math.max(flow.y, startY + totalBoxHeight);
                flow.left = oldLeft;
                flow.width = oldWidth;
                flow.align = oldAlign;
                flow.y += marginBottom;
            } else {
                Color bg = style.color("background-color", null);
                int padX = style.px("padding-left", style.px("padding", 0));
                if (tag.equals("a") && flow.hasItemsOnLine()) {
                    flow.advanceInline(14);
                }
                if ((bg != null && bg.getAlpha() > 0 || hasAnyBorder(style)) && !node.children.isEmpty()) {
                    String txt = visibleText(node);
                    if (!txt.isBlank() && node.children.size() == 1 && node.children.get(0).isText()) {
                        paintInlineBadge(node, txt, flow, style, bg, link, linkNode);
                        flow.g.setFont(oldFont);
                        flow.g.setColor(oldColor);
                        flow.g.setComposite(oldComposite);
                        continue;
                    }
                }
                if (padX > 0) flow.advanceInline(padX);
                paintChildren(node, css, flow, link, linkNode);
                if (padX > 0) flow.advanceInline(padX);
            }

            flow.g.setFont(oldFont);
            flow.g.setColor(oldColor);
            flow.g.setComposite(oldComposite);
        }
    }

    private static String formatListMarker(String listType, boolean ordered, int index) {
        String lower = listType.toLowerCase(Locale.ROOT);
        if (ordered || lower.equals("decimal")) return index + ". ";
        return switch (lower) {
            case "circle" -> "\u25E6 ";
            case "square" -> "\u25AA ";
            case "lower-alpha", "lower-latin" -> (char) ('a' + Math.max(0, (index - 1) % 26)) + ". ";
            case "upper-alpha", "upper-latin" -> (char) ('A' + Math.max(0, (index - 1) % 26)) + ". ";
            default -> "\u2022 ";
        };
    }

    private static String applyTextTransform(String text, String transform) {
        if (text == null || text.isEmpty() || transform == null) return text == null ? "" : text;
        return switch (transform.trim().toLowerCase(Locale.ROOT)) {
            case "uppercase" -> text.toUpperCase(Locale.ROOT);
            case "lowercase" -> text.toLowerCase(Locale.ROOT);
            case "capitalize" -> {
                StringBuilder sb = new StringBuilder(text.length());
                boolean capNext = true;
                for (int i = 0; i < text.length(); i++) {
                    char c = text.charAt(i);
                    if (Character.isWhitespace(c)) {
                        capNext = true;
                        sb.append(c);
                    } else if (capNext) {
                        sb.append(Character.toTitleCase(c));
                        capNext = false;
                    } else {
                        sb.append(c);
                    }
                }
                yield sb.toString();
            }
            default -> text;
        };
    }

    private static boolean hasClickListener(Dom.Node node) {
        return node != null && (node.hasAttribute("onclick") || "button".equals(node.attr("role")));
    }

    private void paintInlineBadge(Dom.Node node, String text, Flow flow, CssEngine.Style style, Color bg, String link, Dom.Node linkNode) {
        Font font = flow.g.getFont();
        FontMetrics fm = flow.g.getFontMetrics(font);
        int padX = Math.max(4, style.px("padding-left", style.px("padding", 4)));
        int padY = Math.max(2, style.px("padding-top", style.px("padding", 2)));
        String clean = applyTextTransform(text.trim().replaceAll("\\s+", " "), style.get("text-transform", "none"));
        int w = fm.stringWidth(clean) + padX * 2;
        int h = fm.getHeight() + padY * 2;
        Color fg = style.color("color", CHROME_TEXT);
        int radius = style.px("border-top-left-radius", style.px("border-radius", 4));

        flow.addInlineItem(w, h, fm.getAscent() + padY, (g, drawX, drawY) -> {
            if (bg != null && bg.getAlpha() > 0) {
                g.setColor(bg);
                g.fillRoundRect(drawX, drawY, w, h, radius * 2, radius * 2);
            }
            paintBoxDecoration(g, style, drawX, drawY, w, h);
            g.setFont(font);
            g.setColor(link != null ? CHROME_BLUE : fg);
            int base = drawY + padY + fm.getAscent();
            g.drawString(clean, drawX + padX, base);
            Rectangle hitBox = new Rectangle(drawX, drawY, w, h);
            if (link != null || (linkNode != null && hasClickListener(linkNode))) {
                if (link != null) g.drawLine(drawX + padX, base + 1, drawX + padX + fm.stringWidth(clean), base + 1);
                linkHits.add(new LinkHit(hitBox, link, linkNode != null ? linkNode : node));
            } else if (hasClickListener(node)) {
                nodeHits.add(new NodeHit(hitBox, node));
            }
        });
    }

    private void paintProgressOrMeter(Dom.Node node, Flow flow, CssEngine.Style style) {
        int width = Math.max(60, style.px("width", 140, flow.width));
        int height = Math.max(10, style.px("height", 14));
        double max = Math.max(0.001, parseDouble(node.attr("max"), node.tag.equals("progress") ? 1.0 : 100.0));
        double min = parseDouble(node.attr("min"), 0.0);
        double val = parseDouble(node.attr("value"), min + (max - min) * 0.5);
        double ratio = Math.max(0.0, Math.min(1.0, (val - min) / Math.max(0.001, max - min)));
        Color fill = node.tag.equals("meter") && ratio > 0.85 ? new Color(24, 128, 56) : CHROME_BLUE;

        flow.addInlineItem(width + 6, height + 4, height - 2, (g, x, y) -> {
            g.setColor(new Color(232, 234, 237));
            g.fillRoundRect(x, y + 2, width, height, height, height);
            int fillW = (int) Math.round(width * ratio);
            if (fillW > 0) {
                g.setColor(fill);
                g.fillRoundRect(x, y + 2, fillW, height, height, height);
            }
            g.setColor(new Color(210, 212, 216));
            g.drawRoundRect(x, y + 2, width, height, height, height);
        });
    }

    private void paintDetails(Dom.Node detailsNode, CssEngine css, Flow flow, String link, Dom.Node linkNode, CssEngine.Style style) {
        boolean open = detailsNode.hasAttribute("open");
        Dom.Node summary = findFirst(detailsNode, "summary");
        String summaryText = summary != null ? summary.textContent().trim() : "Details";
        if (summaryText.isEmpty()) summaryText = "Details";

        flow.newLineIfNeeded();
        Font oldFont = flow.g.getFont();
        flow.g.setFont(oldFont.deriveFont(Font.BOLD));
        FontMetrics fm = flow.g.getFontMetrics();
        String label = (open ? "\u25BC " : "\u25B6 ") + summaryText;
        int w = fm.stringWidth(label);
        int h = fm.getHeight();
        if (!flow.dryRun) {
            flow.g.setColor(style.color("color", CHROME_TEXT));
            flow.g.drawString(label, flow.left, flow.y + fm.getAscent());
            Rectangle hitRect = new Rectangle(flow.left, flow.y, w + 8, h);
            actionHits.add(new ActionHit(hitRect, () -> {
                detailsNode.toggleAttribute("open");
                fireDomEvent(detailsNode, "toggle");
                repaint();
            }, "Toggle details"));
        }
        flow.g.setFont(oldFont);
        flow.y += h + 4;

        if (open) {
            for (Dom.Node child : detailsNode.children) {
                if (child == summary) continue;
                if (child.isText()) paintText(child.text, flow, link, linkNode, "none");
                else {
                    Dom.Node wrapper = new Dom.Node("div");
                    wrapper.children.add(child);
                    paintChildren(wrapper, css, flow, link, linkNode);
                }
            }
        }
    }

    private void paintTable(Dom.Node table, CssEngine css, Flow flow) {
        flow.flushLine();
        flow.y += 6;
        Dom.Node caption = findFirst(table, "caption");
        if (caption != null) {
            Font old = flow.g.getFont();
            flow.g.setFont(old.deriveFont(Font.BOLD));
            paintChildren(caption, css, flow, null, null);
            flow.flushLine();
            flow.g.setFont(old);
            flow.y += 4;
        }

        List<Dom.Node> rows = findAll(table, "tr");
        if (rows.isEmpty()) return;

        int maxCols = 1;
        for (Dom.Node tr : rows) {
            int cols = 0;
            for (Dom.Node c : tr.children) {
                if (c.tag.equals("td") || c.tag.equals("th")) {
                    cols += Math.max(1, parseInt(c.attr("colspan"), 1));
                }
            }
            maxCols = Math.max(maxCols, cols);
        }

        int[] naturalWidths = new int[maxCols];
        FontMetrics baseFm = flow.g.getFontMetrics();
        for (Dom.Node tr : rows) {
            int col = 0;
            for (Dom.Node cell : tr.children) {
                if (!cell.tag.equals("td") && !cell.tag.equals("th")) continue;
                int span = Math.max(1, parseInt(cell.attr("colspan"), 1));
                if (col < maxCols) {
                    int w = (baseFm.stringWidth(cell.textContent().trim()) + 28) / span;
                    for (int s = 0; s < span && col + s < maxCols; s++) {
                        naturalWidths[col + s] = Math.max(naturalWidths[col + s], Math.min(360, Math.max(64, w)));
                    }
                    col += span;
                }
            }
        }

        int totalNatural = 0;
        for (int w : naturalWidths) totalNatural += w;
        int availableTableW = flow.width;
        CssEngine.Style tableStyle = css.compute(table);
        int explicitTableW = tableStyle.px("width", -1, flow.width);
        int[] colWidths = new int[maxCols];
        int actualTableWidth = 0;
        for (int i = 0; i < maxCols; i++) {
            if (explicitTableW > 0 && totalNatural > 0) {
                colWidths[i] = Math.max(40, (int) ((long) naturalWidths[i] * explicitTableW / totalNatural));
            } else if (totalNatural > availableTableW && totalNatural > 0) {
                colWidths[i] = Math.max(48, (int) ((long) naturalWidths[i] * availableTableW / totalNatural));
            } else {
                colWidths[i] = Math.max(64, naturalWidths[i]);
            }
            actualTableWidth += colWidths[i];
        }

        int tableX = flow.left;
        if ("center".equalsIgnoreCase(flow.align) || "center".equalsIgnoreCase(table.attr("align"))
                || tableStyle.isAuto("margin-left")) {
            tableX = flow.left + Math.max(0, (flow.width - actualTableWidth) / 2);
        }

        boolean drawCellBorders = !table.attr("border").isEmpty() && !table.attr("border").equals("0")
                || hasAnyBorder(tableStyle);

        for (Dom.Node tr : rows) {
            int col = 0;
            int rowMaxHeight = 28;
            for (Dom.Node cell : tr.children) {
                if (!cell.tag.equals("td") && !cell.tag.equals("th")) continue;
                if (col >= maxCols) break;
                int span = Math.max(1, parseInt(cell.attr("colspan"), 1));
                int spanW = 0;
                for (int s = 0; s < span && col + s < maxCols; s++) spanW += colWidths[col + s];
                int cw = Math.max(32, spanW - 16);
                int h = measureNodeHeight(cell, css, flow.g, 0, cw, null, null) + 12;
                rowMaxHeight = Math.max(rowMaxHeight, Math.max(28, h));
                col += span;
            }

            if (!flow.dryRun) {
                CssEngine.Style rowStyle = css.compute(tr);
                Color rowBg = rowStyle.color("background-color", null);
                if (rowBg != null && rowBg.getAlpha() > 0) {
                    flow.g.setColor(rowBg);
                    flow.g.fillRect(tableX, flow.y, actualTableWidth, rowMaxHeight);
                }

                col = 0;
                int cellX = tableX;
                for (Dom.Node cell : tr.children) {
                    if (!cell.tag.equals("td") && !cell.tag.equals("th")) continue;
                    if (col >= maxCols) break;
                    int span = Math.max(1, parseInt(cell.attr("colspan"), 1));
                    int cw = 0;
                    for (int s = 0; s < span && col + s < maxCols; s++) cw += colWidths[col + s];

                    CssEngine.Style cellStyle = css.compute(cell);
                    Color bg = cellStyle.color("background-color",
                            cell.tag.equals("th") ? new Color(241, 243, 244) : null);
                    if (bg != null && bg.getAlpha() > 0) {
                        flow.g.setColor(bg);
                        flow.g.fillRect(cellX, flow.y, cw, rowMaxHeight);
                    }
                    if (drawCellBorders || hasAnyBorder(cellStyle)) {
                        flow.g.setColor(cellStyle.color("border-color", new Color(218, 220, 224)));
                        flow.g.drawRect(cellX, flow.y, cw, rowMaxHeight);
                    }

                    Flow cellFlow = new Flow(flow.g, cellX + 8, flow.y + 6, Math.max(24, cw - 16), false);
                    cellFlow.align = cellStyle.get("text-align", cell.tag.equals("th") ? "left" : "left").toLowerCase(Locale.ROOT);
                    Font oldFont = flow.g.getFont();
                    flow.g.setFont(cell.tag.equals("th") ? fontFor(cellStyle, oldFont).deriveFont(Font.BOLD) : fontFor(cellStyle, oldFont));
                    flow.g.setColor(cellStyle.color("color", CHROME_TEXT));
                    paintChildren(cell, css, cellFlow, null, null);
                    cellFlow.flushLine();
                    flow.g.setFont(oldFont);

                    cellX += cw;
                    col += span;
                }
            }
            flow.y += rowMaxHeight;
        }
        flow.y += 6;
    }

    private void paintFlexChildren(Dom.Node parent, CssEngine css, Flow flow, String link, Dom.Node linkNode, CssEngine.Style style) {
        String direction = style.get("flex-direction", "row");
        if (direction.contains("column")) {
            paintChildren(parent, css, flow, link, linkNode);
            return;
        }
        flow.flushLine();
        String justify = style.get("justify-content", "flex-start").toLowerCase(Locale.ROOT);
        String oldAlign = flow.align;
        if (justify.contains("center") || justify.contains("around") || justify.contains("evenly")) {
            flow.align = "center";
        } else if (justify.contains("end") || justify.contains("right")) {
            flow.align = "right";
        }

        List<Dom.Node> visibleChildren = new ArrayList<>();
        for (Dom.Node c : parent.children) {
            if (!c.isComment() && !AriaSupport.hidden(c) && !isNonVisual(c.tag)) {
                if (!c.isText() || !c.text.isBlank()) visibleChildren.add(c);
            }
        }

        int gap = Math.max(12, style.px("column-gap", style.px("gap", 14)));
        if (justify.contains("between") && visibleChildren.size() >= 2) {
            gap = Math.max(24, (flow.width - 360) / Math.max(1, visibleChildren.size() - 1));
        }
        boolean first = true;
        for (Dom.Node child : visibleChildren) {
            if (child.isText()) {
                if (!child.text.isBlank()) {
                    if (!first) flow.advanceInline(gap);
                    paintText(child.text, flow, link, linkNode, "none");
                    first = false;
                }
                continue;
            }
            CssEngine.Style childStyle = css.compute(child);
            String childDisplay = childStyle.get("display", Dom.isBlock(child.tag) ? "block" : "inline");
            if (childDisplay.equals("none")) continue;
            if (!first) flow.advanceInline(gap);

            Font previousFont = flow.g.getFont();
            Color previousColor = flow.g.getColor();
            Font childFont = fontFor(childStyle, previousFont);
            Color childColor = childStyle.color("color", previousColor);
            flow.g.setFont(childFont);
            flow.g.setColor(childColor);

            String childLink = child.tag.equals("a") ? resolve(child.attr("href")) : link;
            Dom.Node childLinkNode = child.tag.equals("a") ? child : linkNode;
            if (child.tag.equals("input") || child.tag.equals("button") || child.tag.equals("select") || child.tag.equals("textarea")) {
                paintControl(child, flow, childStyle);
            } else if (child.tag.equals("progress") || child.tag.equals("meter")) {
                paintProgressOrMeter(child, flow, childStyle);
            } else if (child.tag.equals("img")) {
                paintImage(child, flow, childStyle, childLink, childLinkNode);
            } else if (child.tag.equals("svg")) {
                paintSvg(child, css, flow, childStyle, childLink, childLinkNode);
            } else if (child.tag.equals("canvas")) {
                paintCanvas(child, flow, childStyle);
            } else {
                Color bg = childStyle.color("background-color", null);
                if (bg != null && bg.getAlpha() > 0 || hasAnyBorder(childStyle)) {
                    paintInlineBadge(child, visibleText(child), flow, childStyle, bg, childLink, childLinkNode);
                } else {
                    paintInlineSubtree(child, css, flow, childLink, childLinkNode);
                }
            }

            flow.g.setFont(previousFont);
            flow.g.setColor(previousColor);
            first = false;
        }
        flow.flushLine();
        flow.align = oldAlign;
    }

    private void paintInlineSubtree(Dom.Node node, CssEngine css, Flow flow, String inheritedLink, Dom.Node inheritedLinkNode) {
        if (node == null || node.isComment() || AriaSupport.hidden(node) || isNonVisual(node.tag)) {
            return;
        }
        if (node.isText()) {
            CssEngine.Style st = node.parent != null ? css.compute(node.parent) : css.compute(node);
            String dec = st.get("text-decoration", st.get("text-decoration-line", "none"));
            paintText(applyTextTransform(node.text, st.get("text-transform", "none")), flow, inheritedLink, inheritedLinkNode, dec);
            return;
        }
        CssEngine.Style style = css.compute(node);
        if ("none".equalsIgnoreCase(style.get("display", ""))) return;
        Font oldF = flow.g.getFont();
        Color oldC = flow.g.getColor();
        flow.g.setFont(fontFor(style, oldF));
        flow.g.setColor(style.color("color", oldC));
        String link = node.tag.equals("a") ? resolve(node.attr("href")) : inheritedLink;
        Dom.Node linkNode = node.tag.equals("a") ? node : inheritedLinkNode;
        if (node.tag.equals("a") && flow.hasItemsOnLine()) {
            flow.advanceInline(14);
        }
        if (node.tag.equals("input") || node.tag.equals("button") || node.tag.equals("select") || node.tag.equals("textarea")) {
            paintControl(node, flow, style);
        } else if (node.tag.equals("img")) {
            paintImage(node, flow, style, link, linkNode);
        } else {
            for (Dom.Node c : node.children) {
                paintInlineSubtree(c, css, flow, link, linkNode);
            }
        }
        flow.g.setFont(oldF);
        flow.g.setColor(oldC);
    }

    private void paintPreText(String raw, Flow flow, String link, Dom.Node linkNode, String decoration) {
        if (raw == null || raw.isEmpty()) return;
        Font font = flow.g.getFont();
        FontMetrics metrics = flow.g.getFontMetrics(font);
        Color color = flow.g.getColor();
        String[] lines = raw.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].replace("\r", "");
            if (!line.isEmpty()) {
                int w = metrics.stringWidth(line);
                paintRun(line, flow, link, linkNode, font, metrics, color, w, decoration);
            }
            if (i < lines.length - 1) flow.newLine();
        }
    }

    private void paintText(String raw, Flow flow, String link, Dom.Node linkNode, String decoration) {
        if (raw == null || raw.isEmpty()) return;
        Font font = flow.g.getFont();
        FontMetrics metrics = flow.g.getFontMetrics(font);
        Color color = flow.g.getColor();
        int index = 0;
        int spaceWidth = Math.max(4, metrics.charWidth(' '));
        boolean leadingSpace = Character.isWhitespace(raw.charAt(0));
        if (leadingSpace && flow.hasItemsOnLine()) {
            flow.advanceInline(spaceWidth);
        }
        while (index < raw.length()) {
            while (index < raw.length() && Character.isWhitespace(raw.charAt(index))) index++;
            if (index >= raw.length()) break;
            int start = index;
            while (index < raw.length() && !Character.isWhitespace(raw.charAt(index))) index++;
            String word = raw.substring(start, index);
            int next = index;
            while (next < raw.length() && Character.isWhitespace(raw.charAt(next))) next++;
            boolean hasSpaceAfter = next > index;
            int wordWidth = metrics.stringWidth(word);
            if (flow.hasItemsOnLine() && flow.currentLineWidth() + wordWidth > flow.width) {
                flow.newLine();
            }
            if (wordWidth > flow.width && word.length() > 1) {
                paintLongWord(word, flow, link, linkNode, font, metrics, color, decoration);
            } else {
                paintRun(word, flow, link, linkNode, font, metrics, color, wordWidth, decoration);
            }
            if (hasSpaceAfter && flow.currentLineWidth() + spaceWidth < flow.width) {
                flow.advanceInline(spaceWidth);
            }
            index = next;
        }
    }

    private void paintLongWord(String word, Flow flow, String link, Dom.Node linkNode, Font font, FontMetrics metrics, Color color, String decoration) {
        StringBuilder part = new StringBuilder();
        for (int i = 0; i < word.length(); i++) {
            char ch = word.charAt(i);
            if (part.length() > 0 && metrics.stringWidth(part.toString() + ch) > flow.width) {
                String text = part.toString();
                paintRun(text, flow, link, linkNode, font, metrics, color, metrics.stringWidth(text), decoration);
                flow.newLine();
                part.setLength(0);
            }
            part.append(ch);
        }
        if (part.length() > 0) {
            String text = part.toString();
            paintRun(text, flow, link, linkNode, font, metrics, color, metrics.stringWidth(text), decoration);
        }
    }

    private void paintRun(String text, Flow flow, String link, Dom.Node linkNode, Font font, FontMetrics metrics, Color color, int width, String decoration) {
        int height = metrics.getHeight();
        int ascent = metrics.getAscent();
        String dec = decoration == null ? "none" : decoration.toLowerCase(Locale.ROOT);
        flow.addInlineItem(width, height, ascent, (g, drawX, drawY) -> {
            g.setFont(font);
            g.setColor(link != null ? CHROME_BLUE : color);
            int baseline = drawY + ascent;
            g.drawString(text, drawX, baseline);
            if (dec.contains("underline") || (link != null && !dec.contains("none"))) {
                g.drawLine(drawX, baseline + 1, drawX + width, baseline + 1);
            }
            if (dec.contains("line-through")) {
                int midY = baseline - ascent / 3;
                g.drawLine(drawX, midY, drawX + width, midY);
            }
            if (link != null || linkNode != null) {
                linkHits.add(new LinkHit(new Rectangle(drawX, drawY, width, height), link, linkNode));
            }
        });
    }

    private void paintControl(Dom.Node node, Flow flow, CssEngine.Style style) {
        String type = node.attr("type").toLowerCase(Locale.ROOT);
        if (type.equals("hidden")) return;

        boolean isSelect = node.tag.equals("select");
        boolean isButton = node.tag.equals("button") || type.equals("submit") || type.equals("button") || type.equals("reset");
        String rawValue = node.attr("value");
        if (isSelect) {
            List<Dom.Node> options = node.getElementsByTagName("option");
            Dom.Node selectedOpt = null;
            for (Dom.Node opt : options) {
                if (opt.hasAttribute("selected")) { selectedOpt = opt; break; }
            }
            if (selectedOpt == null && !options.isEmpty()) selectedOpt = options.get(0);
            rawValue = selectedOpt != null ? visibleText(selectedOpt).trim() : "";
        } else if (rawValue.isBlank() && !node.tag.equals("input")) {
            rawValue = visibleText(node).trim();
        }
        if (rawValue.isBlank() && isButton) rawValue = type.equals("reset") ? "Reset" : "Submit";
        final String buttonLabel = rawValue;

        Font font = flow.g.getFont();
        FontMetrics fm = flow.g.getFontMetrics(font);

        if (type.equals("checkbox") || type.equals("radio")) {
            int boxSize = 16;
            flow.addInlineItem(boxSize + 6, boxSize + 4, boxSize - 2, (g, x, y) -> {
                g.setColor(Color.WHITE);
                if (type.equals("radio")) g.fillOval(x, y + 2, boxSize, boxSize);
                else g.fillRoundRect(x, y + 2, boxSize, boxSize, 4, 4);
                g.setColor(new Color(140, 145, 150));
                if (type.equals("radio")) g.drawOval(x, y + 2, boxSize, boxSize);
                else g.drawRoundRect(x, y + 2, boxSize, boxSize, 4, 4);
                if (node.hasAttribute("checked")) {
                    g.setColor(CHROME_BLUE);
                    if (type.equals("radio")) g.fillOval(x + 4, y + 6, boxSize - 8, boxSize - 8);
                    else g.fillRoundRect(x + 3, y + 5, boxSize - 6, boxSize - 6, 2, 2);
                }
                Rectangle rect = new Rectangle(x, y + 2, boxSize, boxSize);
                if (!AriaSupport.disabled(node)) {
                    actionHits.add(new ActionHit(rect, () -> {
                        if (type.equals("radio") && !node.attr("name").isBlank()) {
                            for (Dom.Node peer : node.ownerDocument().getElementsByTagName("input")) {
                                if ("radio".equalsIgnoreCase(peer.attr("type")) && node.attr("name").equals(peer.attr("name"))) {
                                    peer.removeAttribute("checked");
                                }
                            }
                            node.setAttribute("checked", "");
                        } else {
                            node.toggleAttribute("checked");
                        }
                        fireDomEvent(node, "change");
                        fireDomEvent(node, "click");
                        repaint();
                    }, "Toggle " + AriaSupport.role(node)));
                }
            });
            return;
        }

        int defaultWidth = isButton
                ? Math.max(76, fm.stringWidth(buttonLabel) + 28)
                : isSelect
                ? Math.max(110, fm.stringWidth(buttonLabel) + 40)
                : Math.max(160, Math.min(320, parseInt(node.attr("size"), 24) * 9));
        int width = Math.max(54, Math.min(flow.width, style.px("width", defaultWidth, flow.width)));
        boolean singleLineTextarea = node.tag.equals("textarea") && parseInt(node.attr("rows"), 2) <= 1;
        int height = Math.max(30, style.px("height", (node.tag.equals("textarea") && !singleLineTextarea) ? 72 : 36));

        Color bg = style.color("background-color", isButton ? new Color(248, 249, 250) : Color.WHITE);
        Color fg = style.color("color", CHROME_TEXT);
        Color borderCol = style.color("border-color", new Color(218, 220, 224));

        final int fWidth = width;
        final int fHeight = height;
        final Color fBg = bg;
        final Color fFg = fg;
        final Color fBorderCol = borderCol;
        final Font fFont = font;
        final FontMetrics fFm = fm;

        flow.addInlineItem(fWidth + 6, fHeight + 16, fHeight - 4, (g, x, y) -> {
            boolean isFocused = (focusedInput == node);
            int drawY = y + 4;
            int radius = style.px("border-radius", isButton || isSelect ? 8 : 6);

            g.setColor(fBg);
            g.fillRoundRect(x, drawY, fWidth, fHeight, radius * 2, radius * 2);
            g.setColor(isFocused ? CHROME_BLUE : fBorderCol);
            if (isFocused) g.setStroke(new BasicStroke(2f));
            g.drawRoundRect(x, drawY, fWidth, fHeight, radius * 2, radius * 2);
            if (isFocused) g.setStroke(new BasicStroke(1f));

            g.setFont(fFont);
            String currentVal = node.attr("value");
            if (isSelect) currentVal = buttonLabel;
            else if (currentVal.isBlank() && !node.tag.equals("input")) currentVal = visibleText(node).trim();
            if (currentVal.isBlank() && isButton) currentVal = buttonLabel;
            if ("password".equals(type) && !currentVal.isEmpty()) {
                currentVal = "\u2022".repeat(currentVal.length());
            }
            String displayValue = currentVal.isBlank() ? node.attr("placeholder") : currentVal;

            g.setColor(currentVal.isBlank() && !isButton && !isSelect ? CHROME_MUTED : fFg);
            int textY = drawY + (fHeight - fFm.getHeight()) / 2 + fFm.getAscent();
            int textX = isButton ? x + Math.max(8, (fWidth - fFm.stringWidth(displayValue)) / 2) : x + 12;

            Shape oldClip = g.getClip();
            g.clipRect(x + 4, drawY + 2, Math.max(24, fWidth - (isSelect ? 24 : 8)), fHeight - 4);
            g.drawString(displayValue, textX, textY);
            if (isFocused && !isButton && !isSelect) {
                int caretX = textX + fFm.stringWidth(currentVal);
                g.setColor(CHROME_BLUE);
                g.drawLine(caretX + 1, drawY + 8, caretX + 1, drawY + fHeight - 8);
            }
            g.setClip(oldClip);

            if (isSelect) {
                g.setColor(CHROME_MUTED);
                g.drawString("\u25BE", x + fWidth - 18, textY);
            }

            Rectangle bounds = new Rectangle(x, drawY, fWidth, fHeight);
            if (AriaSupport.disabled(node)) return;
            if (isSelect) {
                actionHits.add(new ActionHit(bounds, () -> {
                    List<Dom.Node> options = node.getElementsByTagName("option");
                    if (!options.isEmpty()) {
                        int cur = 0;
                        for (int i = 0; i < options.size(); i++) {
                            if (options.get(i).hasAttribute("selected")) { cur = i; break; }
                        }
                        options.get(cur).removeAttribute("selected");
                        Dom.Node next = options.get((cur + 1) % options.size());
                        next.setAttribute("selected", "");
                        node.setAttribute("value", next.hasAttribute("value") ? next.attr("value") : next.textContent().trim());
                        fireDomEvent(node, "change");
                        repaint();
                    }
                }, "Cycle select option"));
            } else if (isButton) {
                actionHits.add(new ActionHit(bounds, () -> {
                    boolean proceed = fireDomEvent(node, "click");
                    if (proceed && !type.equals("button")) {
                        if (type.equals("reset")) resetEnclosingForm(node);
                        else submitEnclosingForm(node);
                    }
                }, "Button: " + buttonLabel));
            } else {
                actionHits.add(new ActionHit(bounds, () -> {
                    focusedInput = node;
                    fireDomEvent(node, "focus");
                    repaint();
                }, "Focus input field"));
            }
        });
    }

    private void resetEnclosingForm(Dom.Node buttonNode) {
        Dom.Node form = buttonNode.closest("form");
        if (form == null) return;
        for (Dom.Node input : form.getElementsByTagName("input")) {
            String t = input.attr("type").toLowerCase(Locale.ROOT);
            if (!t.equals("submit") && !t.equals("button") && !t.equals("reset") && !t.equals("hidden")) {
                input.setAttribute("value", "");
                input.removeAttribute("checked");
            }
        }
        fireDomEvent(form, "reset");
        repaint();
    }

    private void submitEnclosingForm(Dom.Node buttonNode) {
        Dom.Node form = buttonNode.closest("form");
        if (form == null) return;
        if (!fireDomEvent(form, "submit")) return;
        String action = form.attr("action");
        String targetUrl = action.isBlank() ? baseUrl : resolve(action);
        if (targetUrl == null || targetUrl.isBlank()) return;

        StringBuilder query = new StringBuilder();
        for (Dom.Node field : form.querySelectorAll("input, select, textarea")) {
            String name = field.attr("name");
            if (name.isBlank() || AriaSupport.disabled(field)) continue;
            String type = field.attr("type").toLowerCase(Locale.ROOT);
            if ((type.equals("checkbox") || type.equals("radio")) && !field.hasAttribute("checked")) continue;
            String val = field.attr("value");
            if (val.isBlank() && field.tag.equals("textarea")) val = field.textContent();
            if (!query.isEmpty()) query.append('&');
            query.append(URLEncoder.encode(name, StandardCharsets.UTF_8))
                    .append('=')
                    .append(URLEncoder.encode(val, StandardCharsets.UTF_8));
        }
        if (!query.isEmpty()) {
            targetUrl += (targetUrl.contains("?") ? "&" : "?") + query;
        }
        linkHandler.open(targetUrl);
    }

    private void paintImage(Dom.Node node, Flow flow, CssEngine.Style style, String inheritedLink, Dom.Node inheritedLinkNode) {
        String src = node.attr("src");
        BufferedImage img = imageLoader.getCached(src);
        String alt = node.attr("alt").trim();
        if (img == null && alt.isBlank()) return;

        int defaultW = img != null ? img.getWidth() : Math.min(220, Math.max(80, alt.length() * 8 + 20));
        int defaultH = img != null ? img.getHeight() : 36;

        int width = Math.max(16, Math.min(flow.width, style.px("width", parseInt(node.attr("width"), defaultW), flow.width)));
        int height = Math.max(16, Math.min(480, style.px("height", parseInt(node.attr("height"), defaultH))));

        flow.addInlineItem(width + 4, height + 4, height - 4, (g, x, y) -> {
            if (img != null) {
                g.drawImage(img, x, y, width, height, null);
            } else {
                g.setColor(new Color(248, 249, 250));
                g.fillRoundRect(x, y, width, height, 6, 6);
                g.setColor(new Color(218, 220, 224));
                g.drawRoundRect(x, y, width, height, 6, 6);
                g.setColor(CHROME_MUTED);
                g.drawString(alt, x + 8, y + 22);
            }
            if (inheritedLink != null || inheritedLinkNode != null) {
                linkHits.add(new LinkHit(new Rectangle(x, y, width, height), inheritedLink, inheritedLinkNode));
            }
        });
    }

    private void paintCanvas(Dom.Node canvas, Flow flow, CssEngine.Style style) {
        int width = Math.max(32, Math.min(flow.width, style.px("width", parseInt(canvas.attr("width"), 300), flow.width)));
        int height = Math.max(24, Math.min(480, style.px("height", parseInt(canvas.attr("height"), 150))));
        Color bg = style.color("background-color", Color.WHITE);
        int radius = style.px("border-radius", 4);
        flow.addInlineItem(width + 4, height + 4, height - 2, (g, x, y) -> {
            if (bg != null && bg.getAlpha() > 0) {
                g.setColor(bg);
                g.fillRoundRect(x, y, width, height, radius * 2, radius * 2);
            }
            paintBoxDecoration(g, style, x, y, width, height);
        });
    }

    private void paintSvg(Dom.Node svg, CssEngine css, Flow flow, CssEngine.Style style, String inheritedLink, Dom.Node inheritedLinkNode) {
        int rawW = parseInt(svg.attr("width"), style.px("width", -1, flow.width));
        int rawH = parseInt(svg.attr("height"), style.px("height", -1));
        double vbX = 0, vbY = 0, vbW = rawW > 0 ? rawW : 24, vbH = rawH > 0 ? rawH : 24;
        String viewBox = svg.attr("viewbox").trim();
        if (!viewBox.isEmpty()) {
            String[] parts = viewBox.split("[\\s,]+");
            if (parts.length == 4) {
                vbX = parseDouble(parts[0], 0);
                vbY = parseDouble(parts[1], 0);
                vbW = Math.max(1, parseDouble(parts[2], 24));
                vbH = Math.max(1, parseDouble(parts[3], 24));
            }
        }
        if (rawW <= 0 && rawH <= 0 && viewBox.isEmpty()) return;
        int width = Math.max(12, Math.min(flow.width, rawW > 0 ? rawW : (int) Math.round(vbW)));
        int height = Math.max(12, Math.min(360, rawH > 0 ? rawH : (int) Math.round(vbH)));

        final double fVbX = vbX, fVbY = vbY, fVbW = vbW, fVbH = vbH;
        Color defaultFill = style.color("fill", style.color("color", CHROME_TEXT));
        flow.addInlineItem(width + 4, height + 4, height - 2, (g, x, y) -> {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.translate(x, y);
            g2.scale(width / fVbW, height / fVbH);
            g2.translate(-fVbX, -fVbY);
            paintSvgChildren(svg, css, g2, defaultFill);
            g2.dispose();
            if (inheritedLink != null || inheritedLinkNode != null) {
                linkHits.add(new LinkHit(new Rectangle(x, y, width, height), inheritedLink, inheritedLinkNode));
            }
        });
    }

    private void paintSvgChildren(Dom.Node parent, CssEngine css, Graphics2D g, Color inheritedFill) {
        for (Dom.Node child : parent.children) {
            if (child.isText() || child.isComment() || AriaSupport.hidden(child)) continue;
            CssEngine.Style st = css.compute(child);
            String fillAttr = !child.attr("fill").isBlank() ? child.attr("fill") : st.get("fill", "");
            Color fill = "none".equalsIgnoreCase(fillAttr) ? null : CssEngine.parseColor(fillAttr, inheritedFill);
            String strokeAttr = !child.attr("stroke").isBlank() ? child.attr("stroke") : st.get("stroke", "");
            Color stroke = CssEngine.parseColor(strokeAttr, null);
            float strokeW = (float) parseDouble(!child.attr("stroke-width").isBlank() ? child.attr("stroke-width") : st.get("stroke-width", "1"), 1.0);

            switch (child.tag) {
                case "g" -> paintSvgChildren(child, css, g, fill != null ? fill : inheritedFill);
                case "rect" -> {
                    int rx = (int) Math.round(parseDouble(child.attr("x"), 0));
                    int ry = (int) Math.round(parseDouble(child.attr("y"), 0));
                    int rw = (int) Math.round(parseDouble(child.attr("width"), 0));
                    int rh = (int) Math.round(parseDouble(child.attr("height"), 0));
                    if (fill != null) { g.setColor(fill); g.fillRect(rx, ry, rw, rh); }
                    if (stroke != null) { g.setColor(stroke); g.setStroke(new BasicStroke(strokeW)); g.drawRect(rx, ry, rw, rh); }
                }
                case "circle" -> {
                    double cx = parseDouble(child.attr("cx"), 0);
                    double cy = parseDouble(child.attr("cy"), 0);
                    double r = parseDouble(child.attr("r"), 0);
                    int x = (int) Math.round(cx - r), y = (int) Math.round(cy - r), d = (int) Math.round(r * 2);
                    if (fill != null) { g.setColor(fill); g.fillOval(x, y, d, d); }
                    if (stroke != null) { g.setColor(stroke); g.setStroke(new BasicStroke(strokeW)); g.drawOval(x, y, d, d); }
                }
                case "line" -> {
                    int x1 = (int) Math.round(parseDouble(child.attr("x1"), 0));
                    int y1 = (int) Math.round(parseDouble(child.attr("y1"), 0));
                    int x2 = (int) Math.round(parseDouble(child.attr("x2"), 0));
                    int y2 = (int) Math.round(parseDouble(child.attr("y2"), 0));
                    g.setColor(stroke != null ? stroke : (fill != null ? fill : inheritedFill));
                    g.setStroke(new BasicStroke(strokeW));
                    g.drawLine(x1, y1, x2, y2);
                }
                case "path" -> {
                    Path2D path = parseSvgPath(child.attr("d"));
                    if (path != null) {
                        if (fill != null) { g.setColor(fill); g.fill(path); }
                        if (stroke != null) { g.setColor(stroke); g.setStroke(new BasicStroke(strokeW)); g.draw(path); }
                    }
                }
            }
        }
    }

    private static Path2D parseSvgPath(String d) {
        if (d == null || d.isBlank()) return null;
        Path2D.Double path = new Path2D.Double();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("([MmLlHhVvCcQqZz])|(-?\\d*\\.?\\d+(?:e[-+]?\\d+)?)", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(d);
        List<String> tokens = new ArrayList<>();
        while (m.find()) tokens.add(m.group());
        int i = 0;
        char cmd = 'M';
        double curX = 0, curY = 0, startX = 0, startY = 0;
        try {
            while (i < tokens.size()) {
                String t = tokens.get(i);
                if (Character.isLetter(t.charAt(0))) {
                    cmd = t.charAt(0);
                    i++;
                }
                boolean rel = Character.isLowerCase(cmd);
                switch (Character.toUpperCase(cmd)) {
                    case 'M' -> {
                        double x = Double.parseDouble(tokens.get(i++)) + (rel ? curX : 0);
                        double y = Double.parseDouble(tokens.get(i++)) + (rel ? curY : 0);
                        path.moveTo(x, y);
                        curX = startX = x; curY = startY = y;
                        cmd = rel ? 'l' : 'L';
                    }
                    case 'L' -> {
                        double x = Double.parseDouble(tokens.get(i++)) + (rel ? curX : 0);
                        double y = Double.parseDouble(tokens.get(i++)) + (rel ? curY : 0);
                        path.lineTo(x, y);
                        curX = x; curY = y;
                    }
                    case 'H' -> {
                        double x = Double.parseDouble(tokens.get(i++)) + (rel ? curX : 0);
                        path.lineTo(x, curY);
                        curX = x;
                    }
                    case 'V' -> {
                        double y = Double.parseDouble(tokens.get(i++)) + (rel ? curY : 0);
                        path.lineTo(curX, y);
                        curY = y;
                    }
                    case 'C' -> {
                        double x1 = Double.parseDouble(tokens.get(i++)) + (rel ? curX : 0);
                        double y1 = Double.parseDouble(tokens.get(i++)) + (rel ? curY : 0);
                        double x2 = Double.parseDouble(tokens.get(i++)) + (rel ? curX : 0);
                        double y2 = Double.parseDouble(tokens.get(i++)) + (rel ? curY : 0);
                        double x = Double.parseDouble(tokens.get(i++)) + (rel ? curX : 0);
                        double y = Double.parseDouble(tokens.get(i++)) + (rel ? curY : 0);
                        path.curveTo(x1, y1, x2, y2, x, y);
                        curX = x; curY = y;
                    }
                    case 'Q' -> {
                        double x1 = Double.parseDouble(tokens.get(i++)) + (rel ? curX : 0);
                        double y1 = Double.parseDouble(tokens.get(i++)) + (rel ? curY : 0);
                        double x = Double.parseDouble(tokens.get(i++)) + (rel ? curX : 0);
                        double y = Double.parseDouble(tokens.get(i++)) + (rel ? curY : 0);
                        path.quadTo(x1, y1, x, y);
                        curX = x; curY = y;
                    }
                    case 'Z' -> {
                        path.closePath();
                        curX = startX; curY = startY;
                    }
                    default -> i++;
                }
            }
        } catch (Exception ignored) {}
        return path;
    }

    private static boolean hasAnyBorder(CssEngine.Style style) {
        for (String prop : List.of("border", "border-top", "border-right", "border-bottom", "border-left")) {
            String val = style.get(prop, "");
            if (!val.isBlank() && !val.equalsIgnoreCase("none") && !val.equalsIgnoreCase("0") && !val.equalsIgnoreCase("0px")) {
                return true;
            }
        }
        return false;
    }

    private static void paintBoxDecoration(Graphics2D g, CssEngine.Style style, int x, int y, int width, int height) {
        int radius = style.px("border-top-left-radius", style.px("border-radius", 0));
        String border = style.get("border", "");
        Stroke oldStroke = g.getStroke();
        if (!border.isBlank() && !border.equalsIgnoreCase("none")) {
            int bw = Math.max(1, style.px("border-top-width", 1));
            g.setStroke(strokeFor(style.get("border-top-style", "solid"), bw));
            g.setColor(style.color("border-top-color", style.color("border-color", borderColor(border))));
            if (radius > 0) {
                g.drawRoundRect(x, y, width, Math.max(1, height), radius * 2, radius * 2);
            } else {
                g.drawRect(x, y, width, Math.max(1, height));
            }
        } else {
            drawSideBorder(g, style, "top", x, y, x + width, y);
            drawSideBorder(g, style, "bottom", x, y + height, x + width, y + height);
            drawSideBorder(g, style, "left", x, y, x, y + height);
            drawSideBorder(g, style, "right", x + width, y, x + width, y + height);
        }
        String borderLeft = style.get("border-left", "");
        if (!borderLeft.isBlank() && !borderLeft.equalsIgnoreCase("none") && !borderLeft.equals(border)) {
            int lw = Math.max(2, style.px("border-left-width", 3));
            g.setColor(style.color("border-left-color", borderColor(borderLeft)));
            g.setStroke(strokeFor(style.get("border-left-style", "solid"), lw));
            g.drawLine(x, y, x, y + Math.max(1, height));
        }
        g.setStroke(oldStroke);
    }

    private static void drawSideBorder(Graphics2D g, CssEngine.Style style, String side, int x1, int y1, int x2, int y2) {
        String raw = style.get("border-" + side, "");
        if (raw.isBlank() || raw.equalsIgnoreCase("none")) return;
        int w = Math.max(1, style.px("border-" + side + "-width", 1));
        g.setStroke(strokeFor(style.get("border-" + side + "-style", "solid"), w));
        g.setColor(style.color("border-" + side + "-color", borderColor(raw)));
        g.drawLine(x1, y1, x2, y2);
    }

    private static Stroke strokeFor(String borderStyle, int width) {
        String s = borderStyle == null ? "solid" : borderStyle.toLowerCase(Locale.ROOT);
        if (s.equals("dashed")) {
            return new BasicStroke(width, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, new float[]{6f, 4f}, 0f);
        }
        if (s.equals("dotted")) {
            return new BasicStroke(width, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 10f, new float[]{2f, 4f}, 0f);
        }
        return new BasicStroke(width);
    }

    private static Color borderColor(String border) {
        for (String part : border.trim().split("\\s+")) {
            Color color = CssEngine.parseColor(part, null);
            if (color != null) return color;
        }
        return new Color(218, 220, 224);
    }

    private static boolean isNonVisual(String tag) {
        return tag.equals("head") || tag.equals("style") || tag.equals("script")
                || tag.equals("title") || tag.equals("meta") || tag.equals("link")
                || tag.equals("base") || tag.equals("template") || tag.equals("noscript")
                || tag.equals("defs") || tag.equals("clippath") || tag.equals("lineargradient")
                || tag.equals("iframe") || tag.equals("object") || tag.equals("embed")
                || tag.equals("datalist") || tag.equals("param") || tag.equals("source") || tag.equals("track");
    }

    private static String visibleText(Dom.Node node) {
        if (node == null || node.isComment()) return "";
        if (node.isText()) return node.text;
        if (isNonVisual(node.tag) || AriaSupport.hidden(node)) return "";
        StringBuilder sb = new StringBuilder();
        for (Dom.Node child : node.children) {
            sb.append(visibleText(child));
        }
        return sb.toString();
    }

    private interface InlinePainter {
        void paint(Graphics2D g, int x, int y);
    }

    private record InlineItem(int width, int height, int ascent, InlinePainter painter) {}

    private final class Flow {
        final EnginePage owner = EnginePage.this;
        final Graphics2D g;
        final boolean dryRun;
        final List<InlineItem> lineItems = new ArrayList<>();
        int left, width, y, lineHeight = 22;
        String align = "left";

        Flow(Graphics2D g, int left, int y, int width, boolean dryRun) {
            this.g = g;
            this.left = left;
            this.width = width;
            this.y = y;
            this.dryRun = dryRun;
        }

        boolean hasItemsOnLine() {
            return !lineItems.isEmpty();
        }

        int currentLineWidth() {
            int w = 0;
            for (InlineItem item : lineItems) w += item.width;
            return w;
        }

        void advanceInline(int dx) {
            if (dx <= 0 || lineItems.isEmpty()) return;
            lineItems.add(new InlineItem(dx, 1, 1, (g2, px, py) -> {}));
        }

        void addInlineItem(int itemW, int itemH, int itemAscent, InlinePainter painter) {
            if (!lineItems.isEmpty() && currentLineWidth() + itemW > width) {
                flushLine();
            }
            lineItems.add(new InlineItem(itemW, itemH, itemAscent, painter));
            lineHeight = Math.max(lineHeight, itemH + 4);
        }

        void flushLine() {
            if (lineItems.isEmpty()) {
                return;
            }
            int totalW = 0;
            int maxAscent = 0;
            int maxDescent = 0;
            for (InlineItem item : lineItems) {
                totalW += item.width;
                maxAscent = Math.max(maxAscent, item.ascent);
                maxDescent = Math.max(maxDescent, item.height - item.ascent);
            }
            int computedLineH = Math.max(lineHeight, maxAscent + maxDescent + 4);
            if (!dryRun) {
                int startX = left;
                if ("center".equalsIgnoreCase(align)) {
                    startX = left + Math.max(0, (width - totalW) / 2);
                } else if ("right".equalsIgnoreCase(align) || "end".equalsIgnoreCase(align)) {
                    startX = left + Math.max(0, width - totalW);
                }
                int curX = startX;
                for (InlineItem item : lineItems) {
                    int itemY = y + Math.max(0, maxAscent - item.ascent);
                    item.painter.paint(g, curX, itemY);
                    curX += item.width;
                }
            }
            lineItems.clear();
            y += computedLineH;
            lineHeight = 22;
        }

        void newLine() {
            if (!lineItems.isEmpty()) {
                flushLine();
            } else {
                y += lineHeight;
                lineHeight = 22;
            }
        }

        void newLineIfNeeded() {
            if (!lineItems.isEmpty()) flushLine();
        }
    }

    private String resolve(String href) {
        if (href == null || href.isBlank() || href.startsWith("#") || href.regionMatches(true, 0, "javascript:", 0, 11)) {
            return null;
        }
        try {
            return baseUrl.isBlank() ? href : URI.create(baseUrl).resolve(href).toString();
        } catch (Exception ignored) {
            return href;
        }
    }

    private static String resolveBaseUrl(Dom.Node root, String fallback) {
        Dom.Node base = findFirst(root, "base");
        if (base == null || base.attr("href").isBlank()) return fallback;
        try {
            return fallback == null || fallback.isBlank()
                    ? base.attr("href")
                    : URI.create(fallback).resolve(base.attr("href")).toString();
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static Font fontFor(CssEngine.Style style, Font inherited) {
        int size = Math.max(9, Math.min(72, style.px("font-size", inherited.getSize())));
        String weight = style.get("font-weight", "");
        int flags = "bold".equalsIgnoreCase(weight) || "bolder".equalsIgnoreCase(weight)
                || parseInt(weight, 0) >= 600 ? Font.BOLD : Font.PLAIN;
        String fStyle = style.get("font-style", "");
        if ("italic".equalsIgnoreCase(fStyle) || "oblique".equalsIgnoreCase(fStyle)) flags |= Font.ITALIC;
        String family = style.get("font-family", inherited.getFamily()).split(",")[0]
                .replace("\"", "").replace("'", "").trim();
        if (family.equalsIgnoreCase("monospace") || family.equalsIgnoreCase("ui-monospace")) family = "Monospaced";
        else if (family.equalsIgnoreCase("sans-serif") || family.equalsIgnoreCase("system-ui")) family = "SansSerif";
        else if (family.equalsIgnoreCase("serif")) family = "Serif";
        return new Font(family.isBlank() ? inherited.getFamily() : family, flags, size);
    }

    private static int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value.replaceAll("[^0-9-]", "").trim());
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static double parseDouble(String value, double fallback) {
        try {
            return Double.parseDouble(value.trim());
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static void collectStyles(Dom.Node node, StringBuilder out, String baseUrl, ResourceLoader.Budget budget) {
        if (node == null) return;
        if (node.tag.equals("style")) {
            out.append(node.textContent()).append('\n');
        } else if (node.tag.equals("link") && node.attr("rel").toLowerCase(Locale.ROOT).contains("stylesheet")) {
            try {
                String css = ResourceLoader.fetch(budget, baseUrl, node.attr("href"));
                if (css != null) out.append(css).append('\n');
            } catch (Exception ignored) {
            }
        }
        for (Dom.Node child : node.children) collectStyles(child, out, baseUrl, budget);
    }

    private static Dom.Node findFirst(Dom.Node node, String tag) {
        if (node == null) return null;
        if (node.tag.equals(tag)) return node;
        for (Dom.Node child : node.children) {
            Dom.Node found = findFirst(child, tag);
            if (found != null) return found;
        }
        return null;
    }

    private static List<Dom.Node> findAll(Dom.Node node, String tag) {
        ArrayList<Dom.Node> result = new ArrayList<>();
        collect(node, tag, result);
        return result;
    }

    private static void collect(Dom.Node node, String tag, List<Dom.Node> out) {
        if (node == null) return;
        if (node.tag.equals(tag)) out.add(node);
        for (Dom.Node child : node.children) collect(child, tag, out);
    }

    static final class ResourceLoader {
        private static final int MAX_RESOURCE_BYTES = 512 * 1024;
        private static final int MAX_PAGE_BYTES = 2 * 1024 * 1024;
        private static final int MAX_RESOURCES = 32;
        private static final int MAX_REDIRECTS = 4;
        private static final int TIMEOUT_MILLIS = 8_000;

        static final class Budget {
            private int resources;
            private int bytes;

            synchronized void startResource() throws IOException {
                if (++resources > MAX_RESOURCES) throw new IOException("Page resource limit reached");
            }

            synchronized void addBytes(int count) throws IOException {
                bytes += count;
                if (bytes > MAX_PAGE_BYTES) throw new IOException("Page resource size limit reached");
            }
        }

        private ResourceLoader() {}

        static byte[] fetchBytes(String baseUrl, String reference, int maxBytes) throws IOException {
            if (reference == null || reference.isBlank()) return null;
            String trimmed = reference.trim();
            if (trimmed.regionMatches(true, 0, "data:", 0, 5)) {
                int comma = trimmed.indexOf(',');
                if (comma < 0) return null;
                String meta = trimmed.substring(5, comma).toLowerCase(Locale.ROOT);
                String payload = trimmed.substring(comma + 1);
                if (meta.contains(";base64")) {
                    return Base64.getMimeDecoder().decode(payload);
                }
                return payload.getBytes(StandardCharsets.UTF_8);
            }
            URI target;
            try {
                target = baseUrl == null || baseUrl.isBlank() || baseUrl.startsWith("about:")
                        ? URI.create(trimmed)
                        : URI.create(baseUrl).resolve(trimmed);
            } catch (IllegalArgumentException ex) {
                throw new IOException("Invalid resource URL", ex);
            }
            return fetchBytes(target, 0, maxBytes);
        }

        private static byte[] fetchBytes(URI target, int redirects, int maxBytes) throws IOException {
            String scheme = target.getScheme();
            if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
                throw new IOException("Only HTTP and HTTPS page resources are supported");
            }
            if (redirects > MAX_REDIRECTS) throw new IOException("Too many resource redirects");
            URLConnection connection = target.toURL().openConnection();
            connection.setConnectTimeout(TIMEOUT_MILLIS);
            connection.setReadTimeout(TIMEOUT_MILLIS);
            connection.setRequestProperty("User-Agent", "CustomJavaBrowser/1.0");
            connection.setRequestProperty("Accept", "image/*,text/css,application/javascript,text/javascript,*/*;q=0.1");
            if (connection instanceof HttpURLConnection http) {
                http.setInstanceFollowRedirects(false);
                int status = http.getResponseCode();
                if (status >= 300 && status < 400) {
                    String location = http.getHeaderField("Location");
                    http.disconnect();
                    if (location == null || location.isBlank()) return null;
                    return fetchBytes(target.resolve(location), redirects + 1, maxBytes);
                }
                if (status < 200 || status >= 300) {
                    http.disconnect();
                    return null;
                }
            }
            try (InputStream input = connection.getInputStream();
                 ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int count, total = 0;
                while ((count = input.read(buffer)) != -1) {
                    total += count;
                    if (total > maxBytes) throw new IOException("Resource size limit reached");
                    out.write(buffer, 0, count);
                }
                return out.toByteArray();
            } finally {
                if (connection instanceof HttpURLConnection http) http.disconnect();
            }
        }

        static String fetch(Budget budget, String baseUrl, String reference) throws IOException {
            if (reference == null || reference.isBlank()) return null;
            URI target;
            try {
                target = baseUrl == null || baseUrl.isBlank() || baseUrl.startsWith("about:")
                        ? URI.create(reference.trim())
                        : URI.create(baseUrl).resolve(reference.trim());
            } catch (IllegalArgumentException ex) {
                throw new IOException("Invalid resource URL", ex);
            }
            return fetch(budget, target, 0);
        }

        private static String fetch(Budget budget, URI target, int redirects) throws IOException {
            String scheme = target.getScheme();
            if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
                throw new IOException("Only HTTP and HTTPS page resources are supported");
            }
            if (redirects > MAX_REDIRECTS) throw new IOException("Too many resource redirects");
            budget.startResource();
            URLConnection connection = target.toURL().openConnection();
            connection.setConnectTimeout(TIMEOUT_MILLIS);
            connection.setReadTimeout(TIMEOUT_MILLIS);
            connection.setRequestProperty("User-Agent", "CustomJavaBrowser/1.0");
            connection.setRequestProperty("Accept", "text/css,application/javascript,text/javascript,*/*;q=0.1");
            if (connection instanceof HttpURLConnection http) {
                http.setInstanceFollowRedirects(false);
                int status = http.getResponseCode();
                if (status >= 300 && status < 400) {
                    String location = http.getHeaderField("Location");
                    http.disconnect();
                    if (location == null || location.isBlank()) return null;
                    return fetch(budget, target.resolve(location), redirects + 1);
                }
                if (status < 200 || status >= 300) {
                    http.disconnect();
                    return null;
                }
            }
            try (InputStream input = connection.getInputStream();
                 ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int count, resourceBytes = 0;
                while ((count = input.read(buffer)) != -1) {
                    resourceBytes += count;
                    if (resourceBytes > MAX_RESOURCE_BYTES) throw new IOException("Page resource size limit reached");
                    budget.addBytes(count);
                    output.write(buffer, 0, count);
                }
                return output.toString(StandardCharsets.UTF_8);
            } finally {
                if (connection instanceof HttpURLConnection http) http.disconnect();
            }
        }
    }

    static final class ImageLoader {
        private static final int MAX_IMAGE_BYTES = 8 * 1024 * 1024;
        private static final int MAX_IMAGES = 32;

        static final class Budget {
            private int resources;

            synchronized void startImage() throws IOException {
                if (++resources > MAX_IMAGES) throw new IOException("Page image limit reached");
            }
        }

        private final Map<String, BufferedImage> cache = new ConcurrentHashMap<>();

        BufferedImage getCached(String reference) {
            if (reference == null) return null;
            return cache.get(reference.trim());
        }

        BufferedImage load(String baseUrl, String reference, Budget budget) {
            if (reference == null || reference.isBlank()) return null;
            String normalized = reference.trim();
            BufferedImage existing = cache.get(normalized);
            if (existing != null) return existing;
            try {
                budget.startImage();
                byte[] data = ResourceLoader.fetchBytes(baseUrl, normalized, MAX_IMAGE_BYTES);
                if (data == null || data.length == 0) return null;
                BufferedImage image;
                try (ByteArrayInputStream input = new ByteArrayInputStream(data)) {
                    image = ImageIO.read(input);
                }
                if (image == null) return null;
                cache.put(normalized, image);
                return image;
            } catch (Exception ignored) {
                return null;
            }
        }
    }
}

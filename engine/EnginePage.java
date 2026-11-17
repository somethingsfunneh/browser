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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Graphics2D renderer for the browser's limited HTML/CSS subset. */
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
                Math.min(4, Runtime.getRuntime().availableProcessors())),
                task -> {
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
                for (LinkHit hit : linkHits) {
                    if (hit.bounds.contains(event.getPoint())) {
                        linkHandler.open(hit.target);
                        return;
                    }
                }
            }
        });
    }

    public void setLinkHandler(LinkHandler handler) {
        linkHandler = handler == null ? url -> {} : handler;
    }

    /** Parses and prepares a page off the Swing event thread. */
    public void setPage(String html, String url) {
        baseUrl = url == null ? "" : url;
        long revision = ++pageRevision;

        SwingWorker<RenderDocument, Void> worker = new SwingWorker<>() {
            @Override protected RenderDocument doInBackground() {
                Dom.Node root = Dom.parse(html == null ? "" : html);
                StringBuilder styles = new StringBuilder();
                collectStyles(root, styles);

                CssEngine css = new CssEngine(CssPreprocessor.resolve(styles.toString()));
                for (Dom.Node script : findAll(root, "script")) {
                    new TinyJs(root).execute(script.textContent());
                }
                Dom.Node titleNode = findFirst(root, "title");
                String title = titleNode == null ? "" : titleNode.textContent().trim();
                return new RenderDocument(root, css, title);
            }

            @Override protected void done() {
                if (revision != pageRevision) return;
                try {
                    document = get();
                    linkHits.clear();
                    revalidate();
                    repaint();
                } catch (Exception ignored) {
                    document = null;
                    repaint();
                }
            }
        };
        preparer.execute(worker);
    }

    public void dispose() {
        ++pageRevision;
        preparer.shutdownNow();
    }

    public String pageTitle() {
        RenderDocument ready = document;
        return ready == null ? "" : ready.title;
    }

    @Override public Dimension getPreferredSize() {
        return new Dimension(Math.max(600, getWidth()), contentHeight);
    }

    @Override protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        RenderDocument ready = document;
        if (ready == null) return;

        Graphics2D g = (Graphics2D) graphics.create();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING,
                RenderingHints.VALUE_RENDER_QUALITY);
        linkHits.clear();

        Flow flow = new Flow(g, 16, 12, Math.max(100, getWidth() - 32));
        paintChildren(ready.root, ready.css, flow, null);
        contentHeight = Math.max(getHeight(), flow.y + flow.lineHeight + 20);
        g.dispose();
        revalidate();
    }

    private void paintChildren(Dom.Node parent, CssEngine css,
                               Flow flow, String inheritedLink) {
        if (parent == null || parent.children.isEmpty()) return;

        for (Dom.Node node : parent.children) {
            if (node.isText()) {
                paintText(node.text, flow, inheritedLink);
                continue;
            }

            String tag = node.tag;
            if (tag.equals("head") || tag.equals("style") || tag.equals("script")
                    || tag.equals("title") || tag.equals("meta") || tag.equals("link")) {
                continue;
            }

            CssEngine.Style style = css.compute(node);
            if ("none".equalsIgnoreCase(style.get("display", ""))) continue;
            if (tag.equals("br")) {
                flow.newLine();
                continue;
            }
            if (tag.equals("img")) {
                paintImagePlaceholder(node, flow, style);
                continue;
            }

            boolean block = "block".equalsIgnoreCase(
                    style.get("display", Dom.isBlock(tag) ? "block" : "inline"));
            if (block && flow.hasText) flow.newLine();

            int margin = style.px("margin", block ? 4 : 0);
            int padding = style.px("padding", 0);
            int paddingLeft = style.px("padding-left", 0);
            int paddingRight = style.px("padding-right", 0);
            int paddingTop = style.px("padding-top", 0);
            int paddingBottom = style.px("padding-bottom", 0);
            flow.y += margin;

            int boxX = flow.left;
            int contentX = boxX + padding + paddingLeft;
            int boxWidth = Math.max(MIN_TEXT_WIDTH,
                    flow.width - padding * 2 - paddingLeft - paddingRight);
            int boxStartY = flow.y;

            Font oldFont = flow.g.getFont();
            Color oldColor = flow.g.getColor();
            flow.g.setFont(fontFor(style, oldFont));
            flow.g.setColor(style.color("color", oldColor));

            Color background = style.color("background-color", style.color("background", null));
            if (background != null && background.getAlpha() > 0) {
                flow.g.setColor(background);
                int backgroundHeight = Math.max(24, style.px("height", 24))
                        + paddingTop + paddingBottom;
                flow.g.fillRect(boxX, boxStartY, boxWidth, backgroundHeight);
                flow.g.setColor(style.color("color", oldColor));
            }

            String effectiveLink = tag.equals("a") ? resolve(node.attr("href")) : inheritedLink;
            int previousLeft = flow.left;
            int previousWidth = flow.width;
            if (block) {
                flow.left = contentX;
                flow.width = Math.max(40, boxWidth - (contentX - boxX));
                flow.x = flow.left;
            }

            paintChildren(node, css, flow, effectiveLink);
            if (block && flow.hasText) flow.newLine();

            int boxHeight = Math.max(20, flow.y - boxStartY);
            int fixedHeight = style.px("height", -1);
            if (fixedHeight >= 0) boxHeight = fixedHeight;

            String border = style.get("border", "");
            if (!border.isBlank() && !border.equalsIgnoreCase("none")) {
                flow.g.setColor(Color.GRAY);
                flow.g.drawRect(boxX, boxStartY, boxWidth,
                        boxHeight + paddingTop + paddingBottom);
            }

            flow.left = previousLeft;
            flow.width = previousWidth;
            flow.x = flow.left;
            flow.y = Math.max(flow.y, boxStartY + boxHeight
                    + paddingTop + paddingBottom);
            flow.g.setFont(oldFont);
            flow.g.setColor(oldColor);
            if (block) flow.newLine();
            flow.y += margin;
        }
    }

    private void paintText(String raw, Flow flow, String link) {
        if (raw == null || raw.isEmpty()) return;

        FontMetrics metrics = flow.g.getFontMetrics();
        int index = 0;
        int length = raw.length();
        int spaceWidth = metrics.charWidth(' ');

        while (index < length) {
            while (index < length && Character.isWhitespace(raw.charAt(index))) index++;
            if (index >= length) break;

            int wordStart = index;
            while (index < length && !Character.isWhitespace(raw.charAt(index))) index++;
            String word = raw.substring(wordStart, index);
            int wordWidth = metrics.stringWidth(word);

            int next = index;
            while (next < length && Character.isWhitespace(raw.charAt(next))) next++;
            boolean hasFollowingWord = next < length;
            int advance = wordWidth + (hasFollowingWord ? spaceWidth : 0);

            if (flow.hasText && flow.x + wordWidth > flow.left + flow.width) {
                flow.newLine();
            }

            int baseline = flow.y + metrics.getAscent();
            if (link != null) {
                flow.g.setColor(new Color(6, 69, 173));
                flow.g.drawString(word, flow.x, baseline);
                flow.g.drawLine(flow.x, baseline + 1,
                        flow.x + wordWidth, baseline + 1);
                linkHits.add(new LinkHit(
                        new Rectangle(flow.x, flow.y, wordWidth, metrics.getHeight()), link));
            } else {
                flow.g.setColor(Color.BLACK);
                flow.g.drawString(word, flow.x, baseline);
            }

            flow.x += advance;
            flow.lineHeight = Math.max(flow.lineHeight, metrics.getHeight());
            flow.hasText = true;
            index = next;
        }
    }

    private void paintImagePlaceholder(Dom.Node node, Flow flow, CssEngine.Style style) {
        int width = Math.max(32, Math.min(640,
                style.px("width", parseInt(node.attr("width"), 120))));
        int height = Math.max(24, Math.min(480,
                style.px("height", parseInt(node.attr("height"), 80))));

        flow.newLineIfNeeded();
        flow.g.setColor(new Color(238, 238, 238));
        flow.g.fillRect(flow.x, flow.y, width, height);
        flow.g.setColor(Color.GRAY);
        flow.g.drawRect(flow.x, flow.y, width, height);
        String alt = node.attr("alt");
        if (!alt.isBlank()) flow.g.drawString(alt, flow.x + 6, flow.y + 18);

        flow.y += height + 4;
        flow.x = flow.left;
        flow.hasText = false;
    }

    private String resolve(String href) {
        if (href == null || href.isBlank() || href.startsWith("#")
                || href.regionMatches(true, 0, "javascript:", 0, 11)) {
            return null;
        }
        try {
            return baseUrl.isBlank() ? href : URI.create(baseUrl).resolve(href).toString();
        } catch (Exception ignored) {
            return href;
        }
    }

    private static Font fontFor(CssEngine.Style style, Font inherited) {
        int size = Math.max(8, Math.min(72,
                style.px("font-size", inherited.getSize())));
        int flags = Font.PLAIN;
        if ("bold".equalsIgnoreCase(style.get("font-weight", ""))) flags |= Font.BOLD;
        if ("italic".equalsIgnoreCase(style.get("font-style", ""))) flags |= Font.ITALIC;

        String family = style.get("font-family", inherited.getFamily()).split(",")[0]
                .replace("\"", "")
                .replace("'", "")
                .trim();
        return new Font(family.isBlank() ? inherited.getFamily() : family, flags, size);
    }

    private static int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value.trim());
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static void collectStyles(Dom.Node node, StringBuilder out) {
        if (node == null) return;
        if (node.tag.equals("style")) out.append(node.textContent()).append('\n');
        for (Dom.Node child : node.children) collectStyles(child, out);
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
        ArrayList<Dom.Node> out = new ArrayList<>();
        collect(node, tag, out);
        return out;
    }

    private static void collect(Dom.Node node, String tag, List<Dom.Node> out) {
        if (node == null) return;
        if (node.tag.equals(tag)) out.add(node);
        for (Dom.Node child : node.children) collect(child, tag, out);
    }

    private static final class Flow {
        final Graphics2D g;
        int left;
        int width;
        int x;
        int y;
        int lineHeight = 20;
        boolean hasText;

        Flow(Graphics2D g, int left, int y, int width) {
            this.g = g;
            this.left = left;
            this.width = width;
            this.x = left;
            this.y = y;
        }

        void newLine() {
            y += lineHeight;
            x = left;
            lineHeight = 20;
            hasText = false;
        }

        void newLineIfNeeded() {
            if (hasText) newLine();
        }
    }
}

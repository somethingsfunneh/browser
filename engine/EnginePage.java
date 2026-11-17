package engine;

import javax.swing.JComponent;
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

/** Optimized custom renderer for a limited HTML/CSS subset. */
public final class EnginePage extends JComponent {
    public interface LinkHandler { void open(String url); }

    private static final int DEFAULT_HEIGHT = 600;
    private static final int MIN_TEXT_WIDTH = 32;

    private Dom.Node root;
    private CssEngine css;
    private String baseUrl = "";
    private LinkHandler linkHandler = url -> {};
    private final List<LinkHit> linkHits = new ArrayList<>();
    private int contentHeight = DEFAULT_HEIGHT;

    private record LinkHit(Rectangle bounds, String target) {}

    public EnginePage() {
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

    public void setPage(String html, String url) {
        baseUrl = url == null ? "" : url;
        root = Dom.parse(html == null ? "" : html);
        StringBuilder styles = new StringBuilder();
        collectStyles(root, styles);
        css = new CssEngine(styles.toString());
        for (Dom.Node script : findAll(root, "script")) {
            new TinyJs(root).execute(script.textContent());
        }
        linkHits.clear();
        revalidate();
        repaint();
    }

    public String pageTitle() {
        Dom.Node title = findFirst(root, "title");
        return title == null ? "" : title.textContent().trim();
    }

    @Override public Dimension getPreferredSize() {
        return new Dimension(Math.max(600, getWidth()), contentHeight);
    }

    @Override protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        if (root == null) return;

        Graphics2D g = (Graphics2D) graphics.create();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        linkHits.clear();

        Flow flow = new Flow(g, 16, 12, Math.max(100, getWidth() - 32));
        paintChildren(root, flow, null);
        contentHeight = Math.max(getHeight(), flow.y + flow.lineHeight + 20);
        g.dispose();
    }

    private void paintChildren(Dom.Node parent, Flow flow, String inheritedLink) {
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
            flow.y += margin;

            int boxX = flow.left;
            int contentX = boxX + padding + style.px("padding-left", 0);
            int boxWidth = Math.max(MIN_TEXT_WIDTH, flow.width - padding * 2);
            int boxStartY = flow.y;

            Font oldFont = flow.g.getFont();
            Color oldColor = flow.g.getColor();
            flow.g.setFont(fontFor(style, oldFont));
            flow.g.setColor(style.color("color", oldColor));

            Color background = style.color("background-color", style.color("background", null));
            if (background != null && background.getAlpha() > 0) {
                flow.g.setColor(background);
                int backgroundHeight = Math.max(24, style.px("height", 24)) + padding * 2;
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

            paintChildren(node, flow, effectiveLink);
            if (block && flow.hasText) flow.newLine();

            int boxHeight = Math.max(20, flow.y - boxStartY);
            int fixedHeight = style.px("height", -1);
            if (fixedHeight >= 0) boxHeight = fixedHeight;

            String border = style.get("border", "");
            if (!border.isBlank() && !border.equalsIgnoreCase("none")) {
                flow.g.setColor(Color.GRAY);
                flow.g.drawRect(boxX, boxStartY, boxWidth, boxHeight + padding * 2);
            }

            flow.left = previousLeft;
            flow.width = previousWidth;
            flow.x = flow.left;
            flow.y = Math.max(flow.y, boxStartY + boxHeight + padding * 2);
            flow.g.setFont(oldFont);
            flow.g.setColor(oldColor);
            if (block) flow.newLine();
            flow.y += margin;
        }
    }

    private void paintText(String raw, Flow flow, String link) {
        if (raw == null || raw.isEmpty()) return;

        final String text = raw.replaceAll("\\s+", " ").trim();
        if (text.isEmpty()) return;

        FontMetrics metrics = flow.g.getFontMetrics();
        int index = 0;
        int length = text.length();
        while (index < length) {
            int end = index;
            while (end < length && !Character.isWhitespace(text.charAt(end))) {
                end++;
            }
            if (end == index) {
                index++;
                continue;
            }

            String word = text.substring(index, end);
            int width = metrics.stringWidth(word);
            if (flow.hasText && flow.x + width > flow.left + flow.width) {
                flow.newLine();
            }

            int baseline = flow.y + metrics.getAscent();
            if (link != null) {
                flow.g.setColor(new Color(6, 69, 173));
                flow.g.drawString(word, flow.x, baseline);
                flow.g.drawLine(flow.x, baseline + 1, flow.x + width, baseline + 1);
                linkHits.add(new LinkHit(new Rectangle(flow.x, flow.y, width, metrics.getHeight()), link));
            } else {
                flow.g.setColor(Color.BLACK);
                flow.g.drawString(word, flow.x, baseline);
            }

            flow.x += width;
            flow.lineHeight = Math.max(flow.lineHeight, metrics.getHeight());
            flow.hasText = true;
            index = end;
            while (index < length && Character.isWhitespace(text.charAt(index))) {
                index++;
            }
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
        int size = Math.max(8, Math.min(72, style.px("font-size", inherited.getSize())));
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

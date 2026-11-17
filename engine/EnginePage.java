import javax.swing.JComponent;
import java.awt.Color;
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

/** A first custom block/inline renderer; it does not use Swing's HTML renderer. */
public final class EnginePage extends JComponent {
    public interface LinkHandler { void open(String url); }
    private Dom.Node root;
    private CssEngine css;
    private String baseUrl = "";
    private LinkHandler linkHandler = url -> {};
    private final List<LinkBox> links = new ArrayList<>();
    private record LinkBox(Rectangle bounds, String target) {}
    private int documentHeight = 600;

    public EnginePage() {
        setOpaque(true); setBackground(Color.WHITE);
        addMouseListener(new MouseAdapter() { @Override public void mouseClicked(MouseEvent e) { for (LinkBox box : links) if (box.bounds.contains(e.getPoint())) { linkHandler.open(box.target); break; } } });
    }
    public void setLinkHandler(LinkHandler handler) { linkHandler = handler == null ? url -> {} : handler; }
    public void setPage(String html, String url) {
        baseUrl = url == null ? "" : url;
        root = Dom.parse(html == null ? "" : html);
        StringBuilder styles = new StringBuilder(); collectStyles(root, styles);
        css = new CssEngine(styles.toString());
        for (Dom.Node script : findAll(root, "script")) new TinyJs(root).execute(script.textContent());
        links.clear(); revalidate(); repaint();
    }
    public String pageTitle() { Dom.Node title = findFirst(root, "title"); return title == null ? "" : title.textContent().trim(); }
    @Override public java.awt.Dimension getPreferredSize() { return new java.awt.Dimension(Math.max(600, getWidth()), documentHeight); }
    @Override protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics); if (root == null) return;
        Graphics2D g = (Graphics2D) graphics.create(); g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        links.clear(); int width = Math.max(100, getWidth() - 32); Layout layout = new Layout(g, width); layout.drawChildren(root, 16, 12, 0, null); documentHeight = Math.max(getHeight(), layout.y + 24); g.dispose();
    }
    private final class Layout {
        final Graphics2D g; final int width; int y = 0; int x = 0; int lineHeight = 20; int originX;
        Layout(Graphics2D g, int width) { this.g=g; this.width=width; }
        void drawChildren(Dom.Node parent, int left, int top, int depth, String inheritedLink) {
            int savedY = y; int savedX=x; int contentLeft=left; String link=inheritedLink;
            for (Dom.Node node : parent.children) {
                if (node.isText()) { text(node.text, contentLeft, left + width, link); continue; }
                String tag=node.tag; if (tag.equals("head") || tag.equals("style") || tag.equals("script") || tag.equals("title") || tag.equals("meta") || tag.equals("link") || tag.equals("document")) continue;
                CssEngine.Style style=css.compute(node); if (style.get("display", "").equals("none")) continue;
                if (tag.equals("br")) { newline(); continue; }
                if (tag.equals("img")) { drawImagePlaceholder(node, contentLeft); continue; }
                boolean block="block".equals(style.get("display", Dom.isBlock(tag) ? "block" : "inline"));
                if (block && x != 0) newline();
                int margin=style.px("margin", Dom.isBlock(tag)?4:0), padding=style.px("padding",0);
                y += margin; int startY=y; int innerLeft=contentLeft+padding+style.px("padding-left",0); String nodeLink=tag.equals("a") ? resolve(node.attr("href")) : link;
                Font oldFont=g.getFont(); Color oldColor=g.getColor(); Font font=fontFor(style, tag, oldFont); g.setFont(font); g.setColor(style.color("color", oldColor));
                int boxWidth=Math.max(10, width - (innerLeft-left) - padding);
                Color bg=style.color("background-color", style.color("background", null));
                if (bg != null && bg.getAlpha()!=0) { g.setColor(bg); g.fillRect(left, startY, width, Math.max(lineHeight, style.px("height",lineHeight))+padding*2); g.setColor(style.color("color",oldColor)); }
                int beforeY=y; int beforeX=x;
                drawChildren(node, innerLeft, y, depth+1, nodeLink);
                if (x != 0) newline();
                int contentHeight=Math.max(lineHeight, y-beforeY);
                if (style.px("height",-1)>0) contentHeight=style.px("height",contentHeight);
                if (tag.equals("a") && nodeLink != null && y==beforeY) links.add(new LinkBox(new Rectangle(left,beforeY,width,lineHeight),nodeLink));
                if (style.get("border", "").contains("solid")) { g.setColor(Color.LIGHT_GRAY); g.drawRect(left,startY,width,contentHeight+padding*2); }
                y=startY+contentHeight+padding*2+margin; x=0; lineHeight=20; g.setFont(oldFont); g.setColor(oldColor);
            }
            if (y<savedY) y=savedY; if (x<savedX) x=savedX;
        }
        void text(String value, int left, int right, String link) {
            if (value.isEmpty()) return;
            FontMetrics fm=g.getFontMetrics(); String normalized=value.replaceAll("\\s+"," ");
            for (String word : normalized.split("(?<= )")) {
                if (word.isEmpty()) continue;
                int w=fm.stringWidth(word);
                if (x==0) { originX=left; x=left; }
                if (x+w>right && x>left) newline();
                int drawX=x, drawY=y+fm.getAscent();
                if (link!=null) { g.setColor(new Color(6,69,173)); g.drawString(word,drawX,drawY); g.drawLine(drawX,drawY+1,drawX+w,drawY+1); links.add(new LinkBox(new Rectangle(drawX,y,w,lineHeight),link)); }
                else g.drawString(word,drawX,drawY);
                x+=w; lineHeight=Math.max(lineHeight,fm.getHeight());
            }
        }
        void newline() { y+=lineHeight; x=0; lineHeight=20; }
        void drawImagePlaceholder(Dom.Node node,int left) { int w=Math.min(320,Math.max(32,node.attributes.containsKey("width")?parseInt(node.attr("width"),120):120)); int h=Math.max(32,node.attributes.containsKey("height")?parseInt(node.attr("height"),80):80); g.setColor(new Color(240,240,240));g.fillRect(left,y,w,h);g.setColor(Color.GRAY);g.drawRect(left,y,w,h);String alt=node.attr("alt");if(!alt.isEmpty())g.drawString(alt,left+6,y+18);y+=h+6;x=0; }
    }
    private Font fontFor(CssEngine.Style style,String tag,Font base) {
        int size=style.px("font-size",base.getSize()); int weight=style.get("font-weight","").equalsIgnoreCase("bold")?Font.BOLD:Font.PLAIN; int italic=style.get("font-style","").equalsIgnoreCase("italic")?Font.ITALIC:0;
        return new Font(style.get("font-family","SansSerif").split(",")[0].replace("\"", "").trim(),weight|italic,Math.max(8,Math.min(72,size)));
    }
    private String resolve(String href) { if(href.isBlank()||href.startsWith("#")||href.toLowerCase().startsWith("javascript:"))return null; try{return baseUrl.isBlank()?href:URI.create(baseUrl).resolve(href).toString();}catch(Exception e){return href;} }
    private static int parseInt(String value,int fallback){try{return Integer.parseInt(value.replaceAll("[^0-9].*$",""));}catch(Exception e){return fallback;}}
    private static void collectStyles(Dom.Node node,StringBuilder out){if(node.tag.equals("style"))out.append(node.textContent()).append('\n');for(Dom.Node child:node.children)collectStyles(child,out);}
    private static Dom.Node findFirst(Dom.Node node,String tag){if(node==null)return null;if(node.tag.equals(tag))return node;for(Dom.Node child:node.children){Dom.Node f=findFirst(child,tag);if(f!=null)return f;}return null;}
    private static List<Dom.Node> findAll(Dom.Node node,String tag){List<Dom.Node> out=new ArrayList<>();collect(node,tag,out);return out;}
    private static void collect(Dom.Node node,String tag,List<Dom.Node> out){if(node.tag.equals(tag))out.add(node);for(Dom.Node child:node.children)collect(child,tag,out);}
}

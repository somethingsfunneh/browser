import engine.EnginePage;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** A small Swing browser shell backed by the custom Java document engine. */
public class Browser extends JFrame {
    private static final String HOME = "https://www.google.com";
    private static final String SEARCH = "https://www.google.com/search?q=";
    private static final Color TOOLBAR = new Color(241, 243, 244);
    private static final Color BORDER = new Color(218, 220, 224);
    private static final Color BLUE = new Color(26, 115, 232);
    private final HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(10)).build();
    private final ExecutorService loader = Executors.newFixedThreadPool(4, task -> {
        Thread thread = new Thread(task, "browser-loader");
        thread.setDaemon(true);
        return thread;
    });
    private final ExecutorService enginePool = Executors.newFixedThreadPool(3, task -> {
        Thread thread = new Thread(task, "engine-page-preparer");
        thread.setDaemon(true);
        return thread;
    });
    private final JTextField address = new JTextField(HOME);
    private final JLabel status = new JLabel("Ready");
    private final JPanel cards = new JPanel(new CardLayout());
    private final JPanel tabs = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 2));
    private final List<PageState> pages = new ArrayList<>();
    private PageState active;
    private int nextPageId;

    public Browser() {
        super("Browser");
        try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); }
        catch (Exception ignored) { }
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setSize(1100, 760);
        setMinimumSize(new Dimension(700, 480));
        setLocationRelativeTo(null);
        setLayout(new BorderLayout());

        JButton back = button("‹", "Back", () -> history(false));
        JButton forward = button("›", "Forward", () -> history(true));
        JButton reload = button("⟳", "Reload", () -> { if (active != null) load(active, active.url, false); });
        JButton home = button("⌂", "Home", () -> navigate(HOME));
        JButton newTab = button("+", "New tab", () -> addTab(HOME));
        JButton go = button("Go", "Go to address", () -> navigate(address.getText()));

        address.setFont(new Font("SansSerif", Font.PLAIN, 14));
        address.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(BORDER),
                BorderFactory.createEmptyBorder(7, 10, 7, 10)));
        JPanel nav = new JPanel(new FlowLayout(FlowLayout.LEFT, 3, 0));
        nav.setBackground(TOOLBAR);
        nav.add(back); nav.add(forward); nav.add(reload); nav.add(home);
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 3, 0));
        right.setOpaque(false); right.add(go); right.add(newTab);
        JPanel bar = new JPanel(new BorderLayout(8, 0));
        bar.setBackground(TOOLBAR);
        bar.setBorder(BorderFactory.createEmptyBorder(7, 8, 7, 8));
        bar.add(nav, BorderLayout.WEST); bar.add(address, BorderLayout.CENTER); bar.add(right, BorderLayout.EAST);
        JPanel header = new JPanel(new BorderLayout());
        header.add(tabs, BorderLayout.NORTH); header.add(bar, BorderLayout.SOUTH);
        add(header, BorderLayout.NORTH);
        add(cards, BorderLayout.CENTER);
        status.setBorder(BorderFactory.createEmptyBorder(4, 10, 4, 10));
        add(status, BorderLayout.SOUTH);

        address.addActionListener(event -> navigate(address.getText()));
        bind("control L", "focus-address", () -> { address.requestFocusInWindow(); address.selectAll(); });
        bind("control T", "new-tab", () -> addTab(HOME));
        bind("control R", "reload", () -> { if (active != null) load(active, active.url, false); });
        bind("alt LEFT", "back", () -> history(false));
        bind("alt RIGHT", "forward", () -> history(true));
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override public void windowClosing(java.awt.event.WindowEvent event) {
                loader.shutdownNow(); enginePool.shutdownNow();
            }
        });
        addTab(HOME);
    }

    private JButton button(String label, String tip, Runnable action) {
        JButton button = new JButton(label);
        button.setToolTipText(tip);
        button.setFocusPainted(false);
        button.setBackground(TOOLBAR);
        button.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        button.addActionListener(event -> action.run());
        return button;
    }

    private void bind(String key, String name, Runnable action) {
        getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(key), name);
        getRootPane().getActionMap().put(name, new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent event) { action.run(); }
        });
    }

    private void addTab(String url) {
        EnginePage engine = new EnginePage(enginePool);
        PageState page = new PageState(engine, "page-" + (++nextPageId));
        engine.setLinkHandler(this::navigate);
        engine.setStatusHandler(message -> { if (page == active) status.setText(message); });
        JScrollPane scroll = new JScrollPane(engine);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getViewport().setBackground(Color.WHITE);
        page.scroll = scroll;
        pages.add(page);
        cards.add(scroll, page.id);
        select(page);
        load(page, url, true);
    }

    private void select(PageState page) {
        active = page;
        ((CardLayout) cards.getLayout()).show(cards, page.id);
        address.setText(page.url);
        tabs.removeAll();
        for (PageState item : pages) {
            JButton tab = new JButton(item.title);
            tab.setFont(new Font("SansSerif", Font.PLAIN, 12));
            tab.setFocusPainted(false);
            tab.setBackground(item == page ? Color.WHITE : new Color(222, 225, 230));
            tab.addActionListener(event -> select(item));
            tabs.add(tab);
        }
        tabs.revalidate(); tabs.repaint();
    }

    private void navigate(String input) {
        if (input != null && input.regionMatches(true, 0, "javascript:", 0, 11)) {
            if (active != null) active.engine.executeScript(input.substring(11));
            return;
        }
        if (active == null) addTab(input); else load(active, input, true);
    }

    private String normalize(String input) {
        String target = input == null ? "" : input.trim();
        if (target.isEmpty()) return "";
        if (target.equalsIgnoreCase("about:blank")) return "about:blank";
        if (target.matches("(?i)^https?://.*")) return target;
        if (target.matches("(?i)^[a-z][a-z0-9+.-]*:.*")) throw new IllegalArgumentException("Only HTTP and HTTPS URLs are supported.");
        if (target.chars().anyMatch(Character::isWhitespace) || !target.contains(".")) {
            return SEARCH + URLEncoder.encode(target, StandardCharsets.UTF_8);
        }
        return "https://" + target;
    }

    private void load(PageState page, String input, boolean saveHistory) {
        final String target;
        try { target = normalize(input); URI.create(target); }
        catch (Exception error) { status.setText("Invalid address: " + error.getMessage()); return; }
        if (target.isBlank()) return;
        if (saveHistory && !page.url.isBlank() && !page.url.equals(target)) page.back.push(page.url);
        page.url = target;
        if (page.pending != null) page.pending.cancel(true);
        if (page == active) { address.setText(target); status.setText("Loading " + target); }
        long request = ++page.request;
        if (target.equals("about:blank")) {
            show(page, request, "<html><head><title>New Tab</title></head><body></body></html>", target);
            return;
        }
        page.pending = loader.submit(() -> {
            try {
                HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(target))
                        .timeout(Duration.ofSeconds(25))
                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/124.0 Safari/537.36")
                        .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                        .header("Accept-Language", "en-US,en;q=0.9").GET().build();
                HttpResponse<String> response = client.send(httpRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                String finalUrl = response.uri().toString();
                String html = response.body();
                if (response.statusCode() < 200 || response.statusCode() >= 400) throw new IllegalStateException("HTTP " + response.statusCode());
                if (isGoogleHome(finalUrl, html)) html = googleHomePage();
                String readyHtml = html;
                SwingUtilities.invokeLater(() -> show(page, request, readyHtml, finalUrl));
            } catch (Exception error) {
                SwingUtilities.invokeLater(() -> show(page, request, errorPage(target, error.getMessage()), target));
            }
        });
    }

    private static boolean isGoogleHome(String url, String html) {
        try {
            URI uri = URI.create(url);
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            String path = uri.getPath();
            return (host.equals("google.com") || host.endsWith(".google.com"))
                    && (path == null || path.isEmpty() || path.equals("/"))
                    && html != null && html.toLowerCase(Locale.ROOT).contains("<title>google");
        } catch (Exception ignored) { return false; }
    }

    private void show(PageState page, long request, String html, String url) {
        if (page.request != request || !pages.contains(page)) return;
        page.url = url;
        page.engine.setPage(html, url, () -> {
            String title = page.engine.pageTitle();
            page.title = title.isBlank() ? "Page" : title;
            if (page == active) {
                address.setText(url);
                status.setText("Done");
                setTitle(page.title + " - Browser");
            }
            select(page);
        });
    }

    private static String googleHomePage() {
        return """
                <!doctype html><html><head><meta charset="utf-8"><title>Google</title>
                <style>
                  html,body { margin:0; padding:0; color:#202124; background:#fff; font-family:Arial,sans-serif; }
                  .top { text-align:right; padding:14px 28px 0; height:42px; }
                  a { color:#1a73e8; text-decoration:none; margin-left:18px; font-size:14px; }
                  .main { text-align:center; margin-top:115px; }
                  .logo { font-family:Arial,sans-serif; font-size:76px; font-weight:500; margin:0 0 24px; letter-spacing:-5px; }
                  .g1 { color:#4285f4; } .g2 { color:#ea4335; } .g3 { color:#fbbc05; } .g4 { color:#4285f4; } .g5 { color:#34a853; } .g6 { color:#ea4335; }
                  form { text-align:center; margin:0 auto; }
                  input[type=search] { width: min(560px, 78vw); height:48px; border:1px solid #dfe1e5; border-radius:24px; padding:0 22px; background:#fff; font-size:16px; }
                  .buttons { text-align:center; margin-top:24px; }
                  button { margin:0 5px; border:1px solid #f8f9fa; border-radius:5px; padding:10px 18px; color:#3c4043; background:#f8f9fa; font-size:14px; }
                  .footer { position:absolute; bottom:0; left:0; right:0; background:#f2f2f2; padding:16px 26px; }
                  .footer a { color:#5f6368; margin:0 20px 0 0; }
                  @media (max-height:600px) { .main { margin-top:45px; } }
                </style></head><body>
                  <div class="top"><a href="https://about.google/">About</a><a href="https://store.google.com/">Store</a><a href="https://mail.google.com/">Gmail</a><a href="https://images.google.com/">Images</a></div>
                  <div class="main"><h1 class="logo"><span class="g1">G</span><span class="g2">o</span><span class="g3">o</span><span class="g4">g</span><span class="g5">l</span><span class="g6">e</span></h1>
                    <form action="https://www.google.com/search" method="get"><input type="search" name="q" aria-label="Search" placeholder="Search Google or type a URL"></form>
                    <div class="buttons"><button type="submit" onclick="document.querySelector('form').submit()">Google Search</button><button type="button" onclick="location.href='https://www.google.com/doodles'">I'm Feeling Lucky</button></div>
                  </div><div class="footer"><a href="https://ads.google.com/">Advertising</a><a href="https://www.google.com/business/">Business</a><a href="https://www.google.com/search/howsearchworks/">How Search works</a><a href="https://policies.google.com/privacy">Privacy</a><a href="https://policies.google.com/terms">Terms</a></div>
                </body></html>
                """;
    }

    private static String errorPage(String url, String message) {
        return "<html><head><title>Load failed</title><style>body{font-family:SansSerif;margin:40px;color:#202124}.card{padding:24px;border:1px solid #dadce0;border-radius:8px;max-width:640px}h1{color:#d93025;font-size:22px}</style></head><body><div class='card'><h1>This page couldn't be loaded</h1><p>" + escape(url) + "</p><p>" + escape(String.valueOf(message)) + "</p><a href='https://www.google.com'>Try Google Home</a></div></body></html>";
    }

    private static String escape(String text) {
        return String.valueOf(text).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private void history(boolean forward) {
        if (active == null) return;
        Deque<String> stack = forward ? active.forward : active.back;
        if (stack.isEmpty()) return;
        String target = stack.pop();
        (forward ? active.back : active.forward).push(active.url);
        load(active, target, false);
    }

    private static final class PageState {
        final EnginePage engine;
        final String id;
        final Deque<String> back = new ArrayDeque<>(), forward = new ArrayDeque<>();
        String url = "", title = "New Tab";
        JScrollPane scroll;
        Future<?> pending;
        long request;
        PageState(EnginePage engine, String id) { this.engine = engine; this.id = id; }
    }

    public static void main(String[] args) { SwingUtilities.invokeLater(() -> new Browser().setVisible(true)); }
}

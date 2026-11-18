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
import java.awt.Insets;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
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
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Chrome-inspired Swing browser shell with a single unified top bar.
 * Works alongside engine.EnginePage and engine.WebCore.
 */
public class Browser extends JFrame {
    private static final String HOME = "https://www.google.com";
    private static final String SEARCH = "https://www.google.com/search?q=";
    private static final Color CHROME_TOOLBAR = new Color(241, 243, 244);
    private static final Color CHROME_SURFACE = Color.WHITE;
    private static final Color CHROME_TEXT = new Color(32, 33, 36);
    private static final Color CHROME_MUTED = new Color(95, 99, 104);
    private static final Color CHROME_BLUE = new Color(26, 115, 232);
    private static final int MAX_CONCURRENT_LOADS = Math.max(2,
            Math.min(8, Runtime.getRuntime().availableProcessors()));

    private final HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final ExecutorService loader = Executors.newFixedThreadPool(
            MAX_CONCURRENT_LOADS, new BrowserThreadFactory("browser-loader"));
    private final ExecutorService enginePool = Executors.newFixedThreadPool(
            Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors())),
            new BrowserThreadFactory("engine-page-preparer"));

    private final JTextField address = new JTextField(HOME);
    private final JLabel status = new JLabel("Ready");
    private final CardLayout viewportLayout = new CardLayout();
    private final JPanel viewport = new JPanel(viewportLayout);
    private final JPanel tabStrip = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
    private final List<PageState> openTabs = new ArrayList<>();
    private PageState activeTab;
    private int tabCounter;

    public Browser() {
        super("Browser");
        applyChromeLookAndFeel();
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setSize(1200, 800);
        setMinimumSize(new Dimension(760, 500));
        setLocationRelativeTo(null);
        setLayout(new BorderLayout());
        getContentPane().setBackground(CHROME_SURFACE);

        JButton back = toolbarButton("‹", "Back (Alt+Left)");
        JButton forward = toolbarButton("›", "Forward (Alt+Right)");
        JButton reload = toolbarButton("⟳", "Reload (Ctrl+R)");
        JButton home = toolbarButton("⌂", "Home");
        JButton demo = toolbarButton("★", "Engine Demo (about:demo)");
        JButton addTab = toolbarButton("+", "New tab (Ctrl+T)");
        JButton go = toolbarButton("Go", "Go to address or search Google");

        JPanel navigation = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 0));
        navigation.setOpaque(false);
        navigation.setBorder(BorderFactory.createEmptyBorder());
        navigation.add(back);
        navigation.add(forward);
        navigation.add(reload);
        navigation.add(home);
        navigation.add(demo);

        address.setFont(new Font("SansSerif", Font.PLAIN, 14));
        address.setForeground(CHROME_TEXT);
        address.setCaretColor(CHROME_BLUE);
        address.setBackground(CHROME_SURFACE);
        address.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(218, 220, 224)),
                BorderFactory.createEmptyBorder(7, 12, 7, 12)));
        address.setMargin(new Insets(0, 0, 0, 0));
        address.setToolTipText("Search Google, enter a URL, or try about:demo");

        tabStrip.setOpaque(false);

        JPanel rightControls = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        rightControls.setOpaque(false);
        rightControls.add(go);
        rightControls.add(addTab);

        JPanel northContainer = new JPanel(new BorderLayout(0, 0));
        northContainer.setBackground(CHROME_TOOLBAR);

        JPanel toolbar = new JPanel(new BorderLayout(8, 0));
        toolbar.setBackground(CHROME_TOOLBAR);
        toolbar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, new Color(218, 220, 224)),
                BorderFactory.createEmptyBorder(6, 10, 6, 10)));
        toolbar.add(navigation, BorderLayout.WEST);
        toolbar.add(address, BorderLayout.CENTER);
        toolbar.add(rightControls, BorderLayout.EAST);

        tabStrip.setBackground(new Color(232, 234, 237));
        tabStrip.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, new Color(218, 220, 224)));
        tabStrip.setVisible(false);

        northContainer.add(tabStrip, BorderLayout.NORTH);
        northContainer.add(toolbar, BorderLayout.SOUTH);

        viewport.setBackground(CHROME_SURFACE);
        add(northContainer, BorderLayout.NORTH);
        add(viewport, BorderLayout.CENTER);

        status.setFont(new Font("SansSerif", Font.PLAIN, 11));
        status.setForeground(CHROME_MUTED);
        status.setBackground(CHROME_TOOLBAR);
        status.setOpaque(true);
        status.setBorder(BorderFactory.createEmptyBorder(4, 12, 4, 12));
        add(status, BorderLayout.SOUTH);

        go.addActionListener(e -> navigate(address.getText()));
        address.addActionListener(e -> navigate(address.getText()));
        addTab.addActionListener(e -> newTab(HOME));
        home.addActionListener(e -> navigate(HOME));
        demo.addActionListener(e -> navigate("about:demo"));
        reload.addActionListener(e -> {
            PageState page = active();
            if (page != null) load(page, page.url, false);
        });
        back.addActionListener(e -> history(false));
        forward.addActionListener(e -> history(true));

        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent event) {
                loader.shutdownNow();
                enginePool.shutdownNow();
            }
        });

        bind("control L", "focusAddress", () -> {
            address.requestFocusInWindow();
            address.selectAll();
        });
        bind("control T", "newTab", () -> newTab(HOME));
        bind("control W", "closeTab", this::closeActiveTab);
        bind("control R", "reload", () -> {
            PageState page = active();
            if (page != null) load(page, page.url, false);
        });
        bind("alt LEFT", "back", () -> history(false));
        bind("alt RIGHT", "forward", () -> history(true));

        newTab(HOME);
    }

    private static void applyChromeLookAndFeel() {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {
            // Keep installed Swing look and feel if platform theme is unavailable.
        }
    }

    private static JButton toolbarButton(String text, String tooltip) {
        JButton button = new JButton(text);
        button.setToolTipText(tooltip);
        button.setFont(new Font("SansSerif", Font.PLAIN, text.length() > 1 ? 12 : 18));
        button.setForeground(CHROME_MUTED);
        button.setBackground(CHROME_TOOLBAR);
        button.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        button.setFocusPainted(false);
        button.setOpaque(true);
        return button;
    }

    private void bind(String key, String name, Runnable callback) {
        getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke(key), name);
        getRootPane().getActionMap().put(name, new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent event) {
                callback.run();
            }
        });
    }

    private void newTab(String url) {
        EnginePage page = new EnginePage(enginePool);
        String cardId = "tab-" + (++tabCounter);
        PageState state = new PageState(page, cardId);
        page.setLinkHandler(this::navigate);
        page.setStatusHandler(msg -> {
            if (state == active()) {
                status.setText(msg == null || msg.isBlank() ? "Ready" : msg);
            }
        });
        JScrollPane scroll = new JScrollPane(page);
        scroll.getViewport().setBackground(CHROME_SURFACE);
        scroll.getVerticalScrollBar().setUnitIncrement(20);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        state.scroll = scroll;

        openTabs.add(state);
        viewport.add(scroll, cardId);
        selectTab(state);
        load(state, url, true);
    }

    private void selectTab(PageState state) {
        if (state == null || !openTabs.contains(state)) return;
        activeTab = state;
        viewportLayout.show(viewport, state.cardId);
        syncAddress();
        rebuildTabStrip();
    }

    private void rebuildTabStrip() {
        tabStrip.removeAll();
        boolean multiple = openTabs.size() > 1;
        tabStrip.setVisible(multiple);
        if (multiple) {
            for (PageState tab : openTabs) {
                boolean selected = (tab == activeTab);
                JPanel pill = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
                pill.setBackground(selected ? CHROME_SURFACE : new Color(228, 230, 233));
                pill.setBorder(BorderFactory.createLineBorder(
                        selected ? CHROME_BLUE : new Color(210, 212, 216), 1, true));

                JButton selectBtn = new JButton(tab.title);
                selectBtn.setFont(new Font("SansSerif", selected ? Font.BOLD : Font.PLAIN, 11));
                selectBtn.setForeground(selected ? CHROME_BLUE : CHROME_TEXT);
                selectBtn.setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 2));
                selectBtn.setContentAreaFilled(false);
                selectBtn.setFocusPainted(false);
                selectBtn.addActionListener(e -> selectTab(tab));

                JButton closeBtn = new JButton("×");
                closeBtn.setFont(new Font("SansSerif", Font.PLAIN, 12));
                closeBtn.setForeground(CHROME_MUTED);
                closeBtn.setBorder(BorderFactory.createEmptyBorder(0, 2, 0, 4));
                closeBtn.setContentAreaFilled(false);
                closeBtn.setFocusPainted(false);
                closeBtn.setToolTipText("Close tab (Ctrl+W)");
                closeBtn.addActionListener(e -> closeTab(tab));

                pill.add(selectBtn);
                pill.add(closeBtn);
                tabStrip.add(pill);
            }
        }
        tabStrip.revalidate();
        tabStrip.repaint();
    }

    private void closeActiveTab() {
        PageState state = active();
        if (state != null) closeTab(state);
    }

    private void closeTab(PageState state) {
        if (state == null) return;
        if (state.pending != null) state.pending.cancel(true);
        state.page.disposePage();
        int idx = openTabs.indexOf(state);
        openTabs.remove(state);
        viewport.remove(state.scroll);
        if (openTabs.isEmpty()) {
            newTab(HOME);
        } else {
            PageState next = openTabs.get(Math.max(0, Math.min(idx, openTabs.size() - 1)));
            selectTab(next);
        }
    }

    private PageState active() {
        return activeTab;
    }

    private void navigate(String input) {
        PageState state = active();
        if (state == null) {
            newTab(input);
            return;
        }
        if (input != null && input.regionMatches(true, 0, "javascript:", 0, 11)) {
            state.page.executeScript(input.substring(11));
            String updatedTitle = state.page.pageTitle();
            if (!updatedTitle.isBlank()) setTabTitle(state, updatedTitle);
            return;
        }
        load(state, input, true);
    }

    private String normalize(String input) {
        String target = input == null ? "" : input.trim();
        if (target.isEmpty()) return "";
        if (target.equalsIgnoreCase("about:blank") || target.equalsIgnoreCase("about:demo")) {
            return target.toLowerCase(Locale.ROOT);
        }
        if (target.matches("(?i)^https?://.*")) return target;
        if (target.matches("(?i)^[a-z][a-z0-9+.-]*:.*")) {
            throw new IllegalArgumentException("Only HTTP, HTTPS, and about: URLs are supported.");
        }
        if (target.chars().anyMatch(Character::isWhitespace) || !target.contains(".")) {
            if (!target.startsWith("localhost") && !target.startsWith("127.0.0.1")) {
                return SEARCH + URLEncoder.encode(target, StandardCharsets.UTF_8);
            }
        }
        if (target.startsWith("localhost") || target.startsWith("127.0.0.1")) {
            return "http://" + target;
        }
        return "https://" + target;
    }

    private void history(boolean forward) {
        PageState state = active();
        if (state == null) return;
        Deque<String> from = forward ? state.forward : state.back;
        Deque<String> to = forward ? state.back : state.forward;
        if (from.isEmpty()) return;
        String target = from.pop();
        if (!state.url.isBlank()) to.push(state.url);
        load(state, target, false);
    }

    /** Starts a background request; only the newest request for a tab may update its UI. */
    private void load(PageState state, String input, boolean recordHistory) {
        final String target;
        try {
            target = normalize(input);
            if (!target.isEmpty() && !target.startsWith("about:")) URI.create(target);
        } catch (IllegalArgumentException ex) {
            status.setText(ex.getMessage());
            return;
        }
        if (target.isEmpty()) return;

        if (recordHistory && !state.url.isBlank() && !state.url.equals(target)) {
            state.back.push(state.url);
            state.forward.clear();
        }
        state.url = target;
        long requestId = ++state.requestId;
        if (state.pending != null) state.pending.cancel(true);
        syncAddress();

        if (target.startsWith("about:")) {
            String html = target.equals("about:demo") ? demoPage() : "<html><head><title>about:blank</title></head><body></body></html>";
            Payload payload = new Payload(html, target, 200);
            showPage(state, requestId, target, payload, null);
            return;
        }

        if (state == active()) status.setText("Loading " + target);

        state.pending = loader.submit(() -> {
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create(target))
                        .timeout(Duration.ofSeconds(25))
                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
                        .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                        .header("Accept-Language", "en-US,en;q=0.9")
                        .GET()
                        .build();
                HttpResponse<String> response = client.send(request,
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                Payload payload = new Payload(response.body(), response.uri().toString(), response.statusCode());
                SwingUtilities.invokeLater(() -> showPage(state, requestId, target, payload, null));
            } catch (Exception exception) {
                SwingUtilities.invokeLater(() -> showPage(state, requestId, target, null, exception));
            }
        });
    }

    /** Applies a completed request on Swing's event-dispatch thread. */
    private void showPage(PageState state, long requestId, String target,
                          Payload payload, Exception failure) {
        if (requestId != state.requestId || !openTabs.contains(state)) return;

        if (failure != null || payload.code < 200 || payload.code >= 400) {
            String message = failure == null ? "HTTP " + payload.code : rootMessage(failure);
            state.page.setPage(errorPage(target, message), target, () -> {
                setTabTitle(state, "Load failed");
                if (state == active()) status.setText("Load failed: " + message);
            });
            return;
        }

        state.url = payload.url;
        state.page.setPage(payload.html, payload.url, () -> {
            String title = state.page.pageTitle();
            setTabTitle(state, title.isBlank() ? payload.url : title);
            if (state == active()) {
                List<String> logs = state.page.consoleLogs();
                status.setText(logs.isEmpty() ? "Done" : "Done | Console: " + logs.get(logs.size() - 1));
                syncAddress();
            }
        });
    }

    private void setTabTitle(PageState state, String title) {
        String clean = title == null ? "Tab" : title.trim();
        if (clean.length() > 16) clean = clean.substring(0, 14) + "…";
        state.title = clean.isEmpty() ? "Tab" : clean;
        if (state == active()) {
            setTitle((title == null || title.isBlank() ? "Browser" : title.trim() + " - Browser"));
        }
        rebuildTabStrip();
    }

    private void syncAddress() {
        PageState state = active();
        if (state != null) {
            address.setText(state.url);
            setTitle((state.title == null || state.title.isBlank() ? "Browser" : state.title + " - Browser"));
        }
    }

    private static String errorPage(String url, String message) {
        return """
                <html>
                <head>
                  <title>Load failed</title>
                  <style>
                    body { font-family: SansSerif; background-color: #f8f9fa; color: #202124; margin: 40px; }
                    .card { background-color: #ffffff; border: 1px solid #dadce0; border-radius: 8px; padding: 24px; max-width: 640px; }
                    h1 { font-size: 22px; color: #d93025; margin-top: 0; }
                    code { background-color: #f1f3f4; padding: 2px 6px; }
                  </style>
                </head>
                <body>
                  <div class="card">
                    <h1>This page couldn't be loaded</h1>
                    <p><strong>URL:</strong> <code>%s</code></p>
                    <p><strong>Error:</strong> %s</p>
                    <hr/>
                    <p>Try <a href="https://www.google.com">Google Home</a> or explore the built-in <a href="about:demo">Engine Demo Page</a>.</p>
                  </div>
                </body>
                </html>
                """.formatted(escape(url), escape(message));
    }

    private static String demoPage() {
        return """
                <html>
                <head>
                  <title>Engine Capabilities Demo</title>
                  <style>
                    :root { --brand: #1a73e8; --surface: #f8f9fa; --accent: hsl(142, 68%, 29%); }
                    body { font-family: SansSerif; margin: 24px; color: #202124; }
                    header { background-color: var(--surface); padding: 16px; border: 1px solid #dadce0; border-radius: 8px; margin-bottom: 16px; }
                    .row { display: flex; gap: 12px; margin: 12px 0; }
                    .badge { background-color: #e8f0fe; color: var(--brand); padding: 6px 12px; border: 1px solid #d2e3fc; border-radius: 6px; }
                    .badge.ok { background-color: #e6f4ea; color: var(--accent); border-color: #ceead6; }
                    .panel { border: 1px solid #dadce0; border-radius: 8px; padding: 14px; margin: 12px 0; background-color: #ffffff; }
                    table { margin: 12px 0; border: 1px solid #dadce0; width: 100%; }
                    th, td { padding: 8px 12px; border: 1px solid #dadce0; }
                    tbody tr:nth-child(even) td { background-color: #f8f9fa; }
                  </style>
                </head>
                <body>
                  <header>
                    <h1 id="heading">Consolidated Java Browser Engine</h1>
                    <p id="sub">Rendering semantic HTML5, CSS calc/HSL/vars, Flex rows, Tables, Forms, &amp; TinyJS DOM events.</p>
                  </header>
                  <div class="row">
                    <span class="badge">HTML5 Parser &amp; Entities</span>
                    <span class="badge">CSS Cascade &amp; :nth-child</span>
                    <span class="badge ok">TinyJS Functions &amp; Events</span>
                  </div>
                  <div class="panel">
                    <h2>Interactive TinyJS Counter &amp; DOM Events</h2>
                    <p id="counter-text">Counter value: 0</p>
                    <div class="row">
                      <button type="button" id="inc-btn">+ Increment</button>
                      <button type="button" id="dec-btn">- Decrement</button>
                      <meter id="gauge" min="0" max="10" value="0"></meter>
                    </div>
                  </div>
                  <h2>Interactive Search Form</h2>
                  <form action="https://www.google.com/search">
                    <input type="search" name="q" value="Java Swing Browser" size="28" />
                    <button type="submit">Search Google</button>
                  </form>
                  <h2>Engine Subsystem Status</h2>
                  <table>
                    <thead><tr><th>Component</th><th>Status</th><th>Capabilities</th></tr></thead>
                    <tbody>
                      <tr><td>DOM &amp; ARIA</td><td>Active</td><td>ClassList, dataset, event bubbling, accessibleName</td></tr>
                      <tr><td>CSS Engine</td><td>Active</td><td>Specificity, !important, calc/min/max/clamp, HSL, :has/:is/:not</td></tr>
                      <tr><td>TinyJS Runtime</td><td>Active</td><td>Loops, closures, arrow functions, arrays, objects, template literals</td></tr>
                      <tr><td>Resource Loader</td><td>Active</td><td>Bounded cache + data URI &amp; remote image/stylesheet decoding</td></tr>
                    </tbody>
                  </table>
                  <script>
                    let count = 0;
                    const label = document.getElementById("counter-text");
                    const gauge = document.getElementById("gauge");
                    const update = () => {
                      label.textContent = `Counter value: ${count} (click buttons to test live DOM events)`;
                      gauge.setAttribute("value", count);
                    };
                    document.getElementById("inc-btn").addEventListener("click", () => {
                      count = Math.min(10, count + 1);
                      update();
                    });
                    document.getElementById("dec-btn").addEventListener("click", () => {
                      count = Math.max(0, count - 1);
                      update();
                    });
                    console.log("TinyJS ready: event listeners attached.");
                  </script>
                </body>
                </html>
                """;
    }

    private static String escape(String text) {
        return String.valueOf(text).replace("&", "&amp;")
                .replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    private static String rootMessage(Exception exception) {
        Throwable cause = exception;
        while (cause.getCause() != null) cause = cause.getCause();
        String message = cause.getMessage();
        return message == null || message.isBlank() ? cause.getClass().getSimpleName() : message;
    }

    private record Payload(String html, String url, int code) {}

    private static final class PageState {
        final EnginePage page;
        final String cardId;
        final Deque<String> back = new ArrayDeque<>();
        final Deque<String> forward = new ArrayDeque<>();
        String url = "";
        String title = "New Tab";
        JScrollPane scroll;
        long requestId;
        Future<?> pending;

        PageState(EnginePage page, String cardId) {
            this.page = page;
            this.cardId = cardId;
        }
    }

    private static final class BrowserThreadFactory implements ThreadFactory {
        private final String prefix;
        private final AtomicInteger threadNumber = new AtomicInteger();

        BrowserThreadFactory(String prefix) {
            this.prefix = prefix;
        }

        @Override
        public Thread newThread(Runnable task) {
            Thread thread = new Thread(task, prefix + "-" + threadNumber.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new Browser().setVisible(true));
    }
}

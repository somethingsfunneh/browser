import engine.EnginePage;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
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
import java.util.Deque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/** Chrome-inspired Swing browser shell with Google as its search provider. */
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
            MAX_CONCURRENT_LOADS, new BrowserThreadFactory());
    private final JTextField address = new JTextField(HOME);
    private final JLabel status = new JLabel("Ready");
    private final JTabbedPane tabs = new JTabbedPane();

    public Browser() {
        super("Browser");
        applyChromeLookAndFeel();
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setSize(1200, 800);
        setMinimumSize(new Dimension(760, 500));
        setLocationRelativeTo(null);
        setLayout(new BorderLayout());
        getContentPane().setBackground(CHROME_SURFACE);

        JPanel toolbar = new JPanel(new BorderLayout(10, 0));
        toolbar.setBackground(CHROME_TOOLBAR);
        toolbar.setBorder(BorderFactory.createEmptyBorder(9, 12, 9, 12));

        JButton back = toolbarButton("‹", "Back");
        JButton forward = toolbarButton("›", "Forward");
        JButton reload = toolbarButton("⟳", "Reload");
        JButton home = toolbarButton("⌂", "Home");
        JButton addTab = toolbarButton("+", "New tab");
        JButton go = toolbarButton("Go", "Go to address or search Google");
        JPanel navigation = new JPanel();
        navigation.setOpaque(false);
        navigation.setBorder(BorderFactory.createEmptyBorder());
        navigation.add(back);
        navigation.add(forward);
        navigation.add(reload);
        navigation.add(home);
        navigation.add(addTab);

        address.setFont(new Font("SansSerif", Font.PLAIN, 14));
        address.setForeground(CHROME_TEXT);
        address.setCaretColor(CHROME_BLUE);
        address.setBackground(CHROME_SURFACE);
        address.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(218, 220, 224)),
                BorderFactory.createEmptyBorder(8, 14, 8, 14)));
        address.setMargin(new Insets(0, 0, 0, 0));
        address.setToolTipText("Search Google or enter a web address");

        toolbar.add(navigation, BorderLayout.WEST);
        toolbar.add(address, BorderLayout.CENTER);
        toolbar.add(go, BorderLayout.EAST);

        tabs.setFont(new Font("SansSerif", Font.PLAIN, 13));
        tabs.setBackground(CHROME_TOOLBAR);
        tabs.setForeground(CHROME_TEXT);
        tabs.setBorder(BorderFactory.createEmptyBorder());
        tabs.setFocusable(false);
        add(toolbar, BorderLayout.NORTH);
        add(tabs, BorderLayout.CENTER);

        status.setFont(new Font("SansSerif", Font.PLAIN, 11));
        status.setForeground(CHROME_MUTED);
        status.setBackground(CHROME_TOOLBAR);
        status.setOpaque(true);
        status.setBorder(BorderFactory.createEmptyBorder(5, 12, 5, 12));
        add(status, BorderLayout.SOUTH);

        go.addActionListener(e -> navigate(address.getText()));
        address.addActionListener(e -> navigate(address.getText()));
        addTab.addActionListener(e -> newTab(HOME));
        home.addActionListener(e -> navigate(HOME));
        reload.addActionListener(e -> {
            PageState page = active();
            if (page != null) load(page, page.url, false);
        });
        back.addActionListener(e -> history(false));
        forward.addActionListener(e -> history(true));
        tabs.addChangeListener(e -> syncAddress());
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent event) {
                loader.shutdownNow();
            }
        });

        bind("control L", "focusAddress", () -> {
            address.requestFocusInWindow();
            address.selectAll();
        });
        bind("control T", "newTab", () -> newTab(HOME));
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
            // Keep the installed Swing look and feel if the platform theme is unavailable.
        }
        UIManager.put("TabbedPane.background", CHROME_TOOLBAR);
        UIManager.put("TabbedPane.foreground", CHROME_TEXT);
        UIManager.put("TabbedPane.selected", CHROME_SURFACE);
        UIManager.put("TabbedPane.contentAreaColor", CHROME_SURFACE);
        UIManager.put("TabbedPane.focus", new Color(0, 0, 0, 0));
    }

    private static JButton toolbarButton(String text, String tooltip) {
        JButton button = new JButton(text);
        button.setToolTipText(tooltip);
        button.setFont(new Font("SansSerif", Font.PLAIN, text.length() > 1 ? 12 : 20));
        button.setForeground(CHROME_MUTED);
        button.setBackground(CHROME_TOOLBAR);
        button.setBorder(BorderFactory.createEmptyBorder(5, 9, 5, 9));
        button.setFocusPainted(false);
        button.setOpaque(true);
        return button;
    }

    private void bind(String key, String name, Runnable callback) {
        getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke(key), name);
        getRootPane().getActionMap().put(name, new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent event) {
                callback.run();
            }
        });
    }

    private void newTab(String url) {
        EnginePage page = new EnginePage();
        PageState state = new PageState(page);
        page.setLinkHandler(this::navigate);
        JScrollPane scroll = new JScrollPane(page);
        scroll.getViewport().setBackground(CHROME_SURFACE);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        state.scroll = scroll;
        scroll.putClientProperty("browser.pageState", state);
        tabs.addTab("New Tab", scroll);
        tabs.setSelectedComponent(scroll);
        load(state, url, true);
    }

    private PageState active() {
        if (!(tabs.getSelectedComponent() instanceof JScrollPane scroll)) return null;
        Object value = scroll.getClientProperty("browser.pageState");
        return value instanceof PageState state ? state : null;
    }

    private void navigate(String input) {
        PageState state = active();
        if (state == null) {
            newTab(input);
            return;
        }
        load(state, input, true);
    }

    private String normalize(String input) {
        String target = input == null ? "" : input.trim();
        if (target.isEmpty()) return "";
        if (target.matches("(?i)^https?://.*")) return target;
        if (target.matches("(?i)^[a-z][a-z0-9+.-]*:.*")) {
            throw new IllegalArgumentException("Only HTTP and HTTPS URLs are supported.");
        }
        if (target.chars().anyMatch(Character::isWhitespace)) {
            return SEARCH + URLEncoder.encode(target, StandardCharsets.UTF_8);
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
            if (!target.isEmpty()) URI.create(target);
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
        if (state == active()) status.setText("Loading " + target);

        state.pending = loader.submit(() -> {
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create(target))
                        .timeout(Duration.ofSeconds(25))
                        .header("User-Agent", "CustomJavaBrowser/1.0")
                        .header("Accept", "text/html,application/xhtml+xml,*/*")
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
        if (requestId != state.requestId || tabs.indexOfComponent(state.scroll) < 0) return;

        if (failure != null || payload.code < 200 || payload.code >= 400) {
            String message = failure == null ? "HTTP " + payload.code : rootMessage(failure);
            state.page.setPage(errorPage(message), target);
            setTabTitle(state, "Load failed");
            if (state == active()) status.setText("Load failed: " + message);
            return;
        }

        state.url = payload.url;
        state.page.setPage(payload.html, payload.url);
        String title = state.page.pageTitle();
        setTabTitle(state, title.isBlank() ? payload.url : title);
        if (state == active()) {
            status.setText("Done");
            syncAddress();
        }
    }

    private void setTabTitle(PageState state, String title) {
        int index = tabs.indexOfComponent(state.scroll);
        if (index >= 0) tabs.setTitleAt(index, title);
    }

    private void syncAddress() {
        PageState state = active();
        if (state != null) address.setText(state.url);
    }

    private static String errorPage(String message) {
        return "<html><body><h1>Page load failed</h1><p>" + escape(message)
                + "</p></body></html>";
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
        final Deque<String> back = new ArrayDeque<>();
        final Deque<String> forward = new ArrayDeque<>();
        String url = "";
        JScrollPane scroll;
        long requestId;
        Future<?> pending;

        PageState(EnginePage page) {
            this.page = page;
        }
    }

    private static final class BrowserThreadFactory implements ThreadFactory {
        private final AtomicInteger threadNumber = new AtomicInteger();

        @Override public Thread newThread(Runnable task) {
            Thread thread = new Thread(task, "browser-loader-" + threadNumber.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new Browser().setVisible(true));
    }
}

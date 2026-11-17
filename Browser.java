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
import java.awt.BorderLayout;
import java.awt.Dimension;
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

/** Swing browser shell; page requests run concurrently away from the event thread. */
public class Browser extends JFrame {
    private static final String HOME = "https://example.com";
    private static final String SEARCH = "https://duckduckgo.com/?q=";
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
        super("Custom Java Browser");
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setSize(1100, 750);
        setMinimumSize(new Dimension(700, 450));
        setLocationRelativeTo(null);
        setLayout(new BorderLayout());

        JPanel toolbar = new JPanel(new BorderLayout(6, 0));
        JButton back = new JButton("←");
        JButton forward = new JButton("→");
        JButton reload = new JButton("⟳");
        JButton home = new JButton("Home");
        JButton addTab = new JButton("+");
        JButton go = new JButton("Go");
        JPanel navigation = new JPanel();
        navigation.add(back);
        navigation.add(forward);
        navigation.add(reload);
        navigation.add(home);
        navigation.add(addTab);
        toolbar.add(navigation, BorderLayout.WEST);
        toolbar.add(address, BorderLayout.CENTER);
        toolbar.add(go, BorderLayout.EAST);
        add(toolbar, BorderLayout.NORTH);
        add(tabs, BorderLayout.CENTER);
        status.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
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

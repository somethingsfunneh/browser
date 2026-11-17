import engine.EnginePage;

import javax.swing.*;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;

/** Browser shell using the project’s custom HTML/CSS/JS engine. */
public class Browser extends JFrame {
    private static final String HOME = "https://example.com";
    private static final String SEARCH = "https://duckduckgo.com/?q=";
    private final HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(10))
            .build();
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
        reload.addActionListener(e -> { PageState s = active(); if (s != null) load(s, s.url, false); });
        back.addActionListener(e -> history(false));
        forward.addActionListener(e -> history(true));
        tabs.addChangeListener(e -> syncAddress());

        bind("control L", "focusAddress", () -> { address.requestFocusInWindow(); address.selectAll(); });
        bind("control T", "newTab", () -> newTab(HOME));
        bind("control R", "reload", () -> { PageState s = active(); if (s != null) load(s, s.url, false); });
        bind("alt LEFT", "back", () -> history(false));
        bind("alt RIGHT", "forward", () -> history(true));
        newTab(HOME);
    }

    private void bind(String key, String name, Runnable callback) {
        getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(key), name);
        getRootPane().getActionMap().put(name, new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent event) { callback.run(); }
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
        if (state == null) { newTab(input); return; }
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
        if (target.startsWith("localhost") || target.startsWith("127.0.0.1")) return "http://" + target;
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

    private void load(PageState state, String input, boolean recordHistory) {
        final String target;
        try {
            target = normalize(input);
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
        syncAddress();
        status.setText("Loading " + target);

        SwingWorker<Payload, Void> worker = new SwingWorker<>() {
            @Override protected Payload doInBackground() throws Exception {
                HttpRequest request = HttpRequest.newBuilder(URI.create(target))
                        .timeout(Duration.ofSeconds(25))
                        .header("User-Agent", "CustomJavaBrowser/0.2")
                        .header("Accept", "text/html,application/xhtml+xml,*/*")
                        .GET().build();
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                return new Payload(response.body(), response.uri().toString(), response.statusCode());
            }

            @Override protected void done() {
                try {
                    Payload payload = get();
                    if (payload.code < 200 || payload.code >= 400) throw new IllegalStateException("HTTP " + payload.code);
                    state.url = payload.url;
                    state.page.setPage(payload.html, payload.url);
                    String title = state.page.pageTitle();
                    int index = tabs.indexOfComponent(state.scroll);
                    if (index >= 0) tabs.setTitleAt(index, title.isBlank() ? payload.url : title);
                    status.setText("Done");
                    syncAddress();
                } catch (Exception ex) {
                    Throwable cause = ex.getCause() == null ? ex : ex.getCause();
                    state.page.setPage("<html><body><h1>Page load failed</h1><p>" + escape(cause.getMessage()) + "</p></body></html>", target);
                    status.setText("Load failed: " + cause.getMessage());
                }
            }
        };
        worker.execute();
    }

    private void syncAddress() {
        PageState state = active();
        if (state != null) address.setText(state.url);
    }

    private static String escape(String text) {
        return String.valueOf(text).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private record Payload(String html, String url, int code) {}

    private static final class PageState {
        final EnginePage page;
        final Deque<String> back = new ArrayDeque<>();
        final Deque<String> forward = new ArrayDeque<>();
        String url = "";
        JScrollPane scroll;
        PageState(EnginePage page) { this.page = page; }
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new Browser().setVisible(true));
    }
}

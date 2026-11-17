import javax.swing.*;
import javax.swing.event.HyperlinkEvent;
import javax.swing.text.BadLocationException;
import javax.swing.text.html.HTMLDocument;
import javax.swing.text.html.HTMLEditorKit;
import javax.swing.text.html.StyleSheet;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Browser extends JFrame {
    private static final String HOME_URL = "https://example.com";
    private static final String SEARCH_URL = "https://duckduckgo.com/?q=";

    private final HttpClient httpClient = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final JTabbedPane tabs = new JTabbedPane();
    private final JTextField addressBar = new JTextField(HOME_URL);
    private final JLabel statusBar = new JLabel("Ready");

    private final JButton backButton = new JButton("←");
    private final JButton forwardButton = new JButton("→");
    private final JButton reloadButton = new JButton("⟳");
    private final JButton homeButton = new JButton("Home");
    private final JButton goButton = new JButton("Go");
    private final JButton newTabButton = new JButton("+");

    public Browser() {
        super("Swing Browser");

        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setMinimumSize(new Dimension(700, 450));
        setSize(1100, 750);
        setLocationRelativeTo(null);
        setLayout(new BorderLayout());

        buildToolbar();
        buildStatusBar();

        tabs.addChangeListener(event -> updateToolbar());
        add(tabs, BorderLayout.CENTER);

        installGlobalShortcuts();
        openTab(HOME_URL, true);
    }

    private void buildToolbar() {
        JPanel toolbar = new JPanel(new BorderLayout(6, 0));
        toolbar.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));

        JPanel navigation = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        backButton.setToolTipText("Back (Alt+Left)");
        forwardButton.setToolTipText("Forward (Alt+Right)");
        reloadButton.setToolTipText("Reload (Ctrl+R)");
        homeButton.setToolTipText("Home");
        newTabButton.setToolTipText("New tab (Ctrl+T)");

        navigation.add(backButton);
        navigation.add(forwardButton);
        navigation.add(reloadButton);
        navigation.add(homeButton);
        navigation.add(newTabButton);

        toolbar.add(navigation, BorderLayout.WEST);
        toolbar.add(addressBar, BorderLayout.CENTER);
        toolbar.add(goButton, BorderLayout.EAST);

        backButton.addActionListener(event -> {
            BrowserTab tab = activeTab();
            if (tab != null) {
                tab.goBack();
            }
        });

        forwardButton.addActionListener(event -> {
            BrowserTab tab = activeTab();
            if (tab != null) {
                tab.goForward();
            }
        });

        reloadButton.addActionListener(event -> {
            BrowserTab tab = activeTab();
            if (tab != null) {
                tab.reload();
            }
        });

        homeButton.addActionListener(event -> openInActiveTab(HOME_URL));
        goButton.addActionListener(event -> openInActiveTab(addressBar.getText()));
        addressBar.addActionListener(event -> openInActiveTab(addressBar.getText()));
        newTabButton.addActionListener(event -> openTab(HOME_URL, true));

        add(toolbar, BorderLayout.NORTH);
    }

    private void buildStatusBar() {
        statusBar.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        add(statusBar, BorderLayout.SOUTH);
    }

    private void installGlobalShortcuts() {
        bind("control L", "focusAddress", () -> {
            addressBar.requestFocusInWindow();
            addressBar.selectAll();
        });

        bind("control T", "newTab", () -> openTab(HOME_URL, true));

        bind("control W", "closeTab", () -> {
            if (tabs.getTabCount() > 1) {
                tabs.removeTabAt(tabs.getSelectedIndex());
            } else {
                BrowserTab tab = activeTab();
                if (tab != null) {
                    tab.load(HOME_URL, true);
                }
            }
        });

        bind("control R", "reload", () -> {
            BrowserTab tab = activeTab();
            if (tab != null) {
                tab.reload();
            }
        });

        bind("alt LEFT", "back", () -> {
            BrowserTab tab = activeTab();
            if (tab != null) {
                tab.goBack();
            }
        });

        bind("alt RIGHT", "forward", () -> {
            BrowserTab tab = activeTab();
            if (tab != null) {
                tab.goForward();
            }
        });

        bind("control F", "find", this::showFindDialog);
        bind("control EQUALS", "zoomIn", () -> changeZoom(10));
        bind("control PLUS", "zoomInPlus", () -> changeZoom(10));
        bind("control MINUS", "zoomOut", () -> changeZoom(-10));
        bind("control 0", "zoomReset", () -> setZoom(100));
    }

    private void bind(String keystroke, String actionName, Runnable action) {
        getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke(keystroke), actionName);
        getRootPane().getActionMap().put(actionName, new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent event) {
                action.run();
            }
        });
    }

    private void showFindDialog() {
        BrowserTab tab = activeTab();
        if (tab == null) {
            return;
        }

        String query = JOptionPane.showInputDialog(
                this,
                "Find text on this page:",
                "Find",
                JOptionPane.PLAIN_MESSAGE);

        if (query != null && !query.isBlank()) {
            if (!tab.findText(query)) {
                statusBar.setText("Text not found: " + query);
            }
        }
    }

    private void changeZoom(int delta) {
        BrowserTab tab = activeTab();
        if (tab != null) {
            tab.setZoom(tab.zoom + delta);
        }
    }

    private void setZoom(int zoom) {
        BrowserTab tab = activeTab();
        if (tab != null) {
            tab.setZoom(zoom);
        }
    }

    private BrowserTab activeTab() {
        java.awt.Component selected = tabs.getSelectedComponent();
        return selected instanceof BrowserTab browserTab ? browserTab : null;
    }

    private void openInActiveTab(String input) {
        BrowserTab tab = activeTab();
        if (tab == null) {
            openTab(input, true);
        } else {
            tab.load(input, true);
        }
    }

    private void openTab(String input, boolean select) {
        BrowserTab tab = new BrowserTab();
        tabs.addTab("New Tab", tab);

        int index = tabs.indexOfComponent(tab);
        tabs.setTabComponentAt(index, createTabHeader(tab));

        if (select) {
            tabs.setSelectedComponent(tab);
        }

        tab.load(input, true);
    }

    private JPanel createTabHeader(BrowserTab tab) {
        JPanel header = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        header.setOpaque(false);

        JLabel title = new JLabel("New Tab");
        JButton close = new JButton("×");
        close.setFocusable(false);
        close.setBorder(BorderFactory.createEmptyBorder(0, 3, 0, 3));
        close.setContentAreaFilled(false);
        close.setToolTipText("Close tab");

        close.addActionListener(event -> {
            int index = tabs.indexOfComponent(tab);
            if (index >= 0) {
                if (tabs.getTabCount() == 1) {
                    tab.load(HOME_URL, true);
                } else {
                    tabs.removeTabAt(index);
                }
            }
        });

        header.add(title);
        header.add(close);
        tab.tabTitleLabel = title;
        return header;
    }

    private void updateToolbar() {
        BrowserTab tab = activeTab();
        if (tab == null) {
            addressBar.setText("");
            backButton.setEnabled(false);
            forwardButton.setEnabled(false);
            return;
        }

        addressBar.setText(tab.currentUrl);
        backButton.setEnabled(!tab.backHistory.isEmpty());
        forwardButton.setEnabled(!tab.forwardHistory.isEmpty());
        statusBar.setText(tab.status);
    }

    private String normalizeAddress(String input) {
        String target = input == null ? "" : input.trim();
        if (target.isEmpty()) {
            return "";
        }

        if (target.matches("(?i)^https?://.*")) {
            return target;
        }

        if (target.matches("(?i)^[a-z][a-z0-9+.-]*:.*")) {
            throw new IllegalArgumentException(
                    "Only http:// and https:// addresses are supported.");
        }

        // Treat whitespace or a plain search phrase as a web search.
        if (target.chars().anyMatch(Character::isWhitespace)) {
            return SEARCH_URL + URLEncoder.encode(target, StandardCharsets.UTF_8);
        }

        if (target.startsWith("localhost") || target.startsWith("127.0.0.1")) {
            return "http://" + target;
        }

        return "https://" + target;
    }

    private final class BrowserTab extends JPanel {
        private final JEditorPane page = new JEditorPane();
        private final Deque<String> backHistory = new ArrayDeque<>();
        private final Deque<String> forwardHistory = new ArrayDeque<>();

        private JLabel tabTitleLabel;
        private String currentUrl = "";
        private String status = "Ready";
        private String pageTitle = "New Tab";
        private int zoom = 100;
        private int loadGeneration = 0;
        private SwingWorker<PagePayload, Void> currentWorker;
        private int lastFindPosition = 0;

        BrowserTab() {
            super(new BorderLayout());

            page.setEditable(false);
            page.setContentType("text/html");
            page.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, Boolean.TRUE);
            page.setFont(new java.awt.Font("SansSerif", java.awt.Font.PLAIN, 16));

            page.addHyperlinkListener(event -> handleHyperlink(event));

            add(new JScrollPane(page), BorderLayout.CENTER);
        }

        private void handleHyperlink(HyperlinkEvent event) {
            if (event.getEventType() == HyperlinkEvent.EventType.ENTERED) {
                status = event.getURL() != null
                        ? event.getURL().toString()
                        : event.getDescription();
                updateIfActive();
                return;
            }

            if (event.getEventType() == HyperlinkEvent.EventType.EXITED) {
                status = "Ready";
                updateIfActive();
                return;
            }

            if (event.getEventType() != HyperlinkEvent.EventType.ACTIVATED) {
                return;
            }

            String target = event.getURL() == null
                    ? event.getDescription()
                    : event.getURL().toString();

            if (target == null || target.isBlank()) {
                return;
            }

            try {
                URI resolved = currentUrl.isBlank()
                        ? URI.create(target)
                        : URI.create(currentUrl).resolve(target);
                load(resolved.toString(), true);
            } catch (IllegalArgumentException exception) {
                status = "Invalid link";
                updateIfActive();
            }
        }

        void load(String input, boolean recordHistory) {
            final String target;
            try {
                target = normalizeAddress(input);
            } catch (IllegalArgumentException exception) {
                showError("Invalid address", exception.getMessage());
                return;
            }

            if (target.isBlank()) {
                return;
            }

            if (recordHistory && !currentUrl.isBlank() && !currentUrl.equals(target)) {
                backHistory.push(currentUrl);
                forwardHistory.clear();
            }

            if (currentWorker != null) {
                currentWorker.cancel(true);
            }

            int generation = ++loadGeneration;
            currentUrl = target;
            status = "Loading " + target + "...";
            pageTitle = "Loading...";
            setTabTitle(pageTitle);
            updateIfActive();

            page.setText("""
                    <html><body style="font-family:sans-serif;padding:24px">
                    <h2>Loading page…</h2><p>%s</p>
                    </body></html>
                    """.formatted(escapeHtml(target)));

            currentWorker = new SwingWorker<>() {
                @Override
                protected PagePayload doInBackground() throws Exception {
                    HttpRequest request = HttpRequest.newBuilder()
                            .uri(URI.create(target))
                            .header("User-Agent", "Mozilla/5.0 (Swing Browser)")
                            .header("Accept", "text/html,application/xhtml+xml,*/*")
                            .timeout(Duration.ofSeconds(25))
                            .GET()
                            .build();

                    HttpResponse<byte[]> response = httpClient.send(
                            request,
                            HttpResponse.BodyHandlers.ofByteArray());

                    String contentType = response.headers()
                            .firstValue("Content-Type")
                            .orElse("");

                    Charset charset = findCharset(contentType);
                    String html = new String(response.body(), charset);

                    return new PagePayload(
                            html,
                            response.uri().toString(),
                            response.statusCode(),
                            contentType);
                }

                @Override
                protected void done() {
                    if (generation != loadGeneration || isCancelled()) {
                        return;
                    }

                    try {
                        PagePayload payload = get();

                        if (payload.statusCode() < 200 || payload.statusCode() >= 400) {
                            showError(
                                    "The server returned HTTP " + payload.statusCode(),
                                    payload.finalUrl());
                            return;
                        }

                        currentUrl = payload.finalUrl();
                        renderPage(payload.html(), payload.contentType());
                        status = "Done";
                        updateIfActive();
                    } catch (Exception exception) {
                        Throwable cause = exception.getCause() == null
                                ? exception
                                : exception.getCause();

                        showError("Could not load page", cause.getMessage());
                    }
                }
            };

            currentWorker.execute();
        }

        void reload() {
            if (!currentUrl.isBlank()) {
                load(currentUrl, false);
            }
        }

        void goBack() {
            if (backHistory.isEmpty()) {
                return;
            }

            String target = backHistory.pop();
            if (!currentUrl.isBlank()) {
                forwardHistory.push(currentUrl);
            }
            load(target, false);
        }

        void goForward() {
            if (forwardHistory.isEmpty()) {
                return;
            }

            String target = forwardHistory.pop();
            if (!currentUrl.isBlank()) {
                backHistory.push(currentUrl);
            }
            load(target, false);
        }

        private void renderPage(String rawHtml, String contentType) {
            String title = extractTitle(rawHtml);
            pageTitle = title.isBlank() ? currentUrl : title;
            setTabTitle(pageTitle);

            String html = sanitizeHtml(rawHtml);
            HTMLEditorKit kit = new HTMLEditorKit();
            StyleSheet styleSheet = new StyleSheet();
            styleSheet.addStyleSheet(kit.getStyleSheet());
            styleSheet.addRule("body { font-size: " + zoom + "%; }");
            styleSheet.addRule("img { max-width: 100%; }");
            addEmbeddedStyles(styleSheet, rawHtml);

            kit.setStyleSheet(styleSheet);

            HTMLDocument document = (HTMLDocument) kit.createDefaultDocument();
            document.putProperty("IgnoreCharsetDirective", Boolean.TRUE);

            try {
                document.setBase(URI.create(currentUrl).toURL());
            } catch (Exception ignored) {
                // Render without a base URL if the page address is malformed.
            }

            page.setEditorKit(kit);
            page.setDocument(document);
            page.setText(html);
            page.setCaretPosition(0);
            lastFindPosition = 0;

            if (contentType != null && !contentType.isBlank()
                    && !contentType.toLowerCase().contains("html")) {
                status = "Loaded content type: " + contentType;
            }
        }

        private void addEmbeddedStyles(StyleSheet styleSheet, String html) {
            Matcher matcher = Pattern.compile(
                    "(?is)<style\\b[^>]*>(.*?)</style>")
                    .matcher(html);

            while (matcher.find()) {
                String css = matcher.group(1)
                        .replaceAll("(?s)/\\*.*?\\*/", "")
                        .replaceAll("(?is)@import\\s+[^;]+;", "")
                        .replaceAll("(?is)url\\s*\\([^)]*\\)", "none");

                Matcher ruleMatcher = Pattern.compile(
                        "(?s)([^{}]+)\\{([^{}]*)}")
                        .matcher(css);

                while (ruleMatcher.find()) {
                    String selector = ruleMatcher.group(1).trim();
                    String declarations = ruleMatcher.group(2).trim();

                    if (selector.isEmpty()
                            || selector.startsWith("@")
                            || selector.contains(":")
                            || selector.contains("[")
                            || declarations.isEmpty()) {
                        continue;
                    }

                    try {
                        styleSheet.addRule(selector + " {" + declarations + "}");
                    } catch (RuntimeException ignored) {
                        // Ignore rules the Swing HTML renderer cannot parse.
                    }
                }
            }
        }

        private boolean findText(String query) {
            try {
                String text = page.getDocument().getText(
                        0, page.getDocument().getLength());

                if (text.isEmpty()) {
                    return false;
                }

                int found = text.toLowerCase().indexOf(
                        query.toLowerCase(),
                        Math.min(lastFindPosition, text.length()));

                if (found < 0 && lastFindPosition > 0) {
                    found = text.toLowerCase().indexOf(query.toLowerCase());
                }

                if (found < 0) {
                    return false;
                }

                page.requestFocusInWindow();
                page.setCaretPosition(found);
                page.moveCaretPosition(Math.min(found + query.length(), text.length()));
                lastFindPosition = found + query.length();
                return true;
            } catch (BadLocationException exception) {
                return false;
            }
        }

        void setZoom(int requestedZoom) {
            zoom = Math.max(50, Math.min(200, requestedZoom));
            if (!currentUrl.isBlank()) {
                renderPage(page.getText(), "text/html");
            }
        }

        private void showError(String heading, String detail) {
            status = heading;
            pageTitle = heading;
            setTabTitle(heading);

            page.setText("""
                    <html><body style="font-family:sans-serif;padding:24px;color:#333">
                    <h2>%s</h2><p>%s</p>
                    </body></html>
                    """.formatted(
                    escapeHtml(heading),
                    escapeHtml(detail == null ? "No additional details." : detail)));

            page.setCaretPosition(0);
            updateIfActive();
        }

        private void setTabTitle(String title) {
            String shortTitle = title == null || title.isBlank()
                    ? "New Tab"
                    : title;

            if (shortTitle.length() > 24) {
                shortTitle = shortTitle.substring(0, 21) + "...";
            }

            int index = tabs.indexOfComponent(this);
            if (index >= 0) {
                tabs.setTitleAt(index, shortTitle);
            }

            if (tabTitleLabel != null) {
                tabTitleLabel.setText(shortTitle);
                tabTitleLabel.setToolTipText(title);
            }

            setToolTipText(title);
        }

        private void updateIfActive() {
            if (activeTab() == this) {
                updateToolbar();
            }
        }
    }

    private record PagePayload(
            String html,
            String finalUrl,
            int statusCode,
            String contentType) {}

    private static Charset findCharset(String contentType) {
        Matcher matcher = Pattern.compile(
                "(?i)charset\\s*=\\s*[\"']?([^;\"'\\s]+)")
                .matcher(contentType);

        if (matcher.find()) {
            try {
                return Charset.forName(matcher.group(1));
            } catch (Exception ignored) {
                // Use UTF-8 when the server declares an unknown charset.
            }
        }

        return StandardCharsets.UTF_8;
    }

    private static String extractTitle(String html) {
        Matcher matcher = Pattern.compile(
                "(?is)<title\\b[^>]*>(.*?)</title>")
                .matcher(html);

        if (!matcher.find()) {
            return "";
        }

        return matcher.group(1)
                .replaceAll("(?is)<[^>]+>", "")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .trim();
    }

    private static String sanitizeHtml(String html) {
        String clean = html == null ? "" : html;

        // Swing's HTML renderer does not execute scripts, but remove active
        // content and event-handler attributes before handing it the document.
        clean = clean.replaceAll(
                "(?is)<(script|iframe|object|embed|applet)\\b[^>]*>.*?</\\1\\s*>",
                "");
        clean = clean.replaceAll(
                "(?is)<(script|iframe|object|embed|applet)\\b[^>]*/?>",
                "");
        clean = clean.replaceAll("(?is)<!DOCTYPE[^>]*>", "");
        clean = clean.replaceAll("(?is)<\\?xml[^>]*\\?>", "");
        clean = clean.replaceAll(
                "(?i)\\s+on[a-z]+\\s*=\\s*(\"[^\"]*\"|'[^']*'|[^\\s>]+)",
                "");
        clean = clean.replaceAll(
                "(?i)(href|src)\\s*=\\s*([\"'])\\s*javascript:[^\"']*\\2",
                "$1=\"#\"");

        // Swing understands traditional HTML better than newer semantic tags.
        clean = clean.replaceAll(
                "(?i)<(header|footer|main|section|article|nav|aside)\\b([^>]*)>",
                "<div$2>");
        clean = clean.replaceAll(
                "(?i)</(header|footer|main|section|article|nav|aside)\\s*>",
                "</div>");

        return clean;
    }

    private static String escapeHtml(String value) {
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new Browser().setVisible(true));
    }
}

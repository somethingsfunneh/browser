package engine;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;

/** Bounded HTTP(S) fetch helpers for stylesheets, scripts, and images. */
final class ResourceLoader {
    private static final int TIMEOUT_MILLIS = 8_000;
    private static final int MAX_RESOURCE_BYTES = 8 * 1024 * 1024;
    private static final int MAX_PAGE_BYTES = 2 * 1024 * 1024;
    private static final int MAX_RESOURCES = 32;
    private static final int MAX_REDIRECTS = 4;
    private static final Map<Object, Budget> PAGE_BUDGETS = new WeakHashMap<>();

    private static final class Budget {
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

    /** The token associates stylesheet/script requests with the caller's page budget. */
    static String fetch(Object pageBudget, String baseUrl, String reference) throws IOException {
        if (reference == null || reference.isBlank()) return null;
        byte[] bytes = fetchBytes(resolve(baseUrl, reference), 0, MAX_RESOURCE_BYTES, budgetFor(pageBudget));
        return bytes == null ? null : new String(bytes, StandardCharsets.UTF_8);
    }

    static byte[] fetchBytes(String baseUrl, String reference, int maxBytes) throws IOException {
        if (reference == null || reference.isBlank()) return null;
        String trimmed = reference.trim();
        if (trimmed.regionMatches(true, 0, "data:", 0, 5)) {
            int comma = trimmed.indexOf(',');
            if (comma < 0) return null;
            String metadata = trimmed.substring(5, comma).toLowerCase(Locale.ROOT);
            String payload = trimmed.substring(comma + 1);
            byte[] data = metadata.contains(";base64")
                    ? Base64.getMimeDecoder().decode(payload)
                    : java.net.URLDecoder.decode(payload, StandardCharsets.UTF_8).getBytes(StandardCharsets.UTF_8);
            if (data.length > maxBytes) throw new IOException("Resource size limit reached");
            return data;
        }
        return fetchBytes(resolve(baseUrl, trimmed), 0, Math.min(maxBytes, MAX_RESOURCE_BYTES), null);
    }

    private static synchronized Budget budgetFor(Object token) {
        if (token == null) return new Budget();
        return PAGE_BUDGETS.computeIfAbsent(token, ignored -> new Budget());
    }

    private static URI resolve(String baseUrl, String reference) throws IOException {
        try {
            URI ref = URI.create(reference.trim());
            if (ref.isAbsolute()) return ref;
            if (baseUrl == null || baseUrl.isBlank() || baseUrl.startsWith("about:")) {
                throw new IOException("Relative resource URL has no HTTP base URL");
            }
            return URI.create(baseUrl).resolve(ref);
        } catch (IllegalArgumentException ex) {
            throw new IOException("Invalid resource URL", ex);
        }
    }

    private static byte[] fetchBytes(URI target, int redirects, int maxBytes, Budget budget) throws IOException {
        String scheme = target.getScheme();
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            throw new IOException("Only HTTP and HTTPS page resources are supported");
        }
        if (redirects > MAX_REDIRECTS) throw new IOException("Too many resource redirects");
        if (budget != null) budget.startResource();
        URLConnection connection = target.toURL().openConnection();
        connection.setConnectTimeout(TIMEOUT_MILLIS);
        connection.setReadTimeout(TIMEOUT_MILLIS);
        connection.setRequestProperty("User-Agent", "CustomJavaBrowser/1.0");
        connection.setRequestProperty("Accept", "text/css,application/javascript,text/javascript,image/*,*/*;q=0.1");
        if (connection instanceof HttpURLConnection http) {
            http.setInstanceFollowRedirects(false);
            int status = http.getResponseCode();
            if (status >= 300 && status < 400) {
                String location = http.getHeaderField("Location");
                http.disconnect();
                return location == null || location.isBlank() ? null
                        : fetchBytes(target.resolve(location), redirects + 1, maxBytes, budget);
            }
            if (status < 200 || status >= 300) {
                http.disconnect();
                return null;
            }
        }
        try (InputStream input = connection.getInputStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count, total = 0;
            while ((count = input.read(buffer)) != -1) {
                total += count;
                if (total > maxBytes) throw new IOException("Resource size limit reached");
                if (budget != null) budget.addBytes(count);
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        } finally {
            if (connection instanceof HttpURLConnection http) http.disconnect();
        }
    }
}

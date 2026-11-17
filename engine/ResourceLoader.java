package engine;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;

/** Bounded loader for the small set of external page resources the engine supports. */
final class ResourceLoader {
    private static final int MAX_RESOURCE_BYTES = 512 * 1024;
    private static final int MAX_PAGE_BYTES = 2 * 1024 * 1024;
    private static final int MAX_RESOURCES = 32;
    private static final int MAX_REDIRECTS = 4;
    private static final int TIMEOUT_MILLIS = 4_000;

    static final class Budget {
        private int resources;
        private int bytes;

        void startResource() throws IOException {
            if (++resources > MAX_RESOURCES) {
                throw new IOException("Page resource limit reached");
            }
        }

        void addBytes(int count) throws IOException {
            bytes += count;
            if (bytes > MAX_PAGE_BYTES) {
                throw new IOException("Page resource size limit reached");
            }
        }
    }

    private ResourceLoader() {}

    static String fetch(Budget budget, String baseUrl, String reference) throws IOException {
        if (reference == null || reference.isBlank()) return null;
        URI target;
        try {
            target = URI.create(baseUrl).resolve(reference.trim());
        } catch (IllegalArgumentException ex) {
            throw new IOException("Invalid resource URL", ex);
        }
        return fetch(budget, target, 0);
    }

    private static String fetch(Budget budget, URI target, int redirects) throws IOException {
        String scheme = target.getScheme();
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            throw new IOException("Only HTTP and HTTPS page resources are supported");
        }
        if (redirects > MAX_REDIRECTS) throw new IOException("Too many resource redirects");
        budget.startResource();

        URLConnection connection = target.toURL().openConnection();
        connection.setConnectTimeout(TIMEOUT_MILLIS);
        connection.setReadTimeout(TIMEOUT_MILLIS);
        connection.setRequestProperty("User-Agent", "CustomJavaBrowser/1.0");
        connection.setRequestProperty("Accept", "text/css,application/javascript,text/javascript,*/*;q=0.1");

        if (connection instanceof HttpURLConnection http) {
            http.setInstanceFollowRedirects(false);
            int status = http.getResponseCode();
            if (status >= 300 && status < 400) {
                String location = http.getHeaderField("Location");
                http.disconnect();
                if (location == null || location.isBlank()) return null;
                return fetch(budget, target.resolve(location), redirects + 1);
            }
            if (status < 200 || status >= 300) {
                http.disconnect();
                return null;
            }
        }

        try (InputStream input = connection.getInputStream();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            int resourceBytes = 0;
            while ((count = input.read(buffer)) != -1) {
                resourceBytes += count;
                if (resourceBytes > MAX_RESOURCE_BYTES) {
                    throw new IOException("Page resource size limit reached");
                }
                budget.addBytes(count);
                output.write(buffer, 0, count);
            }
            return output.toString(StandardCharsets.UTF_8);
        } finally {
            if (connection instanceof HttpURLConnection http) http.disconnect();
        }
    }
}

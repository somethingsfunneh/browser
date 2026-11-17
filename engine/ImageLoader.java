package engine;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;

/** A bounded image loader that decodes ordinary web image formats without third-party libraries. */
final class ImageLoader {
    private static final int MAX_IMAGE_BYTES = 8 * 1024 * 1024;
    private static final int MAX_IMAGES = 64;

    static final class Budget {
        private int resources;
        void startImage() throws IOException {
            if (++resources > MAX_IMAGES) throw new IOException("Page image limit reached");
        }
    }

    private final Map<String, BufferedImage> cache = new IdentityHashMap<String, BufferedImage>() {};

    BufferedImage load(String baseUrl, String reference, Budget budget) {
        if (reference == null || reference.isBlank()) return null;
        try {
            budget.startImage();
            String normalized = reference.trim();
            byte[] data = ResourceLoader.fetchBytes(baseUrl, normalized, MAX_IMAGE_BYTES);
            if (data == null || data.length == 0) return null;
            BufferedImage image;
            try (ByteArrayInputStream input = new ByteArrayInputStream(data)) {
                image = ImageIO.read(input);
            }
            if (image == null) return null;
            synchronized (cache) { cache.put(normalized, image); }
            return image;
        } catch (Exception ignored) { return null; }
    }
}

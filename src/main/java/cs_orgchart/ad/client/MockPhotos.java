package cs_orgchart.ad.client;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.Random;
import java.util.zip.CRC32;

/**
 * The thumbnailPhoto values of AD_SYNC_MODE=mock, made in code (no real photos anywhere in the repository): for about
 * two thirds of the mock users a 96x96 colored square with their initials (JPEG or PNG), for the rest no photo, and a
 * few deliberately bad values the photo validator has to deal with. Deterministic: the same user always gets the
 * same value.
 */
public final class MockPhotos {

    /** The bad values, given to the first users of the no-photo third (index 2, 5, 8, ...) */
    public enum Bad { EMPTY, RANDOM_BYTES, PNG_WITH_HTML, HUGE_DIMENSIONS, TEXT_FILE }

    static final int SIDE = 96;

    private static final Color[] COLORS = {
            new Color(0x1E, 0x49, 0xE2), new Color(0x00, 0x91, 0xDA), new Color(0x7F, 0x3F, 0x98),
            new Color(0x00, 0xA3, 0xA1), new Color(0x47, 0x0A, 0x68), new Color(0x0C, 0x23, 0x3C)};

    private MockPhotos() {
    }

    /**
     * The value of the user at this position of the fixture
     *
     * @param initials the initials drawn on the image
     */
    public static Optional<byte[]> photoFor(int index, String initials) {
        if (index % 3 != 2) {
            return Optional.of(index % 2 == 0 ? square(index, initials, "jpeg") : square(index, initials, "png"));
        }
        int badIndex = index / 3;
        if (badIndex < Bad.values().length) {
            return Optional.of(bad(Bad.values()[badIndex], index, initials));
        }
        return Optional.empty();
    }

    public static byte[] bad(Bad kind, int seed, String initials) {
        return switch (kind) {
            case EMPTY -> new byte[0];
            case RANDOM_BYTES -> {
                byte[] noise = new byte[2048];
                new Random(seed).nextBytes(noise);
                yield noise;
            }
            case PNG_WITH_HTML -> concat(square(seed, initials, "png"),
                    "<html><script>alert('polyglot')</script></html>".getBytes(StandardCharsets.US_ASCII));
            case HUGE_DIMENSIONS -> pngHeaderOnly(60_000, 60_000);
            case TEXT_FILE -> "This is a text file, not a photo.\n".getBytes(StandardCharsets.US_ASCII);
        };
    }

    /** A colored square with the initials (the shapes alone where the server has no fonts) */
    public static byte[] square(int seed, String initials, String format) {
        BufferedImage image = new BufferedImage(SIDE, SIDE, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(COLORS[Math.floorMod(seed, COLORS.length)]);
            g.fillRect(0, 0, SIDE, SIDE);
            g.setColor(new Color(255, 255, 255, 60));
            g.setStroke(new BasicStroke(6));
            g.drawOval(10, 10, SIDE - 20, SIDE - 20);
            drawInitials(g, initials);
        } finally {
            g.dispose();
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            if (!ImageIO.write(image, format, out)) {
                throw new IllegalStateException("no ImageIO writer for " + format);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    private static void drawInitials(Graphics2D g, String initials) {
        String text = initials == null || initials.isBlank() ? "?" : initials;
        try {
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 34));
            g.setColor(Color.WHITE);
            FontMetrics metrics = g.getFontMetrics();
            g.drawString(text, (SIDE - metrics.stringWidth(text)) / 2,
                    (SIDE - metrics.getHeight()) / 2 + metrics.getAscent());
        } catch (RuntimeException | Error e) {
            // A headless server without fonts (fontconfig missing): the square without letters is still a photo
        }
    }

    /** A PNG signature and IHDR chunk declaring width x height, with no image data at all */
    static byte[] pngHeaderOnly(int width, int height) {
        ByteBuffer ihdr = ByteBuffer.allocate(13).putInt(width).putInt(height)
                .put((byte) 8).put((byte) 2).put((byte) 0).put((byte) 0).put((byte) 0);
        byte[] type = "IHDR".getBytes(StandardCharsets.US_ASCII);
        CRC32 crc = new CRC32();
        crc.update(type);
        crc.update(ihdr.array());
        ByteBuffer png = ByteBuffer.allocate(8 + 4 + 4 + 13 + 4);
        png.put(new byte[]{(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'});
        png.putInt(13).put(type).put(ihdr.array()).putInt((int) crc.getValue());
        return png.array();
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] joined = new byte[a.length + b.length];
        System.arraycopy(a, 0, joined, 0, a.length);
        System.arraycopy(b, 0, joined, a.length, b.length);
        return joined;
    }
}

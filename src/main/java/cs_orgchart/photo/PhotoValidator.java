package cs_orgchart.photo;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.event.IIOReadWarningListener;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Checks a thumbnailPhoto value and makes a clean copy of it. The bytes come from AD and are UNTRUSTED (users may be
 * able to write the attribute), so nothing is served as received:
 * <ol>
 *   <li>JPEG or PNG only, by the magic bytes (any declared type is ignored); not empty, at most maxBytes;</li>
 *   <li>the declared width and height are read from the header BEFORE decoding: above maxSide, or more than
 *       {@value #MAX_PIXELS} pixels, is refused without decoding (decompression bombs);</li>
 *   <li>decoded with ImageIO (a decoder warning such as a truncated file counts as a failure) and re-encoded as a new
 *       JPEG on a white background: EXIF, ICC profiles, comments and anything appended (polyglot files) are gone.</li>
 * </ol>
 * Undecodable images (for example CMYK JPEGs) are {@link PhotoState#INVALID}. Thread-safe, no Spring.
 */
public class PhotoValidator {

    public static final long MAX_PIXELS = 1_000_000L;
    public static final String CONTENT_TYPE = "image/jpeg";
    static final float JPEG_QUALITY = 0.85f;

    private static final byte[] JPEG_MAGIC = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};
    private static final byte[] PNG_MAGIC = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};

    private final int maxBytes;
    private final int maxSide;

    /**
     * The outcome: {@link PhotoState#PRESENT} with the clean JPEG, or {@link PhotoState#INVALID} with the reason (for
     * the log: never the bytes)
     */
    public record Result(PhotoState state, byte[] content, String contentType, int width, int height, String sha256,
                         String reason) {

        static Result invalid(String reason) {
            return new Result(PhotoState.INVALID, null, null, 0, 0, null, reason);
        }

        public boolean valid() {
            return state == PhotoState.PRESENT;
        }
    }

    public PhotoValidator(int maxBytes, int maxSide) {
        this.maxBytes = maxBytes;
        this.maxSide = maxSide;
    }

    public Result validate(byte[] raw) {
        if (raw == null || raw.length == 0) {
            return Result.invalid("empty value");
        }
        if (raw.length > maxBytes) {
            return Result.invalid("too large: " + raw.length + " bytes, the limit is " + maxBytes);
        }
        String format = startsWith(raw, JPEG_MAGIC) ? "jpeg" : startsWith(raw, PNG_MAGIC) ? "png" : null;
        if (format == null) {
            return Result.invalid("not a JPEG or PNG");
        }
        BufferedImage image;
        try {
            image = decode(raw, format);
        } catch (Refused e) {
            return Result.invalid(e.getMessage());
        } catch (IOException | RuntimeException e) {
            return Result.invalid(format.toUpperCase() + " cannot be decoded (" + e.getClass().getSimpleName() + ")");
        }
        try {
            byte[] clean = encode(flatten(image));
            return new Result(PhotoState.PRESENT, clean, CONTENT_TYPE, image.getWidth(), image.getHeight(),
                    sha256(clean), null);
        } catch (IOException | RuntimeException e) {
            return Result.invalid("cannot be re-encoded (" + e.getClass().getSimpleName() + ")");
        }
    }

    /** A check that failed before or while decoding, with its reason */
    private static final class Refused extends Exception {
        Refused(String reason) {
            super(reason);
        }
    }

    private BufferedImage decode(byte[] raw, String format) throws IOException, Refused {
        Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName(format);
        if (!readers.hasNext()) {
            throw new Refused("no " + format + " decoder");
        }
        ImageReader reader = readers.next();
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(raw))) {
            reader.setInput(input, true, true);
            // Only the header so far: the size decides whether decoding is allowed at all
            int width = reader.getWidth(0);
            int height = reader.getHeight(0);
            if (width <= 0 || height <= 0) {
                throw new Refused("no usable dimensions");
            }
            if (width > maxSide || height > maxSide || (long) width * height > MAX_PIXELS) {
                throw new Refused("declared size " + width + "x" + height + " exceeds the limit (" + maxSide
                        + " px per side, " + MAX_PIXELS + " pixels)");
            }
            AtomicReference<String> warning = new AtomicReference<>();
            reader.addIIOReadWarningListener(warningListener(warning));
            BufferedImage image = reader.read(0);
            if (warning.get() != null) {
                throw new Refused(format.toUpperCase() + " is damaged (" + warning.get() + ")");
            }
            if (image == null) {
                throw new Refused(format.toUpperCase() + " has no image");
            }
            return image;
        } finally {
            reader.dispose();
        }
    }

    private static IIOReadWarningListener warningListener(AtomicReference<String> warning) {
        return (source, message) -> warning.compareAndSet(null, message);
    }

    /** RGB on white: transparent pixels become white, every color model becomes plain RGB */
    private static BufferedImage flatten(BufferedImage image) {
        BufferedImage rgb = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = rgb.createGraphics();
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, rgb.getWidth(), rgb.getHeight());
            g.drawImage(image, 0, 0, null);
        } finally {
            g.dispose();
        }
        return rgb;
    }

    /** A new JPEG without any metadata */
    static byte[] encode(BufferedImage rgb) throws IOException {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ImageOutputStream output = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(output);
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(JPEG_QUALITY);
            writer.write(null, new IIOImage(rgb, null, null), param);
        } finally {
            writer.dispose();
        }
        return out.toByteArray();
    }

    public static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private static boolean startsWith(byte[] bytes, byte[] prefix) {
        if (bytes.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (bytes[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }
}

package cs_orgchart.photo;

import cs_orgchart.ad.client.MockPhotos;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.awt.image.WritableRaster;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link PhotoValidator}: JPEG and PNG only by their magic bytes, limits checked from the header before decoding,
 * damaged or undecodable images INVALID, and every valid image re-encoded as a clean JPEG (no EXIF, nothing appended,
 * transparency on white) with the SHA-256 of the output.
 */
class PhotoValidatorTest {

    private final PhotoValidator validator = new PhotoValidator(204_800, 1024);

    private static byte[] image(String format, int width, int height, int type) throws IOException {
        BufferedImage image = new BufferedImage(width, height, type);
        Graphics2D g = image.createGraphics();
        g.setColor(new Color(30, 73, 226));
        g.fillRect(0, 0, width, height);
        g.setColor(Color.ORANGE);
        g.fillOval(width / 4, height / 4, width / 2, height / 2);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertThat(ImageIO.write(image, format, out)).isTrue();
        return out.toByteArray();
    }

    private static byte[] jpeg() throws IOException {
        return image("jpeg", 96, 96, BufferedImage.TYPE_INT_RGB);
    }

    private static byte[] png() throws IOException {
        return image("png", 96, 80, BufferedImage.TYPE_INT_RGB);
    }

    private static void assertCleanJpeg(PhotoValidator.Result result, int width, int height) throws IOException {
        assertThat(result.state()).isEqualTo(PhotoState.PRESENT);
        assertThat(result.contentType()).isEqualTo("image/jpeg");
        assertThat(result.content()).startsWith((byte) 0xFF, (byte) 0xD8, (byte) 0xFF);
        assertThat(result.sha256()).isEqualTo(PhotoValidator.sha256(result.content())).hasSize(64);
        assertThat(result.width()).isEqualTo(width);
        assertThat(result.height()).isEqualTo(height);
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(result.content()));
        assertThat(decoded.getWidth()).isEqualTo(width);
        assertThat(decoded.getHeight()).isEqualTo(height);
    }

    private static boolean contains(byte[] haystack, String needle) {
        return new String(haystack, StandardCharsets.ISO_8859_1).contains(needle);
    }

    @Test
    void aValidJpegIsReEncoded() throws IOException {
        byte[] original = jpeg();
        PhotoValidator.Result result = validator.validate(original);
        assertCleanJpeg(result, 96, 96);
        assertThat(result.reason()).isNull();
    }

    @Test
    void aValidPngBecomesAJpeg() throws IOException {
        assertCleanJpeg(validator.validate(png()), 96, 80);
    }

    @Test
    void wrongMagicBytesAreRefused() throws IOException {
        byte[] gif = image("gif", 20, 20, BufferedImage.TYPE_INT_RGB);
        assertThat(validator.validate(gif).state()).isEqualTo(PhotoState.INVALID);
        assertThat(validator.validate(gif).reason()).isEqualTo("not a JPEG or PNG");
        byte[] bmp = image("bmp", 20, 20, BufferedImage.TYPE_INT_RGB);
        assertThat(validator.validate(bmp).state()).isEqualTo(PhotoState.INVALID);
    }

    @Test
    void onlyTheContentDecidesTheTypeNotWhatItClaimsToBe() throws IOException {
        // A text file whose first line claims an image type, and a JPEG body behind a PNG name: the magic bytes decide
        byte[] claimsJpeg = "Content-Type: image/jpeg\r\n\r\nnot an image".getBytes(StandardCharsets.US_ASCII);
        assertThat(validator.validate(claimsJpeg).state()).isEqualTo(PhotoState.INVALID);
        assertCleanJpeg(validator.validate(jpeg()), 96, 96);
        // A PNG signature followed by JPEG data is no PNG: it cannot be decoded
        byte[] jpeg = jpeg();
        byte[] fakePng = new byte[8 + jpeg.length];
        System.arraycopy(new byte[]{(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'}, 0, fakePng, 0, 8);
        System.arraycopy(jpeg, 0, fakePng, 8, jpeg.length);
        assertThat(validator.validate(fakePng).state()).isEqualTo(PhotoState.INVALID);
    }

    @Test
    void emptyAndMissingValuesAreInvalid() {
        assertThat(validator.validate(new byte[0]).state()).isEqualTo(PhotoState.INVALID);
        assertThat(validator.validate(null).reason()).isEqualTo("empty value");
    }

    @Test
    void tooManyBytesAreRefused() throws IOException {
        byte[] jpeg = jpeg();
        PhotoValidator small = new PhotoValidator(jpeg.length - 1, 1024);
        assertThat(small.validate(jpeg).state()).isEqualTo(PhotoState.INVALID);
        assertThat(small.validate(jpeg).reason()).startsWith("too large");
        assertThat(new PhotoValidator(jpeg.length, 1024).validate(jpeg).valid()).isTrue();
    }

    @Test
    void hugeDeclaredDimensionsAreRefusedFromTheHeaderWithoutDecoding() {
        // Only a header (no image data): decoding it would fail differently, so the reason shows it was not decoded
        PhotoValidator.Result result = validator.validate(MockPhotos.bad(MockPhotos.Bad.HUGE_DIMENSIONS, 1, "X"));
        assertThat(result.state()).isEqualTo(PhotoState.INVALID);
        assertThat(result.reason()).startsWith("declared size 60000x60000");
    }

    @Test
    void sidesAndPixelCountHaveLimits() throws IOException {
        byte[] wide = image("png", 1100, 10, BufferedImage.TYPE_INT_RGB);
        assertThat(validator.validate(wide).reason()).startsWith("declared size 1100x10");
        // 1001 x 1000 pixels: each side within a 2000 px limit, the pixel count above one million
        byte[] many = image("png", 1001, 1000, BufferedImage.TYPE_INT_RGB);
        PhotoValidator.Result result = new PhotoValidator(Integer.MAX_VALUE, 2000).validate(many);
        assertThat(result.state()).isEqualTo(PhotoState.INVALID);
        assertThat(result.reason()).contains("1000000 pixels");
    }

    @Test
    void truncatedFilesAreInvalid() throws IOException {
        byte[] jpeg = jpeg();
        assertThat(validator.validate(Arrays.copyOf(jpeg, jpeg.length / 2)).state()).isEqualTo(PhotoState.INVALID);
        byte[] png = png();
        assertThat(validator.validate(Arrays.copyOf(png, png.length / 2)).state()).isEqualTo(PhotoState.INVALID);
        assertThat(validator.validate(Arrays.copyOf(png, 12)).state()).isEqualTo(PhotoState.INVALID);
    }

    @Test
    void aFourComponentCmykJpegNeverComesOutAsCmyk() throws IOException {
        BufferedImage cmykBands = new BufferedImage(32, 32, BufferedImage.TYPE_4BYTE_ABGR);
        WritableRaster raster = cmykBands.getRaster();
        for (int x = 0; x < 32; x++) {
            for (int y = 0; y < 32; y++) {
                raster.setPixel(x, y, new int[]{200, 50, 10, 30});
            }
        }
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ImageOutputStream output = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(output);
            // Four components without an RGB color space: what ImageIO cannot turn into an image (as CMYK files)
            writer.write(null, new javax.imageio.IIOImage(raster, null, null), null);
        } finally {
            writer.dispose();
        }
        // An Adobe APP14 segment (transform 0) right after SOI marks the four components as CMYK, as Photoshop writes
        byte[] four = out.toByteArray();
        byte[] adobe = {(byte) 0xFF, (byte) 0xEE, 0x00, 0x0E, 'A', 'd', 'o', 'b', 'e', 0x00, 0x64, 0x00, 0x00, 0x00,
                0x00, 0x00};
        byte[] cmyk = new byte[four.length + adobe.length];
        System.arraycopy(four, 0, cmyk, 0, 2);
        System.arraycopy(adobe, 0, cmyk, 2, adobe.length);
        System.arraycopy(four, 2, cmyk, 2 + adobe.length, four.length - 2);
        PhotoValidator.Result result = validator.validate(cmyk);
        // JDK 21 ImageIO decodes this one: whatever it decodes leaves as a plain three-channel RGB JPEG
        if (result.valid()) {
            BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(result.content()));
            assertThat(decoded.getColorModel().getNumComponents()).isEqualTo(3);
            assertThat(contains(result.content(), "Adobe")).isFalse();
        } else {
            assertThat(result.reason()).contains("JPEG");
        }
    }

    @Test
    void aJpegTheDecoderDoesNotSupportIsInvalid() throws IOException {
        // Arithmetic coding (SOF9) is not supported by ImageIO: the same path as an undecodable CMYK file
        byte[] jpeg = jpeg();
        for (int i = 2; i < jpeg.length - 1; i++) {
            if (jpeg[i] == (byte) 0xFF && jpeg[i + 1] == (byte) 0xC0) {
                jpeg[i + 1] = (byte) 0xC9;
                break;
            }
        }
        PhotoValidator.Result result = validator.validate(jpeg);
        assertThat(result.state()).isEqualTo(PhotoState.INVALID);
        assertThat(result.content()).isNull();
    }

    @Test
    void aPolyglotComesOutClean() {
        byte[] polyglot = MockPhotos.bad(MockPhotos.Bad.PNG_WITH_HTML, 4, "AB");
        assertThat(contains(polyglot, "<script")).isTrue();
        PhotoValidator.Result result = validator.validate(polyglot);
        assertThat(result.valid()).isTrue();
        assertThat(contains(result.content(), "<script")).isFalse();
        assertThat(contains(result.content(), "<html")).isFalse();
        assertThat(result.content()).startsWith((byte) 0xFF, (byte) 0xD8);
    }

    @Test
    void exifAndCommentsAreRemoved() throws IOException {
        byte[] jpeg = jpeg();
        byte[] exif = "Exif\0\0SECRET-GPS-49.0,12.0".getBytes(StandardCharsets.ISO_8859_1);
        byte[] withExif = new byte[jpeg.length + 4 + exif.length];
        // SOI, then an APP1 segment (marker, length incl. the 2 length bytes), then the rest of the original
        withExif[0] = (byte) 0xFF;
        withExif[1] = (byte) 0xD8;
        withExif[2] = (byte) 0xFF;
        withExif[3] = (byte) 0xE1;
        withExif[4] = (byte) ((exif.length + 2) >> 8);
        withExif[5] = (byte) ((exif.length + 2) & 0xFF);
        System.arraycopy(exif, 0, withExif, 6, exif.length);
        System.arraycopy(jpeg, 2, withExif, 6 + exif.length, jpeg.length - 2);
        assertThat(contains(withExif, "SECRET-GPS")).isTrue();

        PhotoValidator.Result result = validator.validate(withExif);
        assertThat(result.valid()).isTrue();
        assertThat(contains(result.content(), "SECRET-GPS")).isFalse();
        assertThat(contains(result.content(), "Exif")).isFalse();
    }

    @Test
    void transparencyBecomesWhite() throws IOException {
        BufferedImage argb = new BufferedImage(40, 40, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = argb.createGraphics();
        g.setColor(Color.BLUE);
        g.fillRect(20, 0, 20, 40);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(argb, "png", out);

        PhotoValidator.Result result = validator.validate(out.toByteArray());
        assertCleanJpeg(result, 40, 40);
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(result.content()));
        Color transparentPart = new Color(decoded.getRGB(5, 20));
        assertThat(transparentPart.getRed()).isGreaterThan(240);
        assertThat(transparentPart.getGreen()).isGreaterThan(240);
        assertThat(transparentPart.getBlue()).isGreaterThan(240);
        assertThat(new Color(decoded.getRGB(30, 20)).getBlue()).isGreaterThan(200);
    }

    @Test
    void theMockBadValuesAreAllHandled() {
        for (MockPhotos.Bad bad : MockPhotos.Bad.values()) {
            PhotoValidator.Result result = validator.validate(MockPhotos.bad(bad, 7, "MB"));
            assertThat(result.state()).as(bad.name())
                    .isEqualTo(bad == MockPhotos.Bad.PNG_WITH_HTML ? PhotoState.PRESENT : PhotoState.INVALID);
        }
        assertThat(validator.validate(MockPhotos.square(3, "KK", "jpeg")).valid()).isTrue();
        assertThat(validator.validate(MockPhotos.square(3, "KK", "png")).valid()).isTrue();
    }

    @Test
    void theSameImageGivesTheSameHash() throws IOException {
        byte[] png = png();
        assertThat(validator.validate(png).sha256()).isEqualTo(validator.validate(png).sha256());
        assertThat(validator.validate(png).sha256()).isNotEqualTo(validator.validate(jpeg()).sha256());
    }
}

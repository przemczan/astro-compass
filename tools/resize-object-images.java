// Resizes tools/image-staging/'s downloaded originals to a common longest-edge size, flattened to
// JPEG for bundling as Compose Multiplatform drawable resources. Deliberately plain JDK
// (javax.imageio + java.awt), no build step -- run directly as a single-file source program:
//
//   java tools/resize-object-images.java tools/image-staging
//
// Output goes to <staging>/resized/m<n>.jpg (lowercase, matching Compose resource naming rules).

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ResizeObjectImages {
    static final int TARGET_LONGEST_EDGE = 1024;
    static final float JPEG_QUALITY = 0.85f;

    public static void main(String[] args) throws IOException {
        Path stagingDir = Path.of(args.length > 0 ? args[0] : "tools/image-staging");
        Path outDir = stagingDir.resolve("resized");
        Files.createDirectories(outDir);

        String manifestText = Files.readString(stagingDir.resolve("manifest.json"));
        List<String> files = new ArrayList<>();
        Matcher matcher = Pattern.compile("\"file\":\\s*\"([^\"]+)\"").matcher(manifestText);
        while (matcher.find()) files.add(matcher.group(1));

        System.out.println("Resizing " + files.size() + " images to " + TARGET_LONGEST_EDGE + "px longest edge...");
        long totalIn = 0, totalOut = 0, failed = 0;

        for (String file : files) {
            Path src = stagingDir.resolve(file);
            if (!Files.exists(src)) {
                System.out.println("MISSING: " + file);
                failed++;
                continue;
            }
            BufferedImage original = ImageIO.read(src.toFile());
            if (original == null) {
                System.out.println("FAILED TO DECODE: " + file);
                failed++;
                continue;
            }

            int w = original.getWidth(), h = original.getHeight();
            double scale = Math.min(1.0, (double) TARGET_LONGEST_EDGE / Math.max(w, h));
            int newW = Math.max(1, (int) Math.round(w * scale));
            int newH = Math.max(1, (int) Math.round(h * scale));

            // TYPE_INT_RGB (no alpha) + a black fill first -- some sources are PNG with
            // transparency; JPEG has no alpha channel, and black is the right matte for a sky photo
            // regardless.
            BufferedImage resized = new BufferedImage(newW, newH, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = resized.createGraphics();
            g.setColor(Color.BLACK);
            g.fillRect(0, 0, newW, newH);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.drawImage(original, 0, 0, newW, newH, null);
            g.dispose();

            String base = file.substring(0, file.lastIndexOf('.')).toLowerCase();
            Path dest = outDir.resolve(base + ".jpg");
            ImageWriter writer = ImageIO.getImageWritersByFormatName("jpg").next();
            ImageWriteParam writeParam = writer.getDefaultWriteParam();
            writeParam.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            writeParam.setCompressionQuality(JPEG_QUALITY);
            try (var ios = ImageIO.createImageOutputStream(dest.toFile())) {
                writer.setOutput(ios);
                writer.write(null, new IIOImage(resized, null, null), writeParam);
            } finally {
                writer.dispose();
            }

            long inSize = Files.size(src);
            long outSize = Files.size(dest);
            totalIn += inSize;
            totalOut += outSize;
            System.out.printf("%s: %dx%d -> %dx%d, %.0fKB -> %.0fKB%n", file, w, h, newW, newH, inSize / 1024.0, outSize / 1024.0);
        }

        System.out.printf("%n%d resized, %d failed. %.1fMB -> %.1fMB%n", files.size() - failed, failed, totalIn / 1024.0 / 1024.0, totalOut / 1024.0 / 1024.0);
    }
}

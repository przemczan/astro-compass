// Turns whatever is currently staged in tools/image-staging-hips2fits/ (see
// tools/fetch-object-images.mjs) into the app's bundled deep-sky-object photos: resizes/re-encodes
// each staged original, writes it into composeApp's Compose resources, and (re)generates the
// Kotlin id -> DrawableResource lookup those photos are looked up through at runtime.
//
// This is the offline "build" half of a two-step pipeline, deliberately separate from the
// network-touching "download" half (tools/fetch-object-images.mjs) -- rerun this any time after
// manually swapping a staged image (same filename) to pick up the replacement, without
// re-fetching anything. Fully regenerates the drawable directory and the generated Kotlin file
// each run, so removing an object from the manifest also removes its bundled photo.
//
// Deliberately plain JDK (javax.imageio + java.awt), no build step -- run directly as a
// single-file source program from the repo root:
//
//   java tools/build-object-images.java [staging-dir] [--max-edge 512] [--quality 0.85]
//
// staging-dir defaults to tools/image-staging-hips2fits.

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class BuildObjectImages {
    static final int DEFAULT_MAX_EDGE = 512;
    static final float DEFAULT_QUALITY = 0.85f;

    static final Path DRAWABLE_DIR =
        Path.of("composeApp/src/commonMain/composeResources/drawable");
    static final Path GENERATED_KOTLIN_FILE =
        Path.of("composeApp/src/commonMain/kotlin/com/astrocompass/catalog/ObjectImages.kt");

    record ManifestEntry(String id, String fovDegrees, String file) {}

    public static void main(String[] args) throws IOException {
        Path stagingDir = Path.of("tools/image-staging-hips2fits");
        int maxEdge = DEFAULT_MAX_EDGE;
        float quality = DEFAULT_QUALITY;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--max-edge" -> maxEdge = Integer.parseInt(args[++i]);
                case "--quality" -> quality = Float.parseFloat(args[++i]);
                default -> stagingDir = Path.of(args[i]);
            }
        }

        List<ManifestEntry> manifest = readManifest(stagingDir.resolve("manifest.json"));
        System.out.println(manifest.size() + " object(s) in manifest.");

        Files.createDirectories(DRAWABLE_DIR);
        clearExistingDrawables();

        List<ManifestEntry> bundled = new ArrayList<>();
        long totalIn = 0, totalOut = 0;
        int missing = 0;

        for (ManifestEntry entry : manifest) {
            Path src = stagingDir.resolve(entry.file());
            if (!Files.exists(src)) {
                System.out.println("MISSING (skipped): " + entry.id() + " -> " + entry.file());
                missing++;
                continue;
            }

            Path dest = DRAWABLE_DIR.resolve(entry.file());
            long inSize = Files.size(src);
            long outSize = resizeAndEncode(src, dest, maxEdge, quality);
            totalIn += inSize;
            totalOut += outSize;
            bundled.add(entry);
        }

        writeGeneratedKotlin(bundled);

        System.out.printf("%n%d bundled, %d missing. %.1fMB -> %.1fMB%n",
            bundled.size(), missing, totalIn / 1024.0 / 1024.0, totalOut / 1024.0 / 1024.0);
        System.out.println("Wrote " + GENERATED_KOTLIN_FILE);
    }

    // Wipes prior drawables before repopulating, so an object dropped from the manifest also
    // loses its bundled photo instead of lingering as dead weight.
    static void clearExistingDrawables() throws IOException {
        if (!Files.isDirectory(DRAWABLE_DIR)) return;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(DRAWABLE_DIR, "*.jpg")) {
            for (Path file : stream) Files.delete(file);
        }
    }

    // manifest.json is our own tool's output (tools/fetch-object-images.mjs), not user input, so
    // regex scraping of the "id"/"fovDegrees"/"file" fields avoids pulling in a JSON library for a
    // dependency-free single-file program -- same approach the pipeline's old resize script used
    // for "file" alone. Field order (id, then fovDegrees, then file within each object) is
    // guaranteed by the writer, so a non-greedy chained match pairs each field with its own entry.
    static List<ManifestEntry> readManifest(Path manifestPath) throws IOException {
        String text = Files.readString(manifestPath);
        List<ManifestEntry> entries = new ArrayList<>();
        Matcher matcher = Pattern.compile(
            "\"id\":\\s*\"([^\"]+)\"[\\s\\S]*?\"fovDegrees\":\\s*([0-9.eE+-]+)[\\s\\S]*?\"file\":\\s*\"([^\"]+)\""
        ).matcher(text);
        while (matcher.find()) {
            entries.add(new ManifestEntry(matcher.group(1), matcher.group(2), matcher.group(3)));
        }
        return entries;
    }

    // Resizes to maxEdge longest-edge (never upscales), matted to black (some cutouts could carry
    // an alpha channel; JPEG has none), and re-encodes as JPEG. Returns the written byte count.
    static long resizeAndEncode(Path src, Path dest, int maxEdge, float quality) throws IOException {
        BufferedImage original = ImageIO.read(src.toFile());
        if (original == null) throw new IOException("Failed to decode " + src);

        int w = original.getWidth(), h = original.getHeight();
        double scale = Math.min(1.0, (double) maxEdge / Math.max(w, h));
        int newW = Math.max(1, (int) Math.round(w * scale));
        int newH = Math.max(1, (int) Math.round(h * scale));

        BufferedImage resized = new BufferedImage(newW, newH, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = resized.createGraphics();
        g.setColor(Color.BLACK);
        g.fillRect(0, 0, newW, newH);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.drawImage(original, 0, 0, newW, newH, null);
        g.dispose();

        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpg").next();
        ImageWriteParam writeParam = writer.getDefaultWriteParam();
        writeParam.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        writeParam.setCompressionQuality(quality);
        try (var ios = ImageIO.createImageOutputStream(dest.toFile())) {
            writer.setOutput(ios);
            writer.write(null, new IIOImage(resized, null, null), writeParam);
        } finally {
            writer.dispose();
        }

        return Files.size(dest);
    }

    // Kotlin's `when(String)` compiles to one method body, and the JVM caps a single method at
    // 64KB of bytecode -- a single `when` over the full catalog already blew past that once (at
    // 1822 entries, well before it would reach the full catalog's size). Splitting into
    // fixed-size chunk functions, each its own `when` well under that ceiling regardless of how
    // large the catalog grows, and dispatching through a plain `?:` chain (trivial bytecode size
    // on its own) fixes it without capping how many objects this pipeline can ever cover.
    private static final int MAX_ENTRIES_PER_CHUNK = 200;

    // Compose Multiplatform generates one Res.drawable.<filename> accessor per bundled file, not
    // an indexable collection -- so callers need a String id -> DrawableResource lookup, and this
    // (chunked, see MAX_ENTRIES_PER_CHUNK) set of `when`s is it. fovDegrees rides along with the
    // drawable (not a separate lookup) because SkyMap.kt must scale the photo by the actual
    // angular field it depicts -- which is wider than the object's own majorAxisArcmin by
    // fetch-object-images.mjs's FOV_MARGIN_FACTOR padding -- not by the object's own catalog size,
    // or the padding stars end up drawn compressed relative to their true positions. Sorted by
    // catalog id for a stable diff between regenerations.
    static void writeGeneratedKotlin(List<ManifestEntry> bundled) throws IOException {
        List<ManifestEntry> sorted = new ArrayList<>(bundled);
        sorted.sort(Comparator.comparing(ManifestEntry::id));
        int chunkCount = Math.max(1, (int) Math.ceil(sorted.size() / (double) MAX_ENTRIES_PER_CHUNK));

        StringBuilder sb = new StringBuilder();
        sb.append("package com.astrocompass.catalog\n\n");
        sb.append("import astrocompass.composeapp.generated.resources.Res\n");
        sb.append("import astrocompass.composeapp.generated.resources.*\n");
        sb.append("import org.jetbrains.compose.resources.DrawableResource\n\n");
        sb.append("/** A bundled sky-survey cutout and the real angular field of view (degrees) it depicts --\n");
        sb.append(" *  wider than the object's own on-sky size, see [objectImage]'s doc comment. */\n");
        sb.append("data class BundledObjectImage(val drawable: DrawableResource, val fovDegrees: Float)\n\n");
        sb.append("/**\n");
        sb.append(" * Looks up the bundled sky-survey cutout for a [DeepSkyObject] by its catalog id (e.g.\n");
        sb.append(" * \"NGC0224\"), or `null` if none was fetched for it. Compose Multiplatform generates one named\n");
        sb.append(" * `Res.drawable.<name>` accessor per bundled file (see `composeResources/drawable/`) rather than\n");
        sb.append(" * an indexable collection, so this is a mechanically generated lookup rather than a loop --\n");
        sb.append(" * generated by `tools/build-object-images.java` from `tools/image-staging-hips2fits/manifest.json`,\n");
        sb.append(" * not hand-typed. Rerun that tool to add/replace coverage; don't edit this file directly.\n");
        sb.append(" * Split across `objectImageChunkN` helpers rather than one `when` over everything -- see\n");
        sb.append(" * MAX_ENTRIES_PER_CHUNK's doc comment in build-object-images.java.\n");
        sb.append(" */\n");
        sb.append("fun objectImage(catalogDesignation: String): BundledObjectImage? =\n");
        sb.append("    objectImageChunk0(catalogDesignation)\n");
        for (int i = 1; i < chunkCount; i++) {
            sb.append("        ?: objectImageChunk").append(i).append("(catalogDesignation)\n");
        }
        sb.append("\n");

        for (int i = 0; i < chunkCount; i++) {
            int from = i * MAX_ENTRIES_PER_CHUNK;
            int to = Math.min(from + MAX_ENTRIES_PER_CHUNK, sorted.size());
            sb.append("private fun objectImageChunk").append(i)
                .append("(catalogDesignation: String): BundledObjectImage? = when (catalogDesignation) {\n");
            for (ManifestEntry entry : sorted.subList(from, to)) {
                String resourceName = entry.file().substring(0, entry.file().lastIndexOf('.'));
                sb.append("    \"").append(entry.id()).append("\" -> BundledObjectImage(Res.drawable.").append(resourceName)
                    .append(", ").append(entry.fovDegrees()).append("f)\n");
            }
            sb.append("    else -> null\n");
            sb.append("}\n\n");
        }

        Files.createDirectories(GENERATED_KOTLIN_FILE.getParent());
        Files.writeString(GENERATED_KOTLIN_FILE, sb.toString());
    }
}

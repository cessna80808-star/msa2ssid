package dev.msa2ssid.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A library of skins kept as PNG files in a folder, with a head preview per entry
 * so the UI can show a picker. Skins are validated on the way in, so an entry that
 * is listed is one Mojang will accept.
 */
public final class SkinLibrary {

    /** One usable skin: display name, file on disk, and its 8x8 head preview. */
    public record Entry(String name, Path file, byte[] headPng) {
    }

    private final Path directory;

    public SkinLibrary(Path directory) {
        this.directory = directory;
    }

    public Path directory() {
        return directory;
    }

    /** Creates the folder if needed and returns every valid skin in it, sorted by name. */
    public List<Entry> load() throws IOException {
        Files.createDirectories(directory);
        List<Entry> entries = new ArrayList<>();
        try (var stream = Files.list(directory)) {
            List<Path> files = stream
                    .filter(p -> Files.isRegularFile(p)
                            && p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".png"))
                    .sorted()
                    .toList();
            for (Path file : files) {
                try {
                    byte[] png = Files.readAllBytes(file);
                    Skins.validateSkin(png);
                    entries.add(new Entry(file.getFileName().toString(), file, Skins.headIcon(png)));
                } catch (Exception ignored) {
                    // Not a usable skin: skip it rather than fail the whole library.
                }
            }
        }
        return entries;
    }

    /** Validates and stores a skin, returning the file it was written to. */
    public Path add(String name, byte[] png) throws IOException {
        Skins.validateSkin(png);
        Files.createDirectories(directory);
        Path target = directory.resolve(fileName(name));
        Files.write(target, png);
        return target;
    }

    /** Downloads a skin URL and adds it to the library. */
    public Path addFromUrl(String name, String url) throws IOException {
        return add(name, Http.getBytes(url));
    }

    private static String fileName(String name) {
        String base = name == null ? "" : name.trim();
        int slash = Math.max(base.lastIndexOf('/'), base.lastIndexOf('\\'));
        if (slash >= 0) base = base.substring(slash + 1);
        if (base.toLowerCase(Locale.ROOT).endsWith(".png")) base = base.substring(0, base.length() - 4);
        base = base.replaceAll("[^A-Za-z0-9._-]", "_");
        if (base.isBlank()) base = "skin";
        return base + ".png";
    }
}

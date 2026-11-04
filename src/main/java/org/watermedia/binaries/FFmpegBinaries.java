package org.watermedia.binaries;

import org.watermedia.tools.IOTool;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashSet;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.zip.ZipFile;

final class FFmpegBinaries {
    private static final int BUFFER_SIZE = 65_536;

    private FFmpegBinaries() {}

    static Path start(final Path directory) throws IOException {
        final String platform = IOTool.platformClassifier();
        if (platform.equals("unsupported") || platform.equals("windows-arm64"))
            throw new IOException("FFmpeg is not bundled for " + IOTool.platform());

        final var manifest = new Properties();
        try (final var input = FFmpegBinaries.class.getResourceAsStream("/META-INF/ffmpeg-manifest.properties")) {
            if (input == null) throw new IOException("FFmpeg integrity manifest is missing");
            manifest.load(input);
        }
        final var hashes = new TreeMap<String, String>();
        final String prefix = platform + ".native.";
        for (final String key: manifest.stringPropertyNames()) {
            if (key.startsWith(prefix) && key.endsWith(".sha256")) {
                final String file = key.substring(prefix.length(), key.length() - ".sha256".length());
                hashes.put(file, manifest.getProperty(key));
            }
        }
        final String version = manifest.getProperty("version");
        if (version == null) throw new IOException("FFmpeg manifest lacks a version");

        final String resource = "/libs/ffmpeg-" + platform + ".zip";
        try (final var input = FFmpegBinaries.class.getResourceAsStream(resource)) {
            if (input == null) throw new IOException("FFmpeg archive is missing: " + resource);
            return install(directory, version, "", 0L, hashes, input);
        }
    }

    static synchronized Path install(final Path directory, final String version, final String archiveHash,
                                     final long archiveSize, final Map<String, String> hashes,
                                     final InputStream source) throws IOException {
        Files.createDirectories(directory);
        try (final var channel = FileChannel.open(directory.resolve(".install.lock"),
                 StandardOpenOption.CREATE, StandardOpenOption.WRITE); final var lock = channel.lock()) {

            final Path current = IOTool.currentGeneration(directory);
            if (current != null) return current;

            final Path stage = Files.createTempDirectory(directory, "install-");
            boolean installed = false;
            try {
                final Path archive = stage.resolve("archive.zip");
                final byte[] buffer = new byte[BUFFER_SIZE];
                try (final var output = Files.newOutputStream(archive)) {
                    int count;
                    while ((count = source.read(buffer)) != -1) output.write(buffer, 0, count);
                }

                final var found = new HashSet<String>();
                try (final var zip = new ZipFile(archive.toFile())) {
                    for (final var entry: zip.stream().toList()) {
                        final String name = entry.getName();
                        if (!found.add(name) || entry.isDirectory())
                            throw new IOException("Unexpected FFmpeg archive entry: " + name);
                        final Path destination = stage.resolve(name);
                        try (final var input = zip.getInputStream(entry);
                             final var output = Files.newOutputStream(destination)) {
                            int count;
                            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                        }
                    }
                }
                Files.delete(archive);
                IOTool.publishGeneration(directory, stage);
                installed = true;
                return stage;
            } finally {
                if (!installed) IOTool.deleteTree(stage);
            }
        }
    }
}

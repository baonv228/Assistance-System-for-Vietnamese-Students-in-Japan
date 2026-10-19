package com.sep490.vsa.feature.media;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.regex.Pattern;

/**
 * Keeps media files in a folder on the server ({@code app.media.local-dir}, default {@code uploads/} next to
 * where the app runs; git-ignored). {@link MediaWebConfig} serves the folder at {@code /media/**}.
 */
@Component
public class LocalMediaStorage implements MediaStorage {

    /** Keys are made by {@link MediaService}: folder/yyyy/MM/uuid.ext. Anything else is refused. */
    private static final Pattern SAFE_KEY = Pattern.compile("[a-z]+(/[0-9]{4}/[0-9]{2})?/[0-9a-f-]{36}\\.[a-z0-9]{2,4}");

    private final Path root;

    public LocalMediaStorage(@Value("${app.media.local-dir:uploads}") String dir) {
        this.root = Paths.get(dir).toAbsolutePath().normalize();
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not create the media folder " + root, e);
        }
    }

    /** The folder as a file: URL ending in "/", as Spring's resource handler needs for a directory. */
    public String rootLocation() {
        String uri = root.toUri().toString();
        return uri.endsWith("/") ? uri : uri + "/";
    }

    @Override
    public void put(String key, byte[] bytes) {
        Path target = resolve(key);
        try {
            Files.createDirectories(target.getParent());
            // Write to a temporary file first so a half-written file is never served
            Path temp = Files.createTempFile(target.getParent(), ".upload-", ".tmp");
            try {
                Files.write(temp, bytes);
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
            } finally {
                Files.deleteIfExists(temp);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not save media file " + key, e);
        }
    }

    @Override
    public byte[] get(String key) {
        try {
            return Files.readAllBytes(resolve(key));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read media file " + key, e);
        }
    }

    @Override
    public void delete(String key) {
        try {
            Files.deleteIfExists(resolve(key));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not delete media file " + key, e);
        }
    }

    private Path resolve(String key) {
        if (key == null || !SAFE_KEY.matcher(key).matches()) {
            throw new IllegalArgumentException("Invalid media key: " + key);
        }
        Path path = root.resolve(key).normalize();
        if (!path.startsWith(root)) {
            throw new IllegalArgumentException("Invalid media key: " + key);
        }
        return path;
    }
}

package com.sep490.vsa.feature.media;

import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;

/**
 * Where uploaded photos and videos are kept. The database only stores the key ({@code media.storage_key});
 * the public URL is {@code app.media.public-base-url} + key.
 * <p>
 * {@link LocalMediaStorage} ({@code app.media.storage=local}, the default) writes to a folder on the server;
 * {@link R2MediaStorage} ({@code app.media.storage=r2}) keeps the files in a private Cloudflare R2 bucket. Either
 * way the files reach people only through {@link MediaController}.
 */
public interface MediaStorage {

    /** Saves the bytes under {@code key}, replacing nothing (keys are unique). */
    void put(String key, byte[] bytes);

    /** Reads a stored file back (used by the image scan). */
    byte[] get(String key);

    /**
     * The stored file for {@link MediaController} to send, or null when it is not there. The default reads the whole
     * file; {@link LocalMediaStorage} streams it from disk.
     */
    default Resource resource(String key) {
        try {
            return new ByteArrayResource(get(key));
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Deletes the file; does nothing when it is already gone. */
    void delete(String key);
}

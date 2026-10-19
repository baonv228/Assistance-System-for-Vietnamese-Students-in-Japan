package com.sep490.vsa.feature.media;

/**
 * Where uploaded photos and videos are kept. The database only stores the key ({@code media.storage_key});
 * the public URL is {@code app.media.public-base-url} + key.
 * <p>
 * {@link LocalMediaStorage} writes to a folder on the server, which is enough for development and a small
 * single-server deployment. Cloudflare R2 (free tier) can replace it later by adding another implementation
 * of this interface; nothing else needs to change.
 */
public interface MediaStorage {

    /** Saves the bytes under {@code key}, replacing nothing (keys are unique). */
    void put(String key, byte[] bytes);

    /** Reads a stored file back (used by the image scan). */
    byte[] get(String key);

    /** Deletes the file; does nothing when it is already gone. */
    void delete(String key);
}

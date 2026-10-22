package com.sep490.vsa.feature.media;

import com.sep490.vsa.common.security.AppUserPrincipal;
import com.sep490.vsa.feature.media.MediaAccess.Allowed;
import com.sep490.vsa.feature.media.MediaAccess.Audience;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.io.IOException;
import java.util.Optional;

/**
 * Sends stored photos and videos at {@code /media/<storage_key>} while media URLs point at this server (the default
 * {@code app.media.public-base-url=/media/}). Every request is checked by {@link MediaAccess}:
 * <ul>
 *   <li>public file: {@code Cache-Control: max-age=86400, public} ({@link MediaAccess#PUBLIC_CACHE});</li>
 *   <li>a file only its owner may see: {@code Cache-Control: no-store, private};</li>
 *   <li>anything else, a key that is not in the form {@link MediaService} makes, or a missing file: 404, so nobody
 *       learns which keys exist.</li>
 * </ul>
 * The content type is the one stored with the file ({@code media.mime_type}), with {@code X-Content-Type-Options:
 * nosniff}. The file is streamed from {@link MediaStorage#resource}; Spring answers Range requests (video seeking) and
 * If-Modified-Since. With another base URL (Cloudflare R2 later) the files live elsewhere, this controller is not
 * created, and that store needs its own way to keep files private (e.g. signed URLs).
 */
@Controller
@ConditionalOnProperty(name = "app.media.public-base-url", havingValue = "/media/", matchIfMissing = true)
public class MediaController {

    private final MediaAccess access;
    private final MediaStorage storage;

    public MediaController(MediaAccess access, MediaStorage storage) {
        this.access = access;
        this.storage = storage;
    }

    @GetMapping("/media/{*path}")
    public ResponseEntity<Resource> file(@PathVariable String path, @AuthenticationPrincipal AppUserPrincipal me) {
        String key = path.startsWith("/") ? path.substring(1) : path;
        if (!LocalMediaStorage.isSafeKey(key)) {
            return ResponseEntity.notFound().build();
        }
        Optional<Allowed> allowed = access.check(key, me == null ? null : me.id());
        if (allowed.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        Resource file = storage.resource(key);
        long modified;
        try {
            modified = file == null ? -1 : file.lastModified();
        } catch (IOException e) {
            modified = -1;
        }
        if (file == null || modified < 0) {
            return ResponseEntity.notFound().build();
        }
        CacheControl cache = allowed.get().audience() == Audience.PUBLIC
                ? CacheControl.maxAge(MediaAccess.PUBLIC_CACHE).cachePublic()
                : CacheControl.noStore().cachePrivate();
        return ResponseEntity.ok()
                .contentType(contentType(allowed.get().mimeType()))
                .cacheControl(cache)
                .header("X-Content-Type-Options", "nosniff")
                .lastModified(modified)
                .body(file);
    }

    private static MediaType contentType(String mimeType) {
        try {
            return MediaType.parseMediaType(mimeType);
        } catch (IllegalArgumentException e) {         // InvalidMediaTypeException: a malformed or missing type
            return MediaType.APPLICATION_OCTET_STREAM;
        }
    }
}

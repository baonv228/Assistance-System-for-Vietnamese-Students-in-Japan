package com.sep490.vsa.feature.media;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/**
 * Moves the files of a media folder used before ({@code app.media.local-dir}, e.g. {@code uploads}) into the R2 bucket,
 * once, when {@code app.media.r2.import-from} names that folder. Runs after the start on a thread of its own; a file
 * the bucket already has is skipped, so running it again is harmless. The keys stay the same, so the database needs
 * no change. Local files are never deleted: remove the setting (and the folder, once checked) afterwards.
 */
@Component
@ConditionalOnProperty(name = "app.media.storage", havingValue = "r2")
public class R2MediaImport implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(R2MediaImport.class);

    private final MediaStorage storage;
    private final String folder;

    public R2MediaImport(MediaStorage storage, @Value("${app.media.r2.import-from:}") String folder) {
        this.storage = storage;
        this.folder = folder == null ? "" : folder.trim();
    }

    @Override
    public void run(ApplicationArguments args) {
        if (folder.isEmpty()) {
            return;
        }
        Thread thread = new Thread(this::importFolder, "r2-media-import");
        thread.setDaemon(true);
        thread.start();
    }

    /** Uploads every file of the folder whose key the bucket does not have yet; returns how many were uploaded. */
    int importFolder() {
        Path root = Path.of(folder).toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            log.warn("app.media.r2.import-from: the folder {} does not exist, nothing imported", root);
            return 0;
        }
        List<Path> files;
        try (Stream<Path> walk = Files.walk(root)) {
            files = walk.filter(Files::isRegularFile).toList();
        } catch (IOException e) {
            log.warn("Could not list the media folder {}: {}", root, e.getMessage());
            return 0;
        }
        int uploaded = 0;
        int present = 0;
        int skipped = 0;
        int failed = 0;
        log.info("Importing {} files of {} into R2…", files.size(), root);
        for (Path file : files) {
            String key = root.relativize(file).toString().replace('\\', '/');
            if (!LocalMediaStorage.isSafeKey(key)) {
                skipped++;
                continue;
            }
            try {
                if (storage.resource(key) != null) {
                    present++;
                    continue;
                }
                storage.put(key, Files.readAllBytes(file));
                uploaded++;
            } catch (IOException | RuntimeException e) {
                failed++;
                log.warn("Could not import {}: {}", key, e.getMessage());
            }
        }
        log.info("R2 import of {} done: {} uploaded, {} already there, {} not media files, {} failed",
                root, uploaded, present, skipped, failed);
        return uploaded;
    }
}

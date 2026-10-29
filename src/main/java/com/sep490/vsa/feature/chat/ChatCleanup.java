package com.sep490.vsa.feature.chat;

import com.sep490.vsa.feature.chat.ChatRepository.FileRow;
import com.sep490.vsa.feature.media.MediaStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Every day at 03:50 Japan time: the text, envelope and earlier versions of messages recalled more than
 * {@link #RECALLED_KEEP} ago are emptied (kept that long so a report sent before the recall can still be looked at;
 * the row stays as "Tin nhắn đã được thu hồi"), and files uploaded more than {@link #UNATTACHED_KEEP} ago that no
 * message carries (the member gave up sending) are deleted with their stored bytes. Files of recalled messages are
 * deleted when the recall commits ({@link ChatService#recall}).
 */
@Component
public class ChatCleanup {

    private static final Logger log = LoggerFactory.getLogger(ChatCleanup.class);
    /** "cần chốt": how long a recalled message keeps its content. */
    static final Duration RECALLED_KEEP = Duration.ofDays(30);
    /** "cần chốt": how long an uploaded file may wait for its message. */
    static final Duration UNATTACHED_KEEP = Duration.ofHours(24);
    /** Files removed per run at most; the rest go the next day. */
    static final int FILE_BATCH = 1000;

    /** What one run removed. */
    record Result(int messagesEmptied, int filesDeleted) {
    }

    private final ChatRepository repository;
    private final MediaStorage storage;
    private final TransactionTemplate tx;
    private final Clock clock;

    public ChatCleanup(ChatRepository repository, MediaStorage storage, PlatformTransactionManager transactionManager,
                       Clock clock) {
        this.repository = repository;
        this.storage = storage;
        this.tx = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    @Scheduled(cron = "0 50 3 * * *", zone = "Asia/Tokyo")
    public void purge() {
        Result result = run();
        log.info("Chat clean-up: {} recalled messages emptied, {} unsent files deleted", result.messagesEmptied(),
                result.filesDeleted());
    }

    /**
     * Two short transactions. The unsent files are locked before they are deleted, so a message attaching one at that
     * moment either gets it first (and it stays) or finds it gone; the stored bytes go after the commit.
     */
    Result run() {
        Instant now = clock.instant();
        Integer emptied = tx.execute(status -> repository.emptyRecalledBefore(now.minus(RECALLED_KEEP)));
        List<FileRow> files = tx.execute(status -> {
            List<FileRow> old = repository.lockUnattachedBefore(now.minus(UNATTACHED_KEEP), FILE_BATCH);
            repository.deleteFiles(old.stream().map(FileRow::id).toList());
            return old;
        });
        for (FileRow file : files) {
            try {
                storage.delete(file.storageKey());
            } catch (RuntimeException e) {
                log.warn("Could not delete chat file {}: {}", file.storageKey(), e.getMessage());
            }
        }
        return new Result(emptied == null ? 0 : emptied, files.size());
    }
}

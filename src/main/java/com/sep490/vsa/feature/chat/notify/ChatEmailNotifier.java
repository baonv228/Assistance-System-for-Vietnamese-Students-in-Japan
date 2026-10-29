package com.sep490.vsa.feature.chat.notify;

import com.sep490.vsa.common.mail.EmailSender;
import com.sep490.vsa.feature.chat.notify.NotifyRepository.DigestRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * E-mail for chat messages left unread, so a member who is not on the site hears about them: every 5 minutes, ONE
 * e-mail per member naming up to {@value #NAMES_SHOWN} people who wrote, with a link to "Tin nhắn". A message is
 * announced once it has waited {@code app.chat.notify.email-delay-minutes} (10) unread, and a conversation at most once
 * every {@code app.chat.notify.email-spacing-hours} (6); see {@link NotifyRepository#digestRows} for who and what. The
 * member turns it off in the page's notification settings (e-mail on unless they did).
 * <p>
 * Never the text of a message: encrypted messages cannot be read here, and e-mail is not private. After the e-mail went
 * out, {@code notified_up_to_id} (newest message announced) and {@code emailed_at} are set for each conversation it
 * named; when it could not be sent nothing is set, so the next run tries again. Without {@code spring.mail.host} the
 * e-mail is printed to the console ({@code MailConfig}).
 */
@Component
public class ChatEmailNotifier {

    private static final Logger log = LoggerFactory.getLogger(ChatEmailNotifier.class);

    static final String SUBJECT = "Bạn có tin nhắn mới trên Senpai ơi";
    static final int NAMES_SHOWN = 5;
    /** "cần chốt": member-conversations read per run (far above 100–200 members); the rest wait for the next run. */
    static final int ROWS_PER_RUN = 1000;

    private final NotifyRepository repository;
    private final EmailSender mail;
    private final Clock clock;
    private final String messagesUrl;
    private final Duration delay;
    private final Duration spacing;

    public ChatEmailNotifier(NotifyRepository repository, EmailSender mail, Clock clock,
                             @Value("${app.public-url:http://localhost:8080}") String publicUrl,
                             @Value("${app.chat.notify.email-delay-minutes:10}") long delayMinutes,
                             @Value("${app.chat.notify.email-spacing-hours:6}") long spacingHours) {
        this.repository = repository;
        this.mail = mail;
        this.clock = clock;
        this.messagesUrl = publicUrl.replaceAll("/+$", "") + "/messages";
        this.delay = Duration.ofMinutes(delayMinutes);
        this.spacing = Duration.ofHours(spacingHours);
    }

    @Scheduled(fixedDelayString = "${app.chat.notify.email-interval-ms:300000}",
            initialDelayString = "${app.chat.notify.email-initial-delay-ms:60000}")
    public void run() {
        try {
            int sent = sendDigests();
            if (sent > 0) {
                log.info("Chat e-mail digest: {} e-mails sent", sent);
            }
        } catch (RuntimeException e) {
            log.warn("Chat e-mail digest failed: {}", e.getMessage());
        }
    }

    /** One pass; the number of e-mails sent. */
    public int sendDigests() {
        Instant now = clock.instant();
        Map<Long, List<DigestRow>> byMember = new LinkedHashMap<>();
        for (DigestRow row : repository.digestRows(now.minus(delay), now.minus(spacing), ROWS_PER_RUN)) {
            byMember.computeIfAbsent(row.userId(), k -> new ArrayList<>()).add(row);
        }
        int sent = 0;
        for (List<DigestRow> rows : byMember.values()) {
            DigestRow first = rows.get(0);
            try {
                mail.send(first.email(), SUBJECT, text(first.userName(), rows));
            } catch (RuntimeException e) {
                log.warn("Chat e-mail digest to member {} not sent: {}", first.userId(), e.getMessage());
                continue;
            }
            sent++;
            Instant at = clock.instant();
            for (DigestRow row : rows) {
                repository.markEmailed(row.conversationId(), row.userId(), row.newestId(), at);
            }
        }
        return sent;
    }

    /** The e-mail: who wrote (newest first), the link, why no text, how to turn it off. */
    String text(String userName, List<DigestRow> rows) {
        List<String> names = rows.stream().limit(NAMES_SHOWN)
                .map(r -> r.request() ? r.peerName() + " (lời mời nhắn tin)" : r.peerName()).toList();
        int others = rows.size() - names.size();
        String who;
        if (others > 0) {
            who = String.join(", ", names) + " và " + others + " người khác";
        } else if (names.size() == 1) {
            who = names.get(0);
        } else {
            who = String.join(", ", names.subList(0, names.size() - 1)) + " và " + names.get(names.size() - 1);
        }
        return """
                Chào %s,

                Bạn có tin nhắn chưa đọc trên Senpai ơi từ %s.

                Mở Tin nhắn để đọc và trả lời: %s

                Để bảo vệ sự riêng tư của bạn, e-mail này không chứa nội dung tin nhắn.

                --
                Bạn nhận e-mail này vì đang bật "Thông báo qua e-mail" cho tin nhắn. Mỗi cuộc trò chuyện được nhắc \
                nhiều nhất một lần trong %d giờ. Để tắt, mở trang Tin nhắn, vào "Cài đặt thông báo" và bỏ chọn \
                "Thông báo qua e-mail".

                Senpai ơi
                """.formatted(userName, who, messagesUrl, spacing.toHours());
    }
}

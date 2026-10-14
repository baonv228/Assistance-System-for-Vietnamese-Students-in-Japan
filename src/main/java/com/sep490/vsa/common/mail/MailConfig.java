package com.sep490.vsa.common.mail;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

/**
 * Sends email through SMTP when {@code spring.mail.host} is configured (for example Gmail with an app password).
 * Without it, emails are only printed to the console so everyone can develop without an SMTP account.
 */
@Configuration
public class MailConfig {

    private static final Logger log = LoggerFactory.getLogger(MailConfig.class);

    @Bean
    public EmailSender emailSender(ObjectProvider<JavaMailSender> mailSender,
                                   @Value("${app.mail.from:Senpai oi <no-reply@senpai-oi.local>}") String from) {
        JavaMailSender smtp = mailSender.getIfAvailable();
        if (smtp == null) {
            log.warn("spring.mail.host is not set: emails will be printed to the console instead of being sent.");
            return (to, subject, text) -> log.warn("""

                    ===== EMAIL (not sent, SMTP not configured) =====
                    To: {}
                    Subject: {}
                    {}
                    =================================================""", to, subject, text);
        }
        return (to, subject, text) -> {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(from);
            message.setTo(to);
            message.setSubject(subject);
            message.setText(text);
            smtp.send(message);
        };
    }
}

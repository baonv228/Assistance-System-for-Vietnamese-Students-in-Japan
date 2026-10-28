package com.sep490.vsa.feature.auth;

import com.sep490.vsa.common.mail.EmailSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * Emails of UC-U05 / UC-U06, sent on a background thread: "Quên mật khẩu" answers at once and in the same time
 * whether the email has an account or not (nobody can learn which emails are registered from the answer time), and a
 * slow mail server never holds the request. A failed email is only logged; the user can ask again.
 */
@Component
public class PasswordMail {

    private static final Logger log = LoggerFactory.getLogger(PasswordMail.class);

    private final EmailSender emailSender;

    public PasswordMail(EmailSender emailSender) {
        this.emailSender = emailSender;
    }

    @Async
    public void sendResetLink(String email, String link, long minutes) {
        try {
            emailSender.send(email, "[Senpai ơi] Đặt lại mật khẩu", """
                    Chào bạn,

                    Có người (có thể là bạn) vừa yêu cầu đặt lại mật khẩu tài khoản Senpai ơi gắn với email này.
                    Mở liên kết sau để đặt mật khẩu mới (hiệu lực %d phút, chỉ dùng được một lần):

                    %s

                    Nếu bạn không yêu cầu, hãy bỏ qua email này. Mật khẩu hiện tại vẫn giữ nguyên.

                    Senpai ơi – Hệ thống hỗ trợ sinh viên Việt Nam tại Nhật Bản
                    """.formatted(minutes, link));
        } catch (RuntimeException e) {
            log.warn("Could not send the password reset email: {}", e.toString());
        }
    }

    @Async
    public void sendChanged(String email, String displayName) {
        try {
            emailSender.send(email, "[Senpai ơi] Mật khẩu của bạn vừa được đổi", """
                    Chào %s,

                    Mật khẩu tài khoản Senpai ơi của bạn vừa được đổi. Các thiết bị khác đã được đăng xuất.

                    Nếu không phải bạn đổi, hãy dùng “Quên mật khẩu?” ở trang Đăng nhập để đặt lại ngay.

                    Senpai ơi – Hệ thống hỗ trợ sinh viên Việt Nam tại Nhật Bản
                    """.formatted(displayName));
        } catch (RuntimeException e) {
            log.warn("Could not send the password changed email: {}", e.toString());
        }
    }
}

package com.sep490.vsa.feature.auth;

import com.sep490.vsa.common.exception.ApiException;
import com.sep490.vsa.feature.user.User;
import com.sep490.vsa.feature.user.UserStatus;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * The app's half of its Google/Facebook sign-in: the one-time code from {@code senpaioi://auth} and the PKCE verifier
 * are traded for the account ({@link SocialAppCodes}, {@code common/security/AppSocialLogin}). Only now, with the
 * verifier proven, is the account looked up or linked, exactly as {@link SocialLoginSuccessHandler} does for the
 * website.
 */
@Service
public class SocialAppLoginService {

    static final String EXPIRED = "Lần đăng nhập bằng Google hoặc Facebook này đã hết hạn. Vui lòng thử lại.";
    static final String LOCKED = "Tài khoản này đã bị khóa. Vui lòng liên hệ quản trị viên.";

    /** Either the ACTIVE account to sign in, or the profile of a new person who still has to finish sign-up. */
    public record Outcome(User user, PendingSocialSignup pending) {
    }

    private final SocialAppCodes codes;
    private final SocialLoginService socialLoginService;

    public SocialAppLoginService(SocialAppCodes codes, SocialLoginService socialLoginService) {
        this.codes = codes;
        this.socialLoginService = socialLoginService;
    }

    /**
     * The code is used up first, so a wrong verifier burns it too (a stolen code gets one guess). Unknown, expired,
     * used or not matching the verifier: 400 {@link #EXPIRED}. A LOCKED or DELETED account: 403 {@link #LOCKED}, with
     * nothing written for it ({@link SocialLoginService#findOrLink}).
     */
    public Outcome exchange(String code, String verifier) {
        SocialAppCodes.Ticket ticket = codes.take(code).orElseThrow(() -> ApiException.badRequest(EXPIRED));
        if (!SocialAppCodes.verifierMatches(verifier, ticket.challenge())) {
            throw ApiException.badRequest(EXPIRED);
        }
        PendingSocialSignup profile = ticket.profile();
        Optional<User> existing;
        try {
            existing = socialLoginService.findOrLink(profile);
        } catch (DataIntegrityViolationException e) {
            // The same Google/Facebook account signed in twice at once (website and app): the other one has just
            // linked it to the account, so it is found by that link now
            existing = socialLoginService.findOrLink(profile);
        }
        if (existing.isEmpty()) {
            return new Outcome(null, profile);
        }
        if (existing.get().getStatus() != UserStatus.ACTIVE) {
            throw ApiException.forbidden(LOCKED);
        }
        return new Outcome(existing.get(), null);
    }
}

package com.sep490.vsa.feature.guide;

import com.sep490.vsa.common.exception.ApiException;
import com.sep490.vsa.common.security.AppUserPrincipal;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class GuideServiceTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final GuideService service = new GuideService(jdbc,
            mock(com.sep490.vsa.feature.media.MediaService.class), "/media/", mock(GuidePreparationService.class));
    private final GuideRequest request = new GuideRequest("Title", "Summary", "PRE",
            List.of(new GuideRequest.Section("First step", "Instructions", null, null)));

    @Test
    void onlyModeratorsCanCreate() {
        assertThatThrownBy(() -> service.create(request, null)).isInstanceOf(ApiException.class);
        for (String role : List.of("USER", "ADMIN", "ACADEMIC_MANAGER")) {
            assertThatThrownBy(() -> service.create(request,
                    new AppUserPrincipal(1L, "test@example.org", "Test", role, "/")))
                    .isInstanceOf(ApiException.class);
        }
        verifyNoInteractions(jdbc);
    }

    @Test
    void rejectsUnknownTopicBeforeWritingAnything() {
        when(jdbc.queryForObject("SELECT COUNT(*) FROM guide_stages WHERE code=?", Integer.class, "PRE"))
                .thenReturn(0);
        assertThatThrownBy(() -> service.create(request,
                new AppUserPrincipal(1L, "test@example.org", "Test", "MODERATOR", "/moderator")))
                .isInstanceOf(ApiException.class);
        verify(jdbc).queryForObject("SELECT COUNT(*) FROM guide_stages WHERE code=?", Integer.class, "PRE");
        verifyNoMoreInteractions(jdbc);
    }

    @Test
    void optionalLinksAcceptOnlyHttpAndHttps() {
        for (String url : List.of("https://example.org/image.jpg", "http://example.org/document", "")) {
            assertThatCode(() -> GuideService.validateUrl(url)).doesNotThrowAnyException();
        }
        assertThatCode(() -> GuideService.validateUrl(null)).doesNotThrowAnyException();
        for (String url : List.of("javascript:alert(1)", "file:///etc/passwd", "//example.org", "https://", "https://user:password@example.org")) {
            assertThatThrownBy(() -> GuideService.validateUrl(url)).isInstanceOf(ApiException.class);
        }
    }

    @Test
    void validatesRequiredFieldsSectionContentsAndLength() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            assertThat(validator.validate(request)).isEmpty();
            assertThat(validator.validate(new GuideRequest(" ", " ", "", List.of()))).hasSize(2);
            assertThat(validator.validate(new GuideRequest("x".repeat(256), "Summary", "PRE",
                    List.of(new GuideRequest.Section("", " ", null, null))))).hasSize(1);
        }
    }
}

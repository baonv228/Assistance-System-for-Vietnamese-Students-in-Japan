package com.sep490.vsa.feature.admin;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sep490.vsa.common.exception.ApiException;
import com.sep490.vsa.common.text.VietnameseText;
import com.sep490.vsa.feature.admin.AdminOverviewDtos.Period;
import com.sep490.vsa.feature.admin.AdminOverviewDtos.QuotaMeter;
import com.sep490.vsa.feature.user.Role;
import com.sep490.vsa.feature.user.UserStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Kiểm tra các quy tắc thuần của Admin mà không khởi động Spring hay kết nối cơ sở dữ liệu. */
class AdminPureRulesTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 1);

    @Test
    void appliedSettingValuesAreClampedAndUnwiredValuesArePreserved() {
        assertThat(AdminConfigService.clampToRange("max_moderators", -1)).isEqualTo(1);
        assertThat(AdminConfigService.clampToRange("max_moderators", 99)).isEqualTo(3);
        assertThat(AdminConfigService.clampToRange("new_account_days", 0)).isZero();
        assertThat(AdminConfigService.clampToRange("verify_token_hours", -5)).isEqualTo(-5);
        assertThat(AdminConfigService.clampToRange("unknown_setting", 7)).isEqualTo(7);
    }

    @Test
    void settingMetadataExposesRangesOnlyForAppliedKeys() {
        assertThat(AdminConfigService.rangeOf("max_moderators")).containsExactly(1, 3);
        assertThat(AdminConfigService.rangeOf("mute_days")).containsExactly(1, 365);
        assertThat(AdminConfigService.rangeOf("verify_token_hours")).isNull();
        assertThat(AdminConfigService.rangeOf("unknown_setting")).isNull();
    }

    @Test
    void literalSearchEscapesLikeWildcardsAndEscapeCharacter() {
        assertThat(AdminUserService.escapeLike("100%_\\done")).isEqualTo("100\\%\\_\\\\done");
        assertThat(AdminUserService.escapeLike("tiếng Việt-日本語")).isEqualTo("tiếng Việt-日本語");
    }

    @Test
    void activityLogDescriptionCutPreservesSurrogatePairs() {
        assertThat(ActivityLogService.cut("a".repeat(499) + "😀", 500)).isEqualTo("a".repeat(499));
        assertThat(ActivityLogService.cut("a".repeat(498) + "😀", 500)).isEqualTo("a".repeat(498) + "😀");
        assertThat(ActivityLogService.cut("short", 500)).isEqualTo("short");
        assertThat(ActivityLogService.cut(null, 500)).isNull();
    }

    @Test
    void presetPeriodsUseInclusiveDates() {
        assertThat(AdminOverviewService.resolvePeriod("today", null, null, TODAY))
                .isEqualTo(new Period("today", "2026-11-01", "2026-11-01"));
        assertThat(AdminOverviewService.resolvePeriod("last7days", null, null, TODAY).from()).isEqualTo("2026-10-26");
        assertThat(AdminOverviewService.resolvePeriod("last30days", null, null, TODAY).from()).isEqualTo("2026-10-03");
        assertThat(AdminOverviewService.resolvePeriod("custom", "2026-11-01", "2026-11-01", TODAY))
                .isEqualTo(new Period("custom", "2026-11-01", "2026-11-01"));
    }

    @Test
    void customPeriodAcceptsAtMost366InclusiveDays() {
        assertThat(AdminOverviewService.resolvePeriod("custom", "2025-11-01", "2026-11-01", TODAY).from())
                .isEqualTo("2025-11-01");
        assertThatThrownBy(() -> AdminOverviewService.resolvePeriod("custom", "2025-10-31", "2026-11-01", TODAY))
                .hasMessage(AdminOverviewService.INVALID_RANGE);
    }

    @Test
    void malformedReversedFutureAndUnknownPeriodsAreRejected() {
        assertThatThrownBy(() -> AdminOverviewService.resolvePeriod("unknown", null, null, TODAY))
                .hasMessage(AdminOverviewService.INVALID_RANGE);
        assertThatThrownBy(() -> AdminOverviewService.resolvePeriod("custom", "2026/11/01", "2026-11-01", TODAY))
                .hasMessage(AdminOverviewService.INVALID_RANGE);
        assertThatThrownBy(() -> AdminOverviewService.resolvePeriod("custom", "2026-11-02", "2026-11-01", TODAY))
                .hasMessage(AdminOverviewService.INVALID_RANGE);
        assertThatThrownBy(() -> AdminOverviewService.resolvePeriod("custom", "2026-11-01", "2026-11-02", TODAY))
                .hasMessage(AdminOverviewService.INVALID_RANGE);
    }

    @Test
    void quotaMeterWarnsAtEightyPercentAndCapsDisplayedPercent() {
        QuotaMeter below = AdminOverviewService.meter("k", "l", 79, 100, "u", "w");
        QuotaMeter atThreshold = AdminOverviewService.meter("k", "l", 80, 100, "u", "w");
        QuotaMeter above = AdminOverviewService.meter("k", "l", 150, 100, "u", "w");

        assertThat(below.alert()).isFalse();
        assertThat(atThreshold.alert()).isTrue();
        assertThat(above.alert()).isTrue();
        assertThat(above.percent()).isEqualTo(100.0);
    }

    @Test
    void quotaWithoutPositiveLimitDoesNotRaiseThresholdAlert() {
        QuotaMeter noLimit = AdminOverviewService.meter("k", "l", 999, 0, "u", "w");

        assertThat(noLimit.alert()).isFalse();
        assertThat(noLimit.percent()).isZero();
    }

    @Test
    void keywordNormalizationAcceptsLatinAndCjkAtTheirMinimumLengths() {
        assertThat(AdminConfigService.checkedKeyword("  Scam ")).isEqualTo("scam");
        assertThat(AdminConfigService.checkedKeyword("詐欺")).isEqualTo("詐欺");
        assertThat(AdminConfigService.checkedKeyword("tokyo詐欺")).isEqualTo("tokyo詐欺");
    }

    @Test
    void keywordValidationRejectsBlankPunctuationShortWordsAndExcessLength() {
        for (String invalid : new String[] {null, "  ", "!!!", "ab", "詐", "a".repeat(101)}) {
            assertThatThrownBy(() -> AdminConfigService.checkedKeyword(invalid))
                    .isInstanceOf(ApiException.class);
        }
    }

    @Test
    void safetyConfigParserAcceptsIntegersAndAsciiDigitStringsOnly() {
        assertThat(AdminSafetyConfigService.parseWholeNumber(12)).isEqualTo(12);
        assertThat(AdminSafetyConfigService.parseWholeNumber(12L)).isEqualTo(12);
        assertThat(AdminSafetyConfigService.parseWholeNumber(" 007 ")).isEqualTo(7);
        for (Object invalid : new Object[] {null, "", "  ", "-1", "1.5", "+2", "５", 1.5, -1, Long.MAX_VALUE}) {
            assertThat(AdminSafetyConfigService.parseWholeNumber(invalid)).isNull();
        }
    }

    @Test
    void staleAccountGuardRejectsChangedValuesButAllowsMatchingOrUnconditionalUpdates() {
        assertThatCode(() -> AdminUserService.assertNotStale("ADMIN", "admin")).doesNotThrowAnyException();
        assertThatCode(() -> AdminUserService.assertNotStale(null, "ADMIN")).doesNotThrowAnyException();
        assertThatCode(() -> AdminUserService.assertNotStale(" ", "ACTIVE")).doesNotThrowAnyException();
        assertThatThrownBy(() -> AdminUserService.assertNotStale("USER", "MODERATOR"))
                .isInstanceOf(ApiException.class)
                .hasMessage(AdminUserService.STALE_ACCOUNT);
    }

    @Test
    void activeAdminGuardProtectsOnlyAnActiveAdmin() {
        AdminDtos.UserItem activeAdmin = userItem("ADMIN", "ACTIVE");
        AdminDtos.UserItem lockedAdmin = userItem("ADMIN", "LOCKED");
        AdminDtos.UserItem activeUser = userItem("USER", "ACTIVE");

        assertThatThrownBy(() -> AdminUserService.assertNotActiveAdmin(activeAdmin))
                .isInstanceOf(ApiException.class)
                .hasMessage(AdminUserService.ADMIN_PROTECTED);
        assertThatCode(() -> AdminUserService.assertNotActiveAdmin(lockedAdmin)).doesNotThrowAnyException();
        assertThatCode(() -> AdminUserService.assertNotActiveAdmin(activeUser)).doesNotThrowAnyException();
    }

    @Test
    void safetyCardMembershipIncludesEveryReportSafetySetting() {
        for (String key : new String[] {"report_hide_threshold", "false_report_max", "mute_days",
                "closed_report_threshold", "proof_keep_days", "removed_keep_days"}) {
            assertThat(AdminSafetyConfigService.isSafetyKey(key)).as(key).isTrue();
        }
        assertThat(AdminSafetyConfigService.isSafetyKey("lock_minutes")).isFalse();
        assertThat(AdminSafetyConfigService.isSafetyKey("unknown_setting")).isFalse();
    }

    @Test
    void keywordNormalizationTrimsAndUsesLocaleIndependentLowercase() {
        assertThat(AdminConfigService.checkedKeyword("  ÉTUDIANTS  ")).isEqualTo("étudiants");
        assertThat(AdminConfigService.checkedKeyword(" TOKYO-日本 ")).isEqualTo("tokyo-日本");
    }

    @Test
    void keywordBoundaryAllowsExactlyOneHundredCharacters() {
        String maximum = "a".repeat(100);
        assertThat(AdminConfigService.checkedKeyword(maximum)).isEqualTo(maximum);
        assertThatThrownBy(() -> AdminConfigService.checkedKeyword(maximum + "a"))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void defaultAndCaseInsensitiveOverviewPresetsAreResolved() {
        assertThat(AdminOverviewService.resolvePeriod(null, null, null, TODAY).preset()).isEqualTo("last7days");
        assertThat(AdminOverviewService.resolvePeriod("TODAY", null, null, TODAY).from()).isEqualTo("2026-11-01");
        assertThat(AdminOverviewService.resolvePeriod(" Last7Days ", null, null, TODAY).from()).isEqualTo("2026-10-26");
    }

    @Test
    void quotaPercentageRoundsToOneDecimalPlace() {
        QuotaMeter meter = AdminOverviewService.meter("k", "l", 1, 6, "u", "w");
        assertThat(meter.percent()).isEqualTo(16.7);
        assertThat(meter.alert()).isFalse();
    }

    @Test
    void allWiredSettingsHaveValidDocumentedRanges() {
        for (String key : new String[] {"report_hide_threshold", "false_report_max", "mute_days",
                "closed_report_threshold", "new_account_days", "posts_per_day", "pins_per_day",
                "pin_auto_approve", "pin_auto_min_points", "pin_auto_per_day", "videos_per_day",
                "max_moderators", "password_min_length", "lock_attempts", "lock_minutes",
                "email_code_minutes", "email_code_attempts", "email_code_resend_seconds",
                "register_ticket_minutes", "log_keep_days", "proof_keep_days", "removed_keep_days",
                "featured_top_n", "featured_min_score"}) {
            int[] range = AdminConfigService.rangeOf(key);
            assertThat(range).as(key).isNotNull().hasSize(2);
            assertThat(range[0]).as(key + " lower limit").isLessThanOrEqualTo(range[1]);
        }
    }

    @Test
    void roleParsingNormalizesCaseAndRejectsUnknownRoles() {
        assertThat(AdminUserService.parseRole("user")).isEqualTo(Role.USER);
        assertThat(AdminUserService.parseRole(" admin ")).isEqualTo(Role.ADMIN);
        assertThat(AdminUserService.parseRole("moderator")).isEqualTo(Role.MODERATOR);
        assertThat(AdminUserService.parseRole("academic_manager"))
                .isEqualTo(Role.ACADEMIC_MANAGER);
        assertThatThrownBy(() -> AdminUserService.parseRole("OWNER"))
                .isInstanceOf(ApiException.class).hasMessage("Vai trò không hợp lệ: OWNER");
    }

    @Test
    void selfChangeGuardRejectsOnlyTheActorAccount() {
        assertThatThrownBy(() -> AdminUserService.assertNotSelf(7L, 7L, "self change"))
                .isInstanceOf(ApiException.class).hasMessage("self change");
        assertThatCode(() -> AdminUserService.assertNotSelf(7L, 8L, "self change")).doesNotThrowAnyException();
        assertThatCode(() -> AdminUserService.assertNotSelf(7L, null, "self change")).doesNotThrowAnyException();
    }

    @Test
    void mutableStatusValidationAllowsOnlyActiveAndLocked() {
        assertThat(AdminUserService.parseMutableStatus(" active "))
                .isEqualTo(UserStatus.ACTIVE);
        assertThat(AdminUserService.parseMutableStatus("locked"))
                .isEqualTo(UserStatus.LOCKED);
        assertThatThrownBy(() -> AdminUserService.parseMutableStatus("DELETED"))
                .isInstanceOf(ApiException.class).hasMessageContaining("Chỉ có thể khóa (LOCKED) hoặc mở khóa (ACTIVE)");
        assertThatThrownBy(() -> AdminUserService.parseMutableStatus("UNKNOWN"))
                .isInstanceOf(ApiException.class).hasMessage("Trạng thái không hợp lệ: UNKNOWN");
    }

    @Test
    void appliedSettingValidationAcceptsInclusiveBoundsAndRejectsMalformedValues() {
        for (String key : new String[] {"report_hide_threshold", "false_report_max", "mute_days",
                "closed_report_threshold", "new_account_days", "posts_per_day", "pins_per_day",
                "pin_auto_approve", "pin_auto_min_points", "pin_auto_per_day", "videos_per_day",
                "max_moderators", "password_min_length", "lock_attempts", "lock_minutes",
                "email_code_minutes", "email_code_attempts", "email_code_resend_seconds",
                "register_ticket_minutes", "log_keep_days", "proof_keep_days", "removed_keep_days",
                "featured_top_n", "featured_min_score"}) {
            int[] range = AdminConfigService.rangeOf(key);
            assertThat(AdminConfigService.checkedValue(key, Integer.toString(range[0])))
                    .isEqualTo(Integer.toString(range[0]));
            assertThat(AdminConfigService.checkedValue(key, Integer.toString(range[1])))
                    .isEqualTo(Integer.toString(range[1]));
            assertThatThrownBy(() -> AdminConfigService.checkedValue(key, Integer.toString(range[0] - 1)))
                    .isInstanceOf(ApiException.class);
            assertThatThrownBy(() -> AdminConfigService.checkedValue(key, Integer.toString(range[1] + 1)))
                    .isInstanceOf(ApiException.class);
        }
        for (String invalid : new String[] {null, "", " ", "1.5", "-1", "١", "no"}) {
            assertThatThrownBy(() -> AdminConfigService.checkedValue("posts_per_day", invalid))
                    .isInstanceOf(ApiException.class);
        }
        assertThatThrownBy(() -> AdminConfigService.checkedValue("unknown_setting", "1"))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void duplicateKeywordKeySquashesSpacingAndPunctuationButKeepsAccents() {
        assertThat(VietnameseText.squashKeepingMarks("Vay Nóng!")).isEqualTo(
                VietnameseText.squashKeepingMarks("vay-nóng"));
        assertThat(VietnameseText.squashKeepingMarks("vay nóng"))
                .isNotEqualTo(VietnameseText.squashKeepingMarks("vay nong"));
    }

    @Test
    void safetyValidationReportsEveryBadFieldBeforeReturningValues() {
        AdminSafetyConfigService service = new AdminSafetyConfigService(null, null);
        assertThat(service.validate(Map.of("new_account_days", 7, "posts_per_day", "12")))
                .containsExactlyInAnyOrderEntriesOf(Map.of("new_account_days", 7, "posts_per_day", 12));
        assertThatThrownBy(() -> service.validate(Map.of("new_account_days", 7, "mute_days", 0,
                "false_report_max", 1.5)))
                .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.getFields().keySet())
                        .containsExactlyInAnyOrder("mute_days", "false_report_max"));
        assertThatThrownBy(() -> service.validate(Map.of("posts_per_day", 12, "duplicate_warn_radius_m", 10)))
                .isInstanceOf(ApiException.class).hasMessage("Cài đặt không hợp lệ: duplicate_warn_radius_m");
    }

    @Test
    void activityLogJsonPreservesQuotesSlashesUnicodeAndScriptLikeText() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        Map<String, Object> source = Map.of("text", "\"quote\" \\\\ / 日本語 😀 </script>", "amount", 12);
        String json = ActivityLogService.jsonValue(source, mapper);
        assertThat(mapper.readValue(json, new TypeReference<Map<String, Object>>() {})).isEqualTo(source);
        assertThat(ActivityLogService.jsonValue(null, mapper)).isNull();
        assertThat(ActivityLogService.cut("a".repeat(999) + "😀", 1000)).isEqualTo("a".repeat(999));
    }

    private static AdminDtos.UserItem userItem(String role, String status) {
        return new AdminDtos.UserItem(1L, "qa@example.test", "QA", null, role, role, status,
                0, 0, Instant.EPOCH, null, 0, false, null, List.of());
    }
}

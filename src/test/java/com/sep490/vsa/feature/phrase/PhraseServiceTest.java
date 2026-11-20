package com.sep490.vsa.feature.phrase;

import com.sep490.vsa.common.exception.ApiException;
import com.sep490.vsa.common.security.AppUserPrincipal;
import com.sep490.vsa.feature.phrase.PhraseDtos.FlashRequest;
import com.sep490.vsa.feature.phrase.PhraseDtos.Group;
import com.sep490.vsa.feature.phrase.PhraseDtos.Marks;
import com.sep490.vsa.feature.phrase.PhraseDtos.Overview;
import com.sep490.vsa.feature.phrase.PhraseDtos.Part;
import com.sep490.vsa.feature.phrase.PhraseDtos.ReportRequest;
import com.sep490.vsa.feature.phrase.PhraseRepository.Account;
import com.sep490.vsa.feature.phrase.PhraseRepository.Filter;
import com.sep490.vsa.feature.report.ContentReportService;
import com.sep490.vsa.feature.report.ContentReportService.Reason;
import com.sep490.vsa.feature.report.ContentReportService.Target;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.ClassOrderer;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.TestClassOrder;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests of the Member / Guest side of "Nhật ngữ" ({@link PhraseService}), written from the full use case
 * specification (docs/use-cases/dac-ta-uc-hoc-tieng-nhat-va-su-kien.md, UC 7 "Duyệt chủ đề học tiếng Nhật" and UC 8
 * "Xem chi tiết chủ đề học tiếng Nhật") and the business rules (docs/use-cases/business-rules-vi.md: GBR-10, GBR-12,
 * GBR-13, GBR-36, GBR-37, GBR-49).
 * <p>
 * The database is not used: {@link PhraseRepository} and {@link ContentReportService} are mocks.
 */
@TestClassOrder(ClassOrderer.DisplayName.class)
@DisplayName("PhraseService – Nhật ngữ phía Khách / Thành viên (UC 7, UC 8)")
class PhraseServiceTest {

    private static final long MEMBER_ID = 10L;
    private static final long PHRASE_ID = 501L;
    private static final long EDITOR_ID = 3L;

    private static final AppUserPrincipal MEMBER = principal(MEMBER_ID, "USER");
    private static final AppUserPrincipal MODERATOR = principal(20L, "MODERATOR");
    private static final AppUserPrincipal EDITOR = principal(EDITOR_ID, "ACADEMIC_MANAGER");
    private static final AppUserPrincipal ADMIN = principal(1L, "ADMIN");

    private static final String MSG_LOGIN = "Bạn cần đăng nhập để thực hiện thao tác này.";
    private static final String MSG18 = "Bạn không có quyền thực hiện thao tác này.";
    private static final String MSG_NOT_FOUND = "Không tìm thấy mẫu câu này.";

    private static ValidatorFactory validatorFactory;
    private static Validator validator;

    private PhraseRepository repository;
    private ContentReportService reports;
    private PhraseService service;

    @BeforeAll
    static void startValidator() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        validator = validatorFactory.getValidator();
    }

    @AfterAll
    static void closeValidator() {
        validatorFactory.close();
    }

    @BeforeEach
    void setUp() {
        repository = mock(PhraseRepository.class);
        reports = mock(ContentReportService.class);
        service = new PhraseService(repository, reports);
        account(MEMBER_ID, "USER", "ACTIVE");
        account(20L, "MODERATOR", "ACTIVE");
        account(EDITOR_ID, "ACADEMIC_MANAGER", "ACTIVE");
        account(1L, "ADMIN", "ACTIVE");
        when(repository.lockPhrase(eq(PHRASE_ID), anyBoolean())).thenReturn(Optional.of(EDITOR_ID));
        when(repository.marks(anyLong(), anyLong())).thenReturn(new boolean[] {false, false});
        when(repository.kindTotals(anyLong(), anyString())).thenReturn(new int[] {0, 0});
        when(reports.academicReasons()).thenReturn(List.of(new Reason(1, "Sai tiếng Nhật"), new Reason(2, "Sai nghĩa"),
                new Reason(3, "Xúc phạm"), new Reason(4, "Spam")));
    }

    private static AppUserPrincipal principal(long id, String role) {
        return new AppUserPrincipal(id, "user" + id + "@example.org", "User " + id, role, "/");
    }

    private void account(long id, String role, String status) {
        when(repository.account(id)).thenReturn(Optional.of(new Account(role, status)));
    }

    private static void assertApiError(Runnable call, HttpStatus status, String message) {
        assertThatThrownBy(call::run)
                .isInstanceOf(ApiException.class)
                .hasMessage(message)
                .extracting(e -> ((ApiException) e).getStatus())
                .isEqualTo(status);
    }

    // =================================================================================================================
    @Nested
    @TestMethodOrder(MethodOrderer.DisplayName.class)
    @DisplayName("01. overview – UC 7 Duyệt chủ đề học tiếng Nhật (UTC-001 → UTC-010)")
    class OverviewPage {

        @Test
        @DisplayName("UTC-001 Mở trang: có hai phần Mẫu câu và Từ vựng")
        void utc001_overview_hasPhraseAndVocabParts() {
            Overview overview = service.overview(null);

            assertThat(overview.parts()).extracting(Part::kind).containsExactlyInAnyOrder("PHRASE", "VOCAB");
            assertThat(overview.parts()).extracting(Part::name).containsExactlyInAnyOrder("Mẫu câu", "Từ vựng");
            assertThat(overview.parts()).allSatisfy(p -> assertThat(p.icon()).isNotBlank());
        }

        @Test
        @DisplayName("UTC-002 Số mục, số đã thuộc tính riêng từng loại")
        void utc002_overview_countsEntriesAndLearnedPerKind() {
            when(repository.kindTotals(MEMBER_ID, "PHRASE")).thenReturn(new int[] {12, 4});
            when(repository.kindTotals(MEMBER_ID, "VOCAB")).thenReturn(new int[] {30, 7});

            Overview overview = service.overview(MEMBER);

            Part phrase = overview.parts().stream().filter(p -> p.kind().equals("PHRASE")).findFirst().orElseThrow();
            Part vocab = overview.parts().stream().filter(p -> p.kind().equals("VOCAB")).findFirst().orElseThrow();
            assertThat(phrase.count()).isEqualTo(12);
            assertThat(phrase.learned()).isEqualTo(4);
            assertThat(vocab.count()).isEqualTo(30);
            assertThat(vocab.learned()).isEqualTo(7);
        }

        @Test
        @DisplayName("UTC-003 Mỗi phần chỉ có chủ đề, danh mục có mục")
        void utc003_overview_listsTopicsAndCategoriesOfThatKindOnly() {
            Group pay = new Group("pay", "Thanh toán", "payments", 5, 2);
            Group konbini = new Group("konbini", "Cửa hàng tiện lợi", "store", 8, 1);
            when(repository.topics(MEMBER_ID, "VOCAB")).thenReturn(List.of(pay));
            when(repository.categories(MEMBER_ID, "VOCAB")).thenReturn(List.of(konbini));
            when(repository.topics(MEMBER_ID, "PHRASE")).thenReturn(List.of());
            when(repository.categories(MEMBER_ID, "PHRASE")).thenReturn(List.of());

            Overview overview = service.overview(MEMBER);

            Part vocab = overview.parts().stream().filter(p -> p.kind().equals("VOCAB")).findFirst().orElseThrow();
            Part phrase = overview.parts().stream().filter(p -> p.kind().equals("PHRASE")).findFirst().orElseThrow();
            assertThat(vocab.topics()).containsExactly(pay);
            assertThat(vocab.categories()).containsExactly(konbini);
            assertThat(phrase.topics()).isEmpty();
            assertThat(phrase.categories()).isEmpty();
        }

        @Test
        @DisplayName("UTC-004 Khách: không có tiến độ, không có \"Đã lưu\"")
        void utc004_guestOpensPage_noProgressNoSavedCount() {
            Overview overview = service.overview(null);

            assertThat(overview.member()).isFalse();
            assertThat(overview.savedCount()).isZero();
            verify(repository).kindTotals(-1L, "PHRASE");
            verify(repository).kindTotals(-1L, "VOCAB");
            verify(repository, never()).savedCount(anyLong());
        }

        @Test
        @DisplayName("UTC-005 Thành viên: \"Đã lưu (n)\" đúng số mục")
        void utc005_memberOpensPage_seesSavedCount() {
            when(repository.savedCount(MEMBER_ID)).thenReturn(6);

            Overview overview = service.overview(MEMBER);

            assertThat(overview.member()).isTrue();
            assertThat(overview.savedCount()).isEqualTo(6);
            verify(repository).kindTotals(MEMBER_ID, "PHRASE");
        }

        @ParameterizedTest(name = "[{index}] vai trò {0}")
        @ValueSource(strings = {"MODERATOR", "ACADEMIC_MANAGER", "ADMIN"})
        @DisplayName("UTC-006 Nhân viên: xem như Khách")
        void utc006_staffOpensPage_treatedAsGuest(String role) {
            AppUserPrincipal staff = switch (role) {
                case "MODERATOR" -> MODERATOR;
                case "ACADEMIC_MANAGER" -> EDITOR;
                default -> ADMIN;
            };

            Overview overview = service.overview(staff);

            assertThat(overview.member()).isFalse();
            assertThat(overview.savedCount()).isZero();
            verify(repository).kindTotals(-1L, "PHRASE");
            verify(repository, never()).savedCount(anyLong());
        }

        @Test
        @DisplayName("UTC-007 Tài khoản bị khóa: xem như Khách")
        void utc007_lockedAccountOpensPage_treatedAsGuest() {
            account(MEMBER_ID, "USER", "LOCKED");

            Overview overview = service.overview(MEMBER);

            assertThat(overview.member()).isFalse();
            verify(repository).kindTotals(-1L, "VOCAB");
        }

        @Test
        @DisplayName("UTC-008 Có đủ 4 lý do báo lỗi")
        void utc008_overview_offersPhraseReportReasons() {
            Overview overview = service.overview(null);

            assertThat(overview.reportReasons()).extracting(Reason::label)
                    .containsExactly("Sai tiếng Nhật", "Sai nghĩa", "Xúc phạm", "Spam");
            verify(reports).academicReasons();
            verify(reports, never()).reasons();
        }

        @Test
        @DisplayName("UTC-009 Giới hạn 1000 thẻ mỗi danh sách")
        void utc009_overview_givesListLimit1000() {
            assertThat(service.overview(null).listMax()).isEqualTo(PhraseRepository.LIST_MAX).isEqualTo(1000);
        }

        @Test
        @DisplayName("UTC-010 Mở trang không thay đổi dữ liệu")
        void utc010_overview_changesNoData() {
            service.overview(MEMBER);

            verify(repository, never()).setFlash(anyLong(), anyLong(), anyBoolean());
            verify(repository, never()).clearFlash(anyLong(), anyLong());
            verify(repository, never()).setSaved(anyLong(), anyLong(), anyBoolean());
            verify(reports, never()).report(any(), anyLong(), any(), any());
        }
    }

    // =================================================================================================================
    @Nested
    @TestMethodOrder(MethodOrderer.DisplayName.class)
    @DisplayName("02. list – UC 7 AF2 Tìm kiếm, UC 8 Xem chi tiết chủ đề (UTC-011 → UTC-024)")
    class ListPhrases {

        @Test
        @DisplayName("UTC-011 Từ khóa được bỏ khoảng trắng hai đầu")
        void utc011_search_trimsKeyword() {
            service.list(null, null, null, null, "  ryoushuusho  ");

            ArgumentCaptor<Filter> filter = ArgumentCaptor.forClass(Filter.class);
            verify(repository).phrases(filter.capture(), eq(-1L));
            assertThat(filter.getValue().keyword()).isEqualTo("ryoushuusho");
        }

        @ParameterizedTest(name = "[{index}] từ khóa \"{0}\"")
        @ValueSource(strings = {"", "   ", "\t"})
        @DisplayName("UTC-012 Ô tìm kiếm trống: không tìm")
        void utc012_search_blankKeywordMeansNoSearch(String q) {
            service.list(null, null, null, null, q);

            ArgumentCaptor<Filter> filter = ArgumentCaptor.forClass(Filter.class);
            verify(repository).phrases(filter.capture(), eq(-1L));
            assertThat(filter.getValue().keyword()).isNull();
        }

        @Test
        @DisplayName("UTC-013 Từ khóa quá 100 ký tự: cắt còn 100")
        void utc013_search_keywordOver100CharsIsCut() {
            String longText = "あ".repeat(150);

            service.list(null, null, null, null, longText);

            ArgumentCaptor<Filter> filter = ArgumentCaptor.forClass(Filter.class);
            verify(repository).phrases(filter.capture(), eq(-1L));
            assertThat(filter.getValue().keyword()).hasSize(100).isEqualTo("あ".repeat(100));
        }

        @Test
        @DisplayName("UTC-014 Khách tìm kiếm được")
        void utc014_guestSearches_getsResults() {
            PhraseDtos.PhraseCard card = new PhraseDtos.PhraseCard(PHRASE_ID, "PHRASE", "領収書をください。",
                    "Ryōshūsho o kudasai.", "Cho tôi xin hóa đơn.", new PhraseDtos.CategoryRef("konbini",
                    "Cửa hàng tiện lợi", "store"), List.of(new PhraseDtos.TopicRef("pay", "Thanh toán")), false, false,
                    false);
            when(repository.phrases(any(), eq(-1L))).thenReturn(List.of(card));

            assertThat(service.list(null, null, null, null, "hóa đơn")).containsExactly(card);
        }

        @Test
        @DisplayName("UTC-015 Lọc đúng theo chủ đề")
        void utc015_openTopic_filtersByTopicId() {
            service.list(null, " pay ", null, null, null);

            verify(repository).phrases(new Filter("pay", null, null, null), -1L);
        }

        @Test
        @DisplayName("UTC-016 Lọc đúng theo danh mục địa điểm")
        void utc016_openCategory_filtersByCategoryId() {
            service.list(MEMBER, null, "konbini", null, null);

            verify(repository).phrases(new Filter(null, "konbini", null, null), MEMBER_ID);
        }

        @Test
        @DisplayName("UTC-017 Thành viên thấy nhãn của mình trên thẻ")
        void utc017_memberOpensTopic_seesOwnMarks() {
            service.list(MEMBER, "pay", null, null, null);

            verify(repository).phrases(any(), eq(MEMBER_ID));
        }

        @Test
        @DisplayName("UTC-018 Nhân viên xem như Khách, không có nhãn")
        void utc018_staffOpensTopic_seesNoMarks() {
            service.list(MODERATOR, "pay", null, null, null);

            verify(repository).phrases(any(), eq(-1L));
        }

        @ParameterizedTest(name = "[{index}] danh sách {0}")
        @ValueSource(strings = {"learning", "learned", "saved"})
        @DisplayName("UTC-019 Thành viên mở được danh sách của mình")
        void utc019_memberOpensOwnList_succeeds(String mine) {
            service.list(MEMBER, null, null, mine, null);

            verify(repository).phrases(new Filter(null, null, mine, null), MEMBER_ID);
        }

        @ParameterizedTest(name = "[{index}] danh sách {0}")
        @ValueSource(strings = {"learning", "learned", "saved"})
        @DisplayName("UTC-020 Khách mở danh sách riêng: phải đăng nhập")
        void utc020_guestOpensOwnList_401(String mine) {
            assertApiError(() -> service.list(null, null, null, mine, null), HttpStatus.UNAUTHORIZED, MSG_LOGIN);
            verify(repository, never()).phrases(any(), anyLong());
        }

        @Test
        @DisplayName("UTC-021 Nhân viên mở danh sách riêng: bị từ chối")
        void utc021_staffOpensOwnList_403() {
            assertApiError(() -> service.list(EDITOR, null, null, "saved", null), HttpStatus.FORBIDDEN, MSG18);
            verify(repository, never()).phrases(any(), anyLong());
        }

        @Test
        @DisplayName("UTC-022 Tên danh sách sai: báo lỗi")
        void utc022_unknownListName_400() {
            assertApiError(() -> service.list(MEMBER, null, null, "favorites", null), HttpStatus.BAD_REQUEST,
                    "Danh sách không hợp lệ.");
            verify(repository, never()).phrases(any(), anyLong());
        }

        @Test
        @DisplayName("UTC-023 Bộ lọc để trống: không lọc")
        void utc023_blankParameters_noFilter() {
            service.list(null, " ", "", "  ", null);

            verify(repository).phrases(new Filter(null, null, null, null), -1L);
        }

        @Test
        @DisplayName("UTC-024 Xem danh sách không thay đổi dữ liệu")
        void utc024_list_changesNoData() {
            service.list(MEMBER, "pay", null, null, null);

            verify(repository, never()).setFlash(anyLong(), anyLong(), anyBoolean());
            verify(repository, never()).clearFlash(anyLong(), anyLong());
            verify(repository, never()).setSaved(anyLong(), anyLong(), anyBoolean());
            verify(reports, never()).report(any(), anyLong(), any(), any());
        }
    }

    // =================================================================================================================
    @Nested
    @TestMethodOrder(MethodOrderer.DisplayName.class)
    @DisplayName("03. save – UC 8 AF1 Lưu / bỏ lưu mục (UTC-025 → UTC-031)")
    class Save {

        @Test
        @DisplayName("UTC-025 Bấm \"Lưu\": mục được lưu")
        void utc025_memberSaves_saved() {
            PhraseDtos.SaveState state = service.save(MEMBER, PHRASE_ID, true);

            assertThat(state.saved()).isTrue();
            verify(repository).lockPhrase(PHRASE_ID, false);
            verify(repository).setSaved(MEMBER_ID, PHRASE_ID, true);
        }

        @Test
        @DisplayName("UTC-026 Bấm \"Đã lưu\": bỏ lưu")
        void utc026_memberUnsaves_notSaved() {
            PhraseDtos.SaveState state = service.save(MEMBER, PHRASE_ID, false);

            assertThat(state.saved()).isFalse();
            verify(repository).setSaved(MEMBER_ID, PHRASE_ID, false);
        }

        @Test
        @DisplayName("UTC-027 Khách bấm \"Lưu\": phải đăng nhập")
        void utc027_guestSaves_401() {
            assertApiError(() -> service.save(null, PHRASE_ID, true), HttpStatus.UNAUTHORIZED, MSG_LOGIN);
            verify(repository, never()).setSaved(anyLong(), anyLong(), anyBoolean());
        }

        @ParameterizedTest(name = "[{index}] vai trò {0}")
        @ValueSource(strings = {"MODERATOR", "ACADEMIC_MANAGER", "ADMIN"})
        @DisplayName("UTC-028 Nhân viên bấm \"Lưu\": bị từ chối")
        void utc028_staffSaves_403(String role) {
            AppUserPrincipal staff = "MODERATOR".equals(role) ? MODERATOR : "ADMIN".equals(role) ? ADMIN : EDITOR;

            assertApiError(() -> service.save(staff, PHRASE_ID, true), HttpStatus.FORBIDDEN, MSG18);
            verify(repository, never()).setSaved(anyLong(), anyLong(), anyBoolean());
        }

        @Test
        @DisplayName("UTC-029 Lưu mục đã xóa: không tìm thấy")
        void utc029_saveDeletedPhrase_404() {
            when(repository.lockPhrase(PHRASE_ID, false)).thenReturn(Optional.empty());

            assertApiError(() -> service.save(MEMBER, PHRASE_ID, true), HttpStatus.NOT_FOUND, MSG_NOT_FOUND);
            verify(repository, never()).setSaved(anyLong(), anyLong(), anyBoolean());
        }

        @Test
        @DisplayName("UTC-030 Tài khoản bị khóa bấm \"Lưu\": bị từ chối")
        void utc030_lockedAccountSaves_403() {
            account(MEMBER_ID, "USER", "LOCKED");

            assertApiError(() -> service.save(MEMBER, PHRASE_ID, true), HttpStatus.FORBIDDEN, MSG18);
            verify(repository, never()).setSaved(anyLong(), anyLong(), anyBoolean());
        }

        @Test
        @DisplayName("UTC-031 Tài khoản không còn: phải đăng nhập")
        void utc031_accountGoneSaves_401() {
            when(repository.account(MEMBER_ID)).thenReturn(Optional.empty());

            assertApiError(() -> service.save(MEMBER, PHRASE_ID, true), HttpStatus.UNAUTHORIZED, "Bạn chưa đăng nhập.");
        }
    }

    // =================================================================================================================
    @Nested
    @TestMethodOrder(MethodOrderer.DisplayName.class)
    @DisplayName("04. flash – UC 8 AF3 Luyện thẻ ghi nhớ (UTC-032 → UTC-038)")
    class Flash {

        @Test
        @DisplayName("UTC-032 Chọn \"Đã thuộc\": thẻ thành Đã thuộc")
        void utc032_flashAnswerLearned_markedLearned() {
            when(repository.marks(MEMBER_ID, PHRASE_ID)).thenReturn(new boolean[] {false, true});

            Marks marks = service.flash(MEMBER, PHRASE_ID, true);

            verify(repository).setFlash(MEMBER_ID, PHRASE_ID, true);
            assertThat(marks.learned()).isTrue();
            assertThat(marks.learning()).isFalse();
        }

        @Test
        @DisplayName("UTC-033 Chọn \"Chưa thuộc\": thẻ thành Chưa thuộc")
        void utc033_flashAnswerNotLearned_markedLearning() {
            when(repository.marks(MEMBER_ID, PHRASE_ID)).thenReturn(new boolean[] {true, false});

            Marks marks = service.flash(MEMBER, PHRASE_ID, false);

            verify(repository).setFlash(MEMBER_ID, PHRASE_ID, false);
            assertThat(marks.learning()).isTrue();
            assertThat(marks.learned()).isFalse();
        }

        @Test
        @DisplayName("UTC-034 Trả lời hai lần: giữ lần sau cùng")
        void utc034_flashAnsweredTwice_lastAnswerCounts() {
            service.flash(MEMBER, PHRASE_ID, true);
            service.flash(MEMBER, PHRASE_ID, false);

            var order = org.mockito.Mockito.inOrder(repository);
            order.verify(repository).setFlash(MEMBER_ID, PHRASE_ID, true);
            order.verify(repository).setFlash(MEMBER_ID, PHRASE_ID, false);
        }

        @Test
        @DisplayName("UTC-035 Khách lật thẻ: phải đăng nhập")
        void utc035_guestFlash_401NotSaved() {
            assertApiError(() -> service.flash(null, PHRASE_ID, true), HttpStatus.UNAUTHORIZED, MSG_LOGIN);
            verify(repository, never()).setFlash(anyLong(), anyLong(), anyBoolean());
        }

        @Test
        @DisplayName("UTC-036 Nhân viên lật thẻ: bị từ chối")
        void utc036_staffFlash_403NotSaved() {
            assertApiError(() -> service.flash(EDITOR, PHRASE_ID, true), HttpStatus.FORBIDDEN, MSG18);
            verify(repository, never()).setFlash(anyLong(), anyLong(), anyBoolean());
        }

        @Test
        @DisplayName("UTC-037 Lật thẻ mục đã xóa: không tìm thấy")
        void utc037_flashDeletedPhrase_404() {
            when(repository.lockPhrase(PHRASE_ID, false)).thenReturn(Optional.empty());

            assertApiError(() -> service.flash(MEMBER, PHRASE_ID, true), HttpStatus.NOT_FOUND, MSG_NOT_FOUND);
            verify(repository, never()).setFlash(anyLong(), anyLong(), anyBoolean());
        }

        @Test
        @DisplayName("UTC-038 Không chọn kết quả lật thẻ: báo lỗi")
        void utc038_flashWithoutAnswer_validationMessage() {
            assertThat(validator.validate(new FlashRequest(true))).isEmpty();
            assertThat(validator.validate(new FlashRequest(false))).isEmpty();
            assertThat(validator.validate(new FlashRequest(null)))
                    .extracting(v -> v.getMessage())
                    .containsExactly("Hãy chọn Chưa thuộc hoặc Đã thuộc.");
        }
    }

    // =================================================================================================================
    @Nested
    @TestMethodOrder(MethodOrderer.DisplayName.class)
    @DisplayName("05. clearFlash – UC 8 AF2 Bỏ đánh dấu (UTC-039 → UTC-042)")
    class ClearFlash {

        @Test
        @DisplayName("UTC-039 \"Bỏ đánh dấu\": thẻ về Chưa luyện")
        void utc039_clearMarks_backToNotPractised() {
            Marks marks = service.clearFlash(MEMBER, PHRASE_ID);

            verify(repository).clearFlash(MEMBER_ID, PHRASE_ID);
            assertThat(marks.learning()).isFalse();
            assertThat(marks.learned()).isFalse();
        }

        @Test
        @DisplayName("UTC-040 Khách bỏ đánh dấu: phải đăng nhập")
        void utc040_guestClearFlash_401() {
            assertApiError(() -> service.clearFlash(null, PHRASE_ID), HttpStatus.UNAUTHORIZED, MSG_LOGIN);
            verify(repository, never()).clearFlash(anyLong(), anyLong());
        }

        @Test
        @DisplayName("UTC-041 Nhân viên bỏ đánh dấu: bị từ chối")
        void utc041_staffClearFlash_403() {
            assertApiError(() -> service.clearFlash(MODERATOR, PHRASE_ID), HttpStatus.FORBIDDEN, MSG18);
            verify(repository, never()).clearFlash(anyLong(), anyLong());
        }

        @Test
        @DisplayName("UTC-042 Bỏ đánh dấu mục đã xóa: không tìm thấy")
        void utc042_clearFlashDeletedPhrase_404() {
            when(repository.lockPhrase(PHRASE_ID, false)).thenReturn(Optional.empty());

            assertApiError(() -> service.clearFlash(MEMBER, PHRASE_ID), HttpStatus.NOT_FOUND, MSG_NOT_FOUND);
            verify(repository, never()).clearFlash(anyLong(), anyLong());
        }
    }

    // =================================================================================================================
    @Nested
    @TestMethodOrder(MethodOrderer.DisplayName.class)
    @DisplayName("06. reportPhrase – UC 8 AF5 Báo lỗi mẫu câu (UTC-043 → UTC-050)")
    class ReportPhrase {

        @Test
        @DisplayName("UTC-043 Báo lỗi có lý do: tạo báo cáo")
        void utc043_memberReports_reportCreatedForPhrase() {
            service.reportPhrase(MEMBER, PHRASE_ID, new ReportRequest(2L, "Nghĩa chưa đúng"));

            verify(repository).lockPhrase(PHRASE_ID, true);
            verify(reports).report(new Target("PHRASE", PHRASE_ID, EDITOR_ID), MEMBER_ID, 2L, "Nghĩa chưa đúng");
        }

        @Test
        @DisplayName("UTC-044 Báo lỗi không ghi chú: vẫn gửi được")
        void utc044_reportWithoutNote_accepted() {
            service.reportPhrase(MEMBER, PHRASE_ID, new ReportRequest(1L, null));

            verify(reports).report(new Target("PHRASE", PHRASE_ID, EDITOR_ID), MEMBER_ID, 1L, null);
        }

        @Test
        @DisplayName("UTC-045 Báo lỗi không chọn lý do: báo lỗi")
        void utc045_reportWithoutReason_validationMessage() {
            assertThat(validator.validate(new ReportRequest(null, "ghi chú")))
                    .extracting(v -> v.getMessage())
                    .containsExactly("Hãy chọn lý do báo cáo.");
            assertThat(validator.validate(new ReportRequest(1L, null))).isEmpty();
        }

        @Test
        @DisplayName("UTC-046 Báo lỗi lần hai: bị từ chối")
        void utc046_reportTwice_rejected() {
            when(reports.report(any(), anyLong(), any(), any()))
                    .thenThrow(ApiException.conflict("Bạn đã báo cáo nội dung này rồi."));

            assertApiError(() -> service.reportPhrase(MEMBER, PHRASE_ID, new ReportRequest(1L, null)),
                    HttpStatus.CONFLICT, "Bạn đã báo cáo nội dung này rồi.");
        }

        @Test
        @DisplayName("UTC-047 Vượt ngưỡng báo cáo: mục vẫn hiện")
        void utc047_reportOverThreshold_phraseNotHidden() {
            when(reports.report(any(), anyLong(), any(), any())).thenReturn(new ContentReportService.Outcome(9, true));

            service.reportPhrase(MEMBER, PHRASE_ID, new ReportRequest(4L, null));

            verify(repository).account(MEMBER_ID);
            verify(repository).lockPhrase(PHRASE_ID, true);
            verifyNoMoreInteractions(repository);
        }

        @Test
        @DisplayName("UTC-048 Khách báo lỗi: phải đăng nhập")
        void utc048_guestReports_401() {
            assertApiError(() -> service.reportPhrase(null, PHRASE_ID, new ReportRequest(1L, null)),
                    HttpStatus.UNAUTHORIZED, MSG_LOGIN);
            verifyNoInteractions(reports);
        }

        @Test
        @DisplayName("UTC-049 Nhân viên báo lỗi: bị từ chối")
        void utc049_staffReports_403() {
            assertApiError(() -> service.reportPhrase(EDITOR, PHRASE_ID, new ReportRequest(1L, null)),
                    HttpStatus.FORBIDDEN, MSG18);
            verifyNoInteractions(reports);
        }

        @Test
        @DisplayName("UTC-050 Báo lỗi mục đã xóa: không tìm thấy")
        void utc050_reportDeletedPhrase_404() {
            when(repository.lockPhrase(PHRASE_ID, true)).thenReturn(Optional.empty());

            assertApiError(() -> service.reportPhrase(MEMBER, PHRASE_ID, new ReportRequest(1L, null)),
                    HttpStatus.NOT_FOUND, MSG_NOT_FOUND);
            verifyNoInteractions(reports);
        }
    }

}


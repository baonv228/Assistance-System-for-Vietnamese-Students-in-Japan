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
    @DisplayName("01. overview – UC 7 Duyệt chủ đề học tiếng Nhật (UTC-087 → UTC-096)")
    class OverviewPage {

        @Test
        @DisplayName("UTC-087 Mở trang: có hai phần Mẫu câu và Từ vựng")
        void utc087_overview_hasPhraseAndVocabParts() {
            Overview overview = service.overview(null);

            assertThat(overview.parts()).extracting(Part::kind).containsExactlyInAnyOrder("PHRASE", "VOCAB");
            assertThat(overview.parts()).extracting(Part::name).containsExactlyInAnyOrder("Mẫu câu", "Từ vựng");
            assertThat(overview.parts()).allSatisfy(p -> assertThat(p.icon()).isNotBlank());
        }

        @Test
        @DisplayName("UTC-088 Số mục, số đã thuộc tính riêng từng loại")
        void utc088_overview_countsEntriesAndLearnedPerKind() {
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
        @DisplayName("UTC-089 Mỗi phần chỉ có chủ đề, danh mục có mục")
        void utc089_overview_listsTopicsAndCategoriesOfThatKindOnly() {
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
        @DisplayName("UTC-090 Khách: không có tiến độ, không có \"Đã lưu\"")
        void utc090_guestOpensPage_noProgressNoSavedCount() {
            Overview overview = service.overview(null);

            assertThat(overview.member()).isFalse();
            assertThat(overview.savedCount()).isZero();
            verify(repository).kindTotals(-1L, "PHRASE");
            verify(repository).kindTotals(-1L, "VOCAB");
            verify(repository, never()).savedCount(anyLong());
        }

        @Test
        @DisplayName("UTC-091 Thành viên: \"Đã lưu (n)\" đúng số mục")
        void utc091_memberOpensPage_seesSavedCount() {
            when(repository.savedCount(MEMBER_ID)).thenReturn(6);

            Overview overview = service.overview(MEMBER);

            assertThat(overview.member()).isTrue();
            assertThat(overview.savedCount()).isEqualTo(6);
            verify(repository).kindTotals(MEMBER_ID, "PHRASE");
        }

        @ParameterizedTest(name = "[{index}] vai trò {0}")
        @ValueSource(strings = {"MODERATOR", "ACADEMIC_MANAGER", "ADMIN"})
        @DisplayName("UTC-092 Nhân viên: xem như Khách")
        void utc092_staffOpensPage_treatedAsGuest(String role) {
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
        @DisplayName("UTC-093 Tài khoản bị khóa: xem như Khách")
        void utc093_lockedAccountOpensPage_treatedAsGuest() {
            account(MEMBER_ID, "USER", "LOCKED");

            Overview overview = service.overview(MEMBER);

            assertThat(overview.member()).isFalse();
            verify(repository).kindTotals(-1L, "VOCAB");
        }

        @Test
        @DisplayName("UTC-094 Có đủ 4 lý do báo lỗi")
        void utc094_overview_offersPhraseReportReasons() {
            Overview overview = service.overview(null);

            assertThat(overview.reportReasons()).extracting(Reason::label)
                    .containsExactly("Sai tiếng Nhật", "Sai nghĩa", "Xúc phạm", "Spam");
            verify(reports).academicReasons();
            verify(reports, never()).reasons();
        }

        @Test
        @DisplayName("UTC-095 Giới hạn 1000 thẻ mỗi danh sách")
        void utc095_overview_givesListLimit1000() {
            assertThat(service.overview(null).listMax()).isEqualTo(PhraseRepository.LIST_MAX).isEqualTo(1000);
        }

        @Test
        @DisplayName("UTC-096 Mở trang không thay đổi dữ liệu")
        void utc096_overview_changesNoData() {
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
    @DisplayName("02. list – UC 7 AF2 Tìm kiếm, UC 8 Xem chi tiết chủ đề (UTC-097 → UTC-110)")
    class ListPhrases {

        @Test
        @DisplayName("UTC-097 Từ khóa được bỏ khoảng trắng hai đầu")
        void utc097_search_trimsKeyword() {
            service.list(null, null, null, null, "  ryoushuusho  ");

            ArgumentCaptor<Filter> filter = ArgumentCaptor.forClass(Filter.class);
            verify(repository).phrases(filter.capture(), eq(-1L));
            assertThat(filter.getValue().keyword()).isEqualTo("ryoushuusho");
        }

        @ParameterizedTest(name = "[{index}] từ khóa \"{0}\"")
        @ValueSource(strings = {"", "   ", "\t"})
        @DisplayName("UTC-098 Ô tìm kiếm trống: không tìm")
        void utc098_search_blankKeywordMeansNoSearch(String q) {
            service.list(null, null, null, null, q);

            ArgumentCaptor<Filter> filter = ArgumentCaptor.forClass(Filter.class);
            verify(repository).phrases(filter.capture(), eq(-1L));
            assertThat(filter.getValue().keyword()).isNull();
        }

        @Test
        @DisplayName("UTC-099 Từ khóa quá 100 ký tự: cắt còn 100")
        void utc099_search_keywordOver100CharsIsCut() {
            String longText = "あ".repeat(150);

            service.list(null, null, null, null, longText);

            ArgumentCaptor<Filter> filter = ArgumentCaptor.forClass(Filter.class);
            verify(repository).phrases(filter.capture(), eq(-1L));
            assertThat(filter.getValue().keyword()).hasSize(100).isEqualTo("あ".repeat(100));
        }

        @Test
        @DisplayName("UTC-100 Khách tìm kiếm được")
        void utc100_guestSearches_getsResults() {
            PhraseDtos.PhraseCard card = new PhraseDtos.PhraseCard(PHRASE_ID, "PHRASE", "領収書をください。",
                    "Ryōshūsho o kudasai.", "Cho tôi xin hóa đơn.", new PhraseDtos.CategoryRef("konbini",
                    "Cửa hàng tiện lợi", "store"), List.of(new PhraseDtos.TopicRef("pay", "Thanh toán")), false, false,
                    false);
            when(repository.phrases(any(), eq(-1L))).thenReturn(List.of(card));

            assertThat(service.list(null, null, null, null, "hóa đơn")).containsExactly(card);
        }

        @Test
        @DisplayName("UTC-101 Lọc đúng theo chủ đề")
        void utc101_openTopic_filtersByTopicId() {
            service.list(null, " pay ", null, null, null);

            verify(repository).phrases(new Filter("pay", null, null, null), -1L);
        }

        @Test
        @DisplayName("UTC-102 Lọc đúng theo danh mục địa điểm")
        void utc102_openCategory_filtersByCategoryId() {
            service.list(MEMBER, null, "konbini", null, null);

            verify(repository).phrases(new Filter(null, "konbini", null, null), MEMBER_ID);
        }

        @Test
        @DisplayName("UTC-103 Thành viên thấy nhãn của mình trên thẻ")
        void utc103_memberOpensTopic_seesOwnMarks() {
            service.list(MEMBER, "pay", null, null, null);

            verify(repository).phrases(any(), eq(MEMBER_ID));
        }

        @Test
        @DisplayName("UTC-104 Nhân viên xem như Khách, không có nhãn")
        void utc104_staffOpensTopic_seesNoMarks() {
            service.list(MODERATOR, "pay", null, null, null);

            verify(repository).phrases(any(), eq(-1L));
        }

        @ParameterizedTest(name = "[{index}] danh sách {0}")
        @ValueSource(strings = {"learning", "learned", "saved"})
        @DisplayName("UTC-105 Thành viên mở được danh sách của mình")
        void utc105_memberOpensOwnList_succeeds(String mine) {
            service.list(MEMBER, null, null, mine, null);

            verify(repository).phrases(new Filter(null, null, mine, null), MEMBER_ID);
        }

        @ParameterizedTest(name = "[{index}] danh sách {0}")
        @ValueSource(strings = {"learning", "learned", "saved"})
        @DisplayName("UTC-106 Khách mở danh sách riêng: phải đăng nhập")
        void utc106_guestOpensOwnList_401(String mine) {
            assertApiError(() -> service.list(null, null, null, mine, null), HttpStatus.UNAUTHORIZED, MSG_LOGIN);
            verify(repository, never()).phrases(any(), anyLong());
        }

        @Test
        @DisplayName("UTC-107 Nhân viên mở danh sách riêng: bị từ chối")
        void utc107_staffOpensOwnList_403() {
            assertApiError(() -> service.list(EDITOR, null, null, "saved", null), HttpStatus.FORBIDDEN, MSG18);
            verify(repository, never()).phrases(any(), anyLong());
        }

        @Test
        @DisplayName("UTC-108 Tên danh sách sai: báo lỗi")
        void utc108_unknownListName_400() {
            assertApiError(() -> service.list(MEMBER, null, null, "favorites", null), HttpStatus.BAD_REQUEST,
                    "Danh sách không hợp lệ.");
            verify(repository, never()).phrases(any(), anyLong());
        }

        @Test
        @DisplayName("UTC-109 Bộ lọc để trống: không lọc")
        void utc109_blankParameters_noFilter() {
            service.list(null, " ", "", "  ", null);

            verify(repository).phrases(new Filter(null, null, null, null), -1L);
        }

        @Test
        @DisplayName("UTC-110 Xem danh sách không thay đổi dữ liệu")
        void utc110_list_changesNoData() {
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
    @DisplayName("03. save – UC 8 AF1 Lưu / bỏ lưu mục (UTC-111 → UTC-117)")
    class Save {

        @Test
        @DisplayName("UTC-111 Bấm \"Lưu\": mục được lưu")
        void utc111_memberSaves_saved() {
            PhraseDtos.SaveState state = service.save(MEMBER, PHRASE_ID, true);

            assertThat(state.saved()).isTrue();
            verify(repository).lockPhrase(PHRASE_ID, false);
            verify(repository).setSaved(MEMBER_ID, PHRASE_ID, true);
        }

        @Test
        @DisplayName("UTC-112 Bấm \"Đã lưu\": bỏ lưu")
        void utc112_memberUnsaves_notSaved() {
            PhraseDtos.SaveState state = service.save(MEMBER, PHRASE_ID, false);

            assertThat(state.saved()).isFalse();
            verify(repository).setSaved(MEMBER_ID, PHRASE_ID, false);
        }

        @Test
        @DisplayName("UTC-113 Khách bấm \"Lưu\": phải đăng nhập")
        void utc113_guestSaves_401() {
            assertApiError(() -> service.save(null, PHRASE_ID, true), HttpStatus.UNAUTHORIZED, MSG_LOGIN);
            verify(repository, never()).setSaved(anyLong(), anyLong(), anyBoolean());
        }

        @ParameterizedTest(name = "[{index}] vai trò {0}")
        @ValueSource(strings = {"MODERATOR", "ACADEMIC_MANAGER", "ADMIN"})
        @DisplayName("UTC-114 Nhân viên bấm \"Lưu\": bị từ chối")
        void utc114_staffSaves_403(String role) {
            AppUserPrincipal staff = "MODERATOR".equals(role) ? MODERATOR : "ADMIN".equals(role) ? ADMIN : EDITOR;

            assertApiError(() -> service.save(staff, PHRASE_ID, true), HttpStatus.FORBIDDEN, MSG18);
            verify(repository, never()).setSaved(anyLong(), anyLong(), anyBoolean());
        }

        @Test
        @DisplayName("UTC-115 Lưu mục đã xóa: không tìm thấy")
        void utc115_saveDeletedPhrase_404() {
            when(repository.lockPhrase(PHRASE_ID, false)).thenReturn(Optional.empty());

            assertApiError(() -> service.save(MEMBER, PHRASE_ID, true), HttpStatus.NOT_FOUND, MSG_NOT_FOUND);
            verify(repository, never()).setSaved(anyLong(), anyLong(), anyBoolean());
        }

        @Test
        @DisplayName("UTC-116 Tài khoản bị khóa bấm \"Lưu\": bị từ chối")
        void utc116_lockedAccountSaves_403() {
            account(MEMBER_ID, "USER", "LOCKED");

            assertApiError(() -> service.save(MEMBER, PHRASE_ID, true), HttpStatus.FORBIDDEN, MSG18);
            verify(repository, never()).setSaved(anyLong(), anyLong(), anyBoolean());
        }

        @Test
        @DisplayName("UTC-117 Tài khoản không còn: phải đăng nhập")
        void utc117_accountGoneSaves_401() {
            when(repository.account(MEMBER_ID)).thenReturn(Optional.empty());

            assertApiError(() -> service.save(MEMBER, PHRASE_ID, true), HttpStatus.UNAUTHORIZED, "Bạn chưa đăng nhập.");
        }
    }

    // =================================================================================================================
    @Nested
    @TestMethodOrder(MethodOrderer.DisplayName.class)
    @DisplayName("04. flash – UC 8 AF3 Luyện thẻ ghi nhớ (UTC-118 → UTC-124)")
    class Flash {

        @Test
        @DisplayName("UTC-118 Chọn \"Đã thuộc\": thẻ thành Đã thuộc")
        void utc118_flashAnswerLearned_markedLearned() {
            when(repository.marks(MEMBER_ID, PHRASE_ID)).thenReturn(new boolean[] {false, true});

            Marks marks = service.flash(MEMBER, PHRASE_ID, true);

            verify(repository).setFlash(MEMBER_ID, PHRASE_ID, true);
            assertThat(marks.learned()).isTrue();
            assertThat(marks.learning()).isFalse();
        }

        @Test
        @DisplayName("UTC-119 Chọn \"Chưa thuộc\": thẻ thành Chưa thuộc")
        void utc119_flashAnswerNotLearned_markedLearning() {
            when(repository.marks(MEMBER_ID, PHRASE_ID)).thenReturn(new boolean[] {true, false});

            Marks marks = service.flash(MEMBER, PHRASE_ID, false);

            verify(repository).setFlash(MEMBER_ID, PHRASE_ID, false);
            assertThat(marks.learning()).isTrue();
            assertThat(marks.learned()).isFalse();
        }

        @Test
        @DisplayName("UTC-120 Trả lời hai lần: giữ lần sau cùng")
        void utc120_flashAnsweredTwice_lastAnswerCounts() {
            service.flash(MEMBER, PHRASE_ID, true);
            service.flash(MEMBER, PHRASE_ID, false);

            var order = org.mockito.Mockito.inOrder(repository);
            order.verify(repository).setFlash(MEMBER_ID, PHRASE_ID, true);
            order.verify(repository).setFlash(MEMBER_ID, PHRASE_ID, false);
        }

        @Test
        @DisplayName("UTC-121 Khách lật thẻ: phải đăng nhập")
        void utc121_guestFlash_401NotSaved() {
            assertApiError(() -> service.flash(null, PHRASE_ID, true), HttpStatus.UNAUTHORIZED, MSG_LOGIN);
            verify(repository, never()).setFlash(anyLong(), anyLong(), anyBoolean());
        }

        @Test
        @DisplayName("UTC-122 Nhân viên lật thẻ: bị từ chối")
        void utc122_staffFlash_403NotSaved() {
            assertApiError(() -> service.flash(EDITOR, PHRASE_ID, true), HttpStatus.FORBIDDEN, MSG18);
            verify(repository, never()).setFlash(anyLong(), anyLong(), anyBoolean());
        }

        @Test
        @DisplayName("UTC-123 Lật thẻ mục đã xóa: không tìm thấy")
        void utc123_flashDeletedPhrase_404() {
            when(repository.lockPhrase(PHRASE_ID, false)).thenReturn(Optional.empty());

            assertApiError(() -> service.flash(MEMBER, PHRASE_ID, true), HttpStatus.NOT_FOUND, MSG_NOT_FOUND);
            verify(repository, never()).setFlash(anyLong(), anyLong(), anyBoolean());
        }

        @Test
        @DisplayName("UTC-124 Không chọn kết quả lật thẻ: báo lỗi")
        void utc124_flashWithoutAnswer_validationMessage() {
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
    @DisplayName("05. clearFlash – UC 8 AF2 Bỏ đánh dấu (UTC-125 → UTC-128)")
    class ClearFlash {

        @Test
        @DisplayName("UTC-125 \"Bỏ đánh dấu\": thẻ về Chưa luyện")
        void utc125_clearMarks_backToNotPractised() {
            Marks marks = service.clearFlash(MEMBER, PHRASE_ID);

            verify(repository).clearFlash(MEMBER_ID, PHRASE_ID);
            assertThat(marks.learning()).isFalse();
            assertThat(marks.learned()).isFalse();
        }

        @Test
        @DisplayName("UTC-126 Khách bỏ đánh dấu: phải đăng nhập")
        void utc126_guestClearFlash_401() {
            assertApiError(() -> service.clearFlash(null, PHRASE_ID), HttpStatus.UNAUTHORIZED, MSG_LOGIN);
            verify(repository, never()).clearFlash(anyLong(), anyLong());
        }

        @Test
        @DisplayName("UTC-127 Nhân viên bỏ đánh dấu: bị từ chối")
        void utc127_staffClearFlash_403() {
            assertApiError(() -> service.clearFlash(MODERATOR, PHRASE_ID), HttpStatus.FORBIDDEN, MSG18);
            verify(repository, never()).clearFlash(anyLong(), anyLong());
        }

        @Test
        @DisplayName("UTC-128 Bỏ đánh dấu mục đã xóa: không tìm thấy")
        void utc128_clearFlashDeletedPhrase_404() {
            when(repository.lockPhrase(PHRASE_ID, false)).thenReturn(Optional.empty());

            assertApiError(() -> service.clearFlash(MEMBER, PHRASE_ID), HttpStatus.NOT_FOUND, MSG_NOT_FOUND);
            verify(repository, never()).clearFlash(anyLong(), anyLong());
        }
    }

    // =================================================================================================================
    @Nested
    @TestMethodOrder(MethodOrderer.DisplayName.class)
    @DisplayName("06. reportPhrase – UC 8 AF5 Báo lỗi mẫu câu (UTC-129 → UTC-136)")
    class ReportPhrase {

        @Test
        @DisplayName("UTC-129 Báo lỗi có lý do: tạo báo cáo")
        void utc129_memberReports_reportCreatedForPhrase() {
            service.reportPhrase(MEMBER, PHRASE_ID, new ReportRequest(2L, "Nghĩa chưa đúng"));

            verify(repository).lockPhrase(PHRASE_ID, true);
            verify(reports).report(new Target("PHRASE", PHRASE_ID, EDITOR_ID), MEMBER_ID, 2L, "Nghĩa chưa đúng");
        }

        @Test
        @DisplayName("UTC-130 Báo lỗi không ghi chú: vẫn gửi được")
        void utc130_reportWithoutNote_accepted() {
            service.reportPhrase(MEMBER, PHRASE_ID, new ReportRequest(1L, null));

            verify(reports).report(new Target("PHRASE", PHRASE_ID, EDITOR_ID), MEMBER_ID, 1L, null);
        }

        @Test
        @DisplayName("UTC-131 Báo lỗi không chọn lý do: báo lỗi")
        void utc131_reportWithoutReason_validationMessage() {
            assertThat(validator.validate(new ReportRequest(null, "ghi chú")))
                    .extracting(v -> v.getMessage())
                    .containsExactly("Hãy chọn lý do báo cáo.");
            assertThat(validator.validate(new ReportRequest(1L, null))).isEmpty();
        }

        @Test
        @DisplayName("UTC-132 Báo lỗi lần hai: bị từ chối")
        void utc132_reportTwice_rejected() {
            when(reports.report(any(), anyLong(), any(), any()))
                    .thenThrow(ApiException.conflict("Bạn đã báo cáo nội dung này rồi."));

            assertApiError(() -> service.reportPhrase(MEMBER, PHRASE_ID, new ReportRequest(1L, null)),
                    HttpStatus.CONFLICT, "Bạn đã báo cáo nội dung này rồi.");
        }

        @Test
        @DisplayName("UTC-133 Vượt ngưỡng báo cáo: mục vẫn hiện")
        void utc133_reportOverThreshold_phraseNotHidden() {
            when(reports.report(any(), anyLong(), any(), any())).thenReturn(new ContentReportService.Outcome(9, true));

            service.reportPhrase(MEMBER, PHRASE_ID, new ReportRequest(4L, null));

            verify(repository).account(MEMBER_ID);
            verify(repository).lockPhrase(PHRASE_ID, true);
            verifyNoMoreInteractions(repository);
        }

        @Test
        @DisplayName("UTC-134 Khách báo lỗi: phải đăng nhập")
        void utc134_guestReports_401() {
            assertApiError(() -> service.reportPhrase(null, PHRASE_ID, new ReportRequest(1L, null)),
                    HttpStatus.UNAUTHORIZED, MSG_LOGIN);
            verifyNoInteractions(reports);
        }

        @Test
        @DisplayName("UTC-135 Nhân viên báo lỗi: bị từ chối")
        void utc135_staffReports_403() {
            assertApiError(() -> service.reportPhrase(EDITOR, PHRASE_ID, new ReportRequest(1L, null)),
                    HttpStatus.FORBIDDEN, MSG18);
            verifyNoInteractions(reports);
        }

        @Test
        @DisplayName("UTC-136 Báo lỗi mục đã xóa: không tìm thấy")
        void utc136_reportDeletedPhrase_404() {
            when(repository.lockPhrase(PHRASE_ID, true)).thenReturn(Optional.empty());

            assertApiError(() -> service.reportPhrase(MEMBER, PHRASE_ID, new ReportRequest(1L, null)),
                    HttpStatus.NOT_FOUND, MSG_NOT_FOUND);
            verifyNoInteractions(reports);
        }
    }

}


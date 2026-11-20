package com.sep490.vsa.feature.phrase;

import com.sep490.vsa.common.exception.ApiException;
import com.sep490.vsa.feature.admin.ActivityLogService;
import com.sep490.vsa.feature.phrase.AcademicDtos.LibraryRow;
import com.sep490.vsa.feature.phrase.AcademicDtos.PhraseRequest;
import com.sep490.vsa.feature.phrase.AcademicDtos.SaveResult;
import com.sep490.vsa.feature.phrase.AcademicDtos.TopicRequest;
import com.sep490.vsa.feature.phrase.AcademicDtos.TopicRow;
import com.sep490.vsa.feature.phrase.AcademicRepository.CaseRow;
import com.sep490.vsa.feature.phrase.AcademicRepository.PhraseRow;
import com.sep490.vsa.feature.phrase.PhraseDtos.CategoryRef;
import com.sep490.vsa.feature.phrase.PhraseDtos.TopicRef;
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

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests of the Japanese Content Editor's side of "Nhật ngữ" ({@link AcademicService}, role ACADEMIC_MANAGER),
 * written from the full use case specification (docs/use-cases/dac-ta-uc-hoc-tieng-nhat-va-su-kien.md: UC 9 "Tạo chủ đề
 * học", UC 10 "Cập nhật chủ đề học", UC 11 "Xóa chủ đề học", UC 12 "Tạo mục từ vựng", UC 13 "Cập nhật mục từ vựng",
 * UC 14 "Xóa mục từ vựng") and the business rules (docs/use-cases/business-rules-vi.md: GBR-17, GBR-37, GBR-49,
 * GBR-50).
 * <p>
 * The database is not used: {@link AcademicRepository} and {@link ActivityLogService} are mocks, the clock is fixed.
 */
@TestClassOrder(ClassOrderer.DisplayName.class)
@DisplayName("AcademicService – Nhật ngữ phía Biên tập viên nội dung tiếng Nhật (UC 9 – UC 14)")
class AcademicServiceTest {

    private static final long EDITOR_ID = 3L;
    private static final long PHRASE_ID = 501L;
    private static final long CASE_ID = 77L;
    private static final Instant NOW = Instant.parse("2026-11-20T03:00:00Z");

    private static final CategoryRef KONBINI = new CategoryRef("konbini", "Cửa hàng tiện lợi", "store");

    private AcademicRepository repository;
    private ActivityLogService activityLog;
    private AcademicService service;

    /** Existing learning topics (id → name) as {@code lockTopicNames} reads them. */
    private Map<String, String> topicNames;

    @BeforeEach
    void setUp() {
        repository = mock(AcademicRepository.class);
        activityLog = mock(ActivityLogService.class);
        service = new AcademicService(repository, activityLog, Clock.fixed(NOW, ZoneOffset.UTC));

        topicNames = new LinkedHashMap<>();
        topicNames.put("pay", "Thanh toán");
        topicNames.put("order", "Gọi món");
        when(repository.lockTopicNames()).thenReturn(topicNames);
        when(repository.lockTopic("pay")).thenReturn(Optional.of(new TopicRow("pay", "Thanh toán", "payments", 5,
                List.of("konbini"))));

        when(repository.categoryExists("konbini")).thenReturn(true);
        when(repository.categoryExists("super")).thenReturn(true);
        when(repository.topicExists("pay")).thenReturn(true);
        when(repository.topicExists("order")).thenReturn(true);
        when(repository.duplicate(anyString(), anyString(), any())).thenReturn(Optional.empty());
        when(repository.insertPhrase(any(), any())).thenReturn(PHRASE_ID);
        when(repository.libraryRow(PHRASE_ID)).thenReturn(libraryRow(PHRASE_ID, "PHRASE", "領収書をください。"));
        when(repository.lockPhrase(PHRASE_ID)).thenReturn(Optional.of(existing()));
        when(repository.topicIdsOf(PHRASE_ID)).thenReturn(List.of("pay"));
    }

    // ---------------------------------------------------------------- helpers

    private static PhraseRow existing() {
        return new PhraseRow(PHRASE_ID, "konbini", "PHRASE", "領収書をください。", "Ryōshūsho o kudasai.",
                "Cho tôi xin hóa đơn.", EDITOR_ID);
    }

    private static LibraryRow libraryRow(long id, String kind, String japanese) {
        return new LibraryRow(id, kind, japanese, "", "Nghĩa", KONBINI, List.of(new TopicRef("pay", "Thanh toán")), 0);
    }

    /** A valid request equal to {@link #existing()}. */
    private static PhraseRequest request() {
        return request("konbini", "PHRASE", "領収書をください。", "Ryōshūsho o kudasai.", "Cho tôi xin hóa đơn.",
                List.of("pay"), false, null);
    }

    private static PhraseRequest request(String category, String kind, String japanese, String romaji, String meaning,
                                         List<String> topics, boolean confirm, Long caseId) {
        return new PhraseRequest(category, kind, japanese, romaji, meaning, topics, confirm, caseId);
    }

    private static ApiException apiError(Runnable call) {
        Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(call::run);
        assertThat(thrown).isInstanceOf(ApiException.class);
        return (ApiException) thrown;
    }

    private static void assertApiError(Runnable call, HttpStatus status, String message) {
        ApiException e = apiError(call);
        assertThat(e.getStatus()).isEqualTo(status);
        assertThat(e.getMessage()).isEqualTo(message);
    }

    private void verifyNoLog() {
        verify(activityLog, never()).recordLog(any(), anyString(), anyString(), any(), any(), any(), any());
    }

    // =================================================================================================================
    @Nested
    @TestMethodOrder(MethodOrderer.DisplayName.class)
    @DisplayName("01. createTopic – UC 9 Tạo chủ đề học tiếng Nhật (UTC-051 → UTC-062)")
    class CreateTopic {

        @Test
        @DisplayName("UTC-051 Dữ liệu hợp lệ: chủ đề được lưu")
        void utc051_createTopic_validData_saved() {
            service.createTopic(EDITOR_ID, new TopicRequest("Ở nhà trọ", "home"));

            String expectedId = "t" + Long.toString(NOW.toEpochMilli(), 36);
            verify(repository).insertTopic(expectedId, "Ở nhà trọ", "home");
        }

        @Test
        @DisplayName("UTC-052 Thêm chủ đề: ghi nhật ký")
        void utc052_createTopic_writesActivityLog() {
            service.createTopic(EDITOR_ID, new TopicRequest("Ở nhà trọ", "home"));

            verify(activityLog).recordLog(eq(EDITOR_ID), eq("LEARNING_TOPIC_CREATED"), eq("LEARNING_TOPIC"), isNull(),
                    isNull(), any(), eq("Thêm chủ đề học \"Ở nhà trọ\""));
        }

        @Test
        @DisplayName("UTC-053 Không chọn biểu tượng: dùng mặc định")
        void utc053_createTopic_noIcon_usesDefaultLabel() {
            service.createTopic(EDITOR_ID, new TopicRequest("Ở nhà trọ", null));
            service.createTopic(EDITOR_ID, new TopicRequest("Đi ngân hàng", "  "));

            verify(repository).insertTopic(anyString(), eq("Ở nhà trọ"), eq("label"));
            verify(repository).insertTopic(anyString(), eq("Đi ngân hàng"), eq("label"));
        }

        @Test
        @DisplayName("UTC-054 Tên có khoảng trắng thừa: được làm gọn")
        void utc054_createTopic_extraSpaces_trimmedAndMerged() {
            service.createTopic(EDITOR_ID, new TopicRequest("   Ở   nhà    trọ  ", "home"));

            verify(repository).insertTopic(anyString(), eq("Ở nhà trọ"), eq("home"));
        }

        @ParameterizedTest(name = "[{index}] tên \"{0}\"")
        @ValueSource(strings = {"", "   ", "\t\n"})
        @DisplayName("UTC-055 Tên trống: báo lỗi")
        void utc055_createTopic_emptyName_400(String name) {
            ApiException e = apiError(() -> service.createTopic(EDITOR_ID, new TopicRequest(name, "home")));

            assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(e.getMessage()).isEqualTo("Hãy nhập tên chủ đề học.");
            assertThat(e.getFields()).containsEntry("name", "Hãy nhập tên chủ đề học.");
            verify(repository, never()).insertTopic(anyString(), anyString(), anyString());
            verifyNoLog();
        }

        @Test
        @DisplayName("UTC-056 Không gửi dữ liệu: báo lỗi")
        void utc056_createTopic_noData_400() {
            assertApiError(() -> service.createTopic(EDITOR_ID, null), HttpStatus.BAD_REQUEST,
                    "Hãy nhập tên chủ đề học.");
        }

        @Test
        @DisplayName("UTC-057 Tên 60 ký tự: được lưu")
        void utc057_createTopic_name60Chars_saved() {
            String name = "a".repeat(60);

            service.createTopic(EDITOR_ID, new TopicRequest(name, "home"));

            verify(repository).insertTopic(anyString(), eq(name), eq("home"));
        }

        @Test
        @DisplayName("UTC-058 Tên 61 ký tự: báo lỗi")
        void utc058_createTopic_name61Chars_400() {
            ApiException e = apiError(() -> service.createTopic(EDITOR_ID, new TopicRequest("a".repeat(61), "home")));

            assertThat(e.getMessage()).isEqualTo("Tên chủ đề học tối đa 60 ký tự.");
            assertThat(e.getFields()).containsKey("name");
            verify(repository, never()).insertTopic(anyString(), anyString(), anyString());
        }

        @ParameterizedTest(name = "[{index}] tên \"{0}\" trùng \"Thanh toán\"")
        @ValueSource(strings = {"Thanh toán", "thanh toan", "THANH TOÁN", "Thanh  toán", "ThanhToán", "thanh-toán"})
        @DisplayName("UTC-059 Tên trùng (kể cả khác dấu): báo lỗi")
        void utc059_createTopic_duplicateName_400(String name) {
            ApiException e = apiError(() -> service.createTopic(EDITOR_ID, new TopicRequest(name, "home")));

            assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(e.getMessage()).isEqualTo("Chủ đề học này đã có.");
            assertThat(e.getFields()).containsEntry("name", "Chủ đề học này đã có.");
            verify(repository, never()).insertTopic(anyString(), anyString(), anyString());
            verifyNoLog();
        }

        @Test
        @DisplayName("UTC-060 Tên gần giống: không bị coi là trùng")
        void utc060_createTopic_similarName_saved() {
            service.createTopic(EDITOR_ID, new TopicRequest("Thanh toán online", "payments"));

            verify(repository).insertTopic(anyString(), eq("Thanh toán online"), eq("payments"));
        }

        @ParameterizedTest(name = "[{index}] biểu tượng \"{0}\"")
        @ValueSource(strings = {"rocket", "<script>", "LABEL"})
        @DisplayName("UTC-061 Biểu tượng lạ: báo lỗi")
        void utc061_createTopic_unknownIcon_400(String icon) {
            ApiException e = apiError(() -> service.createTopic(EDITOR_ID, new TopicRequest("Ở nhà trọ", icon)));

            assertThat(e.getMessage()).isEqualTo("Hãy chọn một biểu tượng trong danh sách.");
            assertThat(e.getFields()).containsKey("icon");
            verify(repository, never()).insertTopic(anyString(), anyString(), anyString());
        }

        @Test
        @DisplayName("UTC-062 Mã trùng: dùng mã kế tiếp")
        void utc062_createTopic_idTaken_nextIdUsed() {
            String taken = "t" + Long.toString(NOW.toEpochMilli(), 36);
            topicNames.put(taken, "Chủ đề cũ");

            service.createTopic(EDITOR_ID, new TopicRequest("Ở nhà trọ", "home"));

            verify(repository).insertTopic(eq("t" + Long.toString(NOW.toEpochMilli() + 1, 36)), anyString(),
                    anyString());
        }
    }

    // =================================================================================================================
    @Nested
    @TestMethodOrder(MethodOrderer.DisplayName.class)
    @DisplayName("02. updateTopic – UC 10 Cập nhật chủ đề học tiếng Nhật (UTC-063 → UTC-072)")
    class UpdateTopic {

        @Test
        @DisplayName("UTC-063 Đổi tên: lưu và ghi nhật ký")
        void utc063_updateTopic_rename_savedAndLogged() {
            boolean changed = service.updateTopic(EDITOR_ID, "pay", new TopicRequest("Trả tiền", "payments"));

            assertThat(changed).isTrue();
            verify(repository).updateTopic("pay", "Trả tiền", "payments");
            verify(activityLog).recordLog(eq(EDITOR_ID), eq("LEARNING_TOPIC_UPDATED"), eq("LEARNING_TOPIC"), isNull(),
                    any(), any(), eq("Sửa chủ đề học \"Thanh toán\" thành \"Trả tiền\""));
        }

        @Test
        @DisplayName("UTC-064 Chỉ đổi biểu tượng: được lưu")
        void utc064_updateTopic_iconOnly_saved() {
            boolean changed = service.updateTopic(EDITOR_ID, "pay", new TopicRequest("Thanh toán", "shopping_cart"));

            assertThat(changed).isTrue();
            verify(repository).updateTopic("pay", "Thanh toán", "shopping_cart");
            verify(activityLog).recordLog(eq(EDITOR_ID), eq("LEARNING_TOPIC_UPDATED"), eq("LEARNING_TOPIC"), isNull(),
                    any(), any(), eq("Sửa chủ đề học \"Thanh toán\""));
        }

        @Test
        @DisplayName("UTC-065 Đổi hoa thường tên mình: không báo trùng")
        void utc065_updateTopic_changeCaseOfOwnName_notDuplicate() {
            boolean changed = service.updateTopic(EDITOR_ID, "pay", new TopicRequest("THANH TOÁN", "payments"));

            assertThat(changed).isTrue();
            verify(repository).updateTopic("pay", "THANH TOÁN", "payments");
        }

        @Test
        @DisplayName("UTC-066 Không sửa gì: không lưu, không ghi nhật ký")
        void utc066_updateTopic_noChange_nothingSaved() {
            boolean changed = service.updateTopic(EDITOR_ID, "pay", new TopicRequest("  Thanh   toán ", "payments"));

            assertThat(changed).isFalse();
            verify(repository, never()).updateTopic(anyString(), anyString(), anyString());
            verifyNoLog();
        }

        @Test
        @DisplayName("UTC-067 Sửa thành tên trống: báo lỗi")
        void utc067_updateTopic_emptyName_400() {
            assertApiError(() -> service.updateTopic(EDITOR_ID, "pay", new TopicRequest(" ", "payments")),
                    HttpStatus.BAD_REQUEST, "Hãy nhập tên chủ đề học.");
            verify(repository, never()).updateTopic(anyString(), anyString(), anyString());
        }

        @Test
        @DisplayName("UTC-068 Sửa tên quá 60 ký tự: báo lỗi")
        void utc068_updateTopic_nameOver60_400() {
            assertApiError(() -> service.updateTopic(EDITOR_ID, "pay", new TopicRequest("x".repeat(61), "payments")),
                    HttpStatus.BAD_REQUEST, "Tên chủ đề học tối đa 60 ký tự.");
        }

        @Test
        @DisplayName("UTC-069 Sửa trùng tên chủ đề khác: báo lỗi")
        void utc069_updateTopic_nameOfAnotherTopic_400() {
            assertApiError(() -> service.updateTopic(EDITOR_ID, "pay", new TopicRequest("goi mon", "payments")),
                    HttpStatus.BAD_REQUEST, "Chủ đề học này đã có.");
            verify(repository, never()).updateTopic(anyString(), anyString(), anyString());
            verifyNoLog();
        }

        @Test
        @DisplayName("UTC-070 Sửa sang biểu tượng lạ: báo lỗi")
        void utc070_updateTopic_unknownIcon_400() {
            assertApiError(() -> service.updateTopic(EDITOR_ID, "pay", new TopicRequest("Thanh toán", "rocket")),
                    HttpStatus.BAD_REQUEST, "Hãy chọn một biểu tượng trong danh sách.");
        }

        @Test
        @DisplayName("UTC-071 Sửa chủ đề đã xóa: không tìm thấy")
        void utc071_updateTopic_deletedTopic_404() {
            when(repository.lockTopic("gone")).thenReturn(Optional.empty());

            assertApiError(() -> service.updateTopic(EDITOR_ID, "gone", new TopicRequest("Tên mới", "home")),
                    HttpStatus.NOT_FOUND, "Không tìm thấy chủ đề học.");
            verify(repository, never()).updateTopic(anyString(), anyString(), anyString());
        }

        @Test
        @DisplayName("UTC-072 Sửa chủ đề: mẫu câu giữ nguyên")
        void utc072_updateTopic_phrasesUntouched() {
            service.updateTopic(EDITOR_ID, "pay", new TopicRequest("Trả tiền", "payments"));

            verify(repository, never()).setTopics(anyLong(), anyList());
            verify(repository, never()).deleteTopic(anyString());
        }
    }

    // =================================================================================================================
    @Nested
    @TestMethodOrder(MethodOrderer.DisplayName.class)
    @DisplayName("03. deleteTopic – UC 11 Xóa chủ đề học tiếng Nhật (UTC-073 → UTC-076)")
    class DeleteTopic {

        @Test
        @DisplayName("UTC-073 Xóa chủ đề: chỉ gỡ nhãn khỏi mẫu câu")
        void utc073_deleteTopic_removesLabelsOnly() {
            when(repository.deleteTopic("pay")).thenReturn(4);

            service.deleteTopic(EDITOR_ID, "pay");

            verify(repository).deleteTopic("pay");
            verify(repository, never()).softDeletePhrase(anyLong(), any());
            verify(repository, never()).setTopics(anyLong(), anyList());
        }

        @Test
        @DisplayName("UTC-074 Xóa chủ đề: ghi nhật ký")
        void utc074_deleteTopic_writesActivityLog() {
            when(repository.deleteTopic("pay")).thenReturn(4);

            service.deleteTopic(EDITOR_ID, "pay");

            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<String, ?>> before = ArgumentCaptor.forClass(Map.class);
            verify(activityLog).recordLog(eq(EDITOR_ID), eq("LEARNING_TOPIC_DELETED"), eq("LEARNING_TOPIC"), isNull(),
                    before.capture(), isNull(), eq("Xóa chủ đề học \"Thanh toán\""));
            assertThat(new LinkedHashMap<String, Object>(before.getValue())).containsEntry("phrases", 4).containsEntry("name", "Thanh toán");
        }

        @Test
        @DisplayName("UTC-075 Xóa chủ đề đã xóa: không tìm thấy")
        void utc075_deleteTopic_alreadyDeleted_404() {
            when(repository.lockTopic("gone")).thenReturn(Optional.empty());

            assertApiError(() -> service.deleteTopic(EDITOR_ID, "gone"), HttpStatus.NOT_FOUND,
                    "Không tìm thấy chủ đề học.");
            verify(repository, never()).deleteTopic(anyString());
            verifyNoLog();
        }

        @Test
        @DisplayName("UTC-076 Xóa chủ đề: khóa danh sách trước")
        void utc076_deleteTopic_locksTopicListFirst() {
            service.deleteTopic(EDITOR_ID, "pay");

            var order = inOrder(repository);
            order.verify(repository).lockTopicNames();
            order.verify(repository).lockTopic("pay");
            order.verify(repository).deleteTopic("pay");
        }
    }

    // =================================================================================================================
    @Nested
    @TestMethodOrder(MethodOrderer.DisplayName.class)
    @DisplayName("04. topics – UC 9 Bảng chủ đề học (UTC-077)")
    class Topics {

        @Test
        @DisplayName("UTC-077 Bảng chủ đề: đọc đúng, không ghi nhật ký")
        void utc077_topics_listsTopicsWithoutLog() {
            TopicRow pay = new TopicRow("pay", "Thanh toán", "payments", 5, List.of("konbini"));
            when(repository.topics()).thenReturn(List.of(pay));

            assertThat(service.topics()).containsExactly(pay);
            verifyNoInteractions(activityLog);
        }
    }

    // =================================================================================================================
    @Nested
    @TestMethodOrder(MethodOrderer.DisplayName.class)
    @DisplayName("05. options – UC 9, UC 12 Lựa chọn của form (UTC-078)")
    class Options {

        @Test
        @DisplayName("UTC-078 Form có danh sách biểu tượng")
        void utc078_options_listIcons() {
            assertThat(service.options().icons()).contains("label", "payments", "home", "train");
        }
    }

    // =================================================================================================================
    @Nested
    @TestMethodOrder(MethodOrderer.DisplayName.class)
    @DisplayName("06. library – UC 12 Thư viện mẫu câu (UTC-079 → UTC-081)")
    class Library {

        @Test
        @DisplayName("UTC-079 Lọc thư viện, 20 mục mỗi trang")
        void utc079_library_filtersAndPageSize20() {
            when(repository.libraryTotal(any(), any(), any(), any())).thenReturn(45L);

            var page = service.library(" konbini ", "", "VOCAB", "  ", 2);

            verify(repository).library("konbini", null, "VOCAB", null, 2);
            assertThat(page.page()).isEqualTo(2);
            assertThat(page.size()).isEqualTo(20);
            assertThat(page.total()).isEqualTo(45L);
        }

        @Test
        @DisplayName("UTC-080 Số trang âm: về trang đầu")
        void utc080_library_negativePage_firstPage() {
            var page = service.library(null, null, null, null, -5);

            verify(repository).library(null, null, null, null, 0);
            assertThat(page.page()).isZero();
        }

        @Test
        @DisplayName("UTC-081 Xem thư viện: không ghi nhật ký")
        void utc081_library_writesNoActivityLog() {
            service.library(null, null, null, null, 0);

            verifyNoInteractions(activityLog);
        }
    }

    // =================================================================================================================
    @Nested
    @TestMethodOrder(MethodOrderer.DisplayName.class)
    @DisplayName("07. phrase – UC 13 Mở một mục để sửa (UTC-082 → UTC-083)")
    class OpenPhrase {

        @Test
        @DisplayName("UTC-082 Mở mục còn tồn tại: trả về dữ liệu")
        void utc082_openPhrase_found_returnsRow() {
            LibraryRow row = service.phrase(PHRASE_ID);

            assertThat(row.id()).isEqualTo(PHRASE_ID);
            assertThat(row.japanese()).isEqualTo("領収書をください。");
        }

        @Test
        @DisplayName("UTC-083 Mở mục đã xóa: không tìm thấy")
        void utc083_openPhrase_notFound_404() {
            when(repository.libraryRow(999L)).thenReturn(null);

            assertApiError(() -> service.phrase(999L), HttpStatus.NOT_FOUND, "Không tìm thấy mẫu câu.");
        }
    }

    // =================================================================================================================
    @Nested
    @TestMethodOrder(MethodOrderer.DisplayName.class)
    @DisplayName("08. createPhrase – UC 12 Tạo mục từ vựng / mẫu câu (UTC-084 → UTC-106)")
    class CreatePhrase {

        @Test
        @DisplayName("UTC-084 Dữ liệu hợp lệ: mục được lưu")
        void utc084_createPhrase_validData_saved() {
            SaveResult result = service.createPhrase(EDITOR_ID, request());

            ArgumentCaptor<PhraseRow> row = ArgumentCaptor.forClass(PhraseRow.class);
            verify(repository).insertPhrase(row.capture(), eq(NOW));
            assertThat(row.getValue().categoryId()).isEqualTo("konbini");
            assertThat(row.getValue().kind()).isEqualTo("PHRASE");
            assertThat(row.getValue().japanese()).isEqualTo("領収書をください。");
            assertThat(row.getValue().romaji()).isEqualTo("Ryōshūsho o kudasai.");
            assertThat(row.getValue().meaningVi()).isEqualTo("Cho tôi xin hóa đơn.");
            assertThat(row.getValue().createdBy()).isEqualTo(EDITOR_ID);
            verify(repository).setTopics(PHRASE_ID, List.of("pay"));
            assertThat(result.saved()).isTrue();
            assertThat(result.changed()).isTrue();
            assertThat(result.message()).isEqualTo(
                    "Đã thêm. Mẫu câu hiện ở mọi địa điểm \"Cửa hàng tiện lợi\" và trong tab Nhật ngữ.");
        }

        @Test
        @DisplayName("UTC-085 Thêm Từ vựng: thông báo ghi \"Từ vựng\"")
        void utc085_createVocab_messageSaysVocab() {
            SaveResult result = service.createPhrase(EDITOR_ID, request("konbini", "VOCAB", "領収書", "ryōshūsho",
                    "hóa đơn", List.of("pay"), false, null));

            assertThat(result.message()).startsWith("Đã thêm. Từ vựng hiện ở mọi địa điểm");
        }

        @Test
        @DisplayName("UTC-086 Thêm mục: ghi nhật ký")
        void utc086_createPhrase_writesActivityLog() {
            service.createPhrase(EDITOR_ID, request());

            verify(activityLog).recordLog(eq(EDITOR_ID), eq("PHRASE_CREATED"), eq("PHRASE"), eq(PHRASE_ID), isNull(),
                    any(), eq("Thêm mẫu câu: 領収書をください。"));
        }

        @Test
        @DisplayName("UTC-087 Không chọn chủ đề: vẫn lưu")
        void utc087_createPhrase_noTopic_savedPlaceOnlyMessage() {
            SaveResult result = service.createPhrase(EDITOR_ID, request("konbini", "PHRASE", "領収書をください。", "",
                    "Cho tôi xin hóa đơn.", null, false, null));

            assertThat(result.saved()).isTrue();
            assertThat(result.message()).isEqualTo("Đã thêm. Mẫu câu hiện ở mọi địa điểm \"Cửa hàng tiện lợi\".");
            verify(repository).setTopics(PHRASE_ID, List.of());
        }

        @Test
        @DisplayName("UTC-088 Để trống romaji: vẫn lưu")
        void utc088_createPhrase_noRomaji_saved() {
            service.createPhrase(EDITOR_ID, request("konbini", "PHRASE", "領収書をください。", null,
                    "Cho tôi xin hóa đơn.", List.of(), false, null));

            ArgumentCaptor<PhraseRow> row = ArgumentCaptor.forClass(PhraseRow.class);
            verify(repository).insertPhrase(row.capture(), any());
            assertThat(row.getValue().romaji()).isEmpty();
        }

        @Test
        @DisplayName("UTC-089 Khoảng trắng, chủ đề lặp: được làm gọn")
        void utc089_createPhrase_extraSpacesAndRepeatedTopics_cleaned() {
            service.createPhrase(EDITOR_ID, request(" konbini ", " VOCAB ", "  レシート  ", "  reshiito ",
                    "  hóa đơn  ", List.of(" pay", "pay", "order"), false, null));

            ArgumentCaptor<PhraseRow> row = ArgumentCaptor.forClass(PhraseRow.class);
            verify(repository).insertPhrase(row.capture(), any());
            assertThat(row.getValue().categoryId()).isEqualTo("konbini");
            assertThat(row.getValue().kind()).isEqualTo("VOCAB");
            assertThat(row.getValue().japanese()).isEqualTo("レシート");
            assertThat(row.getValue().romaji()).isEqualTo("reshiito");
            assertThat(row.getValue().meaningVi()).isEqualTo("hóa đơn");
            verify(repository).setTopics(PHRASE_ID, List.of("order", "pay"));
        }

        @ParameterizedTest(name = "[{index}] tiếng Nhật \"{0}\"")
        @ValueSource(strings = {"ください", "レシート", "領収書", "ﾚｼｰﾄ", "Suica カード", "々"})
        @DisplayName("UTC-090 Hiragana, katakana, kanji: hợp lệ")
        void utc090_createPhrase_japaneseScripts_accepted(String japanese) {
            SaveResult result = service.createPhrase(EDITOR_ID, request("konbini", "VOCAB", japanese, "", "nghĩa",
                    List.of(), false, null));

            assertThat(result.saved()).isTrue();
        }

        @ParameterizedTest(name = "[{index}] tiếng Nhật \"{0}\"")
        @ValueSource(strings = {"Ryoushuusho o kudasai", "hóa đơn", "12345", "   ", "!!!"})
        @DisplayName("UTC-091 Không có chữ Nhật: báo lỗi")
        void utc091_createPhrase_noJapaneseCharacter_400(String japanese) {
            ApiException e = apiError(() -> service.createPhrase(EDITOR_ID, request("konbini", "PHRASE", japanese, "",
                    "nghĩa", List.of(), false, null)));

            assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(e.getFields()).containsEntry("japanese",
                    "Ô tiếng Nhật cần có chữ Nhật (hiragana, katakana hoặc kanji).");
            verify(repository, never()).insertPhrase(any(), any());
            verifyNoLog();
        }

        @Test
        @DisplayName("UTC-092 Không chọn danh mục: báo lỗi")
        void utc092_createPhrase_noCategory_400() {
            ApiException e = apiError(() -> service.createPhrase(EDITOR_ID, request(null, "PHRASE", "ください", "",
                    "nghĩa", List.of(), false, null)));

            assertThat(e.getFields()).containsEntry("categoryId", "Hãy chọn danh mục địa điểm.");
        }

        @Test
        @DisplayName("UTC-093 Danh mục không còn: báo lỗi")
        void utc093_createPhrase_categoryGone_400() {
            when(repository.categoryExists("gone")).thenReturn(false);

            ApiException e = apiError(() -> service.createPhrase(EDITOR_ID, request("gone", "PHRASE", "ください", "",
                    "nghĩa", List.of(), false, null)));

            assertThat(e.getFields()).containsEntry("categoryId", "Hãy chọn danh mục địa điểm.");
            verify(repository, never()).insertPhrase(any(), any());
        }

        @ParameterizedTest(name = "[{index}] loại \"{0}\"")
        @ValueSource(strings = {"", "WORD", "phrase", "vocab"})
        @DisplayName("UTC-094 Loại không hợp lệ: báo lỗi")
        void utc094_createPhrase_invalidKind_400(String kind) {
            ApiException e = apiError(() -> service.createPhrase(EDITOR_ID, request("konbini", kind, "ください", "",
                    "nghĩa", List.of(), false, null)));

            assertThat(e.getFields()).containsEntry("kind", "Hãy chọn loại: Mẫu câu hoặc Từ vựng.");
        }

        @Test
        @DisplayName("UTC-095 Không chọn loại: báo lỗi")
        void utc095_createPhrase_noKind_400() {
            ApiException e = apiError(() -> service.createPhrase(EDITOR_ID, request("konbini", null, "ください", "",
                    "nghĩa", List.of(), false, null)));

            assertThat(e.getFields()).containsEntry("kind", "Hãy chọn loại: Mẫu câu hoặc Từ vựng.");
        }

        @ParameterizedTest(name = "[{index}] nghĩa \"{0}\"")
        @ValueSource(strings = {"", "   "})
        @DisplayName("UTC-096 Nghĩa trống: báo lỗi")
        void utc096_createPhrase_emptyMeaning_400(String meaning) {
            ApiException e = apiError(() -> service.createPhrase(EDITOR_ID, request("konbini", "PHRASE", "ください", "",
                    meaning, List.of(), false, null)));

            assertThat(e.getFields()).containsEntry("meaningVi", "Hãy nhập nghĩa tiếng Việt.");
        }

        @Test
        @DisplayName("UTC-097 Độ dài tối đa (200/200/300): được lưu")
        void utc097_createPhrase_maxLengths_saved() {
            SaveResult result = service.createPhrase(EDITOR_ID, request("konbini", "PHRASE", "あ".repeat(200),
                    "a".repeat(200), "n".repeat(300), List.of(), false, null));

            assertThat(result.saved()).isTrue();
        }

        @Test
        @DisplayName("UTC-098 Tiếng Nhật 201 ký tự: báo lỗi")
        void utc098_createPhrase_japanese201Chars_400() {
            ApiException e = apiError(() -> service.createPhrase(EDITOR_ID, request("konbini", "PHRASE",
                    "あ".repeat(201), "", "nghĩa", List.of(), false, null)));

            assertThat(e.getFields()).containsEntry("japanese", "Tiếng Nhật tối đa 200 ký tự.");
        }

        @Test
        @DisplayName("UTC-099 Romaji 201 ký tự: báo lỗi")
        void utc099_createPhrase_romaji201Chars_400() {
            ApiException e = apiError(() -> service.createPhrase(EDITOR_ID, request("konbini", "PHRASE", "ください",
                    "a".repeat(201), "nghĩa", List.of(), false, null)));

            assertThat(e.getFields()).containsEntry("romaji", "Romaji tối đa 200 ký tự.");
        }

        @Test
        @DisplayName("UTC-100 Nghĩa 301 ký tự: báo lỗi")
        void utc100_createPhrase_meaning301Chars_400() {
            ApiException e = apiError(() -> service.createPhrase(EDITOR_ID, request("konbini", "PHRASE", "ください", "",
                    "n".repeat(301), List.of(), false, null)));

            assertThat(e.getFields()).containsEntry("meaningVi", "Nghĩa tiếng Việt tối đa 300 ký tự.");
        }

        @Test
        @DisplayName("UTC-101 Nhiều ô sai: báo đủ lỗi một lần")
        void utc101_createPhrase_manyErrors_allReportedAtOnce() {
            when(repository.topicExists("gone")).thenReturn(false);

            ApiException e = apiError(() -> service.createPhrase(EDITOR_ID, request("", "X", "abc", "a".repeat(201),
                    "", List.of("gone"), false, null)));

            assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(e.getFields()).containsOnlyKeys("categoryId", "kind", "japanese", "romaji", "meaningVi",
                    "topicIds");
            verify(repository, never()).insertPhrase(any(), any());
            verifyNoLog();
        }

        @Test
        @DisplayName("UTC-102 Chủ đề không còn: báo lỗi")
        void utc102_createPhrase_topicGone_400() {
            when(repository.topicExists("gone")).thenReturn(false);

            ApiException e = apiError(() -> service.createPhrase(EDITOR_ID, request("konbini", "PHRASE", "ください", "",
                    "nghĩa", List.of("pay", "gone"), false, null)));

            assertThat(e.getFields()).containsEntry("topicIds", "Có chủ đề học không còn tồn tại. Hãy tải lại trang.");
            verify(repository, never()).insertPhrase(any(), any());
        }

        @Test
        @DisplayName("UTC-103 Không gửi dữ liệu: báo lỗi")
        void utc103_createPhrase_noData_400() {
            assertApiError(() -> service.createPhrase(EDITOR_ID, null), HttpStatus.BAD_REQUEST, "Thiếu dữ liệu.");
        }

        @Test
        @DisplayName("UTC-104 Trùng câu trong danh mục: hỏi lại")
        void utc104_createPhrase_sameJapaneseInCategory_asksFirst() {
            LibraryRow other = libraryRow(400L, "PHRASE", "領収書をください。");
            when(repository.duplicate("konbini", "領収書をください。", null)).thenReturn(Optional.of(400L));
            when(repository.libraryRow(400L)).thenReturn(other);

            SaveResult result = service.createPhrase(EDITOR_ID, request());

            assertThat(result.saved()).isFalse();
            assertThat(result.duplicate()).isEqualTo(other);
            assertThat(result.message()).isEqualTo("Danh mục này đã có mục \"領収書をください。\". Bạn vẫn muốn lưu?");
            verify(repository, never()).insertPhrase(any(), any());
            verifyNoLog();
        }

        @Test
        @DisplayName("UTC-105 \"Vẫn lưu\" khi trùng: được lưu")
        void utc105_createPhrase_duplicateConfirmed_saved() {
            SaveResult result = service.createPhrase(EDITOR_ID, request("konbini", "PHRASE", "領収書をください。", "",
                    "Cho tôi xin hóa đơn.", List.of("pay"), true, null));

            assertThat(result.saved()).isTrue();
            verify(repository, never()).duplicate(anyString(), anyString(), any());
            verify(repository).insertPhrase(any(), eq(NOW));
        }

        @Test
        @DisplayName("UTC-106 Kiểm tra trùng đúng danh mục")
        void utc106_createPhrase_duplicateCheckUsesCategoryAndTrimmedJapanese() {
            service.createPhrase(EDITOR_ID, request("super", "PHRASE", " 領収書をください。 ", "", "nghĩa", List.of(),
                    false, null));

            verify(repository).duplicate("super", "領収書をください。", null);
        }
    }

    // =================================================================================================================
    @Nested
    @TestMethodOrder(MethodOrderer.DisplayName.class)
    @DisplayName("09. updatePhrase – UC 13 Cập nhật mục từ vựng / mẫu câu (UTC-107 → UTC-123)")
    class UpdatePhrase {

        private PhraseRequest changedMeaning(Long caseId) {
            return request("konbini", "PHRASE", "領収書をください。", "Ryōshūsho o kudasai.", "Làm ơn cho tôi hóa đơn.",
                    List.of("pay"), false, caseId);
        }

        @Test
        @DisplayName("UTC-107 Sửa nghĩa: được lưu")
        void utc107_updatePhrase_validChange_saved() {
            SaveResult result = service.updatePhrase(EDITOR_ID, PHRASE_ID, changedMeaning(null));

            ArgumentCaptor<PhraseRow> row = ArgumentCaptor.forClass(PhraseRow.class);
            verify(repository).updatePhrase(row.capture(), eq(NOW));
            assertThat(row.getValue().id()).isEqualTo(PHRASE_ID);
            assertThat(row.getValue().meaningVi()).isEqualTo("Làm ơn cho tôi hóa đơn.");
            assertThat(row.getValue().createdBy()).isEqualTo(EDITOR_ID);
            verify(repository).setTopics(PHRASE_ID, List.of("pay"));
            assertThat(result.saved()).isTrue();
            assertThat(result.changed()).isTrue();
            assertThat(result.message()).isEqualTo("Đã lưu thay đổi.");
        }

        @Test
        @DisplayName("UTC-108 Sửa mục: ghi nhật ký")
        void utc108_updatePhrase_writesActivityLog() {
            service.updatePhrase(EDITOR_ID, PHRASE_ID, changedMeaning(null));

            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<String, ?>> before = ArgumentCaptor.forClass(Map.class);
            verify(activityLog).recordLog(eq(EDITOR_ID), eq("PHRASE_UPDATED"), eq("PHRASE"), eq(PHRASE_ID),
                    before.capture(), any(), eq("Sửa mẫu câu: 領収書をください。"));
            assertThat(new LinkedHashMap<String, Object>(before.getValue())).containsEntry("meaningVi", "Cho tôi xin hóa đơn.")
                    .containsEntry("topicIds", List.of("pay"));
        }

        @Test
        @DisplayName("UTC-109 Chỉ đổi chủ đề: được lưu")
        void utc109_updatePhrase_topicsOnly_saved() {
            SaveResult result = service.updatePhrase(EDITOR_ID, PHRASE_ID, request("konbini", "PHRASE",
                    "領収書をください。", "Ryōshūsho o kudasai.", "Cho tôi xin hóa đơn.", List.of("pay", "order"), false,
                    null));

            assertThat(result.changed()).isTrue();
            verify(repository).setTopics(PHRASE_ID, List.of("order", "pay"));
        }

        @Test
        @DisplayName("UTC-110 Không sửa gì: không lưu")
        void utc110_updatePhrase_noChange_nothingSaved() {
            SaveResult result = service.updatePhrase(EDITOR_ID, PHRASE_ID, request());

            assertThat(result.saved()).isTrue();
            assertThat(result.changed()).isFalse();
            assertThat(result.message()).isEqualTo("Không có thay đổi nào.");
            verify(repository, never()).updatePhrase(any(), any());
            verify(repository, never()).setTopics(anyLong(), anyList());
            verifyNoLog();
        }

        @Test
        @DisplayName("UTC-111 Chủ đề khác thứ tự: không tính là sửa")
        void utc111_updatePhrase_sameTopicsOtherOrder_noChange() {
            when(repository.topicIdsOf(PHRASE_ID)).thenReturn(List.of("order", "pay"));

            SaveResult result = service.updatePhrase(EDITOR_ID, PHRASE_ID, request("konbini", "PHRASE",
                    "領収書をください。", "Ryōshūsho o kudasai.", "Cho tôi xin hóa đơn.", List.of("pay", "order"), false,
                    null));

            assertThat(result.changed()).isFalse();
        }

        @Test
        @DisplayName("UTC-112 Kiểm tra trùng bỏ qua chính mục")
        void utc112_updatePhrase_duplicateCheckExcludesItself() {
            service.updatePhrase(EDITOR_ID, PHRASE_ID, changedMeaning(null));

            verify(repository).duplicate("konbini", "領収書をください。", PHRASE_ID);
        }

        @Test
        @DisplayName("UTC-113 Sửa thành câu trùng: hỏi lại")
        void utc113_updatePhrase_sameJapaneseAsOther_asksFirst() {
            when(repository.duplicate("konbini", "レシートください。", PHRASE_ID)).thenReturn(Optional.of(402L));
            when(repository.libraryRow(402L)).thenReturn(libraryRow(402L, "PHRASE", "レシートください。"));

            SaveResult result = service.updatePhrase(EDITOR_ID, PHRASE_ID, request("konbini", "PHRASE",
                    "レシートください。", "", "Cho tôi hóa đơn.", List.of("pay"), false, null));

            assertThat(result.saved()).isFalse();
            assertThat(result.duplicate().id()).isEqualTo(402L);
            verify(repository, never()).updatePhrase(any(), any());
            verifyNoLog();
        }

        @Test
        @DisplayName("UTC-114 \"Vẫn lưu\" khi trùng: được lưu")
        void utc114_updatePhrase_duplicateConfirmed_saved() {
            SaveResult result = service.updatePhrase(EDITOR_ID, PHRASE_ID, request("konbini", "PHRASE",
                    "レシートください。", "", "Cho tôi hóa đơn.", List.of("pay"), true, null));

            assertThat(result.saved()).isTrue();
            verify(repository, never()).duplicate(anyString(), anyString(), any());
            verify(repository).updatePhrase(any(), eq(NOW));
        }

        @Test
        @DisplayName("UTC-115 Dữ liệu sai: báo lỗi, không sửa")
        void utc115_updatePhrase_invalidData_400() {
            ApiException e = apiError(() -> service.updatePhrase(EDITOR_ID, PHRASE_ID, request("konbini", "PHRASE",
                    "kudasai", "", "", List.of(), false, null)));

            assertThat(e.getFields()).containsKeys("japanese", "meaningVi");
            verify(repository, never()).updatePhrase(any(), any());
            verifyNoLog();
        }

        @Test
        @DisplayName("UTC-116 Mục bị xóa khi đang sửa: không tìm thấy")
        void utc116_updatePhrase_deletedMeanwhile_404() {
            when(repository.lockPhrase(999L)).thenReturn(Optional.empty());

            assertApiError(() -> service.updatePhrase(EDITOR_ID, 999L, changedMeaning(null)), HttpStatus.NOT_FOUND,
                    "Không tìm thấy mẫu câu.");
            verify(repository, never()).updatePhrase(any(), any());
        }

        @Test
        @DisplayName("UTC-117 Sửa mục: nhãn của người dùng giữ nguyên")
        void utc117_updatePhrase_keepsMembersMarks() {
            service.updatePhrase(EDITOR_ID, PHRASE_ID, changedMeaning(null));

            verify(repository, never()).softDeletePhrase(anyLong(), any());
            verify(repository, never()).closeCases(anyString(), anyList(), anyString(), anyString(), anyLong(), any());
        }

        @Test
        @DisplayName("UTC-118 Sửa từ báo cáo: đóng báo cáo")
        void utc118_updateFromReport_closesOpenReports() {
            when(repository.lockOpenCase(CASE_ID)).thenReturn(Optional.of(new CaseRow(CASE_ID, "PHRASE", PHRASE_ID,
                    "OPEN")));

            SaveResult result = service.updatePhrase(EDITOR_ID, PHRASE_ID, changedMeaning(CASE_ID));

            assertThat(result.message()).isEqualTo("Đã lưu thay đổi và đóng báo cáo.");
            verify(repository).closeCases("PHRASE", List.of(PHRASE_ID), "CORRECTED", "Đã sửa mẫu câu.", EDITOR_ID, NOW);
            verify(activityLog).recordLog(eq(EDITOR_ID), eq("PHRASE_UPDATED"), eq("PHRASE"), eq(PHRASE_ID), any(),
                    any(), eq("Sửa mẫu câu theo báo cáo: 領収書をください。"));
        }

        @Test
        @DisplayName("UTC-119 Sửa từ báo cáo: khóa mục trước")
        void utc119_updateFromReport_locksPhraseBeforeCase() {
            when(repository.lockOpenCase(CASE_ID)).thenReturn(Optional.of(new CaseRow(CASE_ID, "PHRASE", PHRASE_ID,
                    "OPEN")));

            service.updatePhrase(EDITOR_ID, PHRASE_ID, changedMeaning(CASE_ID));

            var order = inOrder(repository);
            order.verify(repository).lockPhrase(PHRASE_ID);
            order.verify(repository).lockOpenCase(CASE_ID);
        }

        @Test
        @DisplayName("UTC-120 Sửa từ báo cáo mà không đổi: báo cáo vẫn mở")
        void utc120_updateFromReport_noChange_reportStaysOpen() {
            when(repository.lockOpenCase(CASE_ID)).thenReturn(Optional.of(new CaseRow(CASE_ID, "PHRASE", PHRASE_ID,
                    "OPEN")));

            SaveResult result = service.updatePhrase(EDITOR_ID, PHRASE_ID, request("konbini", "PHRASE",
                    "領収書をください。", "Ryōshūsho o kudasai.", "Cho tôi xin hóa đơn.", List.of("pay"), false, CASE_ID));

            assertThat(result.changed()).isFalse();
            assertThat(result.message()).contains("Không sai, bỏ qua");
            verify(repository, never()).closeCases(anyString(), anyList(), anyString(), anyString(), anyLong(), any());
            verifyNoLog();
        }

        @Test
        @DisplayName("UTC-121 Báo cáo đã xử lý: bị từ chối")
        void utc121_updateFromReport_caseHandled_409() {
            when(repository.lockOpenCase(CASE_ID)).thenReturn(Optional.empty());

            assertApiError(() -> service.updatePhrase(EDITOR_ID, PHRASE_ID, changedMeaning(CASE_ID)),
                    HttpStatus.CONFLICT, "Báo cáo này đã được xử lý.");
            verify(repository, never()).updatePhrase(any(), any());
        }

        @Test
        @DisplayName("UTC-122 Báo cáo của mục khác: bị từ chối")
        void utc122_updateFromReport_caseOfAnotherPhrase_409() {
            when(repository.lockOpenCase(CASE_ID)).thenReturn(Optional.of(new CaseRow(CASE_ID, "PHRASE", 999L,
                    "OPEN")));

            assertApiError(() -> service.updatePhrase(EDITOR_ID, PHRASE_ID, changedMeaning(CASE_ID)),
                    HttpStatus.CONFLICT, "Báo cáo này đã được xử lý.");
            verify(repository, never()).updatePhrase(any(), any());
        }

        @Test
        @DisplayName("UTC-123 Hồ sơ không phải mẫu câu: bị từ chối")
        void utc123_updateFromReport_caseNotPhrase_409() {
            when(repository.lockOpenCase(CASE_ID)).thenReturn(Optional.of(new CaseRow(CASE_ID, "BLOG", PHRASE_ID,
                    "OPEN")));

            assertApiError(() -> service.updatePhrase(EDITOR_ID, PHRASE_ID, changedMeaning(CASE_ID)),
                    HttpStatus.CONFLICT, "Báo cáo này đã được xử lý.");
        }
    }

    // =================================================================================================================
    @Nested
    @TestMethodOrder(MethodOrderer.DisplayName.class)
    @DisplayName("10. deletePhrase – UC 14 Xóa mục từ vựng / mẫu câu (UTC-124 → UTC-128)")
    class DeletePhrase {

        @Test
        @DisplayName("UTC-124 Xóa mục: ẩn đi, vẫn giữ dữ liệu")
        void utc124_deletePhrase_softDelete() {
            service.deletePhrase(EDITOR_ID, PHRASE_ID);

            verify(repository).softDeletePhrase(PHRASE_ID, NOW);
        }

        @Test
        @DisplayName("UTC-125 Xóa mục: đóng báo cáo đang mở")
        void utc125_deletePhrase_closesOpenReports() {
            service.deletePhrase(EDITOR_ID, PHRASE_ID);

            verify(repository).closeCases("PHRASE", List.of(PHRASE_ID), "REMOVE", "Mẫu câu đã bị xóa.", EDITOR_ID, NOW);
        }

        @Test
        @DisplayName("UTC-126 Xóa mục: ghi nhật ký")
        void utc126_deletePhrase_writesActivityLog() {
            service.deletePhrase(EDITOR_ID, PHRASE_ID);

            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<String, ?>> before = ArgumentCaptor.forClass(Map.class);
            verify(activityLog).recordLog(eq(EDITOR_ID), eq("PHRASE_DELETED"), eq("PHRASE"), eq(PHRASE_ID),
                    before.capture(), isNull(), eq("Xóa mẫu câu: 領収書をください。"));
            assertThat(new LinkedHashMap<String, Object>(before.getValue())).containsEntry("japanese", "領収書をください。")
                    .containsEntry("meaningVi", "Cho tôi xin hóa đơn.").containsEntry("categoryId", "konbini");
        }

        @Test
        @DisplayName("UTC-127 Xóa mục đã xóa: không tìm thấy")
        void utc127_deletePhrase_alreadyDeleted_404() {
            when(repository.lockPhrase(999L)).thenReturn(Optional.empty());

            assertApiError(() -> service.deletePhrase(EDITOR_ID, 999L), HttpStatus.NOT_FOUND,
                    "Không tìm thấy mẫu câu.");
            verify(repository, never()).softDeletePhrase(anyLong(), any());
            verify(repository, never()).closeCases(anyString(), anyList(), anyString(), anyString(), anyLong(), any());
            verifyNoLog();
        }

        @Test
        @DisplayName("UTC-128 Xóa mục: khóa mục trước")
        void utc128_deletePhrase_locksPhraseFirst() {
            service.deletePhrase(EDITOR_ID, PHRASE_ID);

            var order = inOrder(repository);
            order.verify(repository).lockPhrase(PHRASE_ID);
            order.verify(repository).softDeletePhrase(PHRASE_ID, NOW);
            order.verify(repository).closeCases(anyString(), anyList(), anyString(), anyString(), anyLong(), any());
        }
    }

    // =================================================================================================================
    @Nested
    @TestMethodOrder(MethodOrderer.DisplayName.class)
    @DisplayName("11. reports – UC 13 AF1 Báo cáo mẫu câu (UTC-129 → UTC-130)")
    class Reports {

        @Test
        @DisplayName("UTC-129 Lý do được đếm, nhiều nhất trước")
        void utc129_phraseReports_reasonsCountedMostFirst() {
            var head = new AcademicRepository.CaseHead(CASE_ID, PHRASE_ID, "NORMAL", 3, NOW);
            when(repository.openPhraseCases()).thenReturn(List.of(head));
            when(repository.reportersOf(List.of(CASE_ID))).thenReturn(Map.of(CASE_ID, List.of(
                    new AcademicDtos.Reporter("A", "Sai nghĩa", null, NOW),
                    new AcademicDtos.Reporter("B", "Sai tiếng Nhật", "ghi chú", NOW),
                    new AcademicDtos.Reporter("C", "Sai nghĩa", null, NOW))));

            var cases = service.reports();

            assertThat(cases).hasSize(1);
            assertThat(cases.get(0).reasons()).extracting(AcademicDtos.ReasonCount::label)
                    .containsExactly("Sai nghĩa", "Sai tiếng Nhật");
            assertThat(cases.get(0).reasons()).extracting(AcademicDtos.ReasonCount::count).containsExactly(2, 1);
            assertThat(cases.get(0).reporters()).hasSize(3);
            assertThat(cases.get(0).phrase().id()).isEqualTo(PHRASE_ID);
        }

        @Test
        @DisplayName("UTC-130 Xem báo cáo: không ghi nhật ký")
        void utc130_reports_writesNoActivityLog() {
            service.reports();

            verifyNoInteractions(activityLog);
        }
    }

    // =================================================================================================================
    @Nested
    @TestMethodOrder(MethodOrderer.DisplayName.class)
    @DisplayName("12. dismissReport – UC 13 AF2 Không sai, bỏ qua (UTC-131 → UTC-134)")
    class DismissReport {

        @Test
        @DisplayName("UTC-131 \"Không sai, bỏ qua\": đóng báo cáo")
        void utc131_dismissReport_closesCaseKeepsPhrase() {
            CaseRow open = new CaseRow(CASE_ID, "PHRASE", PHRASE_ID, "OPEN");
            when(repository.findCase(CASE_ID)).thenReturn(Optional.of(open));
            when(repository.lockOpenCase(CASE_ID)).thenReturn(Optional.of(open));

            service.dismissReport(EDITOR_ID, CASE_ID);

            verify(repository).closeCases("PHRASE", List.of(PHRASE_ID), "KEEP", "Không sai, giữ nguyên.", EDITOR_ID,
                    NOW);
            verify(repository, never()).updatePhrase(any(), any());
            verify(activityLog).recordLog(EDITOR_ID, "PHRASE_REPORT_DISMISSED", "PHRASE", PHRASE_ID, null, null,
                    "Bỏ qua báo cáo mẫu câu: 領収書をください。");
        }

        @Test
        @DisplayName("UTC-132 Bỏ qua báo cáo đã đóng: bị từ chối")
        void utc132_dismissReport_alreadyClosed_409() {
            when(repository.findCase(CASE_ID)).thenReturn(Optional.of(new CaseRow(CASE_ID, "PHRASE", PHRASE_ID,
                    "CLOSED")));

            assertApiError(() -> service.dismissReport(EDITOR_ID, CASE_ID), HttpStatus.CONFLICT,
                    "Báo cáo này đã được xử lý.");
            verify(repository, never()).closeCases(anyString(), anyList(), anyString(), anyString(), anyLong(), any());
        }

        @Test
        @DisplayName("UTC-133 Bỏ qua hồ sơ khác loại: bị từ chối")
        void utc133_dismissReport_notPhraseCase_409() {
            when(repository.findCase(CASE_ID)).thenReturn(Optional.of(new CaseRow(CASE_ID, "BLOG", 1L, "OPEN")));

            assertApiError(() -> service.dismissReport(EDITOR_ID, CASE_ID), HttpStatus.CONFLICT,
                    "Báo cáo này đã được xử lý.");
            verify(repository, never()).lockPhrase(anyLong());
        }

        @Test
        @DisplayName("UTC-134 Người khác vừa xử lý: bị từ chối")
        void utc134_dismissReport_handledMeanwhile_409() {
            when(repository.findCase(CASE_ID)).thenReturn(Optional.of(new CaseRow(CASE_ID, "PHRASE", PHRASE_ID,
                    "OPEN")));
            when(repository.lockOpenCase(CASE_ID)).thenReturn(Optional.empty());

            assertApiError(() -> service.dismissReport(EDITOR_ID, CASE_ID), HttpStatus.CONFLICT,
                    "Báo cáo này đã được xử lý.");
            verify(repository, never()).closeCases(anyString(), anyList(), anyString(), anyString(), anyLong(), any());
            verifyNoLog();
        }
    }

    // =================================================================================================================
    @Nested
    @TestMethodOrder(MethodOrderer.DisplayName.class)
    @DisplayName("13. overview – Tổng quan (UTC-135 → UTC-136)")
    class OverviewPage {

        @Test
        @DisplayName("UTC-135 Tổng quan: đủ số liệu")
        void utc135_overview_showsCountsThinTopicsRecentActions() {
            when(repository.openPhraseReports()).thenReturn(2);
            when(repository.phrases("PHRASE")).thenReturn(40);
            when(repository.phrases("VOCAB")).thenReturn(60);

            var overview = service.overview(EDITOR_ID);

            assertThat(overview.counts().phraseReports()).isEqualTo(2);
            assertThat(overview.counts().phrases()).isEqualTo(40);
            assertThat(overview.counts().vocab()).isEqualTo(60);
            verify(repository).thinTopics(3);
            verify(repository).recentActions(EDITOR_ID, 5);
        }

        @Test
        @DisplayName("UTC-136 Tổng quan: không ghi nhật ký")
        void utc136_overview_writesNoActivityLog() {
            service.overview(EDITOR_ID);

            verifyNoInteractions(activityLog);
        }
    }

}


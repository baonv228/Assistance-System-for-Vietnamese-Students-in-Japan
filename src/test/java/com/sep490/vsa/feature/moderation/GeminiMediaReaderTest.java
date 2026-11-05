package com.sep490.vsa.feature.moderation;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sep490.vsa.feature.moderation.GeminiMediaReader.Pacer;
import com.sep490.vsa.feature.moderation.MediaReader.Flag;
import com.sep490.vsa.feature.moderation.MediaReader.Outcome;
import com.sep490.vsa.feature.moderation.MediaReader.Reading;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The real HTTP client against a local stand-in for the Gemini API (no internet, no key): what it sends, how it reads
 * the answers, the request budget, the pause after a failure of the service and the Files API path of large videos.
 * Canned answers follow the API spec.
 */
class GeminiMediaReaderTest {

    private static final String KEY = "test-gemini-key-0123456789";
    private static final String MODEL = "gemini-3.5-flash-lite";
    private static final String GENERATE_PATH = "/v1beta/models/gemini-3.5-flash-lite:generateContent";
    private static final String FILE_NAME = "files/k3x9q2m7v1ab";
    private static final String UPLOAD_QUERY = "upload_id=test-upload&upload_protocol=resumable";
    /** Every byte value, so the base64 has "+", "/" and padding. */
    private static final byte[] PHOTO = new byte[301];

    static {
        for (int i = 0; i < PHOTO.length; i++) {
            PHOTO[i] = (byte) i;
        }
    }

    private final ObjectMapper json = new ObjectMapper();
    private final List<Request> requests = new CopyOnWriteArrayList<>();
    /** What the client counted in api_usage, in order. */
    private final List<String> counted = new CopyOnWriteArrayList<>();
    /** Answers to generateContent, one per request; the last one repeats. */
    private final Deque<Reply> generateReplies = new ConcurrentLinkedDeque<>();
    /** States answered by the file polling, one per request; the last one repeats. */
    private final Deque<String> pollStates = new ConcurrentLinkedDeque<>();
    private volatile String finalizeState = "PROCESSING";
    private volatile String finalizeBody;
    private volatile boolean giveUploadUrl = true;
    /** Runs while a file poll is being answered (before the answer goes out). */
    private volatile Runnable onPoll;
    /** When set, every file poll gets this answer instead of the file. */
    private volatile Reply pollReply;
    private HttpServer server;
    private ExecutorService serverThreads;
    private String base;

    private record Request(String method, String path, String query, Map<String, List<String>> headers, byte[] body) {

        String header(String name) {
            List<String> values = headers.get(name);
            return values == null || values.isEmpty() ? null : values.get(0);
        }

        String route() {
            return method + " " + path + (query == null ? "" : "?" + query);
        }
    }

    private record Reply(int status, String body) {
    }

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        // Requests are answered in parallel, so one held-up answer does not hold up the others
        serverThreads = Executors.newCachedThreadPool();
        server.setExecutor(serverThreads);
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        server.stop(0);
        serverThreads.shutdownNow();
    }

    private void handle(HttpExchange exchange) throws IOException {
        byte[] body = exchange.getRequestBody().readAllBytes();
        Map<String, List<String>> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        headers.putAll(exchange.getRequestHeaders());
        Request request = new Request(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                exchange.getRequestURI().getRawQuery(), headers, body);
        requests.add(request);
        Reply reply = route(request, exchange.getResponseHeaders());
        byte[] out = reply.body().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(reply.status(), out.length == 0 ? -1 : out.length);
        if (out.length > 0) {
            exchange.getResponseBody().write(out);
        }
        exchange.close();
    }

    private Reply route(Request request, Headers response) {
        String method = request.method();
        String path = request.path();
        if (method.equals("POST") && path.endsWith(":generateContent")) {
            Reply next = generateReplies.size() > 1 ? generateReplies.poll() : generateReplies.peek();
            return next == null ? ok(answer(modelJson("", "", "", false), "STOP")) : next;
        }
        if (method.equals("POST") && path.equals("/upload/v1beta/files") && request.query() == null) {
            if (giveUploadUrl) {
                response.add("X-Goog-Upload-URL", base + "/upload/v1beta/files?" + UPLOAD_QUERY);
                response.add("X-Goog-Upload-Status", "active");
            }
            return new Reply(200, "");
        }
        if (method.equals("POST") && path.equals("/upload/v1beta/files")) {
            return ok(finalizeBody != null ? finalizeBody : "{\"file\":" + file(finalizeState) + "}");
        }
        if (method.equals("GET") && path.equals("/v1beta/" + FILE_NAME)) {
            Runnable hook = onPoll;
            if (hook != null) {
                hook.run();
            }
            Reply instead = pollReply;
            if (instead != null) {
                return instead;
            }
            String state = pollStates.size() > 1 ? pollStates.poll() : pollStates.peek();
            return ok(file(state == null ? "ACTIVE" : state));
        }
        if (method.equals("DELETE") && path.equals("/v1beta/" + FILE_NAME)) {
            return ok("{}");
        }
        return new Reply(404, googleError(404, "NOT_FOUND", "Not found: " + path));
    }

    // ---------------------------------------------------------------- canned answers (shapes from the API spec)

    private static Reply ok(String body) {
        return new Reply(200, body);
    }

    /** The JSON object the model writes as its text. */
    private String modelJson(String messageText, String sceneText, String speech, boolean qrCode, String... flags) {
        ObjectNode node = json.createObjectNode();
        node.put("messageText", messageText).put("sceneText", sceneText).put("speech", speech).put("qrCode", qrCode);
        ArrayNode array = node.putArray("flags");
        Arrays.stream(flags).forEach(array::add);
        return node.toString();
    }

    /** A generateContent answer with {@code text} as its one text part (finishReason left out when null). */
    private String answer(String text, String finishReason) {
        ObjectNode root = json.createObjectNode();
        ObjectNode candidate = root.putArray("candidates").addObject();
        ObjectNode content = candidate.putObject("content");
        content.put("role", "model");
        content.putArray("parts").addObject().put("text", text).put("thoughtSignature", "CiQBVKhc7A==");
        if (finishReason != null) {
            candidate.put("finishReason", finishReason);
        }
        candidate.put("index", 0);
        root.putObject("usageMetadata").put("promptTokenCount", 1702).put("candidatesTokenCount", 96)
                .put("thoughtsTokenCount", 12).put("totalTokenCount", 1810);
        root.put("modelVersion", MODEL);
        root.put("responseId", "mAitaLmkHPPlz7IPvtfUqQ4");
        return root.toString();
    }

    private static String blockedPrompt(String blockReason) {
        return """
                {"promptFeedback": {"blockReason": "%s"},
                 "usageMetadata": {"promptTokenCount": 1702, "totalTokenCount": 1702,
                                   "promptTokensDetails": [{"modality": "TEXT", "tokenCount": 582},
                                                           {"modality": "IMAGE", "tokenCount": 1120}]},
                 "modelVersion": "gemini-3.5-flash-lite", "responseId": "kq0ZaZ2xC9-Wz7IP6M2r0Ak"}""".formatted(blockReason);
    }

    private static String blockedCandidate(String finishReason) {
        return """
                {"candidates": [{"finishReason": "%s", "index": 0,
                                 "safetyRatings": [{"category": "HARM_CATEGORY_SEXUALLY_EXPLICIT",
                                                    "probability": "HIGH", "blocked": true}]}],
                 "usageMetadata": {"promptTokenCount": 1702, "totalTokenCount": 1702},
                 "modelVersion": "gemini-3.5-flash-lite"}""".formatted(finishReason);
    }

    private static String googleError(int code, String status, String message) {
        return """
                {"error": {"code": %d, "message": "%s", "status": "%s"}}""".formatted(code, message, status);
    }

    /** Google's error with an ErrorInfo reason in its details (as for an invalid key). */
    private static String googleError(int code, String status, String message, String reason) {
        return """
                {"error": {"code": %d, "message": "%s", "status": "%s",
                  "details": [{"@type": "type.googleapis.com/google.rpc.ErrorInfo", "reason": "%s",
                               "domain": "googleapis.com",
                               "metadata": {"service": "generativelanguage.googleapis.com"}}]}}"""
                .formatted(code, message, status, reason);
    }

    private String file(String state) {
        String error = "FAILED".equals(state) ? ", \"error\": {\"code\": 3, \"message\": \"Video could not be processed\"}" : "";
        return """
                {"name": "%s", "displayName": "asvsj-media", "mimeType": "video/mp4", "sizeBytes": "2000",
                 "createTime": "2026-10-24T06:50:01.123456Z", "updateTime": "2026-10-24T06:50:04.456789Z",
                 "expirationTime": "2026-10-26T06:50:01.098765Z", "sha256Hash": "NmQ0ZjE2",
                 "uri": "%s/v1beta/%s", "state": "%s", "source": "UPLOADED"%s}""".formatted(FILE_NAME, base, FILE_NAME,
                state, error);
    }

    // ---------------------------------------------------------------- helpers

    private GeminiMediaReader reader() {
        return reader(1_000, 1_000_000);
    }

    private GeminiMediaReader reader(int requestsPerMinute, long inlineLimitBytes) {
        return reader(requestsPerMinute, inlineLimitBytes, GeminiMediaReader.FAILURE_PAUSE);
    }

    /**
     * Test knobs: a tiny wait for a free request, the inline limit, 400 ms for a file to become ACTIVE, the pause after
     * a failure of the service.
     */
    private GeminiMediaReader reader(int requestsPerMinute, long inlineLimitBytes, Duration failurePause) {
        return new GeminiMediaReader(KEY, MODEL, base, requestsPerMinute, json, counted::add, Duration.ofMillis(50),
                inlineLimitBytes, Duration.ofMillis(400), Duration.ofMillis(20), failurePause);
    }

    private Reading readPhoto(String generateAnswer) {
        generateReplies.add(ok(generateAnswer));
        return reader().read(PHOTO, "image/jpeg");
    }

    private JsonNode body(Request request) throws IOException {
        return json.readTree(request.body());
    }

    private JsonNode detail(Reading reading) throws IOException {
        return json.readTree(reading.detail());
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    /** Every field name anywhere in the tree. */
    private static Set<String> allFieldNames(JsonNode node) {
        Set<String> names = new LinkedHashSet<>();
        if (node.isObject()) {
            for (Iterator<Map.Entry<String, JsonNode>> it = node.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> field = it.next();
                names.add(field.getKey());
                names.addAll(allFieldNames(field.getValue()));
            }
        } else if (node.isArray()) {
            node.forEach(child -> names.addAll(allFieldNames(child)));
        }
        return names;
    }

    private List<String> routes() {
        return requests.stream().map(Request::route).toList();
    }

    /** What the reader logs while {@code action} runs. */
    private static List<ILoggingEvent> logsOf(Runnable action) {
        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(GeminiMediaReader.class);
        ListAppender<ILoggingEvent> logged = new ListAppender<>();
        logged.start();
        logger.addAppender(logged);
        try {
            action.run();
        } finally {
            logger.detachAppender(logged);
        }
        return logged.list;
    }

    // ---------------------------------------------------------------- the request

    @Test
    void photoRequestCarriesInstructionPhotoSchemaAndSafetyAndNothingElse() throws IOException {
        Reading reading = readPhoto(answer(modelJson("", "", "", false), "STOP"));

        assertThat(reading.outcome()).isEqualTo(Outcome.READ);
        assertThat(requests).hasSize(1);
        Request request = requests.get(0);
        assertThat(request.method()).isEqualTo("POST");
        assertThat(request.path()).isEqualTo(GENERATE_PATH);
        // The key travels in the header only, never in the address
        assertThat(request.query()).isNull();
        assertThat(request.header("x-goog-api-key")).isEqualTo(KEY);
        assertThat(request.header("Content-Type")).startsWith("application/json");
        String raw = new String(request.body(), StandardCharsets.UTF_8);
        assertThat(raw).doesNotContain(KEY);

        JsonNode body = body(request);
        assertThat(fieldNames(body)).containsExactly("systemInstruction", "contents", "safetySettings",
                "generationConfig");
        String instruction = body.at("/systemInstruction/parts/0/text").asText();
        assertThat(instruction).isEqualTo(GeminiMediaReader.SYSTEM_INSTRUCTION)
                .startsWith("You check photos and short videos that members upload to Senpai ơi")
                .contains("(bao nuôi, パパ活, 援助交際)").contains("betting tips (\"soi kèo\", \"kèo nhà cái\")")
                // Screenshots without the phone's interface; posters where they hang are part of the scene
                .contains("leave out the status bar, address bar, tabs, buttons, menus and like/share counters")
                .contains("the text of a card, flyer, leaflet, note or sign held up to the camera, shown on a phone or"
                        + " computer screen, or lying on a table or desk as the subject of the photo")
                .contains("photographed where they hang or are on display at a shop, station or event, even when they"
                        + " fill the picture")
                // Keywords are checked line by line: a sentence that wraps in the picture comes back on one line
                .contains("A sentence or phrase that wraps over several lines in the picture stays on one line:"
                        + " \"NHẬN BAO\" printed above \"NUÔI SINH VIÊN\" is written \"NHẬN BAO NUÔI SINH VIÊN\".")
                // Any QR code counts, wherever it stands (an account without an identity tag may not post one)
                .contains("qrCode: true when a QR code can be seen anywhere in the photo or video: in a screenshot,"
                        + " added on top, on a phone or computer screen, on a card, flyer, poster, sign, menu, sticker"
                        + " or any other object, however small. False only when there is no QR code at all")
                // Identity, bank and card numbers masked; phone numbers kept for the link and contact rule
                .contains("Write \"[số]\" instead of the digits of identity-document numbers (residence card,"
                        + " passport, My Number), bank-account and payment-card numbers; keep phone numbers")
                .contains("Leave out song lyrics and background sound").contains("write \"[bài hát]\" once")
                .doesNotContain("False for any QR code on a poster")
                .contains("Not a minor injury, burn or rash shown to ask for advice.")
                .contains("Not prevention or help information (hotline posters).")
                // Gambling itself is refused (a card game with money in sight), a game without stakes or a pachinko
                // sign in a street is not; lottery and horse racing are left out as in the keyword list (V6)
                .contains("GAMBLING: gambling itself shown: people playing cards, dice, mahjong, bầu cua, xóc đĩa or a"
                        + " similar game with money, chips or other stakes in sight")
                .contains("Not card, board or video games played without stakes (no money or chips in sight), a street"
                        + " or building that merely includes a pachinko parlour, lottery shop or casino, lottery"
                        + " tickets, or horse racing.")
                .doesNotContain("not people simply playing a game")
                .endsWith("keep the first 1500 characters.\n");
        assertThat(body.at("/systemInstruction/parts")).hasSize(1);

        assertThat(body.get("contents")).hasSize(1);
        assertThat(body.at("/contents/0/role").asText()).isEqualTo("user");
        JsonNode parts = body.at("/contents/0/parts");
        assertThat(parts).hasSize(2);
        assertThat(fieldNames(parts.get(0))).containsExactly("inlineData");
        assertThat(parts.at("/0/inlineData/mimeType").asText()).isEqualTo("image/jpeg");
        assertThat(parts.at("/0/inlineData/data").asText()).isEqualTo(Base64.getEncoder().encodeToString(PHOTO));
        assertThat(fieldNames(parts.get(1))).containsExactly("text");
        assertThat(parts.at("/1/text").asText()).isEqualTo("Read this photo.");

        assertThat(body.get("safetySettings")).hasSize(4);
        assertThat(body.get("safetySettings")).extracting(s -> s.get("category").asText()).containsExactly(
                "HARM_CATEGORY_HARASSMENT", "HARM_CATEGORY_HATE_SPEECH", "HARM_CATEGORY_SEXUALLY_EXPLICIT",
                "HARM_CATEGORY_DANGEROUS_CONTENT");
        assertThat(body.get("safetySettings")).extracting(s -> s.get("threshold").asText()).containsOnly("OFF");

        JsonNode config = body.get("generationConfig");
        // No thinkingConfig (the default of Flash-Lite, an error on other models), no mediaResolution for a photo
        assertThat(fieldNames(config)).containsExactly("responseMimeType", "responseJsonSchema", "maxOutputTokens");
        assertThat(config.get("responseMimeType").asText()).isEqualTo("application/json");
        assertThat(config.get("maxOutputTokens").isInt()).isTrue();
        assertThat(config.get("maxOutputTokens").asInt()).isEqualTo(8192);

        JsonNode schema = config.get("responseJsonSchema");
        assertThat(schema.get("type").asText()).isEqualTo("object");
        assertThat(fieldNames(schema.get("properties"))).containsExactly("messageText", "sceneText", "speech", "qrCode",
                "flags");
        assertThat(schema.at("/properties/messageText/type").asText()).isEqualTo("string");
        assertThat(schema.at("/properties/sceneText/type").asText()).isEqualTo("string");
        assertThat(schema.at("/properties/speech/type").asText()).isEqualTo("string");
        assertThat(schema.at("/properties/qrCode/type").asText()).isEqualTo("boolean");
        assertThat(schema.at("/properties/flags/type").asText()).isEqualTo("array");
        assertThat(schema.at("/properties/flags/items/type").asText()).isEqualTo("string");
        assertThat(schema.at("/properties/flags/items/enum")).extracting(JsonNode::asText).containsExactly(
                "SEXUAL", "MINOR_SEXUAL", "GRAPHIC_VIOLENCE", "SELF_HARM", "GAMBLING", "GAMBLING_PROMOTION",
                "SEX_SOLICITATION", "DRUG_SALE");
        assertThat(schema.get("properties")).allSatisfy(p -> assertThat(p.get("description").asText()).isNotBlank());
        assertThat(schema.at("/properties/messageText/description").asText()).contains("lying on a table")
                .contains("one line per sentence").contains("[số]");
        assertThat(schema.at("/properties/qrCode/description").asText())
                .contains("anywhere in the photo or video").contains("poster").contains("however small");
        assertThat(schema.get("required")).extracting(JsonNode::asText).containsExactly("messageText", "sceneText",
                "speech", "qrCode", "flags");
        assertThat(schema.get("additionalProperties").isBoolean()).isTrue();
        assertThat(schema.get("additionalProperties").asBoolean()).isFalse();

        // Deprecated, ignored on 3.5 Flash-Lite, or left at the default on purpose
        assertThat(allFieldNames(body)).doesNotContain("temperature", "topP", "topK", "candidateCount",
                "thinkingConfig", "thinkingLevel", "thinkingBudget", "includeThoughts", "mediaResolution",
                "mediaProcessing", "videoMetadata",
                "responseFormat", "responseSchema", "_responseJsonSchema", "propertyOrdering", "maxLength",
                "minLength", "fileData");
        assertThat(raw).doesNotContain("HARM_CATEGORY_JAILBREAK").doesNotContain("HARM_CATEGORY_CIVIC_INTEGRITY");
        assertThat(counted).containsExactly(GeminiMediaReader.GENERATE_USAGE);
    }

    @Test
    void readerIsTheGeminiEngineAndChecksItsSettings() {
        assertThat(reader().engine()).isEqualTo("GEMINI");
        assertThat(reader().enabled()).isTrue();
        assertThatThrownBy(() -> new GeminiMediaReader(KEY, MODEL, base, 0, json, counted::add))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("requests-per-minute");
        assertThatThrownBy(() -> new GeminiMediaReader(" ", MODEL, base, 10, json, counted::add))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(requests).isEmpty();
    }

    // ---------------------------------------------------------------- reading the answer

    @Test
    void readsTextsQrCodeAndFlagsAndKeepsOnlyModelReasonAndTokensInTheDetail() throws IOException {
        Reading reading = readPhoto(answer(modelJson("  Tuyển nhân viên part-time\n時給1200円 LINE: abc123 \n",
                "セブン-イレブン", "", true, "GAMBLING_PROMOTION", "SEX_SOLICITATION"), "STOP"));

        assertThat(reading.outcome()).isEqualTo(Outcome.READ);
        assertThat(reading.messageText()).isEqualTo("Tuyển nhân viên part-time\n時給1200円 LINE: abc123");
        assertThat(reading.sceneText()).isEqualTo("セブン-イレブン");
        assertThat(reading.speech()).isEmpty();
        assertThat(reading.qrCode()).isTrue();
        assertThat(reading.flags()).containsExactly(Flag.GAMBLING_PROMOTION, Flag.SEX_SOLICITATION);
        JsonNode detail = detail(reading);
        assertThat(fieldNames(detail)).containsExactly("model", "finishReason", "totalTokenCount");
        assertThat(detail.get("model").asText()).isEqualTo(MODEL);
        assertThat(detail.get("finishReason").asText()).isEqualTo("STOP");
        assertThat(detail.get("totalTokenCount").asInt()).isEqualTo(1810);
        assertThat(reading.detail()).doesNotContain("Tuyển").doesNotContain("セブン").doesNotContain(KEY);
    }

    @Test
    void thoughtPartsAndPartsWithoutTextAreSkippedAndTextPartsJoined() {
        ObjectNode root = json.createObjectNode();
        ObjectNode candidate = root.putArray("candidates").addObject();
        ArrayNode parts = candidate.putObject("content").putArray("parts");
        parts.addObject().put("text", "{\"messageText\":\"a thought, not the answer\"}").put("thought", true);
        parts.addObject().put("thoughtSignature", "CiQBVKhc7A==");
        parts.addObject().put("text", "{\"messageText\":\"Xin chào\",\"sceneText\":\"\",");
        parts.addObject().put("text", "\"speech\":\"\",\"qrCode\":false,\"flags\":[]}");
        candidate.put("finishReason", "STOP");

        Reading reading = readPhoto(root.toString());

        assertThat(reading.outcome()).isEqualTo(Outcome.READ);
        assertThat(reading.messageText()).isEqualTo("Xin chào");
    }

    @Test
    void unknownAndRepeatedFlagsAreDroppedAndMissingFieldsAreEmpty() {
        Reading reading = readPhoto(answer("{\"flags\": [\"SEXUAL\", \"SCAM\", \"drug_sale\", 42, \"SEXUAL\"],"
                + " \"messageText\": null, \"speech\": 7}", "STOP"));

        assertThat(reading.outcome()).isEqualTo(Outcome.READ);
        assertThat(reading.flags()).containsExactly(Flag.SEXUAL, Flag.DRUG_SALE);
        assertThat(reading.messageText()).isEmpty();
        assertThat(reading.sceneText()).isEmpty();
        assertThat(reading.speech()).isEmpty();
        assertThat(reading.qrCode()).isFalse();
    }

    @Test
    void textsAreTrimmedAndCutTo1500CharactersWithoutSplittingACharacter() {
        String emoji = "😀";
        Reading reading = readPhoto(answer(modelJson("a".repeat(2_000), "   x   ", "b".repeat(1_499) + emoji + "c",
                false), "STOP"));

        assertThat(reading.outcome()).isEqualTo(Outcome.READ);
        assertThat(reading.messageText()).isEqualTo("a".repeat(1_500));
        assertThat(reading.sceneText()).isEqualTo("x");
        assertThat(reading.speech()).isEqualTo("b".repeat(1_499));
    }

    @Test
    void jsonInsideMarkdownFenceIsStillRead() {
        Reading reading = readPhoto(answer("```json\n" + modelJson("fb88", "", "", false) + "\n```", "STOP"));

        assertThat(reading.outcome()).isEqualTo(Outcome.READ);
        assertThat(reading.messageText()).isEqualTo("fb88");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "FINISH_REASON_UNSPECIFIED"})
    void missingFinishReasonWithTextCountsAsStop(String finishReason) {
        Reading reading = readPhoto(answer(modelJson("hello", "", "", false), finishReason.isEmpty() ? null : finishReason));

        assertThat(reading.outcome()).isEqualTo(Outcome.READ);
        assertThat(reading.messageText()).isEqualTo("hello");
    }

    // ---------------------------------------------------------------- refused by Google

    @ParameterizedTest
    @ValueSource(strings = {"SAFETY", "PROHIBITED_CONTENT", "IMAGE_SAFETY", "BLOCKLIST"})
    void blockedPromptIsBlocked(String blockReason) throws IOException {
        Reading reading = readPhoto(blockedPrompt(blockReason));

        assertThat(reading.outcome()).isEqualTo(Outcome.BLOCKED);
        JsonNode detail = detail(reading);
        assertThat(detail.get("blockReason").asText()).isEqualTo(blockReason);
        assertThat(detail.get("model").asText()).isEqualTo(MODEL);
        assertThat(detail.get("totalTokenCount").asInt()).isEqualTo(1702);
    }

    @ParameterizedTest
    @ValueSource(strings = {"BLOCK_REASON_UNSPECIFIED", "SOMETHING_NEW"})
    void promptBlockedForAnotherReasonIsAnError(String blockReason) throws IOException {
        Reading reading = readPhoto(blockedPrompt(blockReason));

        assertThat(reading.outcome()).isEqualTo(Outcome.ERROR);
        assertThat(detail(reading).get("error").asText()).contains(blockReason);
    }

    @Test
    void promptBlockedForOtherReasonsIsUnreadable() throws IOException {
        Reading reading = readPhoto(blockedPrompt("OTHER"));

        assertThat(reading.outcome()).isEqualTo(Outcome.UNREADABLE);
        assertThat(detail(reading).get("error").asText()).isEqualTo("generateContent: prompt blocked, blockReason OTHER");
        assertThat(detail(reading).get("model").asText()).isEqualTo(MODEL);
    }

    @ParameterizedTest
    @ValueSource(strings = {"SAFETY", "PROHIBITED_CONTENT", "BLOCKLIST", "SPII", "IMAGE_SAFETY",
            "IMAGE_PROHIBITED_CONTENT", "ESCALATION"})
    void answerStoppedAsUnsafeIsBlocked(String finishReason) throws IOException {
        Reading reading = readPhoto(blockedCandidate(finishReason));

        assertThat(reading.outcome()).isEqualTo(Outcome.BLOCKED);
        assertThat(detail(reading).get("finishReason").asText()).isEqualTo(finishReason);
    }

    @ParameterizedTest
    @ValueSource(strings = {"OTHER", "LANGUAGE", "MALFORMED_RESPONSE", "PUP_LIMITED_DISABLED", "SOMETHING_NEW"})
    void answerStoppedForAnotherReasonIsAnError(String finishReason) throws IOException {
        // A cut-off answer is not valid JSON anyway
        Reading reading = readPhoto(answer("{\"messageText\": \"Tuyển nhân vi", finishReason));

        assertThat(reading.outcome()).isEqualTo(Outcome.ERROR);
        JsonNode detail = detail(reading);
        assertThat(detail.get("error").asText()).startsWith("generateContent: finishReason " + finishReason);
        assertThat(reading.detail()).doesNotContain("Tuyển");
    }

    @ParameterizedTest
    @ValueSource(strings = {"RECITATION", "MAX_TOKENS"})
    void answerStoppedForRecitationOrLengthIsUnreadable(String finishReason) throws IOException {
        // A song or a text the model will not recite, or more text than one answer holds: trying again will not help
        Reading reading = readPhoto(answer("{\"messageText\": \"Tuyển nhân vi", finishReason));

        assertThat(reading.outcome()).isEqualTo(Outcome.UNREADABLE);
        JsonNode detail = detail(reading);
        assertThat(detail.get("error").asText()).isEqualTo("generateContent: finishReason " + finishReason);
        assertThat(detail.get("model").asText()).isEqualTo(MODEL);
        assertThat(reading.detail()).doesNotContain("Tuyển");
        assertThat(reading.messageText()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void maxTokensWithoutAnyAnswerTextIsAnErrorOfOurConfigurationNotOfTheFile(boolean thoughtCountGiven)
            throws IOException {
        // The whole output budget went into thinking (only a thought part, or no part at all): not about this file
        ObjectNode root = json.createObjectNode();
        ObjectNode candidate = root.putArray("candidates").addObject();
        ObjectNode content = candidate.putObject("content");
        content.put("role", "model");
        if (!thoughtCountGiven) {
            content.putArray("parts").addObject().put("text", "Let me look at the sign first…").put("thought", true);
        }
        candidate.put("finishReason", "MAX_TOKENS").put("index", 0);
        ObjectNode usage = root.putObject("usageMetadata").put("promptTokenCount", 1702);
        if (thoughtCountGiven) {
            usage.put("thoughtsTokenCount", 8192);
        }
        usage.put("totalTokenCount", 9894);
        root.put("modelVersion", MODEL);

        Reading reading = readPhoto(root.toString());

        assertThat(reading.outcome()).isEqualTo(Outcome.ERROR);
        JsonNode detail = detail(reading);
        assertThat(detail.get("error").asText()).isEqualTo("generateContent: finishReason MAX_TOKENS without any"
                + " answer text: maxOutputTokens was used up by thinking");
        if (thoughtCountGiven) {
            assertThat(detail.get("thoughtsTokenCount").asInt()).isEqualTo(8192);
        } else {
            assertThat(detail.has("thoughtsTokenCount")).isFalse();
        }
        assertThat(reading.detail()).doesNotContain("Let me look");
    }

    // ---------------------------------------------------------------- answers that cannot be used

    @ParameterizedTest
    @ValueSource(strings = {"{\"candidates\": [], \"usageMetadata\": {\"totalTokenCount\": 10}}", "{}",
            "{\"candidates\": \"x\"}", "{\"candidates\": [{\"finishReason\": \"STOP\"}]}",
            "{\"candidates\": [{\"content\": {\"parts\": \"x\"}, \"finishReason\": \"STOP\"}]}",
            "{\"candidates\": [{\"content\": {\"parts\": [{\"text\": 123}]}, \"finishReason\": \"STOP\"}]}",
            "<html><body>Bad gateway</body></html>", "null", "[1, 2]", "{\"candidates\": [", ""})
    void unusableAnswerIsAnErrorAndNeverThrows(String answer) throws IOException {
        Reading reading = readPhoto(answer);

        assertThat(reading.outcome()).isEqualTo(Outcome.ERROR);
        assertThat(detail(reading).get("error").asText()).startsWith("generateContent: ");
        assertThat(counted).containsExactly(GeminiMediaReader.GENERATE_USAGE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"messageText\": \"Tuyển nhân vi", "[\"messageText\"]", "\"text\"", "   ",
            "Sorry, I cannot help with that."})
    void answerTextThatIsNotAJsonObjectIsAnError(String text) throws IOException {
        Reading reading = readPhoto(answer(text, "STOP"));

        assertThat(reading.outcome()).isEqualTo(Outcome.ERROR);
        assertThat(reading.detail()).doesNotContain("Tuyển").doesNotContain("Sorry");
        assertThat(detail(reading).get("model").asText()).isEqualTo(MODEL);
    }

    // ---------------------------------------------------------------- HTTP errors

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "400|INVALID_ARGUMENT|API key not valid. Please pass a valid API key.",
            "400|FAILED_PRECONDITION|User location is not supported for the API use.",
            "403|PERMISSION_DENIED|Your project has been denied access. Please contact support.",
            "404|NOT_FOUND|models/gemini-3.5-flash-lite is not found for API version v1beta.",
            "500|INTERNAL|An internal error has occurred.",
            "503|UNAVAILABLE|The model is overloaded. Please try again later."})
    void httpErrorIsAnErrorWithStatusAndGoogleMessage(int status, String googleStatus, String message)
            throws IOException {
        generateReplies.add(new Reply(status, googleError(status, googleStatus, message)));

        Reading reading = reader().read(PHOTO, "image/jpeg");

        assertThat(reading.outcome()).isEqualTo(Outcome.ERROR);
        JsonNode detail = detail(reading);
        assertThat(detail.get("error").asText())
                .isEqualTo("generateContent HTTP " + status + " " + googleStatus + ": " + message);
        assertThat(detail.get("httpStatus").asInt()).isEqualTo(status);
        assertThat(detail.get("googleStatus").asText()).isEqualTo(googleStatus);
        assertThat(detail.get("model").asText()).isEqualTo(MODEL);
        assertThat(reading.detail()).doesNotContain(KEY);
    }

    @Test
    void quotaErrorKeepsTheQuotaId() throws IOException {
        // The 429 body as reported by the community (Google does not document it)
        generateReplies.add(new Reply(429, """
                {"error": {"code": 429,
                  "message": "You exceeded your current quota, please check your plan and billing details. For more information on this error, head to: https://ai.google.dev/gemini-api/docs/rate-limits.",
                  "status": "RESOURCE_EXHAUSTED",
                  "details": [
                    {"@type": "type.googleapis.com/google.rpc.QuotaFailure",
                     "violations": [{"quotaMetric": "generativelanguage.googleapis.com/generate_content_free_tier_requests",
                                     "quotaId": "GenerateRequestsPerMinutePerProjectPerModel-FreeTier",
                                     "quotaDimensions": {"location": "global", "model": "gemini-3.5-flash-lite"},
                                     "quotaValue": "15"}]},
                    {"@type": "type.googleapis.com/google.rpc.Help",
                     "links": [{"description": "Learn more about Gemini API quotas", "url": "https://ai.google.dev/gemini-api/docs/rate-limits"}]},
                    {"@type": "type.googleapis.com/google.rpc.RetryInfo", "retryDelay": "43s"}]}}"""));

        Reading reading = reader().read(PHOTO, "image/jpeg");

        assertThat(reading.outcome()).isEqualTo(Outcome.ERROR);
        JsonNode detail = detail(reading);
        assertThat(detail.get("httpStatus").asInt()).isEqualTo(429);
        assertThat(detail.get("googleStatus").asText()).isEqualTo("RESOURCE_EXHAUSTED");
        assertThat(detail.get("quotaId").asText()).isEqualTo("GenerateRequestsPerMinutePerProjectPerModel-FreeTier");
        assertThat(detail.get("error").asText()).startsWith("generateContent HTTP 429 RESOURCE_EXHAUSTED: You exceeded");
        assertThat(counted).containsExactly(GeminiMediaReader.GENERATE_USAGE);
    }

    @Test
    void longOrNonJsonErrorBodiesAreCutTo300Characters() throws IOException {
        generateReplies.add(new Reply(500, googleError(500, "INTERNAL", "x".repeat(1_000))));
        generateReplies.add(new Reply(502, "<html>\n<body>Bad   gateway</body>\n</html>"));

        // Two readers: after the first failure one reader would not ask again for a while
        JsonNode first = detail(reader().read(PHOTO, "image/jpeg"));
        assertThat(first.get("error").asText()).isEqualTo("generateContent HTTP 500 INTERNAL: " + "x".repeat(300));
        JsonNode second = detail(reader().read(PHOTO, "image/jpeg"));
        assertThat(second.get("error").asText()).isEqualTo("generateContent HTTP 502: <html> <body>Bad gateway</body> </html>");
        assertThat(second.get("httpStatus").asInt()).isEqualTo(502);
        assertThat(second.has("googleStatus")).isFalse();
    }

    @Test
    void closedPortIsAnError() throws IOException {
        int port;
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            port = socket.getLocalPort();
        }
        GeminiMediaReader reader = new GeminiMediaReader(KEY, MODEL, "http://127.0.0.1:" + port, 10, json,
                counted::add);

        Reading reading = reader.read(PHOTO, "image/jpeg");

        assertThat(reading.outcome()).isEqualTo(Outcome.ERROR);
        assertThat(detail(reading).get("error").asText()).startsWith("generateContent: ").contains("Connect");
        assertThat(reading.detail()).doesNotContain(KEY);
        // Counted like every request that was tried, answered or not
        assertThat(counted).containsExactly(GeminiMediaReader.GENERATE_USAGE);

        // No answer at all is a failure of the service: the next reading is not even tried for a while
        assertThat(reader.read(PHOTO, "image/jpeg").outcome()).isEqualTo(Outcome.BUSY);
        assertThat(counted).containsExactly(GeminiMediaReader.GENERATE_USAGE);
    }

    @Test
    void inputThatIsNotAPhotoOrVideoIsAnErrorWithoutAnyRequest() {
        GeminiMediaReader reader = reader();

        assertThat(reader.read(null, "image/jpeg").outcome()).isEqualTo(Outcome.ERROR);
        assertThat(reader.read(new byte[0], "image/jpeg").outcome()).isEqualTo(Outcome.ERROR);
        assertThat(reader.read(PHOTO, "application/pdf").outcome()).isEqualTo(Outcome.ERROR);
        assertThat(reader.read(PHOTO, null).outcome()).isEqualTo(Outcome.ERROR);
        assertThat(requests).isEmpty();
        assertThat(counted).isEmpty();
    }

    @Test
    void aFailingUsageCounterDoesNotBreakTheReading() {
        generateReplies.add(ok(answer(modelJson("ok", "", "", false), "STOP")));
        GeminiMediaReader reader = new GeminiMediaReader(KEY, MODEL, base, 10, json, service -> {
            throw new IllegalStateException("database down");
        });

        Reading reading = reader.read(PHOTO, "image/jpeg");

        assertThat(reading.outcome()).isEqualTo(Outcome.READ);
        assertThat(reading.messageText()).isEqualTo("ok");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "403|PERMISSION_DENIED|Your project has been denied access.||true",
            "404|NOT_FOUND|models/gemini-3.5-flash-lite is not found for API version v1beta.||true",
            "400|FAILED_PRECONDITION|User location is not supported for the API use.||true",
            "400|INVALID_ARGUMENT|API key not valid. Please pass a valid API key.|API_KEY_INVALID|true",
            "400|INVALID_ARGUMENT|Request contains an invalid argument.||false",
            "429|RESOURCE_EXHAUSTED|You exceeded your current quota.||false",
            "503|UNAVAILABLE|The model is overloaded.||false"})
    void keyModelAndProjectProblemsAreLoggedAsErrorsForTheAdmin(int status, String googleStatus, String message,
                                                                 String reason, boolean severe) {
        generateReplies.add(new Reply(status, reason == null ? googleError(status, googleStatus, message)
                : googleError(status, googleStatus, message, reason)));

        List<ILoggingEvent> logged = logsOf(
                () -> assertThat(reader().read(PHOTO, "image/jpeg").outcome()).isEqualTo(Outcome.ERROR));

        assertThat(logged).hasSize(1);
        ILoggingEvent event = logged.get(0);
        assertThat(event.getLevel()).isEqualTo(severe ? Level.ERROR : Level.WARN);
        assertThat(event.getFormattedMessage()).contains(message).doesNotContain(KEY);
    }

    // ---------------------------------------------------------------- pause after a failure of the service

    @Test
    void afterAFailureOfTheServiceNothingIsSentForAWhileThenRequestsGoOutAgain() throws Exception {
        generateReplies.add(new Reply(503, googleError(503, "UNAVAILABLE", "The model is overloaded.")));
        generateReplies.add(ok(answer(modelJson("ok", "", "", false), "STOP")));
        // A pause far longer than the few reads below take, even on a slow machine
        GeminiMediaReader reader = reader(1_000, 1_000, Duration.ofSeconds(4));

        assertThat(reader.read(PHOTO, "image/jpeg").outcome()).isEqualTo(Outcome.ERROR);
        long failedAt = System.nanoTime();

        // Right after it: BUSY at once, and nothing is sent, not even a large video's upload
        Reading photo = reader.read(PHOTO, "image/jpeg");
        assertThat(photo.outcome()).isEqualTo(Outcome.BUSY);
        assertThat(detail(photo).get("busy").asText()).startsWith("Gemini failed ").contains(" ms ago; not asked again"
                + " before ");
        assertThat(reader.read(new byte[2_000], "video/mp4").outcome()).isEqualTo(Outcome.BUSY);
        assertThat(routes()).containsExactly("POST " + GENERATE_PATH);
        assertThat(counted).containsExactly(GeminiMediaReader.GENERATE_USAGE);

        // Once the pause is over, requests go out again
        Thread.sleep(Math.max(0, Duration.ofMillis(4_200).minus(Duration.ofNanos(System.nanoTime() - failedAt))
                .toMillis()));
        Reading again = reader.read(PHOTO, "image/jpeg");
        assertThat(again.outcome()).isEqualTo(Outcome.READ);
        assertThat(again.messageText()).isEqualTo("ok");
        assertThat(routes()).containsExactly("POST " + GENERATE_PATH, "POST " + GENERATE_PATH);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {"429|RESOURCE_EXHAUSTED|", "500|INTERNAL|", "503|UNAVAILABLE|",
            "403|PERMISSION_DENIED|", "404|NOT_FOUND|", "400|FAILED_PRECONDITION|",
            "400|INVALID_ARGUMENT|API_KEY_INVALID"})
    void quotaServerKeyModelAndProjectErrorsPauseTheRequests(int status, String googleStatus, String reason) {
        generateReplies.add(new Reply(status, reason == null ? googleError(status, googleStatus, "failed")
                : googleError(status, googleStatus, "failed", reason)));
        GeminiMediaReader reader = reader();

        assertThat(reader.read(PHOTO, "image/jpeg").outcome()).isEqualTo(Outcome.ERROR);
        assertThat(reader.read(PHOTO, "image/jpeg").outcome()).isEqualTo(Outcome.BUSY);
        assertThat(requests).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"UNREADABLE", "BLOCKED", "NOT_JSON", "INVALID_ARGUMENT"})
    void answersAboutOneFileDoNotPauseTheRequests(String kind) {
        generateReplies.add(switch (kind) {
            case "UNREADABLE" -> ok(answer("{\"messageText\": \"Tuyển nhân vi", "RECITATION"));
            case "BLOCKED" -> ok(blockedPrompt("SAFETY"));
            case "NOT_JSON" -> ok(answer("Sorry, I cannot help with that.", "STOP"));
            default -> new Reply(400, googleError(400, "INVALID_ARGUMENT", "Request contains an invalid argument."));
        });
        generateReplies.add(ok(answer(modelJson("ok", "", "", false), "STOP")));
        GeminiMediaReader reader = reader();

        assertThat(reader.read(PHOTO, "image/jpeg").outcome()).isNotIn(Outcome.READ, Outcome.BUSY);
        assertThat(reader.read(PHOTO, "image/jpeg").outcome()).isEqualTo(Outcome.READ);
        assertThat(requests).hasSize(2);
    }

    @Test
    void aProjectLimitedForPolicyViolationsPausesTheRequests() throws IOException {
        // Every file fails alike until an Admin has the project checked in AI Studio
        generateReplies.add(ok(answer("", "PUP_LIMITED_DISABLED")));
        GeminiMediaReader reader = reader();

        List<ILoggingEvent> logged = logsOf(() -> {
            Reading reading = reader.read(PHOTO, "image/jpeg");
            assertThat(reading.outcome()).isEqualTo(Outcome.ERROR);
        });
        assertThat(logged).extracting(ILoggingEvent::getLevel).containsExactly(Level.ERROR);

        assertThat(reader.read(PHOTO, "image/jpeg").outcome()).isEqualTo(Outcome.BUSY);
        assertThat(requests).hasSize(1);
    }

    @Test
    void anUploadedFileGoneWhilePolledIsAnErrorOfThatFileOnly() throws IOException {
        // A 404 (or 403) while polling is about that file: no severe log, no pause for the other files
        pollReply = new Reply(404, googleError(404, "NOT_FOUND", "File " + FILE_NAME + " not found."));
        GeminiMediaReader reader = reader(1_000, 1_000);

        List<ILoggingEvent> logged = logsOf(() -> {
            Reading reading = reader.read(new byte[2_000], "video/mp4");
            assertThat(reading.outcome()).isEqualTo(Outcome.ERROR);
        });
        assertThat(logged).extracting(ILoggingEvent::getLevel).doesNotContain(Level.ERROR);
        assertThat(routes()).containsExactly(
                "POST /upload/v1beta/files",
                "POST /upload/v1beta/files?" + UPLOAD_QUERY,
                "GET /v1beta/" + FILE_NAME,
                "DELETE /v1beta/" + FILE_NAME);

        Reading next = reader.read(PHOTO, "image/jpeg");
        assertThat(next.outcome()).isEqualTo(Outcome.READ);
        assertThat(routes()).last().isEqualTo("POST " + GENERATE_PATH);
    }

    // ---------------------------------------------------------------- request budget

    @Test
    void overTheBudgetTheReadingIsBusyAndNothingIsSent() throws IOException {
        GeminiMediaReader reader = reader(2, 1_000_000);
        long started = System.nanoTime();

        assertThat(reader.read(PHOTO, "image/jpeg").outcome()).isEqualTo(Outcome.READ);
        assertThat(reader.read(PHOTO, "image/jpeg").outcome()).isEqualTo(Outcome.READ);
        Reading third = reader.read(PHOTO, "image/jpeg");

        assertThat(third.outcome()).isEqualTo(Outcome.BUSY);
        assertThat(detail(third).get("busy").asText()).contains("2 a minute");
        assertThat(requests).hasSize(2);
        assertThat(counted).containsExactly(GeminiMediaReader.GENERATE_USAGE, GeminiMediaReader.GENERATE_USAGE);
        // BUSY answers at once instead of waiting a minute for the next free request
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(10));
    }

    @Test
    void pacerKeepsAtMostThatManyStartsInAnySixtySeconds() {
        Pacer pacer = new Pacer(2);

        assertThat(pacer.reserve(1_000, 0)).isZero();
        assertThat(pacer.reserve(1_500, 0)).isZero();
        // The next start is 61_000: too far for a 20 s wait, and nothing is reserved
        assertThat(pacer.reserve(2_000, 20_000)).isEqualTo(-1);
        assertThat(pacer.reserve(45_000, 20_000)).isEqualTo(16_000);
        assertThat(pacer.reserve(45_000, 20_000)).isEqualTo(16_500);
        // 61_000 and 61_500 are taken: the next one would start at 121_000
        assertThat(pacer.reserve(45_000, 20_000)).isEqualTo(-1);
        assertThat(pacer.reserve(110_000, 20_000)).isEqualTo(11_000);
        assertThat(pacer.reserve(300_000, 0)).isZero();
    }

    // ---------------------------------------------------------------- videos

    @ParameterizedTest
    @ValueSource(strings = {"video/mp4", "video/quicktime"})
    void smallVideoGoesInlineWithItsStoredType(String mimeType) throws IOException {
        byte[] video = "small video bytes".getBytes(StandardCharsets.US_ASCII);
        generateReplies.add(ok(answer(modelJson("", "", "Xin chào mọi người", false), "STOP")));

        Reading reading = reader().read(video, mimeType);

        assertThat(reading.outcome()).isEqualTo(Outcome.READ);
        assertThat(reading.speech()).isEqualTo("Xin chào mọi người");
        assertThat(routes()).containsExactly("POST " + GENERATE_PATH);
        JsonNode parts = body(requests.get(0)).at("/contents/0/parts");
        assertThat(parts.at("/0/inlineData/mimeType").asText()).isEqualTo(mimeType);
        assertThat(parts.at("/0/inlineData/data").asText()).isEqualTo(Base64.getEncoder().encodeToString(video));
        assertThat(parts.at("/1/text").asText()).isEqualTo("Read this video.");
        // Frames in high resolution, to read small text; still no thinkingConfig
        JsonNode config = body(requests.get(0)).get("generationConfig");
        assertThat(fieldNames(config)).containsExactly("responseMimeType", "responseJsonSchema", "maxOutputTokens",
                "mediaResolution");
        assertThat(config.get("mediaResolution").asText()).isEqualTo("MEDIA_RESOLUTION_HIGH");
    }

    @ParameterizedTest
    @ValueSource(strings = {"video/mp4", "video/quicktime"})
    void largeVideoIsUploadedPolledUntilActiveReadByUriAndDeleted(String mimeType) throws IOException {
        byte[] video = new byte[2_000];
        Arrays.fill(video, (byte) 7);
        finalizeState = "PROCESSING";
        pollStates.addAll(List.of("PROCESSING", "ACTIVE"));
        generateReplies.add(ok(answer(modelJson("", "", "chào mọi người", false), "STOP")));

        Reading reading = reader(1_000, 1_000).read(video, mimeType);

        assertThat(reading.outcome()).isEqualTo(Outcome.READ);
        assertThat(reading.speech()).isEqualTo("chào mọi người");
        assertThat(routes()).containsExactly(
                "POST /upload/v1beta/files",
                "POST /upload/v1beta/files?" + UPLOAD_QUERY,
                "GET /v1beta/" + FILE_NAME,
                "GET /v1beta/" + FILE_NAME,
                "POST " + GENERATE_PATH,
                "DELETE /v1beta/" + FILE_NAME);

        Request start = requests.get(0);
        assertThat(start.header("x-goog-api-key")).isEqualTo(KEY);
        assertThat(start.header("X-Goog-Upload-Protocol")).isEqualTo("resumable");
        assertThat(start.header("X-Goog-Upload-Command")).isEqualTo("start");
        assertThat(start.header("X-Goog-Upload-Header-Content-Length")).isEqualTo("2000");
        assertThat(start.header("X-Goog-Upload-Header-Content-Type")).isEqualTo(mimeType);
        assertThat(start.header("Content-Type")).startsWith("application/json");
        assertThat(body(start)).isEqualTo(json.readTree("{\"file\": {\"displayName\": \"asvsj-media\"}}"));

        Request upload = requests.get(1);
        assertThat(upload.header("X-Goog-Upload-Offset")).isEqualTo("0");
        assertThat(upload.header("X-Goog-Upload-Command")).isEqualTo("upload, finalize");
        assertThat(upload.header("Content-Type")).isEqualTo("application/octet-stream");
        // The session URL carries the upload: no key there
        assertThat(upload.header("x-goog-api-key")).isNull();
        assertThat(upload.body()).isEqualTo(video);

        assertThat(requests.get(2).header("x-goog-api-key")).isEqualTo(KEY);
        JsonNode parts = body(requests.get(4)).at("/contents/0/parts");
        assertThat(parts).hasSize(2);
        assertThat(fieldNames(parts.get(0))).containsExactly("fileData");
        assertThat(parts.at("/0/fileData/mimeType").asText()).isEqualTo(mimeType);
        assertThat(parts.at("/0/fileData/fileUri").asText()).isEqualTo(base + "/v1beta/" + FILE_NAME);
        assertThat(parts.at("/1/text").asText()).isEqualTo("Read this video.");
        assertThat(body(requests.get(4)).at("/generationConfig/mediaResolution").asText())
                .isEqualTo("MEDIA_RESOLUTION_HIGH");
        assertThat(allFieldNames(body(requests.get(4)))).doesNotContain("thinkingConfig");
        assertThat(requests.get(5).header("x-goog-api-key")).isEqualTo(KEY);

        assertThat(requests).allSatisfy(r -> assertThat(r.route()).doesNotContain(KEY).doesNotContain("key="));
        assertThat(counted).containsExactly(GeminiMediaReader.UPLOAD_USAGE, GeminiMediaReader.GENERATE_USAGE);
    }

    @Test
    void uploadedVideoIsDeletedAlsoWhenTheReadingFails() throws IOException {
        finalizeState = "ACTIVE";
        generateReplies.add(new Reply(503, googleError(503, "UNAVAILABLE", "The model is overloaded.")));

        Reading reading = reader(1_000, 1_000).read(new byte[2_000], "video/mp4");

        assertThat(reading.outcome()).isEqualTo(Outcome.ERROR);
        assertThat(detail(reading).get("httpStatus").asInt()).isEqualTo(503);
        assertThat(routes()).containsExactly(
                "POST /upload/v1beta/files",
                "POST /upload/v1beta/files?" + UPLOAD_QUERY,
                "POST " + GENERATE_PATH,
                "DELETE /v1beta/" + FILE_NAME);
        assertThat(counted).containsExactly(GeminiMediaReader.UPLOAD_USAGE, GeminiMediaReader.GENERATE_USAGE);
    }

    @Test
    void fileGoogleCouldNotProcessIsUnreadableAndDeleted() throws IOException {
        pollStates.add("FAILED");

        Reading reading = reader(1_000, 1_000).read(new byte[2_000], "video/quicktime");

        assertThat(reading.outcome()).isEqualTo(Outcome.UNREADABLE);
        assertThat(detail(reading).get("error").asText())
                .isEqualTo("file processing: file state FAILED: Video could not be processed");
        assertThat(routes()).containsExactly(
                "POST /upload/v1beta/files",
                "POST /upload/v1beta/files?" + UPLOAD_QUERY,
                "GET /v1beta/" + FILE_NAME,
                "DELETE /v1beta/" + FILE_NAME);
        assertThat(counted).containsExactly(GeminiMediaReader.UPLOAD_USAGE);
    }

    @Test
    void fileStillProcessingAfterTheWaitIsAnErrorAndDeleted() throws IOException {
        pollStates.add("PROCESSING");

        Reading reading = reader(1_000, 1_000).read(new byte[2_000], "video/mp4");

        assertThat(reading.outcome()).isEqualTo(Outcome.ERROR);
        assertThat(detail(reading).get("error").asText()).startsWith("file processing: file not ACTIVE within 400 ms");
        assertThat(routes()).first().isEqualTo("POST /upload/v1beta/files");
        assertThat(routes()).last().isEqualTo("DELETE /v1beta/" + FILE_NAME);
        assertThat(routes()).doesNotContain("POST " + GENERATE_PATH);
        assertThat(routes()).filteredOn(r -> r.startsWith("GET ")).hasSizeGreaterThan(2);
        assertThat(counted).containsExactly(GeminiMediaReader.UPLOAD_USAGE);
    }

    @Test
    void uploadWithoutSessionAddressIsAnError() throws IOException {
        giveUploadUrl = false;

        Reading reading = reader(1_000, 1_000).read(new byte[2_000], "video/mp4");

        assertThat(reading.outcome()).isEqualTo(Outcome.ERROR);
        assertThat(detail(reading).get("error").asText()).startsWith("file upload start: ");
        assertThat(routes()).containsExactly("POST /upload/v1beta/files");
        assertThat(counted).containsExactly(GeminiMediaReader.UPLOAD_USAGE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"not json", "{\"file\": {\"name\": \"../../v1beta/models\", \"state\": \"ACTIVE\"}}",
            "{\"file\": {\"state\": \"ACTIVE\", \"uri\": \"x\"}}"})
    void unusableUploadAnswerIsAnErrorAndNeverThrows(String answer) throws IOException {
        finalizeBody = answer;

        Reading reading = reader(1_000, 1_000).read(new byte[2_000], "video/mp4");

        assertThat(reading.outcome()).isEqualTo(Outcome.ERROR);
        assertThat(detail(reading).get("error").asText()).startsWith("file upload: ");
        assertThat(routes()).containsExactly("POST /upload/v1beta/files", "POST /upload/v1beta/files?" + UPLOAD_QUERY);
    }

    @Test
    void activeFileWithoutUriIsAnErrorAndDeleted() throws IOException {
        finalizeBody = "{\"file\": {\"name\": \"" + FILE_NAME + "\", \"state\": \"ACTIVE\"}}";

        Reading reading = reader(1_000, 1_000).read(new byte[2_000], "video/mp4");

        assertThat(reading.outcome()).isEqualTo(Outcome.ERROR);
        assertThat(detail(reading).get("error").asText()).isEqualTo("file processing: ACTIVE file without uri");
        assertThat(routes()).last().isEqualTo("DELETE /v1beta/" + FILE_NAME);
    }

    @Test
    void noVideoIsUploadedWhileTheBudgetIsUsedUp() throws IOException {
        GeminiMediaReader reader = reader(1, 1_000);
        assertThat(reader.read(PHOTO, "image/jpeg").outcome()).isEqualTo(Outcome.READ);

        Reading reading = reader.read(new byte[2_000], "video/mp4");

        assertThat(reading.outcome()).isEqualTo(Outcome.BUSY);
        assertThat(routes()).containsExactly("POST " + GENERATE_PATH);
        assertThat(counted).containsExactly(GeminiMediaReader.GENERATE_USAGE);
    }

    @Test
    void aLargeVideoReservesItsRequestBeforeTheUploadSoNoOtherReadingTakesIt() throws Exception {
        GeminiMediaReader reader = reader(1, 1_000);
        CountDownLatch polling = new CountDownLatch(1);
        CountDownLatch goOn = new CountDownLatch(1);
        onPoll = () -> {
            polling.countDown();
            try {
                goOn.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        CompletableFuture<Reading> video;
        Reading photo;
        try {
            video = CompletableFuture.supplyAsync(() -> reader.read(new byte[2_000], "video/mp4"));
            assertThat(polling.await(10, TimeUnit.SECONDS)).isTrue();
            // While the uploaded video is processed at Google, the only request of the minute is already the video's
            photo = reader.read(PHOTO, "image/jpeg");
        } finally {
            goOn.countDown();
        }

        assertThat(photo.outcome()).isEqualTo(Outcome.BUSY);
        assertThat(video.get(10, TimeUnit.SECONDS).outcome()).isEqualTo(Outcome.READ);
        assertThat(routes()).containsExactly(
                "POST /upload/v1beta/files",
                "POST /upload/v1beta/files?" + UPLOAD_QUERY,
                "GET /v1beta/" + FILE_NAME,
                "POST " + GENERATE_PATH,
                "DELETE /v1beta/" + FILE_NAME);
    }

    @Test
    void filesUpTo5MbGoInlineAndLargerOnesThroughTheFilesApi() throws IOException {
        assertThat(GeminiMediaReader.INLINE_LIMIT_BYTES).isEqualTo(5L * 1024 * 1024);
        finalizeState = "ACTIVE";
        GeminiMediaReader reader = new GeminiMediaReader(KEY, MODEL, base, 10, json, counted::add);

        assertThat(reader.read(new byte[5 * 1024 * 1024], "video/mp4").outcome()).isEqualTo(Outcome.READ);
        assertThat(routes()).containsExactly("POST " + GENERATE_PATH);

        requests.clear();
        assertThat(reader.read(new byte[5 * 1024 * 1024 + 1], "video/mp4").outcome()).isEqualTo(Outcome.READ);
        assertThat(routes()).containsExactly(
                "POST /upload/v1beta/files",
                "POST /upload/v1beta/files?" + UPLOAD_QUERY,
                "POST " + GENERATE_PATH,
                "DELETE /v1beta/" + FILE_NAME);
        assertThat(requests.get(1).body()).hasSize(5 * 1024 * 1024 + 1);
    }

    @Test
    void aLargePhotoThroughTheFilesApiIsStillReadAsAPhoto() throws IOException {
        finalizeState = "ACTIVE";

        Reading reading = reader(1_000, 1_000).read(new byte[2_000], "image/jpeg");

        assertThat(reading.outcome()).isEqualTo(Outcome.READ);
        assertThat(routes()).containsExactly(
                "POST /upload/v1beta/files",
                "POST /upload/v1beta/files?" + UPLOAD_QUERY,
                "POST " + GENERATE_PATH,
                "DELETE /v1beta/" + FILE_NAME);
        JsonNode body = body(requests.get(2));
        assertThat(body.at("/contents/0/parts/0/fileData/mimeType").asText()).isEqualTo("image/jpeg");
        assertThat(body.at("/contents/0/parts/1/text").asText()).isEqualTo("Read this photo.");
        // Only videos get the high media resolution
        assertThat(fieldNames(body.get("generationConfig"))).containsExactly("responseMimeType",
                "responseJsonSchema", "maxOutputTokens");
    }
}

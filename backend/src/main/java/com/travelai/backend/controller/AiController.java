package com.travelai.backend.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.travelai.backend.security.ApiSecurity;
import com.travelai.backend.security.VoiceFormat;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@RestController
@RequestMapping("/api/ai")
public class AiController {
    private final ObjectMapper json;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final String key;
    private final String model;
    private final com.travelai.backend.booking.MonetizationService monetization;
    private final Map<Long, Long> lastRequest = new ConcurrentHashMap<>();
    public AiController(ObjectMapper json, String key, String model) {
        this(json, key, model, null);
    }
    @org.springframework.beans.factory.annotation.Autowired
    public AiController(ObjectMapper json, @Value("${GEMINI_API_KEY:}") String key,
                        @Value("${GEMINI_MODEL:gemini-3.5-flash-lite}") String model,
                        @org.springframework.beans.factory.annotation.Autowired(required = false) com.travelai.backend.booking.MonetizationService monetization) {
        this.json = json; this.key = key.trim(); this.model = model.trim(); this.monetization = monetization;
    }
    public record Turn(String role, String text) {}
    public record ChatInput(List<Turn> messages) {}

    @PostMapping("/chat")
    public Map<String, String> chat(@RequestBody ChatInput input, HttpServletRequest request) {
        var actor = ApiSecurity.actor(request);
        long userId = actor.id();
        if (input == null || input.messages() == null || input.messages().isEmpty() || input.messages().size() > 12)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Hội thoại không hợp lệ.");
        List<Map<String, Object>> contents = new ArrayList<>();
        for (Turn turn : input.messages()) {
            if (turn == null || !("user".equals(turn.role()) || "model".equals(turn.role())) || turn.text() == null || turn.text().isBlank() || turn.text().length() > 3000)
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Tin nhắn không hợp lệ hoặc quá dài.");
            contents.add(Map.of("role", turn.role(), "parts", List.of(Map.of("text", turn.text()))));
        }
        if (!"user".equals(input.messages().getLast().role())) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Thiếu câu hỏi.");
        validateProviderReady();
        if (monetization != null) monetization.assertAiQuotaAvailable(actor);
        Map<String, String> answer = generate(userId, contents);
        if (monetization != null) monetization.consumeAiQuota(actor);
        return answer;
    }
    public record VoiceInput(String audio) {}
    @PostMapping("/voice")
    public Map<String,String> voice(@RequestBody VoiceInput input, HttpServletRequest request) {
        var actor = ApiSecurity.actor(request);
        long userId = actor.id();
        VoiceFormat.Audio audio=VoiceFormat.parse(input == null ? null : input.audio());
        List<Map<String,Object>> contents=voiceContents(audio);
        validateProviderReady();
        if (monetization != null) monetization.assertAiQuotaAvailable(actor);
        Map<String, String> answer = generate(userId, contents);
        if (monetization != null) monetization.consumeAiQuota(actor);
        return answer;
    }
    /** Audio first, then the instruction: Gemini answers the spoken question directly this way. */
    public static List<Map<String,Object>> voiceContents(VoiceFormat.Audio audio) {
        return List.of(Map.of("role","user","parts",List.of(
            Map.of("inlineData",Map.of("mimeType",audio.mime(),"data",audio.base64())),
            Map.of("text","Người dùng gửi một câu hỏi bằng giọng nói (tệp âm thanh đính kèm). Hãy nghe và trả lời trực tiếp bằng tiếng Việt, ngắn gọn. Chỉ khi thật sự không nghe được nội dung mới nói rõ điều đó."))));
    }
    private void validateProviderReady() {
        if (key.isEmpty()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Chat AI chưa được cấu hình khóa Gemini trên máy chủ.");
        if (!model.matches("[a-zA-Z0-9._-]{1,80}")) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Cấu hình mô hình Gemini không hợp lệ.");
    }
    private Map<String,String> generate(long userId,List<Map<String,Object>> contents) {
        validateProviderReady();
        long now = System.currentTimeMillis();
        Long before = lastRequest.put(userId, now);
        if (before != null && now - before < 2000) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Vui lòng chờ trước khi gửi tiếp.");
        if (lastRequest.size() > 10000) lastRequest.entrySet().removeIf(entry -> now - entry.getValue() > 3600000);
        try {
            Map<String, Object> payload = Map.of(
                "systemInstruction", Map.of("parts", List.of(Map.of("text", "Bạn là trợ lý du lịch TravelAI. Trả lời bằng tiếng Việt; không tự bịa giá, ưu đãi, lịch hay tình trạng đặt chỗ. Nói rõ khi không chắc. Không yêu cầu thông tin thẻ ngân hàng."))),
                "contents", contents,
                "generationConfig", Map.of("maxOutputTokens", 1024));
            HttpRequest outgoing = HttpRequest.newBuilder(URI.create("https://generativelanguage.googleapis.com/v1beta/models/" + model + ":generateContent"))
                .timeout(Duration.ofSeconds(45)).header("x-goog-api-key", key).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(payload))).build();
            HttpResponse<String> response = client.send(outgoing, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 429) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Gemini đang hết hạn mức hoặc quá tải; vui lòng thử lại sau.");
            if (response.statusCode() < 200 || response.statusCode() >= 300) throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Gemini không xử lý được yêu cầu; kiểm tra khóa, mô hình và hạn mức.");
            JsonNode parts = json.readTree(response.body()).path("candidates").path(0).path("content").path("parts");
            StringBuilder answer = new StringBuilder();
            for (JsonNode part : parts) if (part.path("text").isTextual()) answer.append(part.path("text").asText());
            if (answer.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Gemini không trả về nội dung phù hợp.");
            return Map.of("answer", answer.toString());
        } catch (ResponseStatusException error) { throw error; }
          catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Yêu cầu AI bị gián đoạn."); }
          catch (Exception error) { throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Không kết nối được Gemini."); }
    }
    @PostMapping("/detect")
    public void removedFakeDetection() {
        throw new ResponseStatusException(HttpStatus.GONE, "Nhận diện ảnh sử dụng AI service, không dùng API cũ.");
    }
}

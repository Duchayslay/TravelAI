package com.travelai.backend.booking;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.travelai.backend.security.SessionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.text.Normalizer;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static com.travelai.backend.booking.BookingContracts.VipItineraryInput;
import static com.travelai.backend.booking.BookingPolicies.VIETNAM;

/**
 * Generates VIP member itineraries strictly outside @Transactional boundaries so external
 * Gemini HTTP calls never hold HikariCP database connections.
 */
@Service
public class VipItineraryPlannerService {

    private static final Logger log = LoggerFactory.getLogger(VipItineraryPlannerService.class);
    public static final long MIN_INTERVAL_MS = 4_000L;
    private static final Set<String> TIMELINE_CATEGORIES = Set.of(
            "WAKE", "TRANSIT", "FOOD", "STAY", "SIGHT", "TOUR", "NIGHT"
    );

    private final MonetizationService monetizationService;
    private final Clock clock;
    private final ObjectMapper json;
    private final String geminiKey;
    private final String geminiModel;
    private final String geminiBaseUrl;
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
    private final Map<Long, Long> lastRequest = new ConcurrentHashMap<>();

    public VipItineraryPlannerService(
            MonetizationService monetizationService,
            Clock clock,
            ObjectMapper json,
            String geminiKey,
            String geminiModel,
            String geminiBaseUrl
    ) {
        this.monetizationService = monetizationService;
        this.clock = clock;
        this.json = json == null ? new ObjectMapper() : json;
        this.geminiKey = geminiKey == null ? "" : geminiKey.trim();
        this.geminiModel = (geminiModel == null || geminiModel.isBlank()) ? "gemini-3.5-flash-lite" : geminiModel.trim();
        String base = (geminiBaseUrl == null || geminiBaseUrl.isBlank())
                ? "https://generativelanguage.googleapis.com"
                : geminiBaseUrl.trim();
        this.geminiBaseUrl = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }

    @Autowired
    public VipItineraryPlannerService(
            MonetizationService monetizationService,
            Clock clock,
            ObjectMapper json,
            @Value("${GEMINI_API_KEY:}") String geminiKey,
            @Value("${GEMINI_MODEL:gemini-3.5-flash-lite}") String geminiModel
    ) {
        this(monetizationService, clock, json, geminiKey, geminiModel, "https://generativelanguage.googleapis.com");
    }

    public static String sanitizeProvince(String rawProvince) {
        if (rawProvince == null) return "";
        String cleaned = rawProvince
                .replaceAll("[\\p{Cntrl}]+", " ")
                .replaceAll("[\"`{}<>\\\\]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
        return cleaned;
    }

    void clearRateLimitsForTest() {
        lastRequest.clear();
    }

    private void enforceRateLimit(long userId) {
        long nowMs = System.currentTimeMillis();
        Long previous = lastRequest.put(userId, nowMs);
        if (previous != null && nowMs - previous < MIN_INTERVAL_MS) {
            lastRequest.put(userId, previous);
            throw new ResponseStatusException(
                    HttpStatus.TOO_MANY_REQUESTS,
                    "Vui lòng chờ ít giây trước khi tạo lại lịch trình VIP."
            );
        }
        if (lastRequest.size() > 10_000) {
            lastRequest.entrySet().removeIf(entry -> nowMs - entry.getValue() > 3_600_000L);
        }
    }

    public Map<String, Object> generateVipItinerary(SessionService.Actor actor, VipItineraryInput input) {
        String province = sanitizeProvince(input == null ? null : input.province());
        if (province.isEmpty() || province.length() > 80) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Vui lòng chọn tỉnh/thành phố hợp lệ.");
        }
        int daysCount = input.days() == null ? 3 : input.days();
        if (daysCount < 1 || daysCount > 7) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Số ngày lịch trình VIP từ 1 đến 7 ngày.");
        }
        String style = input.style() == null ? "LUXURY" : input.style().trim().toUpperCase(Locale.ROOT);

        // Short read-only transaction: validates VIP membership and snapshots catalog items, then releases DB connection.
        MonetizationService.VipItinerarySnapshot snapshot = monetizationService.loadVipItinerarySnapshot(actor, province);

        // Rate-limit checked after authorization so FREE users consistently receive 403 FORBIDDEN.
        enforceRateLimit(actor.id());

        String styleName = switch (style) {
            case "CULTURE" -> "Khám phá văn hóa & di sản chuyên sâu";
            case "OPTIMAL" -> "Tối ưu thời gian & ngân sách thông minh";
            default -> "Nghỉ dưỡng đặc quyền hạng sang (VIP Concierge)";
        };

        LocalDate startDay = LocalDate.ofInstant(clock.instant(), VIETNAM).plusDays(1);
        List<Map<String, Object>> geminiDays = tryGenerateTimelineWithGemini(
                actor.id(), province, daysCount, styleName, snapshot.discountPercent(), startDay,
                snapshot.places(), snapshot.tours(), snapshot.hotels()
        );
        String aiSource = geminiDays != null ? "GEMINI_AI" : "SMART_PLANNER";
        List<Map<String, Object>> dayPlans = geminiDays != null
                ? geminiDays
                : buildDetailedCatalogDays(
                        province, normalizeText(province), daysCount, style, styleName,
                        snapshot.discountPercent(), startDay,
                        snapshot.places(), snapshot.tours(), snapshot.hotels()
                );

        long origEstimate = 0L;
        if (!snapshot.tours().isEmpty() && snapshot.tours().get(0).price() != null) {
            origEstimate = Math.addExact(origEstimate, Math.round(snapshot.tours().get(0).price()));
        }
        if (!snapshot.hotels().isEmpty() && snapshot.hotels().get(0).price() != null) {
            int nights = Math.max(1, daysCount - 1);
            origEstimate = Math.addExact(origEstimate, Math.multiplyExact(Math.round(snapshot.hotels().get(0).price()), nights));
        }
        long memberEstimate = discountedMemberPrice(origEstimate, snapshot.discountPercent());
        long savedEstimate = Math.subtractExact(origEstimate, memberEstimate);

        String spokenSummary = "Lịch trình đặc quyền " + snapshot.badge() + " " + daysCount + " ngày tại " + province
                + " theo phong cách " + styleName + ". "
                + (savedEstimate > 0
                    ? "Dự kiến với thẻ " + snapshot.planName() + ", bạn được giảm trực tiếp " + snapshot.discountPercent() + " phần trăm, tiết kiệm " + savedEstimate + " đồng."
                    : "Bạn được hưởng ưu đãi giảm " + snapshot.discountPercent() + " phần trăm cho mọi dịch vụ đặt kèm.");

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("simulation", "SIMULATED");
        response.put("currency", "VND");
        response.put("tier", snapshot.planCode());
        response.put("badge", snapshot.badge());
        response.put("discountPercent", snapshot.discountPercent());
        response.put("province", province);
        response.put("style", style);
        response.put("styleLabel", styleName);
        response.put("aiSource", aiSource);
        response.put("days", dayPlans);
        response.put("estimatedOriginalVnd", origEstimate);
        response.put("estimatedMemberVnd", memberEstimate);
        response.put("estimatedSavedVnd", savedEstimate);
        response.put("conciergeSummary", spokenSummary);
        response.put("recommendedTours", snapshot.tours().stream().limit(3).map(t -> {
            long o = t.price() == null ? 0L : Math.round(t.price());
            long m = discountedMemberPrice(o, snapshot.discountPercent());
            return Map.<String, Object>of("id", t.id(), "name", t.name(), "originalPrice", o, "memberPrice", m, "savedAmount", o - m);
        }).toList());
        response.put("recommendedHotels", snapshot.hotels().stream().limit(3).map(h -> {
            long o = h.price() == null ? 0L : Math.round(h.price());
            long m = discountedMemberPrice(o, snapshot.discountPercent());
            return Map.<String, Object>of("id", h.id(), "name", h.name(), "originalPrice", o, "memberPrice", m, "savedAmount", o - m);
        }).toList());
        return response;
    }

    private List<Map<String, Object>> tryGenerateTimelineWithGemini(
            long userId,
            String province,
            int daysCount,
            String styleName,
            int discountPercent,
            LocalDate startDay,
            List<MonetizationService.PlaceSnap> matchedPlaces,
            List<MonetizationService.TourSnap> matchedTours,
            List<MonetizationService.HotelSnap> matchedHotels
    ) {
        if (geminiKey.isEmpty() || !geminiModel.matches("[a-zA-Z0-9._-]{1,80}")) {
            return null;
        }
        try {
            String placeList = matchedPlaces.isEmpty()
                    ? "Các danh thắng nổi tiếng tại " + province
                    : String.join(", ", matchedPlaces.stream().limit(8).map(MonetizationService.PlaceSnap::name).toList());
            String tourList = matchedTours.isEmpty()
                    ? "Chưa có tour cố định"
                    : String.join(", ", matchedTours.stream().limit(4).map(MonetizationService.TourSnap::name).toList());
            String hotelList = matchedHotels.isEmpty()
                    ? "Khách sạn trung tâm " + province
                    : String.join(", ", matchedHotels.stream().limit(4).map(MonetizationService.HotelSnap::name).toList());

            int stopsPerDay = daysCount >= 4 ? 5 : 6;
            int maxOutputTokens = Math.min(4096, Math.max(2048, daysCount * 750));

            String prompt = "Thiết kế lịch trình du lịch ngắn gọn theo từng mốc giờ cho " + daysCount + " ngày tại " + province
                    + " theo phong cách '" + styleName + "' (hội viên được giảm " + discountPercent + "%). "
                    + "Ưu tiên lồng ghép tự nhiên các địa danh thật trong hệ thống: [" + placeList + "], "
                    + "tour: [" + tourList + "], lưu trú: [" + hotelList + "]. "
                    + "Yêu cầu bắt buộc: Mỗi ngày có đúng " + stopsPerDay + " mốc giờ từ sáng tới tối (ví dụ 07:00, 09:00, 12:00, 14:30, 19:30), "
                    + "mô tả ngắn gọn dưới 90 ký tự mỗi mục, nội dung giữa các ngày KHÔNG ĐƯỢC lặp lại câu chữ của nhau. "
                    + "Chỉ trả về JSON thuần theo cấu trúc: "
                    + "{\"days\":[{\"day\":1,\"title\":\"...\",\"timeline\":[{\"time\":\"07:00\",\"category\":\"WAKE|TRANSIT|FOOD|STAY|SIGHT|TOUR|NIGHT\",\"title\":\"...\",\"subtitle\":\"...\",\"placeLabel\":\"...\"}]}]}";

            Map<String, Object> payload = Map.of(
                    "contents", List.of(Map.of("role", "user", "parts", List.of(Map.of("text", prompt)))),
                    "generationConfig", Map.of(
                            "responseMimeType", "application/json",
                            "maxOutputTokens", maxOutputTokens
                    )
            );
            HttpRequest req = HttpRequest.newBuilder(
                            URI.create(geminiBaseUrl + "/v1beta/models/" + geminiModel + ":generateContent"))
                    .timeout(Duration.ofSeconds(15))
                    .header("x-goog-api-key", geminiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(payload)))
                    .build();
            HttpResponse<String> res = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() < 200 || res.statusCode() >= 300) {
                log.warn("Gemini VIP itinerary returned HTTP {} for user {}; falling back to catalog planner", res.statusCode(), userId);
                return null;
            }

            JsonNode candidate = json.readTree(res.body()).path("candidates").path(0);
            String finishReason = candidate.path("finishReason").asText("");
            JsonNode parts = candidate.path("content").path("parts");
            StringBuilder raw = new StringBuilder();
            for (JsonNode part : parts) {
                if (part.path("text").isTextual()) raw.append(part.path("text").asText());
            }
            if (raw.isEmpty()) {
                log.warn("Gemini VIP itinerary returned empty content (finishReason={}) for user {}; falling back to catalog planner", finishReason, userId);
                return null;
            }
            String cleanJson = raw.toString().trim().replaceAll("^```(?:json)?\\s*", "").replaceAll("\\s*```$", "");
            JsonNode root = json.readTree(cleanJson);
            JsonNode daysNode = root.path("days");
            if (!daysNode.isArray() || daysNode.size() != daysCount) {
                log.warn("Gemini VIP itinerary returned {} days instead of {} (finishReason={}) for user {}; falling back to catalog planner",
                        daysNode.size(), daysCount, finishReason, userId);
                return null;
            }

            List<Map<String, Object>> parsed = new ArrayList<>();
            for (int i = 0; i < daysCount; i++) {
                JsonNode dn = daysNode.get(i);
                JsonNode tlNode = dn.path("timeline");
                if (!tlNode.isArray() || tlNode.size() < 4) {
                    log.warn("Gemini VIP itinerary day {} had insufficient stops ({}) for user {}; falling back to catalog planner",
                            i + 1, tlNode.size(), userId);
                    return null;
                }
                List<Map<String, Object>> stops = new ArrayList<>();
                for (JsonNode sn : tlNode) {
                    String time = safeSlice(sn.path("time").asText("08:00"), 12);
                    String cat = sn.path("category").asText("SIGHT").trim().toUpperCase(Locale.ROOT);
                    if (!TIMELINE_CATEGORIES.contains(cat)) cat = "SIGHT";
                    String title = safeSlice(sn.path("title").asText("Hoạt động trải nghiệm"), 90);
                    String subtitle = safeSlice(sn.path("subtitle").asText(""), 160);
                    String placeLabel = safeSlice(sn.path("placeLabel").asText(province), 60);
                    stops.add(timelineStop(time, cat, title, subtitle, placeLabel));
                }
                LocalDate date = startDay.plusDays(i);
                Map<String, Object> dayMap = new LinkedHashMap<>();
                dayMap.put("day", i + 1);
                dayMap.put("dateLabel", date.getDayOfMonth() + " thg " + date.getMonthValue());
                dayMap.put("title", safeSlice(dn.path("title").asText("Ngày " + (i + 1) + " tại " + province + " · " + styleName), 120));
                dayMap.put("morning", stops.get(0).get("time") + " " + stops.get(0).get("title") + " · " + stops.get(Math.min(2, stops.size() - 1)).get("title"));
                dayMap.put("afternoon", stops.get(Math.min(3, stops.size() - 1)).get("time") + " " + stops.get(Math.min(3, stops.size() - 1)).get("title"));
                dayMap.put("evening", stops.get(stops.size() - 1).get("time") + " " + stops.get(stops.size() - 1).get("title"));
                dayMap.put("timeline", stops);
                parsed.add(dayMap);
            }
            return parsed;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Gemini VIP itinerary request interrupted for user {}; falling back to catalog planner", userId);
            return null;
        } catch (Exception e) {
            log.warn("Gemini VIP itinerary failed ({}) for user {}; falling back to catalog planner", e.getClass().getSimpleName(), userId);
            return null;
        }
    }

    private List<Map<String, Object>> buildDetailedCatalogDays(
            String province,
            String normProv,
            int daysCount,
            String style,
            String styleName,
            int discountPercent,
            LocalDate startDay,
            List<MonetizationService.PlaceSnap> matchedPlaces,
            List<MonetizationService.TourSnap> matchedTours,
            List<MonetizationService.HotelSnap> matchedHotels
    ) {
        String[][] meals = regionalMealsFor(normProv, province);
        String[] nightSpots = regionalNightActivitiesFor(normProv, province);
        MonetizationService.HotelSnap primaryHotel = matchedHotels.isEmpty() ? null : matchedHotels.get(0);
        String hotelName = primaryHotel != null ? primaryHotel.name() : ("Khách sạn trung tâm " + province);

        List<Map<String, Object>> dayPlans = new ArrayList<>();
        for (int d = 1; d <= daysCount; d++) {
            LocalDate currentDate = startDay.plusDays(d - 1);
            MonetizationService.PlaceSnap morningSpot = matchedPlaces.isEmpty()
                    ? null
                    : matchedPlaces.get(((d - 1) * 2) % matchedPlaces.size());
            MonetizationService.PlaceSnap afternoonSpot = matchedPlaces.size() > 1
                    ? matchedPlaces.get(((d - 1) * 2 + 1) % matchedPlaces.size())
                    : null;
            MonetizationService.TourSnap dayTour = (d <= matchedTours.size()) ? matchedTours.get(d - 1) : null;
            String[] dayMeal = meals[(d - 1) % meals.length];
            String nightAct = nightSpots[(d - 1) % nightSpots.length];

            List<Map<String, Object>> timeline = new ArrayList<>();
            if (d == 1) {
                timeline.add(timelineStop(
                        "07:00", "TRANSIT",
                        "Khởi hành & Đón bình minh",
                        "Thức dậy chuẩn bị hành lý và di chuyển đến trung tâm " + province,
                        province
                ));
                timeline.add(timelineStop(
                        "08:00", "FOOD",
                        "Điểm tâm sáng đặc sản",
                        "Thưởng thức " + dayMeal[0] + " nóng hổi khởi đầu chuyến đi",
                        "Ẩm thực " + province
                ));
                timeline.add(timelineStop(
                        "09:15", "SIGHT",
                        morningSpot != null ? ("Tham quan " + morningSpot.name()) : ("Khám phá biểu tượng " + province),
                        morningSpot != null
                                ? "Check-in khung giờ ánh sáng đẹp, ít đông khách buổi sáng"
                                : "Dạo bước quảng trường trung tâm và chụp ảnh lưu niệm",
                        morningSpot != null ? morningSpot.name() : ("Trung tâm " + province)
                ));
                timeline.add(timelineStop(
                        "12:00", "FOOD",
                        "Bữa trưa bản địa",
                        "Nghỉ chân thưởng thức " + dayMeal[1],
                        "Nhà hàng địa phương"
                ));
                timeline.add(timelineStop(
                        "14:00", "STAY",
                        "Nhận phòng nghỉ dưỡng",
                        "Check-in ưu tiên Hội viên (-" + discountPercent + "%) và nghỉ ngơi thư giãn",
                        hotelName
                ));
                if (dayTour != null) {
                    timeline.add(timelineStop(
                            "16:00", "TOUR",
                            dayTour.name(),
                            "Trải nghiệm hành trình điểm nhấn buổi chiều (giá ưu đãi -" + discountPercent + "%)",
                            Objects.toString(dayTour.location(), province)
                    ));
                } else {
                    timeline.add(timelineStop(
                            "16:30", "SIGHT",
                            afternoonSpot != null ? ("Dạo cảnh " + afternoonSpot.name()) : "Ngắm hoàng hôn chiều tà",
                            "Tận hưởng không khí mát mẻ cuối chiều và săn góc ảnh hoàng hôn",
                            afternoonSpot != null ? afternoonSpot.name() : province
                    ));
                }
                timeline.add(timelineStop(
                        "19:30", "FOOD",
                        "Bữa tối chào mừng",
                        "Thưởng thức " + dayMeal[2] + " trong không gian ấm cúng",
                        "Khu phố ẩm thực"
                ));
                timeline.add(timelineStop(
                        "21:00", "NIGHT",
                        "Khám phá nhịp sống đêm",
                        nightAct,
                        hotelName
                ));
            } else if (d == daysCount) {
                timeline.add(timelineStop(
                        "07:00", "WAKE",
                        "Thức dậy & Hít thở khí trời",
                        "Đón nắng sớm tại khu lưu trú và thu xếp hành lý gọn gàng",
                        hotelName
                ));
                timeline.add(timelineStop(
                        "08:00", "FOOD",
                        "Bữa sáng thảnh thơi & Cà phê",
                        "Nhâm nhi " + dayMeal[0] + " đúng điệu người bản xứ",
                        hotelName
                ));
                timeline.add(timelineStop(
                        "09:15", "SIGHT",
                        morningSpot != null ? ("Ghé thăm " + morningSpot.name()) : ("Dạo chợ truyền thống " + province),
                        "Tham quan điểm văn hóa kết thúc hành trình và chọn góc ảnh kỷ niệm",
                        morningSpot != null ? morningSpot.name() : ("Chợ trung tâm " + province)
                ));
                timeline.add(timelineStop(
                        "11:30", "STAY",
                        "Trả phòng & Mua quà đặc sản",
                        "Hoàn tất thủ tục check-out và chọn mua quà biếu đặc trưng " + province,
                        hotelName
                ));
                timeline.add(timelineStop(
                        "12:45", "FOOD",
                        "Bữa trưa tổng kết hành trình",
                        "Thưởng thức " + dayMeal[1] + " trước giờ khởi hành",
                        "Trung tâm " + province
                ));
                timeline.add(timelineStop(
                        "15:00", "SIGHT",
                        afternoonSpot != null ? ("Tản bộ " + afternoonSpot.name()) : "Thư giãn trà chiều",
                        "Dừng chân quán cà phê view đẹp, xem lại bộ ảnh chuyến đi",
                        afternoonSpot != null ? afternoonSpot.name() : province
                ));
                timeline.add(timelineStop(
                        "17:30", "TRANSIT",
                        "Khởi hành trở về",
                        "Kết thúc trọn vẹn chuyến đi đặc quyền tại " + province,
                        "Sân bay / Ga trung tâm"
                ));
            } else {
                timeline.add(timelineStop(
                        "07:00", "WAKE",
                        "Đón bình minh ngày " + d,
                        "Khởi động nhẹ nhàng và sẵn sàng cho ngày khám phá chuyên sâu",
                        hotelName
                ));
                timeline.add(timelineStop(
                        "08:00", "FOOD",
                        "Ăn sáng nạp năng lượng",
                        "Thưởng thức " + dayMeal[0] + " truyền thống",
                        "Phố điểm tâm " + province
                ));
                if (dayTour != null) {
                    timeline.add(timelineStop(
                            "09:00", "TOUR",
                            dayTour.name(),
                            "Khởi hành tour trải nghiệm trọn gói (ưu đãi Hội viên -" + discountPercent + "%)",
                            Objects.toString(dayTour.location(), province)
                    ));
                } else {
                    timeline.add(timelineStop(
                            "09:00", "SIGHT",
                            morningSpot != null ? ("Khám phá " + morningSpot.name()) : ("Hành trình di sản " + province),
                            "Tìm hiểu câu chuyện lịch sử, kiến trúc và văn hóa bản địa",
                            morningSpot != null ? morningSpot.name() : province
                    ));
                }
                timeline.add(timelineStop(
                        "12:15", "FOOD",
                        "Thưởng thức bữa trưa",
                        "Dùng bữa với thực đơn " + dayMeal[1] + " và nghỉ trưa ngắn",
                        "Nhà hàng bản địa"
                ));
                timeline.add(timelineStop(
                        "14:30", "SIGHT",
                        afternoonSpot != null ? ("Trải nghiệm " + afternoonSpot.name()) : ("Làng nghề & Không gian xanh " + province),
                        "Tham quan chiều sâu văn hóa và trải nghiệm hoạt động thủ công/sinh thái",
                        afternoonSpot != null ? afternoonSpot.name() : province
                ));
                timeline.add(timelineStop(
                        "17:00", "SIGHT",
                        "Ngắm hoàng hôn & Trà chiều",
                        "Thư giãn tại điểm hẹn tầm nhìn thoáng đãng theo phong cách " + ("CULTURE".equals(style) ? "di sản" : "nghỉ dưỡng"),
                        province
                ));
                timeline.add(timelineStop(
                        "19:30", "FOOD",
                        "Ẩm thực tối đặc sắc",
                        "Khám phá hương vị " + dayMeal[2],
                        "Trung tâm " + province
                ));
                timeline.add(timelineStop(
                        "21:00", "NIGHT",
                        "Trải nghiệm đêm bản địa",
                        nightAct,
                        hotelName
                ));
            }

            Map<String, Object> day = new LinkedHashMap<>();
            day.put("day", d);
            day.put("dateLabel", currentDate.getDayOfMonth() + " thg " + currentDate.getMonthValue());
            day.put("title", "Ngày " + d + " tại " + province + " · " + styleName);
            day.put("morning", timeline.get(1).get("time") + " " + timeline.get(1).get("title") + " · " + timeline.get(2).get("time") + " " + timeline.get(2).get("title"));
            day.put("afternoon", timeline.get(4).get("time") + " " + timeline.get(4).get("title") + " · " + timeline.get(5).get("time") + " " + timeline.get(5).get("title"));
            day.put("evening", timeline.get(timeline.size() - 1).get("time") + " " + timeline.get(timeline.size() - 1).get("title"));
            day.put("timeline", timeline);
            dayPlans.add(day);
        }
        return dayPlans;
    }

    private static long discountedMemberPrice(long basePrice, int discountPercent) {
        if (basePrice <= 0 || discountPercent <= 0) return Math.max(0L, basePrice);
        long discount = BigInteger.valueOf(basePrice)
                .multiply(BigInteger.valueOf(discountPercent))
                .add(BigInteger.valueOf(50))
                .divide(BigInteger.valueOf(100))
                .longValueExact();
        return Math.max(0L, Math.subtractExact(basePrice, discount));
    }

    private static Map<String, Object> timelineStop(
            String time,
            String category,
            String title,
            String subtitle,
            String placeLabel
    ) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("time", time);
        m.put("category", category);
        m.put("title", title);
        m.put("subtitle", subtitle);
        m.put("placeLabel", placeLabel);
        return m;
    }

    private static String safeSlice(String text, int maxLen) {
        if (text == null) return "";
        String trimmed = text.trim();
        return trimmed.length() <= maxLen ? trimmed : trimmed.substring(0, maxLen);
    }

    private static String normalizeText(String input) {
        if (input == null || input.isBlank()) return "";
        String decomposed = Normalizer.normalize(input.replace('đ', 'd').replace('Đ', 'D'), Normalizer.Form.NFD);
        return decomposed.replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT).trim();
    }

    private static String[][] regionalMealsFor(String normProv, String province) {
        if (normProv.contains("ha noi")) {
            return new String[][]{
                    {"phở bò gia truyền & cà phê trứng phố cổ", "bún chả Hà Thành kèm nem cua bể", "chả cá Lã Vọng & chè sen"},
                    {"bún thang phố cổ & trà nhài", "bánh cuốn Thanh Trì & chả quế", "lẩu riêu cua bắp bò sườn sụn"},
                    {"xôi xéo Hàng Bài & cà phê phin", "miến lươn Đông Thịnh & nộm bò khô", "ẩm thực phố đêm Tạ Hiện – Hàng Buồm"}
            };
        }
        if (normProv.contains("da nang") || normProv.contains("quang nam")) {
            return new String[][]{
                    {"mì Quảng tôm thịt & cà phê muối", "bánh tráng cuốn thịt heo hai đầu da", "hải sản tươi sống ven biển Mỹ Khê"},
                    {"bún chả cá Đà Nẵng nóng hổi", "cao lầu & cơm gà phố Hội", "bánh xèo tôm nhảy & nem lụi"},
                    {"bánh mì chảo & sinh tố nhiệt đới", "gỏi cá Nam Ô & cháo hải sản", "BBQ hải sản ngắm cầu sông Hàn"}
            };
        }
        if (normProv.contains("hue")) {
            return new String[][]{
                    {"bún bò Huế chuẩn vị cố đô", "cơm hến Cồn Hến & bánh bèo nậm lọc", "cơm Vua cung đình & chè Huế 36 món"},
                    {"bánh canh Nam Phổ & cà phê muối Huế", "bánh khoái Thượng Tứ & nem lụi", "ẩm thực ven sông Hương & trà cung đình"},
                    {"xôi thịt hon & chè hạt sen", "bún thịt nướng Kim Long", "đặc sản cố đô truyền thống"}
            };
        }
        if (normProv.contains("ho chi minh") || normProv.contains("sai gon")) {
            return new String[][]{
                    {"cơm tấm sườn bì chả & cà phê sữa đá", "hủ tiếu Nam Vang & gỏi cuốn tôm thịt", "ẩm thực ven sông Sài Gòn"},
                    {"bánh mì Sài Gòn giòn rụm & bạc xỉu", "lẩu mắm miền Tây & cá kho tộ", "hải sản & món nướng phố đi bộ"},
                    {"phở miền Nam & sữa đậu nành", "bánh khọt & bún thịt nướng", "nhà hàng fusion hiện đại trung tâm"}
            };
        }
        return new String[][]{
                {"điểm tâm đặc sản " + province + " & cà phê phin", "thực đơn truyền thống vùng " + province, "đặc sản địa phương theo mùa"},
                {"bún/phở nóng bản địa & trà sớm", "cơm nhà truyền thống & món đặc trưng " + province, "lẩu & món nướng đặc sản vùng miền"},
                {"bánh mì/xôi bản xứ & nước ép tươi", "hải sản / nông sản tươi sạch " + province, "ẩm thực chợ đêm địa phương"}
        };
    }

    private static String[] regionalNightActivitiesFor(String normProv, String province) {
        if (normProv.contains("ha noi")) {
            return new String[]{
                    "Tản bộ phố đi bộ Hồ Hoàn Kiếm và nghe nhạc acoustic ven hồ",
                    "Trải nghiệm tour đêm văn hóa hoặc thưởng trà phố cổ tĩnh lặng",
                    "Ngắm cầu Long Biên lên đèn và dạo chợ đêm Hàng Đào"
            };
        }
        if (normProv.contains("da nang")) {
            return new String[]{
                    "Dạo du thuyền sông Hàn và ngắm Cầu Rồng lên đèn rực rỡ",
                    "Tản bộ biển Mỹ Khê về đêm và ghé chợ đêm Sơn Trà",
                    "Nghe nhạc sống ven sông Hàn và thưởng thức cocktail tầng cao"
            };
        }
        return new String[]{
                "Dạo phố đi bộ trung tâm " + province + " và chụp ảnh không gian lên đèn",
                "Ghé chợ đêm địa phương khám phá quà lưu niệm & nghệ thuật đường phố",
                "Thư giãn tại lounge khách sạn và tận hưởng không khí đêm yên bình"
        };
    }
}

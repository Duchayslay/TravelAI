package com.travelai.backend.booking;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.travelai.backend.entity.Hotel;
import com.travelai.backend.entity.Notification;
import com.travelai.backend.entity.Place;
import com.travelai.backend.entity.Tour;
import com.travelai.backend.repository.HotelRepository;
import com.travelai.backend.repository.PlaceRepository;
import com.travelai.backend.repository.TourRepository;
import com.travelai.backend.repository.UserRepository;
import com.travelai.backend.security.SessionService;
import com.travelai.backend.service.NotificationPublisher;
import jakarta.persistence.EntityManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.text.Normalizer;
import java.time.*;
import java.util.*;

import static com.travelai.backend.booking.BookingContracts.*;
import static com.travelai.backend.booking.BookingPolicies.VIETNAM;

@Service
@Transactional
public class MonetizationService {

    public static final int FREE_DAILY_AI_LIMIT = 10;
    private static final Set<String> VALID_PLACEMENTS = Set.of("EXPLORE_DESTINATION", "HOME_FEED", "ALL");
    private static final Set<String> TIMELINE_CATEGORIES = Set.of(
            "WAKE", "TRANSIT", "FOOD", "STAY", "SIGHT", "TOUR", "NIGHT"
    );

    private final BookingService bookingService;
    private final EntityManager em;
    private final TourRepository tours;
    private final HotelRepository hotels;
    private final PlaceRepository places;
    private final UserRepository users;
    private final NotificationPublisher notifications;
    private final Clock clock;
    private final ObjectMapper json;
    private final String geminiKey;
    private final String geminiModel;
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();

    public MonetizationService(
            BookingService bookingService,
            EntityManager em,
            TourRepository tours,
            HotelRepository hotels,
            UserRepository users,
            NotificationPublisher notifications,
            Clock clock
    ) {
        this(bookingService, em, tours, hotels, null, users, notifications, clock, new ObjectMapper(), "", "gemini-3.5-flash-lite");
    }

    public MonetizationService(
            BookingService bookingService,
            EntityManager em,
            TourRepository tours,
            HotelRepository hotels,
            PlaceRepository places,
            UserRepository users,
            NotificationPublisher notifications,
            Clock clock
    ) {
        this(bookingService, em, tours, hotels, places, users, notifications, clock, new ObjectMapper(), "", "gemini-3.5-flash-lite");
    }

    @Autowired
    public MonetizationService(
            BookingService bookingService,
            EntityManager em,
            TourRepository tours,
            HotelRepository hotels,
            @Autowired(required = false) PlaceRepository places,
            UserRepository users,
            NotificationPublisher notifications,
            Clock clock,
            ObjectMapper json,
            @Value("${GEMINI_API_KEY:}") String geminiKey,
            @Value("${GEMINI_MODEL:gemini-3.5-flash-lite}") String geminiModel
    ) {
        this.bookingService = bookingService;
        this.em = em;
        this.tours = tours;
        this.hotels = hotels;
        this.places = places;
        this.users = users;
        this.notifications = notifications;
        this.clock = clock;
        this.json = json == null ? new ObjectMapper() : json;
        this.geminiKey = geminiKey == null ? "" : geminiKey.trim();
        this.geminiModel = (geminiModel == null || geminiModel.isBlank()) ? "gemini-3.5-flash-lite" : geminiModel.trim();
    }

    public void ensureSeedCatalog() {
        if (em.find(SubscriptionPlan.class, "PLUS") == null) {
            SubscriptionPlan plus = new SubscriptionPlan();
            plus.code = "PLUS";
            plus.name = "TravelAI Plus";
            plus.priceVnd = 99_000L;
            plus.durationDays = 30;
            plus.discountPercent = 5;
            plus.waiveFirstRescheduleFee = false;
            plus.unlimitedAi = true;
            plus.badge = "⭐ Plus";
            plus.description = "Không giới hạn lượt quét AI & giọng nói, tự động giảm 5% khi đặt Tour/Khách sạn và gắn huy hiệu ⭐ Plus trên Bảng tin.";
            plus.active = true;
            em.persist(plus);
        }
        if (em.find(SubscriptionPlan.class, "VIP") == null) {
            SubscriptionPlan vip = new SubscriptionPlan();
            vip.code = "VIP";
            vip.name = "TravelAI Explorer VIP";
            vip.priceVnd = 249_000L;
            vip.durationDays = 90;
            vip.discountPercent = 10;
            vip.waiveFirstRescheduleFee = true;
            vip.unlimitedAi = true;
            vip.badge = "💎 Explorer";
            vip.description = "Không giới hạn AI, tự động giảm 10% mọi đơn đặt Tour/Khách sạn, miễn phí đổi lịch lần đầu và gắn huy hiệu 💎 Explorer.";
            vip.active = true;
            em.persist(vip);
        }
        if (em.find(AdPackage.class, "SPOTLIGHT") == null) {
            AdPackage spotlight = new AdPackage();
            spotlight.code = "SPOTLIGHT";
            spotlight.name = "Gói Spotlight Khám Phá";
            spotlight.priceVnd = 199_000L;
            spotlight.durationDays = 7;
            spotlight.placement = "EXPLORE_DESTINATION";
            spotlight.description = "Ghim nổi bật đầu danh mục Khám phá và ưu tiên gợi ý cùng tỉnh sau khi nhận diện địa danh bằng AI.";
            spotlight.active = true;
            em.persist(spotlight);
        }
        if (em.find(AdPackage.class, "HERO_FEED") == null) {
            AdPackage hero = new AdPackage();
            hero.code = "HERO_FEED";
            hero.name = "Gói Trang Chủ & Bảng Tin";
            hero.priceVnd = 499_000L;
            hero.durationDays = 14;
            hero.placement = "HOME_FEED";
            hero.description = "Hiển thị tại khu vực Đề xuất được tài trợ trên Trang chủ và chèn thẻ nổi bật trên Bảng tin cộng đồng.";
            hero.active = true;
            em.persist(hero);
        }
        if (em.find(AdPackage.class, "MEGA") == null) {
            AdPackage mega = new AdPackage();
            mega.code = "MEGA";
            mega.name = "Gói Mega Campaign Toàn Diện";
            mega.priceVnd = 999_000L;
            mega.durationDays = 30;
            mega.placement = "ALL";
            mega.description = "Phủ toàn bộ vị trí Trang chủ, Khám phá, Bảng tin và Gợi ý điểm đến sau quét AI kèm thống kê lượt xem & click.";
            mega.active = true;
            em.persist(mega);
        }
    }

    private void expireStaleRecords(Instant now) {
        em.createQuery("update UserSubscription s set s.status='EXPIRED' where s.status='ACTIVE' and s.expiresAt<=:now")
                .setParameter("now", now).executeUpdate();
        em.createQuery("update AdCampaign c set c.status='EXPIRED' where c.status='ACTIVE' and c.endsAt is not null and c.endsAt<=:now")
                .setParameter("now", now).executeUpdate();
    }

    public Map<String, Object> publicCatalog() {
        ensureSeedCatalog();
        List<Map<String, Object>> plans = em.createQuery(
                        "select p from SubscriptionPlan p where p.active=true order by p.priceVnd asc",
                        SubscriptionPlan.class)
                .getResultList().stream().map(this::planView).toList();
        List<Map<String, Object>> packages = em.createQuery(
                        "select a from AdPackage a where a.active=true order by a.priceVnd asc",
                        AdPackage.class)
                .getResultList().stream().map(this::packageView).toList();
        return Map.of(
                "simulation", "SIMULATED",
                "currency", "VND",
                "freeDailyAiLimit", FREE_DAILY_AI_LIMIT,
                "subscriptionPlans", plans,
                "adPackages", packages
        );
    }

    public Map<String, String> badgeForUser(Long userId) {
        if (userId == null) return Map.of("tier", "FREE", "badge", "");
        Instant now = clock.instant();
        UserSubscription active = findActiveSubscription(userId, now);
        if (active == null) return Map.of("tier", "FREE", "badge", "");
        return Map.of("tier", active.planCode, "badge", Objects.toString(active.badge, ""));
    }

    private UserSubscription findActiveSubscription(long userId, Instant now) {
        return em.createQuery(
                        "select s from UserSubscription s where s.userId=:uid and s.status='ACTIVE' and s.expiresAt>:now order by s.discountPercent desc, s.expiresAt desc",
                        UserSubscription.class)
                .setParameter("uid", userId)
                .setParameter("now", now)
                .setMaxResults(1)
                .getResultStream()
                .findFirst()
                .orElse(null);
    }

    public Map<String, Object> mySummary(SessionService.Actor actor) {
        ensureSeedCatalog();
        Instant now = clock.instant();
        expireStaleRecords(now);
        UserSubscription current = findActiveSubscription(actor.id(), now);
        List<Map<String, Object>> history = em.createQuery(
                        "select s from UserSubscription s where s.userId=:uid order by s.createdAt desc",
                        UserSubscription.class)
                .setParameter("uid", actor.id())
                .setMaxResults(25)
                .getResultList().stream().map(this::subscriptionView).toList();
        List<Map<String, Object>> campaigns = em.createQuery(
                        "select c from AdCampaign c where c.ownerId=:uid order by c.createdAt desc",
                        AdCampaign.class)
                .setParameter("uid", actor.id())
                .setMaxResults(50)
                .getResultList().stream().map(this::campaignView).toList();
        long totalSaved = em.createQuery(
                        "select r from Reservation r where r.ownerId=:uid and r.status in ('CONFIRMED','RESCHEDULED')",
                        Reservation.class)
                .setParameter("uid", actor.id())
                .getResultList().stream()
                .mapToLong(r -> Math.max(0L, r.originalAmount - r.totalPaid))
                .sum();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("simulation", "SIMULATED");
        result.put("currency", "VND");
        result.put("currentSubscription", current == null ? freeSubscriptionView() : subscriptionView(current));
        result.put("totalBookingSavedVnd", totalSaved);
        result.put("aiQuota", quotaSnapshot(actor, current, now));
        result.put("subscriptionHistory", history);
        result.put("campaigns", campaigns);
        return result;
    }

    private UserSubscription requirePremiumMember(SessionService.Actor actor, Instant now) {
        UserSubscription current = findActiveSubscription(actor.id(), now);
        if (current == null) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Tính năng đặc quyền chỉ mở cho Hội viên TravelAI Plus hoặc Explorer VIP. Hãy nâng cấp gói để sử dụng."
            );
        }
        return current;
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

    public Map<String, Object> vipDeals(SessionService.Actor actor) {
        ensureSeedCatalog();
        Instant now = clock.instant();
        expireStaleRecords(now);
        UserSubscription sub = requirePremiumMember(actor, now);

        List<Map<String, Object>> deals = new ArrayList<>();
        for (Tour t : tours.findAllByOrderByIdDesc()) {
            if (!"active".equalsIgnoreCase(t.getStatus()) || t.getPrice() == null) continue;
            long orig = Math.round(t.getPrice());
            long member = discountedMemberPrice(orig, sub.discountPercent);
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("kind", "TOUR");
            d.put("id", t.getId());
            d.put("name", t.getName());
            d.put("location", t.getLocation());
            d.put("province", t.getProvince());
            d.put("rating", t.getRating());
            d.put("image", t.getImage());
            d.put("originalPrice", orig);
            d.put("memberPrice", member);
            d.put("savedAmount", Math.subtractExact(orig, member));
            deals.add(d);
        }
        for (Hotel h : hotels.findAllByOrderByIdDesc()) {
            if (!"active".equalsIgnoreCase(h.getStatus()) || h.getPrice() == null) continue;
            long orig = Math.round(h.getPrice());
            long member = discountedMemberPrice(orig, sub.discountPercent);
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("kind", "HOTEL");
            d.put("id", h.getId());
            d.put("name", h.getName());
            d.put("location", h.getLocation());
            d.put("province", h.getProvince());
            d.put("rating", h.getRating());
            d.put("image", h.getImage());
            d.put("originalPrice", orig);
            d.put("memberPrice", member);
            d.put("savedAmount", Math.subtractExact(orig, member));
            deals.add(d);
        }
        deals.sort(Comparator.comparingLong((Map<String, Object> m) -> ((Number) m.get("savedAmount")).longValue()).reversed());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("simulation", "SIMULATED");
        out.put("currency", "VND");
        out.put("tier", sub.planCode);
        out.put("planName", sub.planName);
        out.put("badge", sub.badge);
        out.put("discountPercent", sub.discountPercent);
        out.put("waiveFirstRescheduleFee", sub.waiveFirstRescheduleFee);
        out.put("deals", deals.stream().limit(12).toList());
        return out;
    }

    public Map<String, Object> generateVipItinerary(SessionService.Actor actor, VipItineraryInput input) {
        ensureSeedCatalog();
        Instant now = clock.instant();
        expireStaleRecords(now);
        UserSubscription sub = requirePremiumMember(actor, now);

        String province = input == null || input.province() == null ? "" : input.province().trim();
        if (province.isEmpty() || province.length() > 80) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Vui lòng chọn tỉnh/thành phố hợp lệ.");
        }
        int daysCount = input.days() == null ? 3 : input.days();
        if (daysCount < 1 || daysCount > 7) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Số ngày lịch trình VIP từ 1 đến 7 ngày.");
        }
        String style = input.style() == null ? "LUXURY" : input.style().trim().toUpperCase(Locale.ROOT);
        String normProv = normalizeText(province);

        List<Place> matchedPlaces = places == null ? List.of() : places.findAll().stream()
                .filter(p -> normalizeText(Objects.toString(p.getProvince(), "") + " " + Objects.toString(p.getName(), "")).contains(normProv))
                .toList();
        List<Tour> matchedTours = tours.findAllByOrderByIdDesc().stream()
                .filter(t -> "active".equalsIgnoreCase(t.getStatus())
                        && normalizeText(Objects.toString(t.getProvince(), "") + " " + Objects.toString(t.getLocation(), "")).contains(normProv))
                .toList();
        List<Hotel> matchedHotels = hotels.findAllByOrderByIdDesc().stream()
                .filter(h -> "active".equalsIgnoreCase(h.getStatus())
                        && normalizeText(Objects.toString(h.getProvince(), "") + " " + Objects.toString(h.getLocation(), "")).contains(normProv))
                .toList();

        String styleName = switch (style) {
            case "CULTURE" -> "Khám phá văn hóa & di sản chuyên sâu";
            case "OPTIMAL" -> "Tối ưu thời gian & ngân sách thông minh";
            default -> "Nghỉ dưỡng đặc quyền hạng sang (VIP Concierge)";
        };

        LocalDate startDay = LocalDate.ofInstant(now, VIETNAM).plusDays(1);
        List<Map<String, Object>> geminiDays = tryGenerateTimelineWithGemini(
                province, daysCount, styleName, sub.discountPercent, startDay, matchedPlaces, matchedTours, matchedHotels
        );
        String aiSource = geminiDays != null ? "GEMINI_AI" : "SMART_PLANNER";
        List<Map<String, Object>> dayPlans = geminiDays != null
                ? geminiDays
                : buildDetailedCatalogDays(
                        province, normProv, daysCount, style, styleName, sub.discountPercent, startDay,
                        matchedPlaces, matchedTours, matchedHotels
                );

        long origEstimate = 0L;
        if (!matchedTours.isEmpty() && matchedTours.get(0).getPrice() != null) {
            origEstimate = Math.addExact(origEstimate, Math.round(matchedTours.get(0).getPrice()));
        }
        if (!matchedHotels.isEmpty() && matchedHotels.get(0).getPrice() != null) {
            int nights = Math.max(1, daysCount - 1);
            origEstimate = Math.addExact(origEstimate, Math.multiplyExact(Math.round(matchedHotels.get(0).getPrice()), nights));
        }
        long memberEstimate = discountedMemberPrice(origEstimate, sub.discountPercent);
        long savedEstimate = Math.subtractExact(origEstimate, memberEstimate);

        String spokenSummary = "Lịch trình đặc quyền " + sub.badge + " " + daysCount + " ngày tại " + province
                + " theo phong cách " + styleName + ". "
                + (savedEstimate > 0
                    ? "Dự kiến với thẻ " + sub.planName + ", bạn được giảm trực tiếp " + sub.discountPercent + " phần trăm, tiết kiệm " + savedEstimate + " đồng."
                    : "Bạn được hưởng ưu đãi giảm " + sub.discountPercent + " phần trăm cho mọi dịch vụ đặt kèm.");

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("simulation", "SIMULATED");
        response.put("currency", "VND");
        response.put("tier", sub.planCode);
        response.put("badge", sub.badge);
        response.put("discountPercent", sub.discountPercent);
        response.put("province", province);
        response.put("style", style);
        response.put("styleLabel", styleName);
        response.put("aiSource", aiSource);
        response.put("days", dayPlans);
        response.put("estimatedOriginalVnd", origEstimate);
        response.put("estimatedMemberVnd", memberEstimate);
        response.put("estimatedSavedVnd", savedEstimate);
        response.put("conciergeSummary", spokenSummary);
        response.put("recommendedTours", matchedTours.stream().limit(3).map(t -> {
            long o = t.getPrice() == null ? 0L : Math.round(t.getPrice());
            long m = discountedMemberPrice(o, sub.discountPercent);
            return Map.<String, Object>of("id", t.getId(), "name", t.getName(), "originalPrice", o, "memberPrice", m, "savedAmount", o - m);
        }).toList());
        response.put("recommendedHotels", matchedHotels.stream().limit(3).map(h -> {
            long o = h.getPrice() == null ? 0L : Math.round(h.getPrice());
            long m = discountedMemberPrice(o, sub.discountPercent);
            return Map.<String, Object>of("id", h.getId(), "name", h.getName(), "originalPrice", o, "memberPrice", m, "savedAmount", o - m);
        }).toList());
        return response;
    }

    private List<Map<String, Object>> tryGenerateTimelineWithGemini(
            String province,
            int daysCount,
            String styleName,
            int discountPercent,
            LocalDate startDay,
            List<Place> matchedPlaces,
            List<Tour> matchedTours,
            List<Hotel> matchedHotels
    ) {
        if (geminiKey.isEmpty() || !geminiModel.matches("[a-zA-Z0-9._-]{1,80}")) {
            return null;
        }
        try {
            String placeList = matchedPlaces.isEmpty()
                    ? "Các danh thắng nổi tiếng tại " + province
                    : String.join(", ", matchedPlaces.stream().limit(8).map(Place::getName).toList());
            String tourList = matchedTours.isEmpty()
                    ? "Chưa có tour cố định"
                    : String.join(", ", matchedTours.stream().limit(4).map(Tour::getName).toList());
            String hotelList = matchedHotels.isEmpty()
                    ? "Khách sạn trung tâm " + province
                    : String.join(", ", matchedHotels.stream().limit(4).map(Hotel::getName).toList());

            String prompt = "Thiết kế lịch trình du lịch chi tiết theo từng mốc giờ cho " + daysCount + " ngày tại " + province
                    + " theo phong cách '" + styleName + "' (hội viên được giảm " + discountPercent + "%). "
                    + "Ưu tiên lồng ghép tự nhiên các địa danh thật trong hệ thống: [" + placeList + "], "
                    + "tour: [" + tourList + "], lưu trú: [" + hotelList + "]. "
                    + "Yêu cầu bắt buộc: Mỗi ngày có đúng 7 mốc giờ cụ thể từ sáng tới tối (ví dụ 07:00, 08:00, 09:30, 12:00, 14:00, 16:30, 19:30 hoặc 21:00), "
                    + "nội dung giữa các ngày KHÔNG ĐƯỢC lặp lại câu chữ của nhau. "
                    + "Chỉ trả về JSON thuần theo cấu trúc: "
                    + "{\"days\":[{\"day\":1,\"title\":\"...\",\"timeline\":[{\"time\":\"07:00\",\"category\":\"WAKE|TRANSIT|FOOD|STAY|SIGHT|TOUR|NIGHT\",\"title\":\"...\",\"subtitle\":\"...\",\"placeLabel\":\"...\"}]}]}";

            Map<String, Object> payload = Map.of(
                    "contents", List.of(Map.of("role", "user", "parts", List.of(Map.of("text", prompt)))),
                    "generationConfig", Map.of(
                            "responseMimeType", "application/json",
                            "maxOutputTokens", 2048
                    )
            );
            HttpRequest req = HttpRequest.newBuilder(URI.create("https://generativelanguage.googleapis.com/v1beta/models/" + geminiModel + ":generateContent"))
                    .timeout(Duration.ofSeconds(15))
                    .header("x-goog-api-key", geminiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(payload)))
                    .build();
            HttpResponse<String> res = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() < 200 || res.statusCode() >= 300) return null;

            JsonNode parts = json.readTree(res.body()).path("candidates").path(0).path("content").path("parts");
            StringBuilder raw = new StringBuilder();
            for (JsonNode part : parts) {
                if (part.path("text").isTextual()) raw.append(part.path("text").asText());
            }
            if (raw.isEmpty()) return null;
            String cleanJson = raw.toString().trim().replaceAll("^```(?:json)?\\s*", "").replaceAll("\\s*```$", "");
            JsonNode root = json.readTree(cleanJson);
            JsonNode daysNode = root.path("days");
            if (!daysNode.isArray() || daysNode.size() != daysCount) return null;

            List<Map<String, Object>> parsed = new ArrayList<>();
            for (int i = 0; i < daysCount; i++) {
                JsonNode dn = daysNode.get(i);
                JsonNode tlNode = dn.path("timeline");
                if (!tlNode.isArray() || tlNode.size() < 4) return null;
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
                dayMap.put("afternoon", stops.get(Math.min(4, stops.size() - 1)).get("time") + " " + stops.get(Math.min(4, stops.size() - 1)).get("title"));
                dayMap.put("evening", stops.get(stops.size() - 1).get("time") + " " + stops.get(stops.size() - 1).get("title"));
                dayMap.put("timeline", stops);
                parsed.add(dayMap);
            }
            return parsed;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception ignored) {
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
            List<Place> matchedPlaces,
            List<Tour> matchedTours,
            List<Hotel> matchedHotels
    ) {
        String[][] meals = regionalMealsFor(normProv, province);
        String[] nightSpots = regionalNightActivitiesFor(normProv, province);
        Hotel primaryHotel = matchedHotels.isEmpty() ? null : matchedHotels.get(0);
        String hotelName = primaryHotel != null ? primaryHotel.getName() : ("Khách sạn trung tâm " + province);

        List<Map<String, Object>> dayPlans = new ArrayList<>();
        for (int d = 1; d <= daysCount; d++) {
            LocalDate currentDate = startDay.plusDays(d - 1);
            Place morningSpot = matchedPlaces.isEmpty() ? null : matchedPlaces.get(((d - 1) * 2) % matchedPlaces.size());
            Place afternoonSpot = matchedPlaces.size() > 1
                    ? matchedPlaces.get(((d - 1) * 2 + 1) % matchedPlaces.size())
                    : null;
            Tour dayTour = (d <= matchedTours.size()) ? matchedTours.get(d - 1) : null;
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
                        morningSpot != null ? ("Tham quan " + morningSpot.getName()) : ("Khám phá biểu tượng " + province),
                        morningSpot != null
                                ? "Check-in khung giờ ánh sáng đẹp, ít đông khách buổi sáng"
                                : "Dạo bước quảng trường trung tâm và chụp ảnh lưu niệm",
                        morningSpot != null ? morningSpot.getName() : ("Trung tâm " + province)
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
                            dayTour.getName(),
                            "Trải nghiệm hành trình điểm nhấn buổi chiều (giá ưu đãi -" + discountPercent + "%)",
                            Objects.toString(dayTour.getLocation(), province)
                    ));
                } else {
                    timeline.add(timelineStop(
                            "16:30", "SIGHT",
                            afternoonSpot != null ? ("Dạo cảnh " + afternoonSpot.getName()) : "Ngắm hoàng hôn chiều tà",
                            "Tận hưởng không khí mát mẻ cuối chiều và săn góc ảnh hoàng hôn",
                            afternoonSpot != null ? afternoonSpot.getName() : province
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
                        morningSpot != null ? ("Ghé thăm " + morningSpot.getName()) : ("Dạo chợ truyền thống " + province),
                        "Tham quan điểm văn hóa kết thúc hành trình và chọn góc ảnh kỷ niệm",
                        morningSpot != null ? morningSpot.getName() : ("Chợ trung tâm " + province)
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
                        afternoonSpot != null ? ("Tản bộ " + afternoonSpot.getName()) : "Thư giãn trà chiều",
                        "Dừng chân quán cà phê view đẹp, xem lại bộ ảnh chuyến đi",
                        afternoonSpot != null ? afternoonSpot.getName() : province
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
                            dayTour.getName(),
                            "Khởi hành tour trải nghiệm trọn gói (ưu đãi Hội viên -" + discountPercent + "%)",
                            Objects.toString(dayTour.getLocation(), province)
                    ));
                } else {
                    timeline.add(timelineStop(
                            "09:00", "SIGHT",
                            morningSpot != null ? ("Khám phá " + morningSpot.getName()) : ("Hành trình di sản " + province),
                            "Tìm hiểu câu chuyện lịch sử, kiến trúc và văn hóa bản địa",
                            morningSpot != null ? morningSpot.getName() : province
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
                        afternoonSpot != null ? ("Trải nghiệm " + afternoonSpot.getName()) : ("Làng nghề & Không gian xanh " + province),
                        "Tham quan chiều sâu văn hóa và trải nghiệm hoạt động thủ công/sinh thái",
                        afternoonSpot != null ? afternoonSpot.getName() : province
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

    public Map<String, Object> getAiQuota(SessionService.Actor actor) {
        Instant now = clock.instant();
        UserSubscription current = findActiveSubscription(actor.id(), now);
        return quotaSnapshot(actor, current, now);
    }

    public Map<String, Object> consumeAiQuota(SessionService.Actor actor) {
        bookingService.lockForMonetization();
        Instant now = clock.instant();
        expireStaleRecords(now);
        UserSubscription current = findActiveSubscription(actor.id(), now);
        LocalDate today = LocalDate.ofInstant(now, VIETNAM);
        String rowId = actor.id() + ":" + today;
        AiDailyUsage usage = em.find(AiDailyUsage.class, rowId);
        boolean unlimited = actor.admin() || (current != null && current.unlimitedAi);
        if (!unlimited && usage != null && usage.usedCount >= FREE_DAILY_AI_LIMIT) {
            throw new ResponseStatusException(
                    HttpStatus.TOO_MANY_REQUESTS,
                    "Bạn đã dùng hết " + FREE_DAILY_AI_LIMIT + " lượt AI miễn phí hôm nay. Nâng cấp gói TravelAI Plus hoặc Explorer VIP để mở không giới hạn."
            );
        }
        if (usage == null) {
            usage = new AiDailyUsage();
            usage.id = rowId;
            usage.userId = actor.id();
            usage.usageDate = today;
            usage.usedCount = 1;
            em.persist(usage);
        } else {
            usage.usedCount = Math.addExact(usage.usedCount, 1);
        }
        return quotaSnapshot(actor, current, now);
    }

    private Map<String, Object> quotaSnapshot(SessionService.Actor actor, UserSubscription current, Instant now) {
        LocalDate today = LocalDate.ofInstant(now, VIETNAM);
        AiDailyUsage usage = em.find(AiDailyUsage.class, actor.id() + ":" + today);
        int used = usage == null ? 0 : usage.usedCount;
        boolean unlimited = actor.admin() || (current != null && current.unlimitedAi);
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("tier", current == null ? "FREE" : current.planCode);
        map.put("badge", current == null ? null : current.badge);
        map.put("unlimited", unlimited);
        map.put("usedToday", used);
        map.put("dailyLimit", unlimited ? null : FREE_DAILY_AI_LIMIT);
        map.put("remaining", unlimited ? null : Math.max(0, FREE_DAILY_AI_LIMIT - used));
        return map;
    }

    public Map<String, Object> purchaseSubscription(SessionService.Actor actor, SubscriptionPurchaseInput input, String key) {
        ensureSeedCatalog();
        bookingService.lockForMonetization();
        Instant now = clock.instant();
        expireStaleRecords(now);
        if (input == null || !input.confirmed() || input.planCode() == null || input.planCode().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Vui lòng chọn gói thành viên và xác nhận thanh toán SIMULATED.");
        }
        String code = input.planCode().trim().toUpperCase(Locale.ROOT);
        Map<String, Object> safePayload = Map.of(
                "planCode", code,
                "confirmed", input.confirmed(),
                "card", Objects.toString(input.cardId(), "")
        );
        return bookingService.executeIdempotent(actor, "SUB_PURCHASE", key, safePayload, () -> {
            SubscriptionPlan plan = em.find(SubscriptionPlan.class, code);
            if (plan == null || !plan.active) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Gói thành viên không tồn tại hoặc đang tạm ngưng.");
            }
            String subId = UUID.randomUUID().toString();
            String accountId = bookingService.chargeLinkedCard(
                    actor, input.cardId(), input.testCvv(), plan.priceVnd,
                    "SUBSCRIPTION_CHARGE", subId, "Đăng ký " + plan.name, "SUB_PURCHASE", key, now
            );

            UserSubscription existing = findActiveSubscription(actor.id(), now);
            Instant baseStart = now;
            Instant expiresAt = now.plus(Duration.ofDays(plan.durationDays));
            if (existing != null && existing.planCode.equals(plan.code) && existing.expiresAt.isAfter(now)) {
                expiresAt = existing.expiresAt.plus(Duration.ofDays(plan.durationDays));
                existing.status = "RENEWED";
            } else if (existing != null && existing.expiresAt.isAfter(now)) {
                existing.status = "REPLACED";
            }

            UserSubscription sub = new UserSubscription();
            sub.id = subId;
            sub.userId = actor.id();
            sub.planCode = plan.code;
            sub.planName = plan.name;
            sub.discountPercent = plan.discountPercent;
            sub.waiveFirstRescheduleFee = plan.waiveFirstRescheduleFee;
            sub.unlimitedAi = plan.unlimitedAi;
            sub.badge = plan.badge;
            sub.status = "ACTIVE";
            sub.paidAmount = plan.priceVnd;
            sub.sourceAccountId = accountId;
            sub.startsAt = baseStart;
            sub.expiresAt = expiresAt;
            sub.createdAt = now;
            em.persist(sub);

            notifications.save(Notification.builder()
                    .userId(actor.id())
                    .actorName("TravelAI")
                    .type("SUBSCRIPTION_ACTIVE")
                    .title("Đã kích hoạt " + plan.name)
                    .message("Quyền lợi giảm " + plan.discountPercent + "% đặt chỗ và mở không giới hạn AI đã có hiệu lực.")
                    .targetReference(sub.id)
                    .isRead(false)
                    .createdAt(LocalDateTime.ofInstant(now, VIETNAM))
                    .build());

            return subscriptionView(sub);
        });
    }

    public Map<String, Object> cancelSubscription(SessionService.Actor actor, String subscriptionId) {
        bookingService.lockForMonetization();
        UserSubscription sub = em.find(UserSubscription.class, subscriptionId);
        if (sub == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy gói đăng ký.");
        actor.requireOwner(sub.userId);
        if (!"ACTIVE".equals(sub.status)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Gói đăng ký không còn ở trạng thái hoạt động.");
        }
        sub.status = "CANCELLED";
        return subscriptionView(sub);
    }

    public Map<String, Object> purchaseAdCampaign(SessionService.Actor actor, AdPurchaseInput input, String key) {
        ensureSeedCatalog();
        bookingService.lockForMonetization();
        Instant now = clock.instant();
        expireStaleRecords(now);
        if (input == null || !input.confirmed() || input.packageCode() == null || input.targetKind() == null || input.targetId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Vui lòng chọn gói quảng cáo, dịch vụ và xác nhận thanh toán SIMULATED.");
        }
        String pkgCode = input.packageCode().trim().toUpperCase(Locale.ROOT);
        String targetKind = input.targetKind().trim().toUpperCase(Locale.ROOT);
        if (!Set.of("TOUR", "HOTEL").contains(targetKind)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Chỉ hỗ trợ quảng bá TOUR hoặc HOTEL.");
        }
        String headline = input.headline() == null ? "" : input.headline().trim().replaceAll("\\s+", " ");
        if (headline.length() > 140) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Tiêu đề quảng bá tối đa 140 ký tự.");
        }
        TargetSummary target = resolveTarget(targetKind, input.targetId());
        if (target == null || !target.active()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Dịch vụ được chọn không tồn tại hoặc đang tạm ẩn.");
        }
        if (headline.isEmpty()) {
            headline = "Đề xuất nổi bật: " + target.name();
        }
        final String resolvedHeadline = headline;
        Map<String, Object> safePayload = Map.of(
                "packageCode", pkgCode,
                "targetKind", targetKind,
                "targetId", input.targetId(),
                "headline", resolvedHeadline,
                "confirmed", input.confirmed(),
                "card", Objects.toString(input.cardId(), "")
        );
        return bookingService.executeIdempotent(actor, "AD_PURCHASE", key, safePayload, () -> {
            AdPackage pkg = em.find(AdPackage.class, pkgCode);
            if (pkg == null || !pkg.active) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Gói quảng cáo không tồn tại hoặc đang tạm ngưng.");
            }
            String campaignId = UUID.randomUUID().toString();
            String accountId = bookingService.chargeLinkedCard(
                    actor, input.cardId(), input.testCvv(), pkg.priceVnd,
                    "AD_CAMPAIGN_CHARGE", campaignId, "Quảng cáo " + pkg.name, "AD_PURCHASE", key, now
            );

            AdCampaign c = new AdCampaign();
            c.id = campaignId;
            c.ownerId = actor.id();
            c.packageCode = pkg.code;
            c.packageName = pkg.name;
            c.placement = pkg.placement;
            c.targetKind = targetKind;
            c.targetId = input.targetId();
            c.headline = resolvedHeadline;
            c.durationDays = pkg.durationDays;
            c.status = "PENDING";
            c.paidAmount = pkg.priceVnd;
            c.sourceAccountId = accountId;
            c.impressions = 0L;
            c.clicks = 0L;
            c.createdAt = now;
            em.persist(c);

            notifications.save(Notification.builder()
                    .userId(actor.id())
                    .actorName("TravelAI")
                    .type("AD_CAMPAIGN_SUBMITTED")
                    .title("Chiến dịch quảng cáo đang chờ duyệt")
                    .message("Chiến dịch \"" + target.name() + "\" (" + pkg.name + ") đã ghi nhận thanh toán SIMULATED và đang chờ Admin duyệt hiển thị.")
                    .targetReference(c.id)
                    .isRead(false)
                    .createdAt(LocalDateTime.ofInstant(now, VIETNAM))
                    .build());

            return campaignView(c);
        });
    }

    public List<Map<String, Object>> activeSponsored(String placementFilter, String provinceFilter) {
        Instant now = clock.instant();
        expireStaleRecords(now);
        String requested = placementFilter == null ? "" : placementFilter.trim().toUpperCase(Locale.ROOT);
        String normalizedProvince = normalizeText(provinceFilter);

        List<AdCampaign> activeList = em.createQuery(
                        "select c from AdCampaign c where c.status='ACTIVE' and c.endsAt>:now order by c.paidAmount desc, c.createdAt desc",
                        AdCampaign.class)
                .setParameter("now", now)
                .setMaxResults(30)
                .getResultList();

        List<Map<String, Object>> results = new ArrayList<>();
        for (AdCampaign c : activeList) {
            if (!matchesPlacement(c.placement, requested)) continue;
            TargetSummary target = resolveTarget(c.targetKind, c.targetId);
            if (target == null || !target.active()) continue;
            if (!normalizedProvince.isEmpty()) {
                String hay = normalizeText(Objects.toString(target.province(), "") + " " + Objects.toString(target.location(), ""));
                if (!hay.contains(normalizedProvince)) continue;
            }
            results.add(campaignViewWithTarget(c, target));
        }
        return results;
    }

    private boolean matchesPlacement(String campaignPlacement, String requested) {
        if (requested.isEmpty() || "ALL".equals(campaignPlacement)) return true;
        return switch (requested) {
            case "HOME", "FEED", "HOME_FEED" -> "HOME_FEED".equals(campaignPlacement);
            case "EXPLORE", "DESTINATION", "EXPLORE_DESTINATION" -> "EXPLORE_DESTINATION".equals(campaignPlacement);
            default -> true;
        };
    }

    public Map<String, Object> recordImpression(String campaignId) {
        if (campaignId == null || campaignId.length() > 64) return Map.of("recorded", false);
        int updated = em.createQuery("update AdCampaign c set c.impressions = c.impressions + 1 where c.id=:id and c.status='ACTIVE'")
                .setParameter("id", campaignId).executeUpdate();
        return Map.of("recorded", updated > 0);
    }

    public Map<String, Object> recordClick(String campaignId) {
        if (campaignId == null || campaignId.length() > 64) return Map.of("recorded", false);
        int updated = em.createQuery("update AdCampaign c set c.clicks = c.clicks + 1 where c.id=:id and c.status='ACTIVE'")
                .setParameter("id", campaignId).executeUpdate();
        return Map.of("recorded", updated > 0);
    }

    public Map<String, Object> adminOverview(SessionService.Actor actor) {
        actor.requireAdmin();
        ensureSeedCatalog();
        Instant now = clock.instant();
        expireStaleRecords(now);

        long activeSubscribers = em.createQuery(
                        "select count(distinct s.userId) from UserSubscription s where s.status='ACTIVE' and s.expiresAt>:now",
                        Long.class)
                .setParameter("now", now).getSingleResult();
        long activeCampaigns = em.createQuery(
                        "select count(c) from AdCampaign c where c.status='ACTIVE' and c.endsAt>:now",
                        Long.class)
                .setParameter("now", now).getSingleResult();
        long pendingCampaigns = em.createQuery(
                        "select count(c) from AdCampaign c where c.status='PENDING'",
                        Long.class)
                .getSingleResult();
        long subRevenue = em.createQuery(
                        "select coalesce(sum(s.paidAmount), 0) from UserSubscription s",
                        Long.class)
                .getSingleResult();
        long adRevenue = em.createQuery(
                        "select coalesce(sum(c.paidAmount), 0) from AdCampaign c where c.status <> 'REJECTED'",
                        Long.class)
                .getSingleResult();

        List<Map<String, Object>> allPlans = em.createQuery("select p from SubscriptionPlan p order by p.priceVnd asc", SubscriptionPlan.class)
                .getResultList().stream().map(this::planView).toList();
        List<Map<String, Object>> allPackages = em.createQuery("select a from AdPackage a order by a.priceVnd asc", AdPackage.class)
                .getResultList().stream().map(this::packageView).toList();
        List<Map<String, Object>> allCampaigns = em.createQuery("select c from AdCampaign c order by c.createdAt desc", AdCampaign.class)
                .setMaxResults(100).getResultList().stream().map(this::campaignView).toList();
        List<Map<String, Object>> allSubs = em.createQuery("select s from UserSubscription s order by s.createdAt desc", UserSubscription.class)
                .setMaxResults(100).getResultList().stream().map(this::subscriptionView).toList();

        Map<String, Object> map = new LinkedHashMap<>();
        map.put("simulation", "SIMULATED");
        map.put("currency", "VND");
        map.put("activeSubscribers", activeSubscribers);
        map.put("activeCampaigns", activeCampaigns);
        map.put("pendingCampaigns", pendingCampaigns);
        map.put("subscriptionRevenue", subRevenue);
        map.put("adRevenue", adRevenue);
        map.put("totalMonetizationRevenue", Math.addExact(subRevenue, adRevenue));
        map.put("subscriptionPlans", allPlans);
        map.put("adPackages", allPackages);
        map.put("campaigns", allCampaigns);
        map.put("subscriptions", allSubs);
        return map;
    }

    public Map<String, Object> decideCampaign(SessionService.Actor actor, String campaignId, AdDecisionInput input) {
        actor.requireAdmin();
        bookingService.lockForMonetization();
        Instant now = clock.instant();
        expireStaleRecords(now);
        AdCampaign c = em.find(AdCampaign.class, campaignId);
        if (c == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy chiến dịch quảng cáo.");
        String action = input == null || input.action() == null ? "" : input.action().trim().toUpperCase(Locale.ROOT);
        String note = input == null || input.reason() == null ? "" : input.reason().trim();
        if (note.length() > 450) note = note.substring(0, 450);

        switch (action) {
            case "APPROVE" -> {
                if (!"PENDING".equals(c.status)) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "Chỉ có thể duyệt chiến dịch đang ở trạng thái chờ duyệt (PENDING).");
                }
                TargetSummary target = resolveTarget(c.targetKind, c.targetId);
                if (target == null || !target.active()) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "Dịch vụ gốc đã bị ẩn hoặc xóa, không thể bật hiển thị.");
                }
                c.status = "ACTIVE";
                c.startsAt = now;
                c.endsAt = now.plus(Duration.ofDays(c.durationDays));
                c.adminNote = note.isEmpty() ? null : note;
                notifications.save(Notification.builder()
                        .userId(c.ownerId)
                        .actorName("TravelAI Admin")
                        .type("AD_CAMPAIGN_APPROVED")
                        .title("Chiến dịch quảng cáo đã được duyệt")
                        .message("Chiến dịch \"" + target.name() + "\" đã bắt đầu hiển thị trong " + c.durationDays + " ngày.")
                        .targetReference(c.id)
                        .isRead(false)
                        .createdAt(LocalDateTime.ofInstant(now, VIETNAM))
                        .build());
            }
            case "REJECT" -> {
                if (!"PENDING".equals(c.status)) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "Chỉ từ chối được chiến dịch đang chờ duyệt (PENDING).");
                }
                c.status = "REJECTED";
                c.adminNote = note.isEmpty() ? "Từ chối bởi quản trị viên" : note;
                bookingService.refundLinkedAccount(
                        actor, c.sourceAccountId, c.id, "AD_REFUND", c.paidAmount,
                        "AD_REJECT", c.id, now
                );
                notifications.save(Notification.builder()
                        .userId(c.ownerId)
                        .actorName("TravelAI Admin")
                        .type("AD_CAMPAIGN_REJECTED")
                        .title("Chiến dịch quảng cáo bị từ chối & hoàn tiền SIMULATED")
                        .message("Đã hoàn 100% số tiền SIMULATED " + c.paidAmount + " VND. Lý do: " + c.adminNote)
                        .targetReference(c.id)
                        .isRead(false)
                        .createdAt(LocalDateTime.ofInstant(now, VIETNAM))
                        .build());
            }
            case "PAUSE" -> {
                if (!"ACTIVE".equals(c.status)) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "Chỉ tạm dừng được chiến dịch đang chạy (ACTIVE).");
                }
                c.status = "PAUSED";
                if (!note.isEmpty()) c.adminNote = note;
            }
            case "RESUME" -> {
                if (!"PAUSED".equals(c.status)) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "Chỉ bật lại được chiến dịch đang tạm dừng (PAUSED).");
                }
                if (c.endsAt != null && !c.endsAt.isAfter(now)) {
                    c.status = "EXPIRED";
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "Chiến dịch đã hết thời hạn hiển thị.");
                }
                c.status = "ACTIVE";
            }
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Hành động duyệt không hợp lệ.");
        }
        return campaignView(c);
    }

    public Map<String, Object> updatePlan(SessionService.Actor actor, String code, PlanUpdateInput input) {
        actor.requireAdmin();
        ensureSeedCatalog();
        SubscriptionPlan plan = em.find(SubscriptionPlan.class, code == null ? "" : code.trim().toUpperCase(Locale.ROOT));
        if (plan == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy gói đăng ký.");
        if (input.name() != null && !input.name().isBlank()) plan.name = input.name().trim();
        if (input.priceVnd() != null) {
            if (input.priceVnd() < 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Giá không hợp lệ.");
            plan.priceVnd = input.priceVnd();
        }
        if (input.durationDays() != null) {
            if (input.durationDays() < 1 || input.durationDays() > 3650) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Số ngày không hợp lệ.");
            plan.durationDays = input.durationDays();
        }
        if (input.discountPercent() != null) {
            if (input.discountPercent() < 0 || input.discountPercent() > 50) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "% giảm giá từ 0 đến 50.");
            plan.discountPercent = input.discountPercent();
        }
        if (input.waiveFirstRescheduleFee() != null) plan.waiveFirstRescheduleFee = input.waiveFirstRescheduleFee();
        if (input.unlimitedAi() != null) plan.unlimitedAi = input.unlimitedAi();
        if (input.badge() != null && !input.badge().isBlank()) plan.badge = input.badge().trim();
        if (input.description() != null && !input.description().isBlank()) plan.description = input.description().trim();
        if (input.active() != null) plan.active = input.active();
        return planView(plan);
    }

    public Map<String, Object> updatePackage(SessionService.Actor actor, String code, PackageUpdateInput input) {
        actor.requireAdmin();
        ensureSeedCatalog();
        AdPackage pkg = em.find(AdPackage.class, code == null ? "" : code.trim().toUpperCase(Locale.ROOT));
        if (pkg == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy gói quảng cáo.");
        if (input.name() != null && !input.name().isBlank()) pkg.name = input.name().trim();
        if (input.priceVnd() != null) {
            if (input.priceVnd() < 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Giá không hợp lệ.");
            pkg.priceVnd = input.priceVnd();
        }
        if (input.durationDays() != null) {
            if (input.durationDays() < 1 || input.durationDays() > 365) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Số ngày không hợp lệ.");
            pkg.durationDays = input.durationDays();
        }
        if (input.placement() != null) {
            String pl = input.placement().trim().toUpperCase(Locale.ROOT);
            if (!VALID_PLACEMENTS.contains(pl)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Vị trí hiển thị không hợp lệ.");
            pkg.placement = pl;
        }
        if (input.description() != null && !input.description().isBlank()) pkg.description = input.description().trim();
        if (input.active() != null) pkg.active = input.active();
        return packageView(pkg);
    }

    private record TargetSummary(String name, String location, String province, Double price, Double rating, String image, boolean active) {}

    private TargetSummary resolveTarget(String kind, long id) {
        if ("TOUR".equals(kind)) {
            Tour t = tours.findById(id).orElse(null);
            if (t == null) return null;
            return new TargetSummary(t.getName(), t.getLocation(), t.getProvince(), t.getPrice(), t.getRating(), t.getImage(), "active".equalsIgnoreCase(t.getStatus()));
        }
        if ("HOTEL".equals(kind)) {
            Hotel h = hotels.findById(id).orElse(null);
            if (h == null) return null;
            return new TargetSummary(h.getName(), h.getLocation(), h.getProvince(), h.getPrice(), h.getRating(), h.getImage(), "active".equalsIgnoreCase(h.getStatus()));
        }
        return null;
    }

    private Map<String, Object> planView(SubscriptionPlan p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", p.code);
        m.put("name", p.name);
        m.put("priceVnd", p.priceVnd);
        m.put("durationDays", p.durationDays);
        m.put("discountPercent", p.discountPercent);
        m.put("waiveFirstRescheduleFee", p.waiveFirstRescheduleFee);
        m.put("unlimitedAi", p.unlimitedAi);
        m.put("badge", p.badge);
        m.put("description", p.description);
        m.put("active", p.active);
        m.put("simulation", "SIMULATED");
        m.put("currency", "VND");
        return m;
    }

    private Map<String, Object> packageView(AdPackage a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", a.code);
        m.put("name", a.name);
        m.put("priceVnd", a.priceVnd);
        m.put("durationDays", a.durationDays);
        m.put("placement", a.placement);
        m.put("description", a.description);
        m.put("active", a.active);
        m.put("simulation", "SIMULATED");
        m.put("currency", "VND");
        return m;
    }

    private Map<String, Object> freeSubscriptionView() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("planCode", "FREE");
        m.put("planName", "Cơ Bản (Free)");
        m.put("discountPercent", 0);
        m.put("waiveFirstRescheduleFee", false);
        m.put("unlimitedAi", false);
        m.put("badge", null);
        m.put("status", "FREE");
        m.put("simulation", "SIMULATED");
        m.put("currency", "VND");
        return m;
    }

    private Map<String, Object> subscriptionView(UserSubscription s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.id);
        m.put("userId", s.userId);
        users.findById(s.userId).ifPresent(u -> {
            m.put("userName", u.getName());
            m.put("userEmail", u.getEmail());
        });
        m.put("planCode", s.planCode);
        m.put("planName", s.planName);
        m.put("discountPercent", s.discountPercent);
        m.put("waiveFirstRescheduleFee", s.waiveFirstRescheduleFee);
        m.put("unlimitedAi", s.unlimitedAi);
        m.put("badge", s.badge);
        m.put("status", s.status);
        m.put("paidAmount", s.paidAmount);
        m.put("startsAt", s.startsAt == null ? null : s.startsAt.toString());
        m.put("expiresAt", s.expiresAt == null ? null : s.expiresAt.toString());
        m.put("createdAt", s.createdAt == null ? null : s.createdAt.toString());
        m.put("simulation", "SIMULATED");
        m.put("currency", "VND");
        return m;
    }

    private Map<String, Object> campaignView(AdCampaign c) {
        return campaignViewWithTarget(c, resolveTarget(c.targetKind, c.targetId));
    }

    private Map<String, Object> campaignViewWithTarget(AdCampaign c, TargetSummary target) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.id);
        m.put("ownerId", c.ownerId);
        users.findById(c.ownerId).ifPresent(u -> {
            m.put("ownerName", u.getName());
            m.put("ownerEmail", u.getEmail());
        });
        m.put("packageCode", c.packageCode);
        m.put("packageName", c.packageName);
        m.put("placement", c.placement);
        m.put("targetKind", c.targetKind);
        m.put("targetId", c.targetId);
        m.put("targetName", target == null ? (c.targetKind + " #" + c.targetId) : target.name());
        m.put("targetLocation", target == null ? null : target.location());
        m.put("targetProvince", target == null ? null : target.province());
        m.put("targetPrice", target == null ? null : target.price());
        m.put("targetRating", target == null ? null : target.rating());
        m.put("targetImage", target == null ? null : target.image());
        m.put("headline", c.headline);
        m.put("sponsoredLabel", "Được tài trợ");
        m.put("durationDays", c.durationDays);
        m.put("status", c.status);
        m.put("paidAmount", c.paidAmount);
        m.put("impressions", c.impressions);
        m.put("clicks", c.clicks);
        m.put("adminNote", c.adminNote);
        m.put("startsAt", c.startsAt == null ? null : c.startsAt.toString());
        m.put("endsAt", c.endsAt == null ? null : c.endsAt.toString());
        m.put("createdAt", c.createdAt == null ? null : c.createdAt.toString());
        m.put("simulation", "SIMULATED");
        m.put("currency", "VND");
        return m;
    }

    private static String normalizeText(String input) {
        if (input == null || input.isBlank()) return "";
        String decomposed = Normalizer.normalize(input.replace('đ', 'd').replace('Đ', 'D'), Normalizer.Form.NFD);
        return decomposed.replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT).trim();
    }
}

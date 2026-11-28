package com.travelai.backend.booking;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.travelai.backend.entity.Tour;
import com.travelai.backend.entity.User;
import com.travelai.backend.repository.TourRepository;
import com.travelai.backend.repository.UserRepository;
import com.travelai.backend.security.SessionService;
import com.travelai.backend.security.SessionService.Actor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.*;
import java.util.*;

import static com.travelai.backend.booking.BookingContracts.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(MonetizationIntegrationTest.ClockConfig.class)
class MonetizationIntegrationTest {

    static class ControlledClock extends Clock {
        volatile Instant now = Instant.parse("2026-03-01T02:00:00Z");
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(now, zone); }
        @Override public Instant instant() { return now; }
    }

    @TestConfiguration
    static class ClockConfig {
        @Bean @Primary ControlledClock monetizationTestClock() { return new ControlledClock(); }
    }

    @Autowired BookingService bookingService;
    @Autowired MonetizationService monetizationService;
    @Autowired UserRepository users;
    @Autowired TourRepository tours;
    @Autowired SessionService sessions;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired ControlledClock clock;

    Actor admin;
    Actor traveler;
    String adminBearer;
    String travelerBearer;
    Map<String, Object> card;
    Tour activeTour;
    String offeringId;
    final Instant departureOne = Instant.parse("2026-07-10T03:00:00Z");
    final Instant departureTwo = Instant.parse("2026-07-12T03:00:00Z");

    @BeforeEach
    void setUp() {
        clock.now = Instant.parse("2026-03-01T02:00:00Z");
        admin = createUser("ADMIN");
        traveler = createUser("USER");
        adminBearer = "Bearer " + sessions.login(users.findById(admin.id()).orElseThrow().getEmail(), "Password#12345").get("token");
        travelerBearer = "Bearer " + sessions.login(users.findById(traveler.id()).orElseThrow().getEmail(), "Password#12345").get("token");

        card = bookingService.provisionBank(admin, new BankInput(traveler.id(), 2_000_000L, 12, 2028), key());
        bookingService.linkBank(traveler, new BankLinkInput(
                (String) card.get("bankName"),
                (String) card.get("cardId"),
                (String) card.get("testCvv")
        ));

        activeTour = tours.saveAndFlush(Tour.builder()
                .name("Tour Cầu Vàng Đà Nẵng")
                .location("Bà Nà, Đà Nẵng")
                .province("Đà Nẵng")
                .price(500_000.0)
                .rating(4.8)
                .durationDays(2)
                .maxGuests(10)
                .status("active")
                .description("Hành trình tham quan Đà Nẵng")
                .build());

        offeringId = (String) bookingService.saveOffer(admin, new OfferInput(
                null, "TOUR", activeTour.getId(), activeTour.getName(), true,
                new Policy(24, 24, 30, 10, null), null, null,
                List.of(
                        new Departure(departureOne, 4, List.of("A1", "A2", "A3", "A4"), 500_000L),
                        new Departure(departureTwo, 4, List.of("A1", "A2", "A3", "A4"), 500_000L)
                ),
                null
        )).get("id");
        bookingService.publishCalendar(admin, new CalendarInput(2026, "Lịch lễ thử nghiệm", Set.of(LocalDate.of(2026, 9, 2)), true));
    }

    private Actor createUser(String role) {
        User u = User.builder()
                .name("Test " + role)
                .email(key() + "@travelai.test")
                .password("Password#12345")
                .role(role)
                .status("active")
                .bio("")
                .avatar("")
                .build();
        User saved = users.saveAndFlush(u);
        return new Actor(saved.getId(), role);
    }

    private String key() {
        return UUID.randomUUID().toString();
    }

    @Test
    void vipSubscriptionChargesSimulatedBankAppliesTenPercentBookingDiscountWaivesFirstRescheduleFeeAndUnlocksUnlimitedAi() throws Exception {
        // 1. Free user has 10 daily AI quota limit
        for (int i = 0; i < 10; i++) {
            mvc.perform(post("/api/monetization/ai-quota/consume").header("Authorization", travelerBearer))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.unlimited").value(false));
        }
        mvc.perform(post("/api/monetization/ai-quota/consume").header("Authorization", travelerBearer))
                .andExpect(status().isTooManyRequests());

        // 2. Purchase VIP subscription idempotently with SIMULATED bank card (249,000 VND)
        String subKey = key();
        String payload = json.writeValueAsString(Map.of(
                "planCode", "VIP",
                "cardId", card.get("cardId"),
                "testCvv", card.get("testCvv"),
                "confirmed", true
        ));
        String firstPurchase = mvc.perform(post("/api/monetization/subscriptions/purchase")
                        .header("Authorization", travelerBearer)
                        .header("Idempotency-Key", subKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.planCode").value("VIP"))
                .andExpect(jsonPath("$.simulation").value("SIMULATED"))
                .andReturn().getResponse().getContentAsString();

        // Retry with same Idempotency-Key does not double-charge
        mvc.perform(post("/api/monetization/subscriptions/purchase")
                        .header("Authorization", travelerBearer)
                        .header("Idempotency-Key", subKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(content().json(firstPurchase));

        long balanceAfterSub = ((Number) bookingService.bank(traveler).get("balance")).longValue();
        assertEquals(2_000_000L - 249_000L, balanceAfterSub);

        // 3. AI quota is now unlimited for VIP
        mvc.perform(post("/api/monetization/ai-quota/consume").header("Authorization", travelerBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unlimited").value(true))
                .andExpect(jsonPath("$.badge").value("💎 Explorer"));

        // 4. Booking review automatically applies 10% VIP discount (500,000 -> 450,000 VND)
        Map<String, Object> review = bookingService.review(traveler, new ReviewInput(
                offeringId, null, null, departureOne, null, null, 1, List.of("A1"), null, null
        ), key());
        JsonNode quoteNode = json.valueToTree(review.get("quote"));
        assertEquals(10, quoteNode.get("memberDiscountPercent").asInt());
        assertEquals(50_000L, quoteNode.get("memberDiscount").asLong());
        assertEquals(450_000L, quoteNode.get("total").asLong());

        Map<String, Object> booking = bookingService.pay(traveler, (String) review.get("id"), new PayInput(
                (String) review.get("digest"), true, (String) card.get("cardId"), (String) card.get("testCvv")
        ), key());
        assertEquals("CONFIRMED", booking.get("status"));
        assertEquals(450_000L, ((Number) booking.get("totalPaid")).longValue());

        // 5. First reschedule waives the 20% rescheduling fee for VIP (fee = 0 VND)
        Map<String, Object> rescheduleReview = bookingService.review(traveler, new ReviewInput(
                offeringId, (String) booking.get("id"), ((Number) booking.get("revision")).longValue(),
                departureTwo, null, null, 1, List.of("A2"), null, null
        ), key());
        JsonNode reschedQuote = json.valueToTree(rescheduleReview.get("quote"));
        assertEquals(0L, reschedQuote.get("fee").asLong());
        assertEquals(0L, reschedQuote.get("total").asLong());

        // 6. Summary tracks cumulative booking savings for the VIP member (50,000 VND saved)
        mvc.perform(get("/api/monetization/me").header("Authorization", travelerBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalBookingSavedVnd").value(50_000));
    }

    @Test
    void premiumExclusiveEndpointsRejectFreeUsersAndUnlockConciergeAndDealsForMembers() throws Exception {
        // 1. Free user is strictly blocked (403 FORBIDDEN) from VIP-exclusive features
        mvc.perform(get("/api/monetization/vip/deals").header("Authorization", travelerBearer))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/monetization/vip/itinerary")
                        .header("Authorization", travelerBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"province\":\"Đà Nẵng\",\"days\":2,\"style\":\"LUXURY\"}"))
                .andExpect(status().isForbidden());

        // 2. Upgrade to PLUS (99,000 VND, 5% discount) -> unlocks both VIP Lounge Deals and VIP AI Concierge
        String subPayload = json.writeValueAsString(Map.of(
                "planCode", "PLUS",
                "cardId", card.get("cardId"),
                "testCvv", card.get("testCvv"),
                "confirmed", true
        ));
        mvc.perform(post("/api/monetization/subscriptions/purchase")
                        .header("Authorization", travelerBearer)
                        .header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(subPayload))
                .andExpect(status().isOk());

        mvc.perform(get("/api/monetization/vip/deals").header("Authorization", travelerBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tier").value("PLUS"))
                .andExpect(jsonPath("$.discountPercent").value(5))
                .andExpect(jsonPath("$.deals[0].originalPrice").value(500_000))
                .andExpect(jsonPath("$.deals[0].memberPrice").value(475_000))
                .andExpect(jsonPath("$.deals[0].savedAmount").value(25_000));

        String itineraryBody = mvc.perform(post("/api/monetization/vip/itinerary")
                        .header("Authorization", travelerBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"province\":\"Đà Nẵng\",\"days\":3,\"style\":\"LUXURY\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tier").value("PLUS"))
                .andExpect(jsonPath("$.province").value("Đà Nẵng"))
                .andExpect(jsonPath("$.days.length()").value(3))
                .andExpect(jsonPath("$.days[0].timeline[0].time").value("07:00"))
                .andExpect(jsonPath("$.days[0].timeline[1].time").value("08:00"))
                .andExpect(jsonPath("$.days[0].dateLabel").isNotEmpty())
                .andExpect(jsonPath("$.estimatedSavedVnd").value(25_000))
                .andReturn().getResponse().getContentAsString();
        JsonNode itineraryJson = json.readTree(itineraryBody);
        assertTrue(itineraryJson.at("/days/0/timeline").size() >= 6);
        assertTrue(itineraryJson.at("/days/1/timeline").size() >= 6);
        // Ensure Day 1 and Day 2 do not repeat identical afternoon/evening blocks
        org.junit.jupiter.api.Assertions.assertNotEquals(
                itineraryJson.at("/days/0/afternoon").asText(),
                itineraryJson.at("/days/1/afternoon").asText()
        );
    }

    @Test
    void sponsoredAdCampaignRequiresAdminApprovalTracksMetricsAndRefundsOnRejection() throws Exception {
        // 1. Traveler buys SPOTLIGHT ad campaign (199,000 VND, 7 days) -> starts PENDING
        String adPayload = json.writeValueAsString(Map.of(
                "packageCode", "SPOTLIGHT",
                "targetKind", "TOUR",
                "targetId", activeTour.getId(),
                "headline", "Ưu đãi mùa hè Cầu Vàng",
                "cardId", card.get("cardId"),
                "testCvv", card.get("testCvv"),
                "confirmed", true
        ));
        String createdBody = mvc.perform(post("/api/monetization/ads/purchase")
                        .header("Authorization", travelerBearer)
                        .header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(adPayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.simulation").value("SIMULATED"))
                .andReturn().getResponse().getContentAsString();
        String campaignId = json.readTree(createdBody).get("id").asText();

        // Not visible publicly while PENDING
        mvc.perform(get("/api/monetization/sponsored").param("placement", "EXPLORE").param("province", "Đà Nẵng"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        // Non-admin cannot approve
        mvc.perform(post("/api/admin/monetization/ads/" + campaignId + "/decision")
                        .header("Authorization", travelerBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"APPROVE\"}"))
                .andExpect(status().isForbidden());

        // 2. Admin approves -> becomes ACTIVE and appears in sponsored results
        mvc.perform(post("/api/admin/monetization/ads/" + campaignId + "/decision")
                        .header("Authorization", adminBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"APPROVE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        mvc.perform(get("/api/monetization/sponsored").param("placement", "EXPLORE").param("province", "Đà Nẵng"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].targetName").value("Tour Cầu Vàng Đà Nẵng"))
                .andExpect(jsonPath("$[0].sponsoredLabel").value("Được tài trợ"));

        // 3. Track impression and click
        mvc.perform(post("/api/monetization/sponsored/" + campaignId + "/impression")).andExpect(status().isOk());
        mvc.perform(post("/api/monetization/sponsored/" + campaignId + "/click")).andExpect(status().isOk());

        mvc.perform(get("/api/monetization/me").header("Authorization", travelerBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.campaigns[0].impressions").value(1))
                .andExpect(jsonPath("$.campaigns[0].clicks").value(1));

        // 4. Buying a second campaign and having Admin REJECT it refunds 100% SIMULATED VND
        long beforeSecond = ((Number) bookingService.bank(traveler).get("balance")).longValue();
        String secondBody = mvc.perform(post("/api/monetization/ads/purchase")
                        .header("Authorization", travelerBearer)
                        .header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(adPayload))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String secondId = json.readTree(secondBody).get("id").asText();
        assertEquals(beforeSecond - 199_000L, ((Number) bookingService.bank(traveler).get("balance")).longValue());

        mvc.perform(post("/api/admin/monetization/ads/" + secondId + "/decision")
                        .header("Authorization", adminBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"REJECT\",\"reason\":\"Nội dung cần chỉnh sửa\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"));
        assertEquals(beforeSecond, ((Number) bookingService.bank(traveler).get("balance")).longValue());
    }
}

package com.travelai.backend.booking;

import com.travelai.backend.security.ApiSecurity;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

import static com.travelai.backend.booking.BookingContracts.*;

@RestController
public class MonetizationController {

    private final MonetizationService service;
    private final VipItineraryPlannerService planner;

    public MonetizationController(MonetizationService service) {
        this(service, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public MonetizationController(MonetizationService service, VipItineraryPlannerService planner) {
        this.service = service;
        this.planner = planner;
    }

    @GetMapping("/api/monetization/catalog")
    public Map<String, Object> catalog() {
        return service.publicCatalog();
    }

    @GetMapping("/api/monetization/sponsored")
    public List<Map<String, Object>> sponsored(
            @RequestParam(required = false) String placement,
            @RequestParam(required = false) String province
    ) {
        return service.activeSponsored(placement, province);
    }

    @PostMapping("/api/monetization/sponsored/{id}/impression")
    public Map<String, Object> impression(@PathVariable String id) {
        return service.recordImpression(id);
    }

    @PostMapping("/api/monetization/sponsored/{id}/click")
    public Map<String, Object> click(@PathVariable String id) {
        return service.recordClick(id);
    }

    @GetMapping("/api/monetization/me")
    public Map<String, Object> mySummary(HttpServletRequest request) {
        return service.mySummary(ApiSecurity.actor(request));
    }

    @GetMapping("/api/monetization/ai-quota")
    public Map<String, Object> aiQuota(HttpServletRequest request) {
        return service.getAiQuota(ApiSecurity.actor(request));
    }

    @PostMapping("/api/monetization/ai-quota/consume")
    public Map<String, Object> consumeAiQuota(HttpServletRequest request) {
        return service.consumeAiQuota(ApiSecurity.actor(request));
    }

    @PostMapping("/api/monetization/subscriptions/purchase")
    public Map<String, Object> purchaseSubscription(
            HttpServletRequest request,
            @RequestBody SubscriptionPurchaseInput input,
            @RequestHeader("Idempotency-Key") String key
    ) {
        return service.purchaseSubscription(ApiSecurity.actor(request), input, key);
    }

    @PostMapping("/api/monetization/subscriptions/{id}/cancel")
    public Map<String, Object> cancelSubscription(HttpServletRequest request, @PathVariable String id) {
        return service.cancelSubscription(ApiSecurity.actor(request), id);
    }

    @PostMapping("/api/monetization/ads/purchase")
    public Map<String, Object> purchaseAd(
            HttpServletRequest request,
            @RequestBody AdPurchaseInput input,
            @RequestHeader("Idempotency-Key") String key
    ) {
        return service.purchaseAdCampaign(ApiSecurity.actor(request), input, key);
    }

    @GetMapping("/api/monetization/vip/deals")
    public Map<String, Object> vipDeals(HttpServletRequest request) {
        return service.vipDeals(ApiSecurity.actor(request));
    }

    @PostMapping("/api/monetization/vip/itinerary")
    public Map<String, Object> vipItinerary(
            HttpServletRequest request,
            @RequestBody VipItineraryInput input
    ) {
        return planner.generateVipItinerary(ApiSecurity.actor(request), input);
    }

    @GetMapping("/api/admin/monetization/overview")
    public Map<String, Object> adminOverview(HttpServletRequest request) {
        return service.adminOverview(ApiSecurity.actor(request));
    }

    @PostMapping("/api/admin/monetization/ads/{id}/decision")
    public Map<String, Object> decideCampaign(
            HttpServletRequest request,
            @PathVariable String id,
            @RequestBody AdDecisionInput input
    ) {
        return service.decideCampaign(ApiSecurity.actor(request), id, input);
    }

    @PutMapping("/api/admin/monetization/plans/{code}")
    public Map<String, Object> updatePlan(
            HttpServletRequest request,
            @PathVariable String code,
            @RequestBody PlanUpdateInput input
    ) {
        return service.updatePlan(ApiSecurity.actor(request), code, input);
    }

    @PutMapping("/api/admin/monetization/packages/{code}")
    public Map<String, Object> updatePackage(
            HttpServletRequest request,
            @PathVariable String code,
            @RequestBody PackageUpdateInput input
    ) {
        return service.updatePackage(ApiSecurity.actor(request), code, input);
    }
}

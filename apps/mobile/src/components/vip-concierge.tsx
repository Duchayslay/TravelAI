import { Ionicons } from "@expo/vector-icons";
import { router } from "expo-router";
import * as Speech from "expo-speech";
import { useState } from "react";
import { Pressable, ScrollView, StyleSheet, Text, View } from "react-native";
import {
  Card,
  ErrorText,
  Field,
  money,
  ui,
  useAction,
} from "@/components/ui";
import { useMembership } from "@/context/membership";
import { api } from "@/services/api";
import {
  MONETIZATION_ACCENTS,
  TIER_PALETTES,
  getTierPalette,
  timelineCategoryVisual,
} from "@/services/monetization-helpers";
import type { VipItineraryDay, VipItineraryResponse, VipTimelineStop } from "@/services/types";

const POPULAR_PROVINCES = [
  "Đà Nẵng",
  "Hà Nội",
  "TP. Hồ Chí Minh",
  "Thừa Thiên Huế",
  "Quảng Ninh",
  "Lâm Đồng",
];

const STYLES = [
  { id: "LUXURY", label: "💎 Nghỉ dưỡng hạng sang" },
  { id: "CULTURE", label: "🏛️ Văn hóa & Di sản" },
  { id: "OPTIMAL", label: "⚡ Tối ưu thông minh" },
];

function fallbackStopsFromDay(day: VipItineraryDay, province: string): VipTimelineStop[] {
  if (day.timeline && day.timeline.length > 0) return day.timeline;
  return [
    { time: "08:00", category: "SIGHT", title: "Buổi sáng", subtitle: day.morning, placeLabel: province },
    { time: "14:00", category: "TOUR", title: "Buổi chiều", subtitle: day.afternoon, placeLabel: province },
    { time: "19:30", category: "NIGHT", title: "Buổi tối", subtitle: day.evening, placeLabel: province },
  ];
}

export function VipConcierge({ defaultProvince = "Đà Nẵng" }: { defaultProvince?: string }) {
  const membership = useMembership();
  const palette = getTierPalette(membership.tier);
  const action = useAction();
  const [province, setProvince] = useState(defaultProvince);
  const [days, setDays] = useState(3);
  const [style, setStyle] = useState("LUXURY");
  const [plan, setPlan] = useState<VipItineraryResponse | null>(null);
  const [activeDayIndex, setActiveDayIndex] = useState(0);

  if (!membership.isPremium) {
    const freeHero = TIER_PALETTES.FREE;
    const vipPalette = TIER_PALETTES.VIP;
    return (
      <View style={[styles.outerCard, { borderColor: vipPalette.cardBorder, backgroundColor: "#ffffff" }]}>
        <View style={[styles.ribbonHeader, { backgroundColor: freeHero.heroBg }]}>
          <View style={styles.headerRow}>
            <View
              style={[
                styles.pillBadge,
                {
                  backgroundColor: vipPalette.ctaSecondaryBg,
                  borderColor: vipPalette.ctaSecondaryBorder,
                },
              ]}
            >
              <Text style={[styles.pillBadgeText, { color: vipPalette.ctaSecondaryText }]}>
                🔒 ĐẶC QUYỀN HỘI VIÊN
              </Text>
            </View>
            <Text style={[styles.tierHint, { color: freeHero.heroEyebrow }]}>
              Dành cho TravelAI Plus & Explorer VIP
            </Text>
          </View>
          <Text style={[styles.ribbonTitle, { color: freeHero.heroTitle }]}>
            ✨ AI VIP Concierge — Lập Lịch Trình Theo Giờ & Dự Toán Tiết Kiệm
          </Text>
          <Text style={[styles.ribbonSub, { color: freeHero.heroBody }]}>
            Tài khoản Cơ Bản (Free) hiện đang khóa tính năng này. Nâng cấp để mở khóa trọn bộ trợ lý thiết kế chuyến đi hạng sang:
          </Text>
        </View>

        <View style={styles.bodyContent}>
          <View style={styles.perkBox}>
            <Text style={styles.perkItem}>
              ✦ Thiết kế timeline chi tiết theo từng khung giờ (07:00, 08:00, 09:15... đến 21:00)
            </Text>
            <Text style={styles.perkItem}>
              ✦ Tính toán trực tiếp ngân sách ưu đãi Hội viên (-5% đến -10%) cho chuyến đi
            </Text>
            <Text style={styles.perkItem}>
              ✦ Nghe trợ lý giọng nói đọc thuyết minh toàn bộ lịch trình VIP bằng Tiếng Việt
            </Text>
          </View>
          <Pressable
            accessibilityRole="button"
            onPress={() => router.push("/subscriptions")}
            style={[styles.ctaButton, { backgroundColor: vipPalette.ctaBg }]}
          >
            <Text style={[styles.ctaButtonText, { color: vipPalette.ctaText }]}>
              👑 Nâng cấp Hội viên VIP để mở khóa ngay →
            </Text>
          </Pressable>
        </View>
      </View>
    );
  }

  const currentDay = plan?.days[activeDayIndex] || plan?.days[0] || null;
  const stops = currentDay && plan ? fallbackStopsFromDay(currentDay, plan.province) : [];
  const accentLineColor = membership.isVip ? "#d97706" : "#087fba";

  return (
    <View
      style={[
        styles.outerCard,
        { backgroundColor: palette.cardBg, borderColor: palette.cardBorder },
      ]}
    >
      <View style={[styles.ribbonHeader, { backgroundColor: palette.heroBg }]}>
        <View style={styles.headerRow}>
          <View
            style={[
              styles.pillBadge,
              {
                backgroundColor: palette.ctaSecondaryBg,
                borderColor: palette.ctaSecondaryBorder,
              },
            ]}
          >
            <Text style={[styles.pillBadgeText, { color: palette.ctaSecondaryText }]}>
              {membership.badge} · ĐÃ MỞ KHÓA CONCIERGE
            </Text>
          </View>
          <View style={styles.savingsPill}>
            <Text style={styles.savingsPillText}>Ưu đãi -{membership.discountPercent}%</Text>
          </View>
        </View>
        <Text style={[styles.ribbonTitle, { color: palette.heroTitle }]}>
          ✨ AI VIP Concierge — Thiết Kế Hành Trình Riêng Cho Bạn
        </Text>
        <Text style={[styles.ribbonSub, { color: palette.heroBody }]}>
          Chọn điểm đến, số ngày và phong cách trải nghiệm để API lập lịch trình chi tiết từng khung giờ kèm báo giá đặc quyền {membership.planName}.
        </Text>
      </View>

      <View style={styles.bodyContent}>
        <Text style={[styles.sectionLabel, { color: palette.headingText }]}>
          Chọn nhanh điểm đến nổi bật
        </Text>
        <View style={styles.chipRow}>
          {POPULAR_PROVINCES.map((p) => {
            const active = province === p;
            return (
              <Pressable
                key={p}
                accessibilityRole="button"
                onPress={() => setProvince(p)}
                style={[
                  styles.chip,
                  active && { backgroundColor: palette.ctaBg, borderColor: palette.ctaBg },
                ]}
              >
                <Text
                  style={[
                    styles.chipText,
                    active && { color: palette.ctaText, fontWeight: "800" },
                  ]}
                >
                  {p}
                </Text>
              </Pressable>
            );
          })}
        </View>

        <Field
          label="Tỉnh / Thành phố muốn đi"
          value={province}
          onChangeText={setProvince}
          placeholder="Nhập tên tỉnh/thành"
        />

        <Text style={[styles.sectionLabel, { color: palette.headingText }]}>
          Số ngày hành trình
        </Text>
        <View style={styles.chipRow}>
          {[2, 3, 4, 5].map((d) => {
            const active = days === d;
            return (
              <Pressable
                key={d}
                accessibilityRole="button"
                onPress={() => setDays(d)}
                style={[
                  styles.chip,
                  active && { backgroundColor: palette.ctaBg, borderColor: palette.ctaBg },
                ]}
              >
                <Text
                  style={[
                    styles.chipText,
                    active && { color: palette.ctaText, fontWeight: "800" },
                  ]}
                >
                  {d} ngày {d - 1} đêm
                </Text>
              </Pressable>
            );
          })}
        </View>

        <Text style={[styles.sectionLabel, { color: palette.headingText }]}>
          Phong cách đặc quyền
        </Text>
        <View style={styles.chipRow}>
          {STYLES.map((s) => {
            const active = style === s.id;
            return (
              <Pressable
                key={s.id}
                accessibilityRole="button"
                onPress={() => setStyle(s.id)}
                style={[
                  styles.chip,
                  active && { backgroundColor: palette.ctaBg, borderColor: palette.ctaBg },
                ]}
              >
                <Text
                  style={[
                    styles.chipText,
                    active && { color: palette.ctaText, fontWeight: "800" },
                  ]}
                >
                  {s.label}
                </Text>
              </Pressable>
            );
          })}
        </View>

        <ErrorText message={action.error} />
        <Pressable
          accessibilityRole="button"
          disabled={action.busy || !province.trim()}
          onPress={() =>
            action.run(async () => {
              const res = await api<VipItineraryResponse>("/monetization/vip/itinerary", {
                method: "POST",
                body: { province: province.trim(), days, style },
              });
              setActiveDayIndex(0);
              setPlan(res);
            })
          }
          style={[
            styles.ctaButton,
            { backgroundColor: palette.ctaBg },
            action.busy && { opacity: 0.6 },
          ]}
        >
          <Text style={[styles.ctaButtonText, { color: palette.ctaText }]}>
            {action.busy
              ? "⏳ Đang gọi API thiết kế lịch trình theo giờ..."
              : `✨ Tạo Lịch Trình ${membership.badge} (${days}N${days - 1}Đ)`}
          </Text>
        </Pressable>

        {plan && (
          <View style={styles.resultWrap}>
            {/* 1. ROYAL BUDGET & VOICE NARRATION CARD */}
            <View
              style={[
                styles.budgetBox,
                { backgroundColor: palette.heroBg, borderColor: palette.heroBorder },
              ]}
            >
              <View style={{ flex: 1, gap: 6 }}>
                <Text style={[styles.budgetTitle, { color: palette.heroEyebrow }]}>
                  Dự toán đặc quyền tại {plan.province} ({plan.styleLabel})
                </Text>
                {plan.estimatedOriginalVnd > 0 ? (
                  <>
                    <Text style={[styles.strikeOnDark, { color: palette.heroBody }]}>
                      Giá niêm yết: {money(plan.estimatedOriginalVnd)}
                    </Text>
                    <Text style={[styles.memberTotal, { color: palette.heroTitle }]}>
                      Giá {plan.badge} (-{plan.discountPercent}%):{" "}
                      {money(plan.estimatedMemberVnd)}
                    </Text>
                    <Text style={styles.savedHighlight}>
                      🎉 Tiết kiệm ngay {money(plan.estimatedSavedVnd)} nhờ thẻ Hội viên!
                    </Text>
                  </>
                ) : (
                  <Text style={styles.savedHighlight}>
                    Mọi Tour & Khách sạn bạn đặt thêm đều được giảm tự động {plan.discountPercent}%!
                  </Text>
                )}
              </View>
              <Pressable
                accessibilityRole="button"
                onPress={() => {
                  void Speech.stop().then(() =>
                    Speech.speak(plan.conciergeSummary, { language: "vi-VN" }),
                  );
                }}
                style={[styles.voiceBtn, { backgroundColor: palette.heroCtaBg }]}
              >
                <Text style={[styles.voiceBtnText, { color: palette.heroCtaText }]}>
                  🔊 Nghe thuyết minh VIP
                </Text>
              </Pressable>
            </View>

            {/* 2. PLANIFICATEUR-STYLE HOURLY TIMELINE BOARD IN TRAVELAI OCEAN/TIER THEME */}
            <View style={styles.plannerBoard}>
              <View style={styles.plannerHeaderRow}>
                <View style={{ flex: 1, gap: 2 }}>
                  <Text style={styles.plannerHeading}>Kế hoạch hành trình theo giờ</Text>
                  <Text style={styles.plannerSubheading}>
                    {plan.province} · {plan.days.length} ngày ·{" "}
                    {plan.aiSource === "GEMINI_AI" ? "Gemini AI sinh tự động" : "Lịch trình thông minh TravelAI"}
                  </Text>
                </View>
              </View>

              {/* Horizontal Day Tabs (Ngày 1 / Ngày 2 / Ngày 3 + > arrow) */}
              <View style={styles.dayTabBarWrap}>
                <ScrollView
                  horizontal
                  showsHorizontalScrollIndicator={false}
                  contentContainerStyle={styles.dayTabsRow}
                >
                  {plan.days.map((d, idx) => {
                    const isActive = idx === activeDayIndex;
                    return (
                      <Pressable
                        key={d.day}
                        accessibilityRole="tab"
                        accessibilityState={{ selected: isActive }}
                        onPress={() => setActiveDayIndex(idx)}
                        style={styles.dayTabItem}
                      >
                        <Text
                          style={[
                            styles.dayTabTitle,
                            isActive && { color: "#103c5b", fontWeight: "900" },
                          ]}
                        >
                          Ngày {d.day}
                        </Text>
                        {!!d.dateLabel && (
                          <Text
                            style={[
                              styles.dayTabDate,
                              isActive && { color: "#075985", fontWeight: "700" },
                            ]}
                          >
                            {d.dateLabel}
                          </Text>
                        )}
                        <View
                          style={[
                            styles.dayTabIndicator,
                            isActive && { backgroundColor: accentLineColor },
                          ]}
                        />
                      </Pressable>
                    );
                  })}
                </ScrollView>
                {plan.days.length > 1 && (
                  <Pressable
                    accessibilityRole="button"
                    accessibilityLabel="Chuyển sang ngày tiếp theo"
                    onPress={() => setActiveDayIndex((prev) => (prev + 1) % plan.days.length)}
                    style={styles.nextDayArrow}
                  >
                    <Ionicons name="chevron-forward" size={18} color="#103c5b" />
                  </Pressable>
                )}
              </View>

              {/* Vertical Timeline Rail + Clean White Hourly Cards */}
              <View style={styles.timelineList}>
                {stops.map((stop, idx) => {
                  const visual = timelineCategoryVisual(stop.category);
                  const isFirst = idx === 0;
                  const isLast = idx === stops.length - 1;
                  return (
                    <View key={`${currentDay?.day}-${idx}-${stop.time}`} style={styles.timelineRow}>
                      {/* Left Rail */}
                      <View style={styles.railCol}>
                        <View
                          style={[
                            styles.railLineTop,
                            idx === 0 && { backgroundColor: "transparent", borderColor: "transparent" },
                          ]}
                        />
                        <View
                          style={[
                            styles.railDot,
                            isFirst
                              ? { backgroundColor: accentLineColor, borderColor: accentLineColor }
                              : { backgroundColor: "#f2f8fc", borderColor: "#93c5fd" },
                          ]}
                        />
                        <View
                          style={[
                            styles.railLineBottom,
                            isLast && { backgroundColor: "transparent", borderColor: "transparent" },
                          ]}
                        />
                      </View>

                      {/* Right White Event Card */}
                      <View style={styles.stopCard}>
                        <View style={[styles.stopIconCircle, { backgroundColor: visual.bg }]}>
                          <Ionicons name={visual.icon} size={20} color={visual.fg} />
                        </View>
                        <View style={styles.stopMainCol}>
                          <Text style={styles.stopTitle}>{stop.title}</Text>
                          <Text style={styles.stopSubtitle}>{stop.subtitle}</Text>
                        </View>
                        <View style={styles.stopMetaCol}>
                          <Text style={styles.stopTime}>{stop.time}</Text>
                          {!!stop.placeLabel && (
                            <Text style={styles.stopPlace} numberOfLines={2}>
                              {stop.placeLabel}
                            </Text>
                          )}
                        </View>
                      </View>
                    </View>
                  );
                })}
              </View>
            </View>

            {/* 3. MATCHED TOURS & HOTELS WITH VIP MEMBER DISCOUNT */}
            {(plan.recommendedTours.length > 0 || plan.recommendedHotels.length > 0) && (
              <Card>
                <Text style={ui.heading}>Dịch vụ khớp lịch trình với Giá Đặc Quyền</Text>
                {plan.recommendedTours.map((t) => (
                  <Pressable
                    key={`T-${t.id}`}
                    onPress={() =>
                      router.push({ pathname: "/booking/tours/[id]", params: { id: String(t.id) } })
                    }
                    style={styles.recRow}
                  >
                    <View style={{ flex: 1 }}>
                      <Text style={styles.recName}>🧭 {t.name}</Text>
                      <Text style={styles.recStrike}>{money(t.originalPrice)}</Text>
                    </View>
                    <Text style={[styles.recVipPrice, { color: palette.priceText }]}>
                      {money(t.memberPrice)} (-{plan.discountPercent}%) →
                    </Text>
                  </Pressable>
                ))}
                {plan.recommendedHotels.map((h) => (
                  <Pressable
                    key={`H-${h.id}`}
                    onPress={() =>
                      router.push({
                        pathname: "/booking/hotels/[id]",
                        params: { id: String(h.id) },
                      })
                    }
                    style={styles.recRow}
                  >
                    <View style={{ flex: 1 }}>
                      <Text style={styles.recName}>🏨 {h.name}</Text>
                      <Text style={styles.recStrike}>{money(h.originalPrice)}</Text>
                    </View>
                    <Text style={[styles.recVipPrice, { color: palette.priceText }]}>
                      {money(h.memberPrice)} (-{plan.discountPercent}%) →
                    </Text>
                  </Pressable>
                ))}
              </Card>
            )}
          </View>
        )}
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  outerCard: {
    borderRadius: 24,
    borderWidth: 2,
    overflow: "hidden",
  },
  ribbonHeader: {
    padding: 20,
    gap: 8,
  },
  headerRow: {
    flexDirection: "row",
    flexWrap: "wrap",
    alignItems: "center",
    justifyContent: "space-between",
    gap: 8,
  },
  pillBadge: {
    borderWidth: 1,
    paddingHorizontal: 12,
    paddingVertical: 4,
    borderRadius: 999,
  },
  pillBadgeText: {
    fontWeight: "900",
    fontSize: 12,
    letterSpacing: 0.3,
  },
  savingsPill: {
    backgroundColor: MONETIZATION_ACCENTS.savingsBg,
    borderColor: MONETIZATION_ACCENTS.savingsBorder,
    borderWidth: 1,
    paddingHorizontal: 10,
    paddingVertical: 4,
    borderRadius: 999,
  },
  savingsPillText: {
    color: MONETIZATION_ACCENTS.savingsText,
    fontWeight: "800",
    fontSize: 12,
  },
  tierHint: { fontSize: 12, fontWeight: "800" },
  ribbonTitle: { fontSize: 20, fontWeight: "800", lineHeight: 27 },
  ribbonSub: { fontSize: 14, lineHeight: 21 },
  bodyContent: {
    padding: 20,
    gap: 12,
  },
  perkBox: {
    backgroundColor: "#f8fafc",
    borderColor: "#cbd5e1",
    borderWidth: 1,
    borderRadius: 16,
    padding: 14,
    gap: 8,
  },
  perkItem: { color: "#1e293b", fontSize: 14, lineHeight: 22, fontWeight: "600" },
  sectionLabel: { fontSize: 14, fontWeight: "800" },
  chipRow: { flexDirection: "row", flexWrap: "wrap", gap: 8 },
  chip: {
    paddingHorizontal: 13,
    paddingVertical: 9,
    borderRadius: 999,
    backgroundColor: "#ffffff",
    borderWidth: 1.5,
    borderColor: "#cbd5e1",
  },
  chipText: { color: "#1e293b", fontSize: 13, fontWeight: "600" },
  ctaButton: {
    marginTop: 4,
    borderRadius: 14,
    paddingVertical: 14,
    paddingHorizontal: 18,
    alignItems: "center",
    justifyContent: "center",
  },
  ctaButtonText: { fontWeight: "900", fontSize: 15 },
  resultWrap: { gap: 14, marginTop: 6 },
  budgetBox: {
    borderRadius: 18,
    borderWidth: 1.5,
    padding: 18,
    gap: 12,
  },
  budgetTitle: { fontSize: 15, fontWeight: "800" },
  strikeOnDark: {
    fontSize: 13,
    textDecorationLine: "line-through",
    opacity: 0.9,
  },
  memberTotal: { fontSize: 20, fontWeight: "900" },
  savedHighlight: {
    color: MONETIZATION_ACCENTS.savingsOnDark,
    fontSize: 14,
    fontWeight: "800",
  },
  voiceBtn: {
    alignSelf: "flex-start",
    borderRadius: 12,
    paddingHorizontal: 16,
    paddingVertical: 11,
  },
  voiceBtnText: { fontSize: 14, fontWeight: "900" },
  plannerBoard: {
    backgroundColor: "#f2f8fc",
    borderColor: "#d6e6f5",
    borderWidth: 1.5,
    borderRadius: 24,
    padding: 20,
    gap: 16,
  },
  plannerHeaderRow: {
    flexDirection: "row",
    alignItems: "center",
    justifyContent: "space-between",
    gap: 10,
  },
  plannerHeading: {
    fontSize: 22,
    fontWeight: "900",
    color: "#103c5b",
    letterSpacing: -0.4,
  },
  plannerSubheading: {
    fontSize: 13,
    fontWeight: "600",
    color: "#475569",
  },
  dayTabBarWrap: {
    flexDirection: "row",
    alignItems: "center",
    borderBottomWidth: 1,
    borderBottomColor: "#dbe8f4",
  },
  dayTabsRow: {
    flexDirection: "row",
    alignItems: "flex-end",
    gap: 24,
    paddingRight: 12,
  },
  dayTabItem: {
    paddingBottom: 10,
    alignItems: "center",
    minWidth: 68,
    position: "relative",
  },
  dayTabTitle: {
    fontSize: 15,
    fontWeight: "700",
    color: "#64748b",
  },
  dayTabDate: {
    fontSize: 12,
    color: "#64748b",
    marginTop: 2,
  },
  dayTabIndicator: {
    position: "absolute",
    left: 0,
    right: 0,
    bottom: -1,
    height: 3,
    borderRadius: 3,
    backgroundColor: "transparent",
  },
  nextDayArrow: {
    width: 34,
    height: 34,
    borderRadius: 17,
    backgroundColor: "#ffffff",
    borderWidth: 1,
    borderColor: "#dbe8f4",
    alignItems: "center",
    justifyContent: "center",
    marginLeft: 8,
    marginBottom: 6,
  },
  timelineList: {
    gap: 0,
  },
  timelineRow: {
    flexDirection: "row",
    alignItems: "stretch",
    gap: 12,
  },
  railCol: {
    width: 24,
    alignItems: "center",
  },
  railLineTop: {
    width: 2,
    height: 22,
    backgroundColor: "#cbd5e1",
  },
  railDot: {
    width: 13,
    height: 13,
    borderRadius: 7,
    borderWidth: 2.5,
  },
  railLineBottom: {
    width: 2,
    flex: 1,
    minHeight: 22,
    backgroundColor: "#cbd5e1",
  },
  stopCard: {
    flex: 1,
    flexDirection: "row",
    alignItems: "center",
    gap: 14,
    backgroundColor: "#ffffff",
    borderRadius: 18,
    borderWidth: 1,
    borderColor: "#e3eef8",
    paddingVertical: 14,
    paddingHorizontal: 16,
    marginBottom: 12,
    shadowColor: "#294c80",
    shadowOpacity: 0.05,
    shadowRadius: 12,
    shadowOffset: { width: 0, height: 4 },
    elevation: 1,
  },
  stopIconCircle: {
    width: 44,
    height: 44,
    borderRadius: 22,
    alignItems: "center",
    justifyContent: "center",
  },
  stopMainCol: {
    flex: 1,
    gap: 3,
  },
  stopTitle: {
    fontSize: 15,
    fontWeight: "800",
    color: "#103c5b",
  },
  stopSubtitle: {
    fontSize: 13,
    lineHeight: 19,
    color: "#475569",
  },
  stopMetaCol: {
    alignItems: "flex-end",
    justifyContent: "space-between",
    gap: 6,
    maxWidth: 135,
  },
  stopTime: {
    fontSize: 13,
    fontWeight: "700",
    color: "#475569",
  },
  stopPlace: {
    fontSize: 12,
    fontWeight: "800",
    color: "#103c5b",
    textAlign: "right",
  },
  recRow: {
    flexDirection: "row",
    alignItems: "center",
    justifyContent: "space-between",
    gap: 10,
    paddingVertical: 8,
    borderBottomWidth: 1,
    borderBottomColor: "#e2e8f0",
  },
  recName: { color: "#103c5b", fontWeight: "700", fontSize: 14 },
  recStrike: {
    color: "#64748b",
    fontSize: 12,
    textDecorationLine: "line-through",
  },
  recVipPrice: { fontWeight: "900", fontSize: 14 },
});

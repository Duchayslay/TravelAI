import { router } from "expo-router";
import { useEffect, useRef } from "react";
import { Pressable, StyleSheet, Text, View } from "react-native";
import { Card, money, ui } from "@/components/ui";
import { useResource } from "@/hooks/use-resource";
import { api } from "@/services/api";
import { MONETIZATION_ACCENTS } from "@/services/monetization-helpers";
import type { PublicSponsoredCampaign } from "@/services/types";

export function SponsoredStrip({
  placement,
  province,
  title = "Đề xuất được tài trợ",
}: {
  placement: "HOME" | "EXPLORE" | "FEED" | "DESTINATION";
  province?: string | null;
  title?: string;
}) {
  const query = `/monetization/sponsored?placement=${encodeURIComponent(placement)}${
    province ? `&province=${encodeURIComponent(province)}` : ""
  }`;
  const sponsored = useResource<PublicSponsoredCampaign[]>(query, 0, true);
  const seen = useRef<Set<string>>(new Set());

  useEffect(() => {
    for (const item of sponsored.data || []) {
      if (!seen.current.has(item.id)) {
        seen.current.add(item.id);
        void api(`/monetization/sponsored/${encodeURIComponent(item.id)}/impression`, {
          method: "POST",
        }).catch(() => {});
      }
    }
  }, [sponsored.data]);

  if (!sponsored.data?.length) return null;

  return (
    <View style={styles.section}>
      <View style={styles.headerRow}>
        <Text style={ui.heading}>{title}</Text>
        <Text style={styles.disclosure}>Quảng cáo minh bạch · SIMULATED</Text>
      </View>
      <View style={[ui.row, { alignItems: "stretch" }]}>
        {sponsored.data.slice(0, 4).map((camp) => (
          <View key={camp.id} style={styles.cardWrap}>
            <Card hoverSurface>
              <View style={styles.topRow}>
                <View style={styles.badge}>
                  <Text style={styles.badgeText}>✦ {camp.sponsoredLabel}</Text>
                </View>
                <Text style={styles.kindTag}>
                  {camp.targetKind === "TOUR" ? "Tour" : "Khách sạn"}
                  {camp.targetProvince ? ` · ${camp.targetProvince}` : ""}
                </Text>
              </View>
              <Text style={styles.headline}>{camp.headline}</Text>
              <Text style={ui.heading}>{camp.targetName}</Text>
              {!!camp.targetLocation && <Text style={ui.text}>{camp.targetLocation}</Text>}
              {camp.targetPrice != null && (
                <Text style={styles.price}>Giá tham khảo: {money(camp.targetPrice)}</Text>
              )}
              <Pressable
                accessibilityRole="link"
                onPress={() => {
                  void api(`/monetization/sponsored/${encodeURIComponent(camp.id)}/click`, {
                    method: "POST",
                  }).catch(() => {});
                  router.push({
                    pathname:
                      camp.targetKind === "TOUR"
                        ? "/booking/tours/[id]"
                        : "/booking/hotels/[id]",
                    params: { id: String(camp.targetId) },
                  });
                }}
                style={styles.cta}
              >
                <Text style={styles.ctaText}>Xem chi tiết dịch vụ →</Text>
              </Pressable>
            </Card>
          </View>
        ))}
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  section: { gap: 12 },
  headerRow: {
    flexDirection: "row",
    flexWrap: "wrap",
    alignItems: "center",
    justifyContent: "space-between",
    gap: 8,
  },
  disclosure: { fontSize: 12, color: "#64748b", fontWeight: "600" },
  cardWrap: { flexBasis: 270, flexGrow: 1, minWidth: 0, maxWidth: 540 },
  topRow: {
    flexDirection: "row",
    alignItems: "center",
    justifyContent: "space-between",
    gap: 8,
  },
  badge: {
    backgroundColor: MONETIZATION_ACCENTS.sponsoredBg,
    borderColor: MONETIZATION_ACCENTS.sponsoredBorder,
    borderWidth: 1,
    borderRadius: 999,
    paddingHorizontal: 10,
    paddingVertical: 4,
  },
  badgeText: { color: MONETIZATION_ACCENTS.sponsoredText, fontSize: 12, fontWeight: "800" },
  kindTag: { color: "#475569", fontSize: 12, fontWeight: "600" },
  headline: { color: "#087fba", fontSize: 13, fontWeight: "700" },
  price: { color: "#075782", fontWeight: "700" },
  cta: {
    marginTop: 4,
    paddingVertical: 10,
    paddingHorizontal: 14,
    borderRadius: 12,
    backgroundColor: "#e2f3fc",
    alignSelf: "flex-start",
  },
  ctaText: { color: "#087fba", fontWeight: "800", fontSize: 14 },
});

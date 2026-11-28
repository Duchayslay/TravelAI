import { destinationFor } from "@/services/catalog-media";
import { PinTabs } from "@/components/pin-ui";
import { Ionicons } from "@expo/vector-icons";
import { CatalogPhoto } from "@/components/catalog-photo";
import { useMemo, useState } from "react";
import { Pressable, ScrollView, StyleSheet, Text, View } from "react-native";
import {
  Card,
  ErrorText,
  Field,
  SearchBar,
  Nav,
  Screen,
  money,
  ui,
} from "@/components/ui";
import { useMembership } from "@/context/membership";
import { useResource } from "@/hooks/use-resource";
import { api } from "@/services/api";
import {
  MONETIZATION_ACCENTS,
  computeMemberPrice,
  getTierPalette,
  prioritizeSponsoredCatalog,
  vipTeaserPrice,
} from "@/services/monetization-helpers";
import { provinceFor, provinces } from "@/services/provinces";
import type { CatalogItem, Place, PublicSponsoredCampaign } from "@/services/types";

export function Catalog({ kind }: { kind: "tours" | "hotels" }) {
  const membership = useMembership();
  const palette = getTierPalette(membership.tier);
  const resource = useResource<CatalogItem[]>(`/${kind}`);
  const sponsored = useResource<PublicSponsoredCampaign[]>("/monetization/sponsored?placement=EXPLORE");
  const places = useResource<Place[]>(kind === "tours" ? "/places" : null);
  const [query, setQuery] = useState("");
  const [searchOpen, setSearchOpen] = useState(true);
  const [province, setProvince] = useState("");
  const [provinceOpen, setProvinceOpen] = useState(false);
  const [area, setArea] = useState("");
  const [maxPrice, setMaxPrice] = useState("");
  const [rating, setRating] = useState(0);
  const itemProvince = (item: CatalogItem) =>
    provinceFor(item.province || item.location);
  const areas = useMemo(
    () =>
      [
        ...new Set(
          (resource.data || [])
            .filter(
              (item) =>
                !province ||
                provinceFor(item.province || item.location) === province,
            )
            .flatMap((item) =>
              (item.location || "")
                .split(",")
                .map((v) => v.trim())
                .filter((v) => /^(quận|huyện|thị xã|phường|xã)\s/i.test(v)),
            ),
        ),
      ].sort((a, b) => a.localeCompare(b, "vi")),
    [resource.data, province],
  );
  const amount = maxPrice.trim() ? Number(maxPrice) : null;
  const invalidPrice =
    amount !== null && (!Number.isFinite(amount) || amount < 0);
  const matches = resource.data?.filter(
    (item) =>
      `${item.name} ${item.location || ""} ${item.province || ""}`
        .toLocaleLowerCase("vi")
        .includes(query.trim().toLocaleLowerCase("vi")) &&
      (!province || itemProvince(item) === province) &&
      (!area ||
        (item.location || "")
          .split(",")
          .some((part) => part.trim() === area)) &&
      (!rating || (item.rating != null && item.rating >= rating)) &&
      (amount === null ||
        (!invalidPrice && item.price != null && item.price <= amount)),
  );
  const groups =
    kind === "hotels"
      ? [
          ...new Set(
            matches?.map(
              (item) => itemProvince(item) || "Chưa xác định tỉnh/thành",
            ) || [],
          ),
        ].sort((a, b) => a.localeCompare(b, "vi"))
      : [""];
  return (
    <Screen title={kind === "tours" ? "Khám phá tour" : "Tìm khách sạn"}>
      <Text style={ui.text}>{kind === "hotels" ? "Chọn một nơi nghỉ phù hợp. Khám phá không gian, so sánh mức giá và xem lịch còn phòng." : "Bạn muốn hành trình tiếp theo đưa mình đi đâu? Tìm theo tên điểm đến để bắt đầu."}</Text>
      <View style={styles.toolbar}>
        <Pressable
          accessibilityRole="button"
          accessibilityLabel={searchOpen ? "Đóng tìm kiếm" : "Mở tìm kiếm"}
          accessibilityState={{ expanded: searchOpen }}
          onPress={() => setSearchOpen(!searchOpen)}
          style={styles.icon}
        >
          <Ionicons
            name={searchOpen ? "close" : "search"}
            size={23}
            color="#087fba"
          />
        </Pressable>
        <Text style={styles.hint}>Tìm chuyến đi phù hợp với bạn</Text>
      </View>
      {searchOpen && (
        <SearchBar loading={resource.loading}
          label="Tìm theo tên hoặc địa điểm"
          value={query}
          onChangeText={setQuery}
          autoFocus
        />
      )}
      {kind === "hotels" && (
        <Card>
          <Text style={ui.heading}>Bộ lọc khách sạn</Text>
          <Pressable
            accessibilityRole="button"
            accessibilityState={{ expanded: provinceOpen }}
            onPress={() => setProvinceOpen(!provinceOpen)}
            style={styles.filterToggle}
          >
            <Ionicons name="location-outline" size={18} color="#087fba" />
            <Text style={styles.filterText}>
              {province || "Tất cả tỉnh/thành"}
            </Text>
            <Ionicons
              name={provinceOpen ? "chevron-up" : "chevron-down"}
              size={18}
              color="#087fba"
            />
          </Pressable>
          {provinceOpen && (
            <ScrollView style={{ maxHeight: 160 }} nestedScrollEnabled>
              <View style={styles.chips}>
                {["", ...Object.keys(provinces)].map((value) => (
                  <Pressable
                    key={value || "all"}
                    accessibilityRole="button"
                    accessibilityState={{ selected: value === province }}
                    onPress={() => {
                      setProvince(value);
                      setArea("");
                      setProvinceOpen(false);
                    }}
                    style={[
                      styles.chip,
                      value === province && styles.chipSelected,
                    ]}
                  >
                    <Text style={styles.chipText}>{value || "Tất cả"}</Text>
                  </Pressable>
                ))}
              </View>
            </ScrollView>
          )}
          {areas.length > 0 && (
            <>
              <Text style={ui.label}>Khu vực có trong địa chỉ</Text>
              <View style={styles.chips}>
                {["", ...areas].map((value) => (
                  <Pressable
                    key={value || "all"}
                    accessibilityRole="button"
                    accessibilityState={{ selected: value === area }}
                    onPress={() => setArea(value)}
                    style={[styles.chip, value === area && styles.chipSelected]}
                  >
                    <Text style={styles.chipText}>{value || "Tất cả"}</Text>
                  </Pressable>
                ))}
              </View>
            </>
          )}
          <Field
            label="Giá danh mục tối đa · SIMULATED VND"
            keyboardType="numeric"
            value={maxPrice}
            onChangeText={setMaxPrice}
            placeholder="Không giới hạn"
          />
          <Text style={ui.label}>Điểm danh mục tối thiểu</Text>
          <PinTabs tabs={[0, 3, 4, 5].map(value => ({ id: String(value), label: value ? `${value}+ ★` : "Tất cả" }))} value={String(rating)} onChange={value => setRating(Number(value))} />
          <Text style={styles.hint}>
            Giá và điểm là thông tin danh mục, không phải giá chốt theo lịch
            hoặc đánh giá đã xác minh. Khu vực phụ thuộc địa chỉ đã nhập.
          </Text>
          {invalidPrice && (
            <Text style={ui.error}>Vui lòng nhập mức giá hợp lệ.</Text>
          )}
        </Card>
      )}
      <ErrorText message={resource.error} />
      {resource.loading && <Text style={ui.text}>Đang tải…</Text>}
      {matches?.length === 0 && (
        <Text style={ui.text}>Chưa có dịch vụ phù hợp.</Text>
      )}
      {groups.map((group) => (
        <View key={group || "all"} style={{ gap: 14 }}>
          {!!group && <Text style={ui.heading}>{group}</Text>}
          <View style={[ui.row, { alignItems: "stretch" }]}>
            {prioritizeSponsoredCatalog(
              matches?.filter(
                (item) =>
                  !group ||
                  (itemProvince(item) || "Chưa xác định tỉnh/thành") === group,
              ) || [],
              sponsored.data,
              kind === "tours" ? "TOUR" : "HOTEL",
            ).map(({ item, campaign }) => (
                <View key={item.id} style={{ flexBasis: 280, flexGrow: 1, minWidth: 0, maxWidth: 540 }}>
                  <Card hoverSurface>
                    {campaign && (
                      <View style={{ flexDirection: "row", alignItems: "center", justifyContent: "space-between", gap: 6 }}>
                        <View
                          style={{
                            backgroundColor: MONETIZATION_ACCENTS.sponsoredBg,
                            borderColor: MONETIZATION_ACCENTS.sponsoredBorder,
                            borderWidth: 1,
                            paddingHorizontal: 10,
                            paddingVertical: 4,
                            borderRadius: 999,
                          }}
                        >
                          <Text style={{ fontSize: 12, fontWeight: "800", color: MONETIZATION_ACCENTS.sponsoredText }}>
                            ✦ {campaign.sponsoredLabel}
                          </Text>
                        </View>
                        <Text style={{ fontSize: 12, color: "#075985", fontWeight: "700", flexShrink: 1 }} numberOfLines={1}>
                          {campaign.headline}
                        </Text>
                      </View>
                    )}
                    <CatalogPhoto key={`${item.id}:${item.image || ""}`} source={item.image} fallback={kind === "tours" ? destinationFor(item.location, places.data || [])?.imageUrl : undefined} label={item.name} />
                    <Pressable
                      onPress={() => {
                        if (campaign) {
                          void api(`/monetization/sponsored/${encodeURIComponent(campaign.id)}/click`, { method: "POST" }).catch(() => {});
                        }
                      }}
                    >
                      <Nav
                        href={{
                          pathname:
                            kind === "tours"
                              ? "/booking/tours/[id]"
                              : "/booking/hotels/[id]",
                          params: { id: item.id },
                        }}
                      >
                        {item.name}
                      </Nav>
                    </Pressable>
                    <Text style={ui.text}>{item.location}</Text>
                    {item.rating != null && (
                      <Text style={ui.text}>
                        Điểm danh mục: {item.rating} ★
                      </Text>
                    )}
                    {item.price != null && (() => {
                      const memberPricing = computeMemberPrice(
                        item.price,
                        membership.discountPercent,
                      );
                      const teaser = vipTeaserPrice(item.price);
                      if (membership.isPremium && memberPricing.hasDiscount) {
                        return (
                          <View
                            style={[
                              styles.vipPriceBox,
                              {
                                backgroundColor: palette.cardBg,
                                borderColor: palette.cardBorder,
                              },
                            ]}
                          >
                            <View style={{ flexDirection: "row", alignItems: "center", justifyContent: "space-between", flexWrap: "wrap", gap: 6 }}>
                              <Text style={styles.strikePrice}>
                                Giá gốc: {money(memberPricing.originalPrice)}
                              </Text>
                              <View style={styles.saveBadge}>
                                <Text style={styles.saveBadgeText}>
                                  Tiết kiệm {money(memberPricing.savedAmount)}
                                </Text>
                              </View>
                            </View>
                            <Text style={[styles.vipPriceText, { color: palette.priceText }]}>
                              {membership.badge} (-{membership.discountPercent}%):{" "}
                              {money(memberPricing.memberPrice)}
                            </Text>
                          </View>
                        );
                      }
                      return (
                        <View style={{ gap: 6 }}>
                          <Text style={styles.price}>
                            Giá tham khảo: {money(item.price)}
                          </Text>
                          {teaser.savedAmount > 0 && (
                            <Nav href="/subscriptions">
                              {`🔒 Giá VIP chỉ ${money(teaser.vipPrice)} (-10%) →`}
                            </Nav>
                          )}
                        </View>
                      );
                    })()}
                    <Text style={styles.hint}>
                      Giá và chỗ trống được xác nhận khi chọn lịch.
                    </Text>
                  </Card>
                </View>
              ))}
          </View>
        </View>
      ))}
    </Screen>
  );
}
const styles = StyleSheet.create({
  toolbar: { flexDirection: "row", alignItems: "center", gap: 12 },
  icon: {
    height: 46,
    width: 46,
    borderRadius: 14,
    alignItems: "center",
    justifyContent: "center",
    backgroundColor: "#e7efdf",
  },
  hint: { color: "#64748b", fontSize: 13, lineHeight: 20, flexShrink: 1 },
  filterToggle: { flexDirection: "row", alignItems: "center", gap: 8 },
  filterText: { color: "#087fba", fontWeight: "700", flex: 1 },
  chips: { flexDirection: "row", flexWrap: "wrap", gap: 8 },
  chip: {
    borderWidth: 1,
    borderColor: "#dce6f4",
    borderRadius: 20,
    paddingHorizontal: 12,
    paddingVertical: 8,
    backgroundColor: "#fff",
  },
  chipSelected: { backgroundColor: "#e0ebce", borderColor: "#9dbdff" },
  chipText: { color: "#334155", fontSize: 13 },
  price: { color: "#075782", fontWeight: "700" },
  vipPriceBox: {
    borderWidth: 1.5,
    borderRadius: 14,
    padding: 12,
    gap: 6,
  },
  strikePrice: {
    color: "#64748b",
    fontSize: 12,
    fontWeight: "600",
    textDecorationLine: "line-through",
  },
  saveBadge: {
    backgroundColor: MONETIZATION_ACCENTS.savingsBg,
    borderColor: MONETIZATION_ACCENTS.savingsBorder,
    borderWidth: 1,
    paddingHorizontal: 8,
    paddingVertical: 3,
    borderRadius: 999,
  },
  saveBadgeText: {
    color: MONETIZATION_ACCENTS.savingsText,
    fontSize: 11,
    fontWeight: "800",
  },
  vipPriceText: { fontSize: 16, fontWeight: "900" },
});

export interface SponsoredItemLike {
  id: string;
  targetKind: "TOUR" | "HOTEL";
  targetId: number;
  headline: string;
  sponsoredLabel: string;
}

export type TierCode = "FREE" | "PLUS" | "VIP";

export interface TierPalette {
  code: TierCode;
  cardBg: string;
  cardBorder: string;
  cardBorderSelected: string;
  headingText: string;
  bodyText: string;
  priceText: string;
  mutedText: string;
  badgeBg: string;
  badgeBorder: string;
  badgeText: string;
  heroBg: string;
  heroSubBg: string;
  heroBorder: string;
  heroEyebrow: string;
  heroTitle: string;
  heroBody: string;
  heroStatLabel: string;
  heroStatValue: string;
  ctaBg: string;
  ctaText: string;
  heroCtaBg: string;
  heroCtaText: string;
  ctaSecondaryBg: string;
  ctaSecondaryBorder: string;
  ctaSecondaryText: string;
}

export const TIER_PALETTES: Record<TierCode, TierPalette> = {
  FREE: {
    code: "FREE",
    cardBg: "#f8fafc",
    cardBorder: "#cbd5e1",
    cardBorderSelected: "#64748b",
    headingText: "#103c5b",
    bodyText: "#334155",
    priceText: "#1e293b",
    mutedText: "#475569",
    badgeBg: "#f1f5f9",
    badgeBorder: "#cbd5e1",
    badgeText: "#334155",
    heroBg: "#103c5b",
    heroSubBg: "#164c73",
    heroBorder: "#d97706",
    heroEyebrow: "#fde68a",
    heroTitle: "#ffffff",
    heroBody: "#e2e8f0",
    heroStatLabel: "#e0f2fe",
    heroStatValue: "#fde68a",
    ctaBg: "#075985",
    ctaText: "#ffffff",
    heroCtaBg: "#fcd34d",
    heroCtaText: "#0b253c",
    ctaSecondaryBg: "#fffbeb",
    ctaSecondaryBorder: "#d97706",
    ctaSecondaryText: "#92400e",
  },
  PLUS: {
    code: "PLUS",
    cardBg: "#f0f9ff",
    cardBorder: "#7dd3fc",
    cardBorderSelected: "#0284c7",
    headingText: "#0c4a6e",
    bodyText: "#1e3a5f",
    priceText: "#075985",
    mutedText: "#475569",
    badgeBg: "#e0f2fe",
    badgeBorder: "#0284c7",
    badgeText: "#075985",
    heroBg: "#0c4a6e",
    heroSubBg: "#075985",
    heroBorder: "#38bdf8",
    heroEyebrow: "#bae6fd",
    heroTitle: "#ffffff",
    heroBody: "#e0f2fe",
    heroStatLabel: "#e0f2fe",
    heroStatValue: "#ffffff",
    ctaBg: "#075985",
    ctaText: "#ffffff",
    heroCtaBg: "#e0f2fe",
    heroCtaText: "#0c4a6e",
    ctaSecondaryBg: "#e0f2fe",
    ctaSecondaryBorder: "#38bdf8",
    ctaSecondaryText: "#075985",
  },
  VIP: {
    code: "VIP",
    cardBg: "#fffdf5",
    cardBorder: "#f59e0b",
    cardBorderSelected: "#b45309",
    headingText: "#103c5b",
    bodyText: "#334155",
    priceText: "#92400e",
    mutedText: "#475569",
    badgeBg: "#0b253c",
    badgeBorder: "#d97706",
    badgeText: "#fcd34d",
    heroBg: "#0b253c",
    heroSubBg: "#123452",
    heroBorder: "#f59e0b",
    heroEyebrow: "#fcd34d",
    heroTitle: "#ffffff",
    heroBody: "#e2e8f0",
    heroStatLabel: "#e2e8f0",
    heroStatValue: "#fcd34d",
    ctaBg: "#0b253c",
    ctaText: "#fcd34d",
    heroCtaBg: "#fcd34d",
    heroCtaText: "#0b253c",
    ctaSecondaryBg: "#fef3c7",
    ctaSecondaryBorder: "#d97706",
    ctaSecondaryText: "#78350f",
  },
};

export const MONETIZATION_ACCENTS = {
  savingsBg: "#dcfce7",
  savingsBorder: "#86efac",
  savingsText: "#166534",
  savingsOnDark: "#6ee7b7",
  sponsoredBg: "#eff6ff",
  sponsoredBorder: "#93c5fd",
  sponsoredText: "#1d4ed8",
} as const;

export function getTierPalette(tier?: string | null): TierPalette {
  const normalized = (tier || "FREE").trim().toUpperCase();
  if (normalized === "VIP") return TIER_PALETTES.VIP;
  if (normalized === "PLUS") return TIER_PALETTES.PLUS;
  return TIER_PALETTES.FREE;
}

function relativeLuminance(hex: string): number {
  const clean = hex.replace("#", "").trim();
  const full =
    clean.length === 3
      ? clean
          .split("")
          .map((ch) => ch + ch)
          .join("")
      : clean;
  const r = parseInt(full.slice(0, 2), 16) / 255;
  const g = parseInt(full.slice(2, 4), 16) / 255;
  const b = parseInt(full.slice(4, 6), 16) / 255;
  const channel = (c: number) => (c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4);
  return 0.2126 * channel(r) + 0.7152 * channel(g) + 0.0722 * channel(b);
}

export function wcagContrastRatio(foregroundHex: string, backgroundHex: string): number {
  const l1 = relativeLuminance(foregroundHex);
  const l2 = relativeLuminance(backgroundHex);
  const lighter = Math.max(l1, l2);
  const darker = Math.min(l1, l2);
  return Math.round(((lighter + 0.05) / (darker + 0.05)) * 100) / 100;
}

export interface TimelineCategoryStyle {
  icon:
    | "sunny-outline"
    | "airplane-outline"
    | "restaurant-outline"
    | "bed-outline"
    | "compass-outline"
    | "map-outline"
    | "camera-outline";
  bg: string;
  fg: string;
}

export function timelineCategoryVisual(category?: string | null): TimelineCategoryStyle {
  const key = (category || "SIGHT").trim().toUpperCase();
  switch (key) {
    case "WAKE":
      return { icon: "sunny-outline", bg: "#fef3c7", fg: "#92400e" };
    case "TRANSIT":
      return { icon: "airplane-outline", bg: "#e0f2fe", fg: "#075985" };
    case "FOOD":
      return { icon: "restaurant-outline", bg: "#ede9fe", fg: "#5b21b6" };
    case "STAY":
      return { icon: "bed-outline", bg: "#ffedd5", fg: "#9a3412" };
    case "TOUR":
      return { icon: "map-outline", bg: "#e0f2fe", fg: "#0c4a6e" };
    case "NIGHT":
      return { icon: "camera-outline", bg: "#e2f3fc", fg: "#075782" };
    default:
      return { icon: "compass-outline", bg: "#dcfce7", fg: "#166534" };
  }
}

export function subscriptionBadgeLabel(tier?: string | null): string | null {
  if (!tier) return null;
  const normalized = tier.trim().toUpperCase();
  if (normalized === "PLUS") return "⭐ Plus";
  if (normalized === "VIP") return "💎 Explorer";
  return null;
}

export function headerProfileLabel(
  isLoggedIn: boolean,
  tier?: string | null,
  planName?: string | null,
): string {
  if (!isLoggedIn) return "Đăng nhập";
  const normalized = (tier || "FREE").trim().toUpperCase();
  if (normalized === "VIP") return "Explorer VIP";
  if (normalized === "PLUS") return "TravelAI Plus";
  if (planName && normalized !== "FREE") return planName;
  return "Hồ sơ";
}

export function calculateCtrPercent(impressions: number, clicks: number): number {
  if (!Number.isFinite(impressions) || impressions <= 0 || !Number.isFinite(clicks) || clicks <= 0) {
    return 0;
  }
  return Math.round((clicks / impressions) * 1000) / 10;
}

export function prioritizeSponsoredCatalog<T extends { id: number }>(
  items: T[],
  sponsored: SponsoredItemLike[] | null | undefined,
  kind: "TOUR" | "HOTEL",
): { item: T; campaign: SponsoredItemLike | null }[] {
  const byTarget = new Map<number, SponsoredItemLike>();
  for (const camp of sponsored || []) {
    if (camp.targetKind === kind && !byTarget.has(camp.targetId)) {
      byTarget.set(camp.targetId, camp);
    }
  }
  const promoted: { item: T; campaign: SponsoredItemLike | null }[] = [];
  const regular: { item: T; campaign: SponsoredItemLike | null }[] = [];
  for (const item of items) {
    const camp = byTarget.get(item.id) || null;
    if (camp) promoted.push({ item, campaign: camp });
    else regular.push({ item, campaign: null });
  }
  return [...promoted, ...regular];
}

export function computeMemberPrice(price: number, discountPercent = 0) {
  const safePrice = Number.isFinite(price) && price > 0 ? Math.round(price) : 0;
  const pct = Number.isFinite(discountPercent) && discountPercent > 0 ? discountPercent : 0;
  if (safePrice === 0 || pct === 0) {
    return {
      originalPrice: safePrice,
      memberPrice: safePrice,
      savedAmount: 0,
      hasDiscount: false,
    };
  }
  const savedAmount = Math.floor((safePrice * pct + 50) / 100);
  const memberPrice = Math.max(0, safePrice - savedAmount);
  return {
    originalPrice: safePrice,
    memberPrice,
    savedAmount,
    hasDiscount: savedAmount > 0,
  };
}

export function vipTeaserPrice(price: number, vipPercent = 10) {
  const result = computeMemberPrice(price, vipPercent);
  return {
    vipPrice: result.memberPrice,
    savedAmount: result.savedAmount,
    vipPercent,
  };
}

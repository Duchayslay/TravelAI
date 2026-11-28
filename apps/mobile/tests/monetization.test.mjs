import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";
import {
  MONETIZATION_ACCENTS,
  TIER_PALETTES,
  calculateCtrPercent,
  computeMemberPrice,
  getTierPalette,
  headerProfileLabel,
  prioritizeSponsoredCatalog,
  subscriptionBadgeLabel,
  timelineCategoryVisual,
  vipTeaserPrice,
  wcagContrastRatio,
} from "../src/services/monetization-helpers.ts";

test("subscriptionBadgeLabel returns badge for PLUS and VIP tiers only", () => {
  assert.equal(subscriptionBadgeLabel("FREE"), null);
  assert.equal(subscriptionBadgeLabel(undefined), null);
  assert.equal(subscriptionBadgeLabel("PLUS"), "⭐ Plus");
  assert.equal(subscriptionBadgeLabel("VIP"), "💎 Explorer");
});

test("headerProfileLabel replaces Hồ sơ with concise tier name when subscribed", () => {
  assert.equal(headerProfileLabel(false, "VIP", "TravelAI Explorer VIP"), "Đăng nhập");
  assert.equal(headerProfileLabel(true, "FREE", "Cơ Bản (Free)"), "Hồ sơ");
  assert.equal(headerProfileLabel(true, "PLUS", "TravelAI Plus"), "TravelAI Plus");
  assert.equal(headerProfileLabel(true, "VIP", "TravelAI Explorer VIP"), "Explorer VIP");
});

test("calculateCtrPercent computes safe rounded click-through rate", () => {
  assert.equal(calculateCtrPercent(0, 0), 0);
  assert.equal(calculateCtrPercent(1000, 85), 8.5);
  assert.equal(calculateCtrPercent(12, 3), 25);
});

test("prioritizeSponsoredCatalog pins active sponsored items first without duplicates", () => {
  const items = [
    { id: 1, name: "Tour thường A", price: 100000, rating: 4.5, location: "Đà Nẵng" },
    { id: 2, name: "Tour tài trợ B", price: 200000, rating: 4.8, location: "Đà Nẵng" },
    { id: 3, name: "Tour thường C", price: 150000, rating: 4.2, location: "Huế" },
  ];
  const sponsored = [
    {
      id: "camp-1",
      targetKind: "TOUR",
      targetId: 2,
      headline: "Ưu đãi hè",
      sponsoredLabel: "Được tài trợ",
    },
  ];
  const ordered = prioritizeSponsoredCatalog(items, sponsored, "TOUR");
  assert.deepEqual(
    ordered.map((entry) => ({ id: entry.item.id, sponsored: Boolean(entry.campaign) })),
    [
      { id: 2, sponsored: true },
      { id: 1, sponsored: false },
      { id: 3, sponsored: false },
    ],
  );
});

test("computeMemberPrice calculates exact discounted VND and savings for Plus and VIP", () => {
  assert.deepEqual(computeMemberPrice(500_000, 0), {
    originalPrice: 500_000,
    memberPrice: 500_000,
    savedAmount: 0,
    hasDiscount: false,
  });
  assert.deepEqual(computeMemberPrice(500_000, 5), {
    originalPrice: 500_000,
    memberPrice: 475_000,
    savedAmount: 25_000,
    hasDiscount: true,
  });
  assert.deepEqual(computeMemberPrice(500_000, 10), {
    originalPrice: 500_000,
    memberPrice: 450_000,
    savedAmount: 50_000,
    hasDiscount: true,
  });
});

test("vipTeaserPrice shows 10% Explorer VIP savings to free users", () => {
  const teaser = vipTeaserPrice(1_200_000);
  assert.equal(teaser.vipPrice, 1_080_000);
  assert.equal(teaser.savedAmount, 120_000);
});

test("_layout Link asChild children flatten array styles for Expo Router Slot", () => {
  const dir = path.dirname(fileURLToPath(import.meta.url));
  const layoutSrc = fs.readFileSync(path.join(dir, "../src/app/_layout.tsx"), "utf8");
  const asChildBlocks = layoutSrc.match(/<Link[^>]*asChild[^>]*>\s*<Pressable[\s\S]*?>/g) ?? [];
  assert.ok(asChildBlocks.length >= 2);
  for (const block of asChildBlocks) {
    assert.equal(
      /style=\{\s*\[/.test(block),
      false,
      `Found unflattened array style on Link asChild child:\n${block}`,
    );
  }
});

test("every subscription tier palette and monetization pill meets WCAG AA contrast (>= 4.5:1)", () => {
  assert.equal(getTierPalette("VIP").code, "VIP");
  assert.equal(getTierPalette("plus").code, "PLUS");
  assert.equal(getTierPalette(undefined).code, "FREE");

  for (const [tier, p] of Object.entries(TIER_PALETTES)) {
    const pairs = [
      ["card heading", p.headingText, p.cardBg],
      ["card body", p.bodyText, p.cardBg],
      ["card price", p.priceText, p.cardBg],
      ["card muted", p.mutedText, p.cardBg],
      ["badge", p.badgeText, p.badgeBg],
      ["hero eyebrow", p.heroEyebrow, p.heroBg],
      ["hero title", p.heroTitle, p.heroBg],
      ["hero body", p.heroBody, p.heroBg],
      ["hero stat label", p.heroStatLabel, p.heroSubBg],
      ["hero stat value", p.heroStatValue, p.heroSubBg],
      ["light cta", p.ctaText, p.ctaBg],
      ["hero cta", p.heroCtaText, p.heroCtaBg],
      ["secondary cta", p.ctaSecondaryText, p.ctaSecondaryBg],
    ];
    for (const [name, fg, bg] of pairs) {
      const ratio = wcagContrastRatio(fg, bg);
      assert.ok(
        ratio >= 4.5,
        `${tier} ${name} (${fg} on ${bg}) contrast ${ratio}:1 is below WCAG AA 4.5:1`,
      );
    }
  }

  assert.ok(wcagContrastRatio(MONETIZATION_ACCENTS.savingsText, MONETIZATION_ACCENTS.savingsBg) >= 4.5);
  assert.ok(wcagContrastRatio(MONETIZATION_ACCENTS.sponsoredText, MONETIZATION_ACCENTS.sponsoredBg) >= 4.5);
  assert.ok(wcagContrastRatio(MONETIZATION_ACCENTS.savingsOnDark, TIER_PALETTES.VIP.heroSubBg) >= 4.5);
  assert.ok(wcagContrastRatio(MONETIZATION_ACCENTS.savingsOnDark, TIER_PALETTES.PLUS.heroSubBg) >= 4.5);

  for (const cat of ["WAKE", "TRANSIT", "FOOD", "STAY", "SIGHT", "TOUR", "NIGHT"]) {
    const v = timelineCategoryVisual(cat);
    assert.ok(
      wcagContrastRatio(v.fg, v.bg) >= 4.5,
      `Timeline badge ${cat} (${v.fg} on ${v.bg}) must meet WCAG AA contrast`,
    );
  }
});




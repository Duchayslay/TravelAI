import { HoverFeedback } from "@/components/hover-feedback";
import { MotionIcon } from "@/components/motion-icon";
import { DepthStage } from "@/components/depth-stage";
import { Image } from "expo-image";
import { useHydrated } from "@/hooks/use-hydrated";
import { useCompactNavigation } from "@/hooks/use-compact-navigation";
import { primaryTabs } from "@/navigation/tabs";
import { Ionicons } from "@expo/vector-icons";
import { DefaultTheme, ThemeProvider, Link, Stack, router, usePathname, useSegments, type Href } from "expo-router";
import { StatusBar } from "expo-status-bar";
import { useEffect, useRef, useState } from "react";
import {
  Pressable,
  StyleSheet,
  Text,
  useWindowDimensions,
  View,
} from "react-native";
import Animated, {
  useAnimatedStyle,
  useReducedMotion,
  useSharedValue,
  withSequence,
  withTiming,
} from "react-native-reanimated";
import {
  SafeAreaProvider,
  useSafeAreaInsets,
} from "react-native-safe-area-context";
import { AuthProvider, useAuth } from "@/context/auth";
import { MembershipProvider, useMembership } from "@/context/membership";
import { Support } from "@/components/support";
import { Nav, ui } from "@/components/ui";
import { useRealtime } from "@/hooks/use-realtime";
import { useResource } from "@/hooks/use-resource";
import { TIER_PALETTES, headerProfileLabel } from "@/services/monetization-helpers";
import type { Settings } from "@/services/types";

const navigation = primaryTabs.map(tab => ({ title: tab.label, href: tab.route }));

function NotificationBell() {
  const { user } = useAuth();
  const unread = useResource<{ count: number }>(
    user ? `/notifications/unread/${user.id}` : null,
    5000,
  );
  const adminUnread = useResource<{ count: number }>(
    user?.role === "ADMIN" ? "/notifications/unread/0" : null,
    5000,
  );
  const rotation = useSharedValue(0);
  const reducedMotion = useReducedMotion();
  const previous = useRef<number | null>(null);
  const count = (unread.data?.count || 0) + (adminUnread.data?.count || 0);
  useRealtime(() => {
    unread.reload();
    adminUnread.reload();
  });
  useEffect(() => {
    if (
      !reducedMotion &&
      previous.current !== null &&
      count > previous.current
    ) {
      rotation.value = withSequence(
        withTiming(-14, { duration: 80 }),
        withTiming(14, { duration: 120 }),
        withTiming(0, { duration: 90 }),
      );
    }
    previous.current = count;
  }, [count, reducedMotion, rotation]);
  const motion = useAnimatedStyle(() => ({
    transform: [{ rotate: `${rotation.value}deg` }],
  }));
  return (
    <Link href="/notifications" asChild>
      <Pressable
        accessibilityRole="link"
        accessibilityLabel={`Thông báo, ${count} chưa đọc`}
        style={styles.iconButton}
      >
        <Animated.View style={motion}>
          <Ionicons name="notifications-outline" size={23} color="#334155" />
        </Animated.View>
        {count > 0 && (
          <View style={styles.badge}>
            <Text style={styles.badgeText}>{count > 99 ? "99+" : count}</Text>
          </View>
        )}
      </Pressable>
    </Link>
  );
}

function AppShell() {
  const { user, loading: authLoading } = useAuth();
  const membership = useMembership();
  const [upgradeHovered, setUpgradeHovered] = useState(false);
  const [profileHovered, setProfileHovered] = useState(false);
  const insets = useSafeAreaInsets();
  const pathname = usePathname();
  const { width } = useWindowDimensions();
  const settings = useResource<Settings>("/system-settings", 30_000);
  const maintenance =
    !authLoading &&
    settings.data?.maintenanceMode &&
    user?.role !== "ADMIN" &&
    pathname !== "/login";
  const compact = useCompactNavigation();
  // Raw window width must not add brand markup before SSR hydration completes.
  const hydrated = useHydrated();
  const segments = useSegments();
  const insideTabs = segments[0] === "(tabs)";
  return (
    <View
      style={{
        flex: 1,
        backgroundColor: "#f2f8fc",
        paddingTop: insets.top,
        paddingBottom: insideTabs ? 0 : insets.bottom,
      }}
    >
      <HoverFeedback /><StatusBar style="dark" />
      <View style={styles.header}>
        <View style={styles.headerInner}>
          <View style={styles.headerTop}>
            {!insideTabs && <Pressable accessibilityRole="button" accessibilityLabel="Quay lại" style={styles.iconButton} onPress={() => router.canGoBack() ? router.back() : router.replace("/")}><Ionicons name="arrow-back" size={22} color="#087fba" /></Pressable>}
            <Link href="/" asChild>
              <Pressable
                accessibilityRole="link"
                accessibilityLabel="TravelAI, trang chủ"
                style={styles.brand}
              >
                <View style={styles.brandMark}>
                  <Ionicons name="compass" size={22} color="#fff" />
                </View>
                {(!compact || (hydrated && width >= 420)) && <Text style={styles.brandName}>
                  Travel<Text style={styles.brandAccent}>AI</Text>
                </Text>}
              </Pressable>
            </Link>
            {!compact && (
              <View style={styles.navRow}>
                {navigation.map((item) => (
                  <Link href={item.href} key={item.href} asChild>
                    <Pressable
                      accessibilityRole="link"
                      style={StyleSheet.flatten([
                        styles.navItem,
                        pathname === item.href && styles.navActive,
                      ])}
                    >
                      <Text
                        style={[
                          styles.navText,
                          pathname === item.href && styles.navTextActive,
                        ]}
                      >
                        {item.title}
                      </Text>
                    </Pressable>
                  </Link>
                ))}
                {user && !membership.isPremium && (
                  <Link href={"/subscriptions" as Href} asChild>
                    <Pressable
                      accessibilityRole="link"
                      onHoverIn={() => setUpgradeHovered(true)}
                      onHoverOut={() => setUpgradeHovered(false)}
                      onFocus={() => setUpgradeHovered(true)}
                      onBlur={() => setUpgradeHovered(false)}
                      style={StyleSheet.flatten([
                        styles.navItem,
                        pathname === "/subscriptions" && styles.navActive,
                        upgradeHovered && styles.navVipHover,
                      ])}
                    >
                      <Text
                        style={[
                          styles.navText,
                          pathname === "/subscriptions" && styles.navTextActive,
                          upgradeHovered && styles.navVipHoverText,
                        ]}
                      >
                        Nâng cấp VIP
                      </Text>
                    </Pressable>
                  </Link>
                )}
                {user && (
                  <Link href="/settings" asChild>
                    <Pressable style={styles.navItem}>
                      <Text style={styles.navText}>Cài đặt</Text>
                    </Pressable>
                  </Link>
                )}
                {user?.role === "ADMIN" && (
                  <Link href="/admin" asChild>
                    <Pressable style={styles.navItem}>
                      <Text style={styles.navText}>Quản trị</Text>
                    </Pressable>
                  </Link>
                )}
              </View>
            )}
            <View style={styles.headerActions}>
              {user && (
                <Link href="/messages" asChild>
                  <Pressable
                    accessibilityRole="link"
                    accessibilityLabel="Tin nhắn"
                    style={styles.iconButton}
                  >
                    <Ionicons
                      name="chatbubble-ellipses-outline"
                      size={22}
                      color="#334155"
                    />
                  </Pressable>
                </Link>
              )}
              <NotificationBell />
              {compact && <Support inline />}
              <Link href={user ? "/profile" : "/login"} asChild>
                <Pressable
                  accessibilityRole="link"
                  accessibilityLabel={
                    user
                      ? headerProfileLabel(true, membership.tier, membership.planName)
                      : "Đăng nhập"
                  }
                  onHoverIn={() => setProfileHovered(true)}
                  onHoverOut={() => setProfileHovered(false)}
                  onFocus={() => setProfileHovered(true)}
                  onBlur={() => setProfileHovered(false)}
                  style={StyleSheet.flatten([
                    styles.profileButton,
                    profileHovered &&
                      membership.isPremium &&
                      (membership.isVip ? styles.profileButtonVip : styles.profileButtonPlus),
                  ])}
                >
                  {user?.avatar ? (
                    <Image
                      key={user.avatar}
                      source={{ uri: user.avatar }}
                      contentFit="cover"
                      style={[
                        { width: 28, height: 28, borderRadius: 14 },
                        profileHovered &&
                          membership.isPremium && {
                            borderWidth: 2,
                            borderColor: membership.isVip
                              ? TIER_PALETTES.VIP.heroBorder
                              : TIER_PALETTES.PLUS.heroBorder,
                          },
                      ]}
                      accessibilityLabel="Ảnh đại diện của bạn"
                    />
                  ) : (
                    <Ionicons
                      name={
                        membership.isVip
                          ? "diamond-outline"
                          : membership.isPremium
                            ? "star-outline"
                            : "person-outline"
                      }
                      size={18}
                      color={
                        profileHovered && membership.isPremium
                          ? membership.isVip
                            ? TIER_PALETTES.VIP.badgeText
                            : TIER_PALETTES.PLUS.heroTitle
                          : "#075782"
                      }
                    />
                  )}
                  {!compact && (
                    <Text
                      style={[
                        styles.profileText,
                        profileHovered &&
                          membership.isPremium &&
                          (membership.isVip ? styles.profileTextVip : styles.profileTextPlus),
                      ]}
                    >
                      {headerProfileLabel(Boolean(user), membership.tier, membership.planName)}
                    </Text>
                  )}
                </Pressable>
              </Link>
            </View>
          </View>

        </View>
      </View>
      {maintenance ? (
        <View style={ui.content}>
          <Text style={ui.title}>Hệ thống đang bảo trì</Text>
          <Text style={ui.text}>Vui lòng quay lại sau.</Text>
          <Nav href="/login">Đăng nhập quản trị</Nav>
        </View>
      ) : (
        <DepthStage><ThemeProvider value={{ ...DefaultTheme, colors: { ...DefaultTheme.colors, background: "transparent" } }}><Stack screenOptions={{ headerShown: false, contentStyle: { backgroundColor: "transparent" } }}><Stack.Screen name="(tabs)" /></Stack></ThemeProvider></DepthStage>
      )}
      {!maintenance && !compact && <Support />}
      {!maintenance && !compact && pathname !== "/ai-chat" && (
        <Link href={"/ai-chat" as Href} asChild>
          <Pressable
            accessibilityRole="link"
            accessibilityLabel="Mở trợ lý du lịch AI"
            style={styles.aiButton}
          >
            <MotionIcon name="sparkles" color="#fff" size={26} />
          </Pressable>
        </Link>
      )}
    </View>
  );
}
export default function RootLayout() {
  return (
    <SafeAreaProvider>
      <AuthProvider>
        <MembershipProvider>
          <AppShell />
        </MembershipProvider>
      </AuthProvider>
    </SafeAreaProvider>
  );
}

const styles = StyleSheet.create({
  header: {
    backgroundColor: "#fff",
    borderBottomWidth: 1,
    borderBottomColor: "#e9eef7",
    zIndex: 5,
  },
  headerInner: {
    width: "100%",
    maxWidth: 1440,
    alignSelf: "center",
    paddingHorizontal: 20,
  },
  headerTop: {
    minHeight: 72,
    flexDirection: "row",
    alignItems: "center",
    justifyContent: "space-between",
    gap: 18,
  },
  brand: { flexDirection: "row", alignItems: "center", gap: 9 },
  brandMark: {
    width: 40,
    height: 40,
    borderRadius: 14,
    backgroundColor: "#087fba",
    alignItems: "center",
    justifyContent: "center",
  },
  brandName: {
    fontSize: 23,
    fontWeight: "900",
    letterSpacing: -0.8,
    color: "#16233d",
  },
  brandAccent: { color: "#087fba" },
  navRow: {
    flex: 1,
    flexDirection: "row",
    alignItems: "center",
    justifyContent: "center",
    gap: 4,
  },
  compactNav: { alignItems: "center", gap: 4, paddingBottom: 8 },
  navItem: { paddingVertical: 10, paddingHorizontal: 12, borderRadius: 12 },
  navActive: { backgroundColor: "#e2f3fc" },
  navText: { fontSize: 14, fontWeight: "600", color: "#65718a" },
  navTextActive: { color: "#087fba" },
  navVipHover: {
    backgroundColor: TIER_PALETTES.VIP.heroBg,
  },
  navVipHoverText: { color: TIER_PALETTES.VIP.badgeText, fontWeight: "800" },
  headerActions: { flexDirection: "row", alignItems: "center", gap: 5 },
  iconButton: {
    width: 43,
    height: 43,
    borderRadius: 14,
    alignItems: "center",
    justifyContent: "center",
  },
  profileButton: {
    minWidth: 42,
    height: 42,
    paddingHorizontal: 12,
    borderRadius: 14,
    flexDirection: "row",
    alignItems: "center",
    justifyContent: "center",
    gap: 7,
    backgroundColor: "#e2f3fc",
  },
  profileText: { color: "#075782", fontWeight: "700", fontSize: 13 },
  profileButtonPlus: {
    backgroundColor: TIER_PALETTES.PLUS.heroBg,
    borderWidth: 1.5,
    borderColor: TIER_PALETTES.PLUS.heroBorder,
  },
  profileTextPlus: { color: TIER_PALETTES.PLUS.heroTitle, fontWeight: "900" },
  profileButtonVip: {
    backgroundColor: TIER_PALETTES.VIP.heroBg,
    borderWidth: 1.5,
    borderColor: TIER_PALETTES.VIP.heroBorder,
  },
  profileTextVip: { color: TIER_PALETTES.VIP.badgeText, fontWeight: "900" },
  badge: {
    position: "absolute",
    right: 2,
    top: 2,
    minWidth: 18,
    height: 18,
    paddingHorizontal: 3,
    borderRadius: 9,
    backgroundColor: "#ef4444",
    alignItems: "center",
    justifyContent: "center",
    borderWidth: 1.5,
    borderColor: "#fff",
  },
  badgeText: { color: "#fff", fontSize: 9, fontWeight: "800" },
  aiButton: {
    position: "absolute",
    right: 20,
    bottom: 20,
    width: 58,
    height: 58,
    borderRadius: 29,
    backgroundColor: "#087fba",
    alignItems: "center",
    justifyContent: "center",
    shadowColor: "#075782",
    shadowOpacity: 0.3,
    shadowRadius: 12,
    shadowOffset: { width: 0, height: 5 },
    elevation: 8,
  },
  pressed: { opacity: 0.7 },
});

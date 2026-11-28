import { MotionEntry } from "@/components/motion-entry";
import { PinTabs } from "@/components/pin-ui";
import { BankPanel } from "@/components/bank-panel";
import { useRealtime } from "@/hooks/use-realtime";
import { Image } from "expo-image";
import * as ImagePicker from "expo-image-picker";
import { ImageManipulator, SaveFormat } from "expo-image-manipulator";
import { router } from "expo-router";
import { useState } from "react";
import { Pressable, Text, View } from "react-native";
import {
  Action,
  Card,
  ErrorText,
  Field,
  money,
  Nav,
  Screen,
  serviceTime,
  ui,
  useAction,
} from "@/components/ui";
import { useAuth } from "@/context/auth";
import { useMembership } from "@/context/membership";
import { useResource } from "@/hooks/use-resource";
import { api } from "@/services/api";
import {
  MONETIZATION_ACCENTS,
  getTierPalette,
} from "@/services/monetization-helpers";
import type { Bank, Booking, MonetizationSummary, Post, User } from "@/services/types";
function ProfileEditor({ user }: { user: User }) {
  const auth = useAuth();
  const action = useAction();
  const [name, setName] = useState(user.name);
  const [bio, setBio] = useState(user.bio);
  const [avatar, setAvatar] = useState(user.avatar);
  const [saved, setSaved] = useState(false);
  return (
    <Card>
      <Text style={ui.heading}>Thông tin cá nhân</Text>
      <Field label="Tên" value={name} onChangeText={setName} />
      <Field label="Giới thiệu" multiline value={bio} onChangeText={setBio} />
      {!!avatar && (
        <Image
          source={{ uri: avatar }}
          style={{ width: 88, height: 88, borderRadius: 44 }}
        />
      )}
      <Action
        secondary
        title="Chọn ảnh đại diện"
        disabled={action.busy}
        onPress={() =>
          action.run(async () => {
            const selected = await ImagePicker.launchImageLibraryAsync({
              mediaTypes: ["images"],
              allowsEditing: true,
              aspect: [1, 1],
              quality: 0.8,
            });
            if (selected.canceled) return;
            const image = ImageManipulator.manipulate(selected.assets[0].uri);
            image.resize({ width: 256, height: 256 });
            const rendered = await image.renderAsync();
            const result = await rendered.saveAsync({
              format: SaveFormat.JPEG,
              compress: 0.65,
              base64: true,
            });
            if (!result.base64 || result.base64.length > 199_970)
              throw new Error("Ảnh quá lớn, vui lòng chọn ảnh khác.");
            setAvatar(`data:image/jpeg;base64,${result.base64}`);
            setSaved(false);
          })
        }
      />
      <ErrorText message={action.error} />
      {saved && <Text style={ui.text}>Đã lưu hồ sơ.</Text>}
      <Action
        title="Lưu hồ sơ"
        disabled={action.busy || !name.trim()}
        onPress={() =>
          action.run(async () => {
            const response = await api<{ user: User }>(
              `/auth/profile/${user.id}`,
              { method: "PUT", body: { name, bio, avatar } },
            );
            auth.updateUser(response.user);
            setSaved(true);
          })
        }
      />
    </Card>
  );
}
interface LegacyBooking {
  id: number;
  tourName?: string;
  hotelName?: string;
  status: string;
  bookingDate?: string;
  checkIn?: string;
  checkOut?: string;
  guests: number;
}
export default function Profile() {
  const { user, signOut } = useAuth();
  const membership = useMembership();
  const palette = getTierPalette(membership.tier);
  const [tab, setTab] = useState("trips");
  const bookings = useResource<Booking[]>(user ? "/booking/bookings" : null, 0, true, user?.id);
  const bank = useResource<Bank>(user ? "/booking/bank" : null, 0, true, user?.id);
  const posts = useResource<Post[]>(user ? `/posts/user/${user.id}` : null, 0, true, user?.id);
  const tours = useResource<LegacyBooking[]>(
    user ? `/tour-bookings/user/${user.id}` : null, 0, true);
  const hotels = useResource<LegacyBooking[]>(
    user ? `/hotel-bookings/user/${user.id}` : null, 0, true);
  const follows = useResource<{ followers: number; following: number }>(
    user ? `/follows/stats/${user.id}` : null,
  );
  const monetization = useResource<MonetizationSummary>(
    user ? "/monetization/me" : null,
    0,
    true,
    user?.id,
  );
  const action = useAction();
  useRealtime(() => {
    bookings.reload();
    bank.reload();
    posts.reload();
  });
  return (
    <Screen title="Hồ sơ của bạn" protected>
      {membership.isPremium ? (
        <View
          style={{
            backgroundColor: palette.heroBg,
            borderRadius: 24,
            borderWidth: 2,
            borderColor: palette.heroBorder,
            padding: 22,
            gap: 12,
          }}
        >
          <View style={{ flexDirection: "row", justifyContent: "space-between", alignItems: "center", flexWrap: "wrap", gap: 8 }}>
            <Text style={{ color: palette.heroEyebrow, fontSize: 12, fontWeight: "900", letterSpacing: 1 }}>
              TRAVELAI EXCLUSIVE MEMBER CARD
            </Text>
            <View
              style={{
                backgroundColor: palette.ctaSecondaryBg,
                borderWidth: 1,
                borderColor: palette.ctaSecondaryBorder,
                paddingHorizontal: 12,
                paddingVertical: 4,
                borderRadius: 999,
              }}
            >
              <Text style={{ color: palette.ctaSecondaryText, fontSize: 12, fontWeight: "900" }}>
                {membership.badge} · {membership.planName}
              </Text>
            </View>
          </View>
          <Text style={{ color: palette.heroTitle, fontSize: 24, fontWeight: "900" }}>
            {user?.name || user?.email}
          </Text>
          {membership.expiresAt && (
            <Text style={{ color: palette.heroBody, fontSize: 14 }}>
              Hiệu lực đặc quyền đến: {serviceTime(membership.expiresAt)}
            </Text>
          )}
          <View style={{ flexDirection: "row", flexWrap: "wrap", gap: 10 }}>
            <View
              style={{
                flexGrow: 1,
                flexBasis: 130,
                backgroundColor: palette.heroSubBg,
                borderWidth: 1,
                borderColor: palette.heroBorder,
                borderRadius: 14,
                padding: 12,
              }}
            >
              <Text style={{ color: palette.heroStatLabel, fontSize: 11, fontWeight: "800" }}>
                CHIẾT KHẤU CỐ ĐỊNH
              </Text>
              <Text style={{ color: palette.heroStatValue, fontSize: 20, fontWeight: "900", marginTop: 3 }}>
                -{membership.discountPercent}%
              </Text>
            </View>
            <View
              style={{
                flexGrow: 1,
                flexBasis: 150,
                backgroundColor: palette.heroSubBg,
                borderWidth: 1,
                borderColor: palette.heroBorder,
                borderRadius: 14,
                padding: 12,
              }}
            >
              <Text style={{ color: palette.heroStatLabel, fontSize: 11, fontWeight: "800" }}>
                ĐÃ TIẾT KIỆM ĐẶT CHỖ
              </Text>
              <Text style={{ color: MONETIZATION_ACCENTS.savingsOnDark, fontSize: 18, fontWeight: "900", marginTop: 3 }}>
                {money(membership.totalSavedVnd)}
              </Text>
            </View>
            <View
              style={{
                flexGrow: 1,
                flexBasis: 130,
                backgroundColor: palette.heroSubBg,
                borderWidth: 1,
                borderColor: palette.heroBorder,
                borderRadius: 14,
                padding: 12,
              }}
            >
              <Text style={{ color: palette.heroStatLabel, fontSize: 11, fontWeight: "800" }}>
                AI & VIP CONCIERGE
              </Text>
              <Text style={{ color: palette.heroTitle, fontSize: 16, fontWeight: "800", marginTop: 4 }}>
                Không giới hạn
              </Text>
            </View>
          </View>
          <View style={{ flexDirection: "row", flexWrap: "wrap", gap: 10, marginTop: 2 }}>
            <Pressable
              accessibilityRole="button"
              onPress={() => router.push("/subscriptions")}
              style={{
                backgroundColor: palette.heroCtaBg,
                paddingHorizontal: 16,
                paddingVertical: 10,
                borderRadius: 12,
              }}
            >
              <Text style={{ color: palette.heroCtaText, fontWeight: "900", fontSize: 13 }}>
                👑 Quản lý gói · VIP Lounge & Quảng cáo →
              </Text>
            </Pressable>
          </View>
        </View>
      ) : (
        <Card section="profile-intro">
          <Text style={ui.heading}>
            {user?.name ? `Xin chào, ${user.name}` : "Góc hành trình của bạn"} · Hạng Cơ Bản (Free)
          </Text>
          <Text style={ui.text}>
            AI hôm nay: {monetization.data ? `${monetization.data.aiQuota.usedToday}/${monetization.data.aiQuota.dailyLimit} lượt miễn phí` : "Giới hạn 10 lượt/ngày"}. Nâng cấp lên TravelAI Plus hoặc Explorer VIP để sở hữu Thẻ Đen, giảm trực tiếp 5%–10% mọi chuyến đi và mở khóa AI VIP Concierge!
          </Text>
          <Nav href="/subscriptions">✨ Xem đặc quyền & Nâng cấp Hội viên VIP →</Nav>
        </Card>
      )}
      <Card section="profile-tools"><Text style={ui.heading}>Tài khoản và tiện ích</Text><View style={ui.row}>
        <Nav href="/subscriptions">Gói VIP & Quảng cáo</Nav>
        <Nav href="/messages">Tin nhắn</Nav><Nav href="/notifications">Thông báo</Nav><Nav href="/settings">Cài đặt</Nav>
        <Nav href="/ai-chat">Trợ lý AI</Nav><Nav href="/policy">Chính sách</Nav>
        {user?.role === "ADMIN" && <Nav href="/admin">Quản trị</Nav>}
      </View></Card>
      <PinTabs value={tab} onChange={setTab} tabs={[{ id: "trips", label: "Chuyến đi", icon: "compass-outline" }, { id: "profile", label: "Hồ sơ", icon: "person-outline" }, { id: "posts", label: "Bài viết", icon: "images-outline" }, { id: "bank", label: "Ví thử nghiệm", icon: "wallet-outline" }]} />
      {tab === "profile" && user && <ProfileEditor key={user.id} user={user} />}
      <ErrorText
        message={
          bookings.error ||
          bank.error ||
          posts.error ||
          follows.error ||
          tours.error ||
          hotels.error ||
          action.error
        }
      />
      <Text style={ui.text}>
        {follows.data?.followers ?? 0} người theo dõi · đang theo dõi{" "}
        {follows.data?.following ?? 0} người
      </Text>
      {tab === "trips" && <>
      {(["TOUR", "HOTEL"] as const).map((kind) => (
        <View key={kind} style={{ gap: 14 }}>
          <MotionEntry section={`profile-${kind}`}><Text style={ui.heading}>
            {kind === "TOUR" ? "Tour đã đặt" : "Khách sạn đã đặt"}
          </Text></MotionEntry>
          {bookings.data
            ?.filter(
              (booking) =>
                booking.ownerId === user?.id && booking.itinerary.kind === kind,
            )
            .map((booking) => (
              <Card key={booking.id}>
                <Text style={ui.heading}>{booking.itinerary.name}</Text>
                <Text style={ui.text}>
                  {booking.status} · {serviceTime(booking.itinerary.start)} ·{" "}
                  {booking.itinerary.selection.guests} khách
                </Text>
                <Text style={ui.text}>
                  Tổng thanh toán {money(booking.totalPaid)}
                </Text>
                <Nav
                  href={{
                    pathname: "/bookings/[id]",
                    params: { id: booking.id },
                  }}
                >
                  Quản lý lịch trình →
                </Nav>
              </Card>
            ))}
          {bookings.data?.filter(
            (booking) =>
              booking.ownerId === user?.id && booking.itinerary.kind === kind,
          ).length === 0 && (
            <Text style={ui.text}>Chưa có chuyến đi trong mục này.</Text>
          )}
        </View>
      ))}
      {[...(tours.data || []), ...(hotels.data || [])].map((booking) => (
        <Card key={`${booking.tourName ? "T" : "H"}${booking.id}`}>
          <Text style={ui.heading}>
            {booking.tourName || booking.hotelName}
          </Text>
          <Text style={ui.text}>
            Hồ sơ cũ · {booking.status} ·{" "}
            {booking.bookingDate || booking.checkIn} · {booking.guests} khách
          </Text>
        </Card>
      ))}
      </>}
      {tab === "bank" && <BankPanel data={bank.data} reload={bank.reload} />}
      {tab === "posts" && <>
      <Text style={ui.heading}>Bài viết của tôi</Text>
      {posts.data?.map((post) => (
        <Card key={post.id}>
          <Text style={ui.text}>{post.content}</Text>
          <Text style={ui.text}>{post.status}</Text>
          <Nav href={{ pathname: "/feed", params: { edit: post.id } }}>
            Xem và sửa bài viết
          </Nav>
        </Card>
      ))}
      {posts.data?.length === 0 && <Text style={ui.text}>Chưa có bài viết nào. Chia sẻ kỷ niệm đầu tiên của bạn trong cộng đồng.</Text>}
      </>}
      <Action
        secondary
        title="Đăng xuất"
        disabled={action.busy}
        onPress={() => action.run(signOut)}
      />
    </Screen>
  );
}

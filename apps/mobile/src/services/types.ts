export interface ImmersiveScene {
  label: string;
  panoramaUrl: string;
  attribution: string;
  sourceUrl: string;
  category: "area" | "building" | "room";
}
export interface User {
  id: number;
  name: string;
  email: string;
  role: string;
  status: string;
  bio: string;
  avatar: string;
}
export interface Place {
  scenes?: ImmersiveScene[];
  latitude?: number;
  longitude?: number;
  id: number;
  name: string;
  description: string;
  imageUrl?: string;
  landmarkCode?: string | null;
  province?: string | null;
  imageAttribution?: string | null;
}
export interface CatalogItem {
  scenes?: ImmersiveScene[];
  id: number;
  name: string;
  location: string;
  province?: string | null;
  description?: string;
  image?: string;
  price: number;
  rating: number;
  durationDays?: number;
  maxGuests?: number;
  rooms?: number;
  status?: string;
}
export interface Policy {
  cancellationHours: number;
  reschedulingHours: number;
  graceMinutes: number;
  holdMinutes: number;
  holidayReschedulingHours: number | null;
}
export interface Departure {
  start: string;
  end?: string | null;
  capacity: number;
  seats: string[];
  price: number;
}
export interface Room {
  id: string;
  number: string;
  maxGuests: number;
  price: number;
  nightlyPrices?: Record<string, number>;
}
export interface Offer {
  id: string;
  kind: "TOUR" | "HOTEL";
  legacyId: number | null;
  name: string;
  active: boolean;
  policy: Policy;
  checkInTime: string;
  checkOutTime: string;
  departures: Departure[];
  rooms: Room[];
  promotion?: { from: string; through: string; percent: number } | null;
  version: string;
}
export interface Selection {
  offeringId: string;
  bookingId?: string;
  bookingRevision?: number;
  departure?: string;
  checkIn?: string;
  checkOut?: string;
  guests: number;
  seats: string[];
  roomGuests: Record<string, number>;
  replacementRooms: Record<string, string>;
}
export interface Itinerary {
  resources: {
    key: string;
    quantity: number;
    capacity: number;
    start: string;
  }[];
  selection: Selection;
  offeringVersion: string;
  name: string;
  kind: "TOUR" | "HOTEL";
  start: string;
  end: string;
  timezone: string;
  policy: Policy;
  roomPrices: Record<string, number[]>;
  itineraryPrice: number;
  changeHours: number;
}
export interface Booking {
  id: string;
  ownerId: number;
  status: string;
  revision: number;
  originalAmount: number;
  totalPaid: number;
  itinerary: Itinerary;
  sourceAccountId: string;
}
export interface NightCharge {
  room: string;
  date: string;
  baseline: number | null;
  price: number;
  holiday: boolean;
  additional: number;
  added: boolean;
}
export interface Review {
  booking?: Booking;
  id: string;
  digest: string;
  status: string;
  expiresAt: string;
  originalItinerary?: Itinerary;
  sourceAccountId?: string;
  quote: {
    itinerary: Itinerary;
    originalAmount: number;
    fee: number;
    holidayDifference: number;
    total: number;
    nights: NightCharge[];
    holiday: boolean;
    calendarVersions: Record<string, string>;
    memberDiscountPercent?: number;
    memberDiscount?: number;
    memberTier?: string;
  };
}
export interface Bank {
  balance: number;
  accountId?: string;
  cards: {
    cardId: string;
    bankName: string;
    status: string;
    expiryMonth: number;
    expiryYear: number;
  }[];
  transactions: {
    reference: string;
    bookingId: string;
    kind: string;
    amount: number;
    createdAt: string;
  }[];
  paymentAttempts: {
    id: string;
    reviewId: string;
    bookingId?: string | null;
    status: "FAILED" | "SUCCEEDED";
    reason: string;
    amount: number;
    createdAt: string;
  }[];
}
export interface Post {
  id: number;
  authorId: number;
  authorName: string;
  authorAvatar?: string;
  authorTier?: string;
  authorBadge?: string | null;
  content: string;
  image?: string;
  status?: string;
  liked?: boolean;
  likesCount?: number;
  shareCount?: number;
  comments?: { id: number; userName: string; text: string }[];
}
export interface Message {
  id: number;
  senderId: number;
  senderName: string;
  receiverId: number;
  receiverName: string;
  content: string;
  createdAt: string;
  isRead: boolean;
}
export interface Notification {
  id: number;
  title: string;
  message: string;
  type: string;
  targetId: number;
  targetReference?: string;
  isRead: boolean;
  createdAt: string;
}
export interface Settings {
  siteName: string;
  adminEmail: string;
  maintenanceMode: boolean;
  emailNotify: boolean;
  systemNotify: boolean;
}
export interface SubscriptionPlanItem {
  code: string;
  name: string;
  priceVnd: number;
  durationDays: number;
  discountPercent: number;
  waiveFirstRescheduleFee: boolean;
  unlimitedAi: boolean;
  badge: string;
  description: string;
  active: boolean;
}
export interface AdPackageItem {
  code: string;
  name: string;
  priceVnd: number;
  durationDays: number;
  placement: "EXPLORE_DESTINATION" | "HOME_FEED" | "ALL";
  description: string;
  active: boolean;
}
export interface UserSubscriptionRecord {
  id?: string;
  userId?: number;
  userName?: string;
  userEmail?: string;
  planCode: string;
  planName: string;
  discountPercent: number;
  waiveFirstRescheduleFee: boolean;
  unlimitedAi: boolean;
  badge: string | null;
  status: string;
  paidAmount?: number;
  startsAt?: string | null;
  expiresAt?: string | null;
  createdAt?: string | null;
}
export interface SponsoredCampaign {
  id: string;
  ownerId: number;
  ownerName?: string;
  ownerEmail?: string;
  packageCode: string;
  packageName: string;
  placement: string;
  targetKind: "TOUR" | "HOTEL";
  targetId: number;
  targetName: string;
  targetLocation?: string | null;
  targetProvince?: string | null;
  targetPrice?: number | null;
  targetRating?: number | null;
  targetImage?: string | null;
  headline: string;
  sponsoredLabel: string;
  durationDays: number;
  status: string;
  paidAmount: number;
  impressions: number;
  clicks: number;
  adminNote?: string | null;
  startsAt?: string | null;
  endsAt?: string | null;
  createdAt?: string | null;
}
export interface AiQuotaStatus {
  tier: string;
  badge: string | null;
  unlimited: boolean;
  usedToday: number;
  dailyLimit: number | null;
  remaining: number | null;
}
export interface MonetizationCatalog {
  freeDailyAiLimit: number;
  subscriptionPlans: SubscriptionPlanItem[];
  adPackages: AdPackageItem[];
}
export interface MonetizationSummary {
  currentSubscription: UserSubscriptionRecord;
  totalBookingSavedVnd?: number;
  aiQuota: AiQuotaStatus;
  subscriptionHistory: UserSubscriptionRecord[];
  campaigns: SponsoredCampaign[];
}
export interface VipDealItem {
  kind: "TOUR" | "HOTEL";
  id: number;
  name: string;
  location?: string | null;
  province?: string | null;
  rating?: number | null;
  image?: string | null;
  originalPrice: number;
  memberPrice: number;
  savedAmount: number;
}
export interface VipDealsResponse {
  tier: string;
  planName: string;
  badge: string;
  discountPercent: number;
  waiveFirstRescheduleFee: boolean;
  deals: VipDealItem[];
}
export type VipTimelineCategory =
  | "WAKE"
  | "TRANSIT"
  | "FOOD"
  | "STAY"
  | "SIGHT"
  | "TOUR"
  | "NIGHT";

export interface VipTimelineStop {
  time: string;
  category: VipTimelineCategory;
  title: string;
  subtitle: string;
  placeLabel?: string | null;
}

export interface VipItineraryDay {
  day: number;
  dateLabel?: string;
  title: string;
  morning: string;
  afternoon: string;
  evening: string;
  timeline?: VipTimelineStop[];
}
export interface VipItineraryResponse {
  tier: string;
  badge: string;
  discountPercent: number;
  province: string;
  style: string;
  styleLabel: string;
  aiSource?: string;
  days: VipItineraryDay[];
  estimatedOriginalVnd: number;
  estimatedMemberVnd: number;
  estimatedSavedVnd: number;
  conciergeSummary: string;
  recommendedTours: {
    id: number;
    name: string;
    originalPrice: number;
    memberPrice: number;
    savedAmount: number;
  }[];
  recommendedHotels: {
    id: number;
    name: string;
    originalPrice: number;
    memberPrice: number;
    savedAmount: number;
  }[];
}
export interface AdminMonetizationOverview {
  activeSubscribers: number;
  activeCampaigns: number;
  pendingCampaigns: number;
  subscriptionRevenue: number;
  adRevenue: number;
  totalMonetizationRevenue: number;
  subscriptionPlans: SubscriptionPlanItem[];
  adPackages: AdPackageItem[];
  campaigns: SponsoredCampaign[];
  subscriptions: UserSubscriptionRecord[];
}

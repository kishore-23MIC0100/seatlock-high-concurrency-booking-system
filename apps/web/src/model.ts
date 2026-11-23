export type Role = 'CUSTOMER' | 'ORGANIZER' | 'ADMIN'
export type Account = {
  id: string
  email: string
  displayName: string
  role: Role
  organizerStatus: 'PENDING' | 'APPROVED' | 'REJECTED' | null
}
export type Event = {
  id: string
  name: string
  description: string
  category: 'MUSIC' | 'COMEDY' | 'THEATRE' | 'SPORTS'
  artKey: string
  startsAt: string
  endsAt: string
  saleStartsAt: string
  venueId: string
  venue: string
  city: string
  zoneId: string
  capacity: number
  minPriceMinor: number
  status: 'DRAFT' | 'PUBLISHED'
  roomCapacity: number | null
  demo: boolean
  revision: number
}
export type Seat = {
  id: number
  row: string
  number: number
  section: string
  accessible: boolean
  priceMinor: number
  tier: string
  state: 'AVAILABLE' | 'HELD' | 'BLOCKED' | 'SOLD'
  revision: number
}
export type Inventory = {
  event: Event
  seats: Seat[]
  serverTime: string
  holdSeconds: number
  convenienceMinor: number
  platformMinor: number
}
export type HeldSeat = Pick<Seat, 'id' | 'row' | 'number' | 'section' | 'priceMinor'>
export type Hold = {
  id: string
  eventId: string
  status: 'ACTIVE' | 'CONVERTED' | 'EXPIRED' | 'CANCELLED'
  createdAt?: string
  expiresAt: string
  serverTime: string
  subtotalMinor: number
  convenienceMinor: number
  platformMinor: number
  totalMinor: number
  seats: HeldSeat[]
  bookingId: string | null
}
export type PaymentProvider = 'RAZORPAY' | 'SEATLOCK_DEMO' | 'LEGACY' | 'FREE'
export type Payment = {
  id: string
  holdId: string
  status: 'PENDING' | 'APPROVED' | 'DECLINED' | 'TIMED_OUT' | 'REVERSED' | 'REFUND_PENDING' | 'REFUND_FAILED' | 'REFUNDED'
  provider: PaymentProvider
  mode: 'TEST' | 'LIVE' | null
  scenario: string | null
  amountMinor: number
  bookingId: string | null
  createdAt: string
}
export type CheckoutPayment = {
  status: Payment['status'] | 'READY' | 'AWAITING_PAYMENT'
  enabled: boolean
  mode: 'TEST' | 'LIVE' | 'DEMO' | 'FREE' | null
  orderId: string | null
  bookingId: string | null
}
export type Ticket = {
  id: string
  bookingId: string
  eventId: string
  eventName: string
  venue: string
  city: string
  zoneId: string
  startsAt: string
  section: string
  row: string
  number: number
  state: 'VALID' | 'CHECKED_IN' | 'REFUND_PENDING' | 'VOID'
  demo: boolean
}
export type BookingRow = {
  id: string
  userId: string
  eventId: string
  eventName: string
  status: 'CONFIRMED' | 'REFUND_PENDING' | 'REFUNDED'
  amountMinor: number
  currency: string
  provider: PaymentProvider
  mode: 'TEST' | 'LIVE' | null
  paymentId: string
  createdAt: string
}
export type Booking = Omit<BookingRow, 'userId'> & { tickets: Ticket[] }
export type Notice = { id: number; message: string; createdAt: string; readAt: string | null }
export type Waitlist = {
  id: number
  eventId: string
  eventName: string
  quantity: number
  needsAccessible: boolean
  state: 'QUEUED' | 'OFFERED' | 'ACCEPTED' | 'EXPIRED' | 'LEFT'
  offerHoldId: string | null
  expiresAt: string | null
  position: number | null
}
export type Admission = { status: 'OPEN' | 'QUEUED' | 'ADMITTED'; position: number | null; expiresAt: string | null }

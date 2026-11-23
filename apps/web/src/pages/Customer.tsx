import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { ArrowRight, CalendarDays, CircleCheck, Download, MapPin, RotateCcw, Ticket as TicketIcon } from 'lucide-react'
import { motion, useReducedMotion } from 'motion/react'
import type { CSSProperties, FormEvent } from 'react'
import { Link, useParams, useSearchParams } from 'react-router-dom'
import { accountKey, api, eventPath, send, useAccount, useEvent } from '../api'
import { CalendarButton } from '../components/CalendarButton'
import { Empty, ErrorPanel, notify, Pending, Stepper } from '../components/ui'
import { date, money, seatName, time } from '../format'
import type { Account, Booking, BookingRow, Hold, Ticket, Waitlist } from '../model'

function TicketCard({ ticket }: { ticket: Ticket }) {
  return <article className="glass ticket-card">
    <header><div className="feature-icon"><TicketIcon size={21} /></div><span className={'state-pill ' + (['VOID','REFUND_PENDING'].includes(ticket.state) ? 'inactive' : '')}>{ticket.demo ? 'DEMO · ' : ''}{ticket.state.replace('_', ' ')}</span></header>
    <h2>{ticket.eventName}</h2>
    <p><CalendarDays size={17} />{date(ticket.startsAt, ticket.zoneId)} · {time(ticket.startsAt, ticket.zoneId)}</p>
    <p><MapPin size={17} />{ticket.venue}, {ticket.city}</p>
    <div className="ticket-perf"><span>{ticket.section}</span><strong>Seat {seatName(ticket)}</strong></div>
    <Link className="text-link" to={'/my-tickets/' + ticket.id}>View ticket <ArrowRight size={16} /></Link>
  </article>
}

export function MyTickets() {
  const { data: account } = useAccount()
  const query = useQuery({ queryKey: ['my-tickets', account?.id], queryFn: ({ signal }) => api<Ticket[]>('/me/tickets', { signal }) })
  return <main id="main-content" className="page-width page-content">
    <div className="content-title"><h1>My tickets</h1><Link className="text-link" to="/my-bookings">My bookings <ArrowRight size={17} /></Link></div>
    {query.isPending && <Pending label="Loading your tickets" />}
    {query.error && <ErrorPanel error={query.error} retry={() => void query.refetch()} />}
    {query.data?.length === 0 && <Empty title="No tickets yet"><Link className="btn btn-primary" to="/events">Explore events</Link></Empty>}
    <div className="ticket-grid">{query.data?.map(ticket => <TicketCard ticket={ticket} key={ticket.id} />)}</div>
  </main>
}

export function TicketDetails() {
  const id = useParams().ticketId ?? ''
  const { data: account } = useAccount()
  const query = useQuery({
    queryKey: ['ticket', id, account?.id], queryFn: ({ signal }) => api<Ticket>('/me/tickets/' + id, { signal }),
  })
  return <main id="main-content" className="page-width page-content">
    <div className="content-title"><h1>{query.data?.demo ? 'Demo ticket' : 'Admission ticket'}</h1><Link className="text-link" to="/my-tickets">All my tickets</Link></div>
    {query.isPending && <Pending label="Loading your ticket" />}
    {query.error && <ErrorPanel error={query.error} retry={() => void query.refetch()} />}
    {query.data && <div className="ticket-detail">
      <TicketCard ticket={query.data} />
      {query.data.state !== 'VOID' && query.data.state !== 'REFUND_PENDING' && <section className="glass qr-card">
        <h2>Entry QR code</h2>
        <img src={'/api/v1/me/tickets/' + id + '/qr'} alt={query.data.demo ? 'Your demo ticket QR code' : 'Your unique admission QR code'} width="220" height="220" />
        <p className="muted">{query.data.demo ? 'Use this QR code at SeatLock demo check-in.' : 'Present your QR code at the venue entrance.'}</p>
        <a className="btn btn-primary" href={'/api/v1/me/tickets/' + id + '/download'} download><Download size={17} /> Download ticket</a>
      </section>}
    </div>}
  </main>
}

function ConfirmationMark({ confirmed }: { confirmed: boolean }) {
  const sparkles = [[-61,-32],[-37,-61],[-8,-70],[33,-63],[58,-38],[63,5],[49,39],[-48,38],[-65,5]]
  return <span className={'confirmation-mark' + (confirmed ? ' confirmed' : '')} aria-hidden="true">
    <span className="confirmation-icon">{confirmed ? <CircleCheck size={32} /> : <RotateCcw size={29} />}</span>
    {confirmed && <span className="confirmation-sparks">{sparkles.map(([x,y], index) => <i key={index}
      style={{ '--spark-x': x + 'px', '--spark-y': y + 'px', '--spark-delay': (index % 3) * 22 + 'ms' } as CSSProperties} />)}</span>}
  </span>
}

export function Confirmation() {
  const id = useParams().bookingId ?? ''
  const { data: account } = useAccount()
  const reduced = useReducedMotion()
  const query = useQuery({
    queryKey: ['booking', id, account?.id], queryFn: ({ signal }) => api<Booking>('/me/bookings/' + id, { signal }),
    refetchInterval: current => current.state.data?.status === 'REFUND_PENDING' ? 10_000 : false,
  })
  const booking = query.data
  const confirmed = booking?.status === 'CONFIRMED'
  const event = useEvent(confirmed ? booking.eventId : '')
  const isDemo = booking?.provider === 'SEATLOCK_DEMO'
  const amountLabel = booking?.provider === 'FREE' ? 'Complimentary booking'
    : isDemo ? 'SeatLock Pay demo total' : booking?.provider === 'LEGACY' ? 'Historical demo total'
      : booking?.mode === 'TEST' ? 'Razorpay test amount'
        : booking?.status === 'REFUNDED' ? 'Razorpay refund' : 'Razorpay payment'
  return <main id="main-content" className="page-width page-content result-page">
    <Stepper active={3} />
    {query.isPending && <Pending label="Loading your receipt" />}
    {query.error && <ErrorPanel error={query.error} retry={() => void query.refetch()} />}
    {query.data && <>
      <motion.section className="glass confirmation" data-confirmed={confirmed}
        initial={reduced ? false : { opacity: 0, y: 14 }} animate={{ opacity: 1, y: 0 }}
        transition={reduced ? { duration: 0 } : { type: 'spring', duration: .55, bounce: .15 }}>
        <ConfirmationMark confirmed={confirmed} />
        <h1>{query.data.status === 'REFUND_PENDING' ? 'Refund in progress'
          : query.data.status === 'REFUNDED' ? query.data.provider === 'RAZORPAY' ? 'Booking refunded' : 'Booking cancelled'
            : 'Booking confirmed'}</h1>
        <p>{query.data.eventName} · {query.data.tickets.length} {query.data.tickets.length === 1 ? 'ticket' : 'tickets'}</p>
        <dl><div><dt>Booking reference</dt><dd>{query.data.id}</dd></div>
          {isDemo && <div><dt>Demo payment reference</dt><dd>{query.data.paymentId}</dd></div>}
          <div><dt>{amountLabel}</dt><dd>{money(query.data.amountMinor)}</dd></div></dl>
        <div className="action-row">
          <Link className="btn btn-primary" to="/my-tickets">My Tickets <ArrowRight size={17} /></Link>
          {event.data && <CalendarButton event={event.data} />}
        </div>
      </motion.section>
      <div className="ticket-grid revealed-tickets">{query.data.tickets.map(ticket => <TicketCard ticket={ticket} key={ticket.id} />)}</div>
    </>}
  </main>
}

export function MyBookings() {
  const { data: account } = useAccount()
  const bookings = useQuery({ queryKey: ['my-bookings', account?.id], queryFn: () => api<BookingRow[]>('/me/bookings') })
  const holds = useQuery({ queryKey: ['active-holds', account?.id], queryFn: () => api<Hold[]>('/me/holds'), refetchInterval: 15_000 })
  return <main id="main-content" className="page-width page-content">
    <div className="content-title"><h1>My bookings</h1><Link className="text-link" to="/my-tickets">My tickets</Link></div>
    {(bookings.isPending || holds.isPending) && <Pending label="Loading your bookings" />}
    {(bookings.error || holds.error) && <ErrorPanel error={bookings.error ?? holds.error} retry={() => { void bookings.refetch(); void holds.refetch() }} />}
    {holds.data && holds.data.length > 0 && <section className="glass list-panel">
      <h2>Active holds</h2><ul>{holds.data.map(hold => <li key={hold.id}><div><strong>{hold.seats.map(seatName).join(', ')}</strong><small>Expires {time(hold.expiresAt)}</small></div>
        <Link className="text-link" to={'/checkout/' + hold.id}>Resume checkout <ArrowRight size={16} /></Link></li>)}</ul>
    </section>}
    {bookings.data?.length === 0 && <Empty title="No completed bookings"><Link className="btn btn-primary" to="/events">Explore events</Link></Empty>}
    {bookings.data && bookings.data.length > 0 && <section className="glass list-panel">
      <h2>Receipts</h2><ul>{bookings.data.map(booking => <li key={booking.id}><div><strong>{booking.eventName}</strong>
        <small>{date(booking.createdAt)} · {booking.status.toLowerCase()} · {booking.provider === 'SEATLOCK_DEMO' ? 'Demo amount ' : ''}{money(booking.amountMinor)}</small></div>
        <Link className="text-link" to={'/booking/' + booking.id + '/confirmation'}>View receipt <ArrowRight size={16} /></Link></li>)}</ul>
    </section>}
  </main>
}

export function WaitlistPage() {
  const [params] = useSearchParams()
  const event = params.get('event')
  const account = useAccount()
  const client = useQueryClient()
  const query = useQuery({
    queryKey: ['waitlist', account.data?.id], queryFn: () => api<Waitlist[]>('/waitlist'), refetchInterval: 5000,
  })
  const join = useMutation({
    mutationFn: (body: { eventId: string; quantity: number; needsAccessible: boolean }) => send('/waitlist', body),
    onSuccess: () => client.invalidateQueries({ queryKey: ['waitlist'] }),
  })
  const leave = useMutation({
    mutationFn: (id: number) => send('/waitlist/' + id, undefined, 'DELETE'),
    onSuccess: () => client.invalidateQueries({ queryKey: ['waitlist'] }),
  })
  function submit(e: FormEvent<HTMLFormElement>) {
    e.preventDefault()
    if (!event) return
    const form = new FormData(e.currentTarget)
    join.mutate({ eventId: event, quantity: Number(form.get('quantity')), needsAccessible: form.has('accessible') })
  }
  return <main id="main-content" className="page-width page-content">
    <div className="content-title"><h1>My waitlist</h1></div>
    {event && <section className="glass details-card"><h2>Join the event waitlist</h2>
      <form className="form-stack" onSubmit={submit}>
        <label>Number of seats<input name="quantity" type="number" min="1" max="6" defaultValue="1" required /></label>
        <label className="checkbox"><input type="checkbox" name="accessible" /> Include one wheelchair-accessible seat</label>
        <div className="action-row"><button className="btn btn-primary" type="submit" disabled={join.isPending}>Join waitlist</button>
          <Link className="text-link" to={eventPath(event) + '/seats'}>Back to seats</Link></div>
      </form>
      {join.error && <ErrorPanel error={join.error} />}
    </section>}
    {query.isPending && <Pending label="Loading the waitlist" />}
    {query.error && <ErrorPanel error={query.error} retry={() => void query.refetch()} />}
    {leave.error && <ErrorPanel error={leave.error} />}
    {query.data?.length === 0 && !event && <Empty title="You're on no waitlists"><Link className="text-link" to="/events">Explore events</Link></Empty>}
    {query.data && query.data.length > 0 && <section className="glass list-panel"><ul>{query.data.map(entry => <li key={entry.id}>
      <div><strong>{entry.eventName}</strong><small>{entry.quantity} seats · {entry.state.toLowerCase()}
        {entry.position ? ' · Position ' + entry.position : ''}{entry.expiresAt && entry.state === 'OFFERED' ? ' · Expires ' + time(entry.expiresAt) : ''}</small></div>
      <div className="action-row">
        {entry.state === 'OFFERED' && <Link className="btn btn-primary" to={'/checkout/' + entry.offerHoldId}>Accept offer</Link>}
        {(entry.state === 'QUEUED' || entry.state === 'OFFERED') &&
          <button className="btn btn-secondary" type="button" disabled={leave.isPending} onClick={() => leave.mutate(entry.id)}>{entry.state === 'OFFERED' ? 'Decline' : 'Leave'}</button>}
      </div>
    </li>)}</ul></section>}
  </main>
}

export function Profile({ settings = false }: { settings?: boolean }) {
  const { data: account } = useAccount()
  const client = useQueryClient()
  const update = useMutation({
    mutationFn: (displayName: string) => send<Account>(settings ? '/me/organizer-application' : '/me', { displayName }, settings ? 'POST' : 'PATCH'),
    onSuccess: data => { client.setQueryData(accountKey, data); notify(settings ? 'Application submitted' : 'Profile saved') },
  })
  if (!account) return null
  return <main id="main-content" className="page-width page-content">
    <div className="content-title"><h1>{settings ? 'Settings' : 'My profile'}</h1></div>
    <section className="glass details-card"><h2>{settings ? 'Organizer access' : 'Account details'}</h2>
      <p className="muted">{settings ? account.organizerStatus ? 'Application: ' + account.organizerStatus.toLowerCase() : 'Apply for an organizer workspace.' : account.email}</p>
      {(!settings || account.role === 'CUSTOMER' && account.organizerStatus !== 'PENDING') && <form className="form-stack" onSubmit={event => {
        event.preventDefault()
        const value = new FormData(event.currentTarget).get('name')?.toString().trim()
        if (value) update.mutate(value)
      }}>
        <label>{settings ? 'Organizer name' : 'Display name'}<input required maxLength={100} name="name" defaultValue={settings ? '' : account.displayName} /></label>
        <button className="btn btn-primary" type="submit" disabled={update.isPending}>{settings ? 'Submit application' : 'Save profile'}</button>
      </form>}
      {update.error && <ErrorPanel error={update.error} />}
    </section>
  </main>
}

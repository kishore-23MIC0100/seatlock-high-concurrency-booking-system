import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { ArrowLeft, ArrowRight, Clock3, Ticket } from 'lucide-react'
import { useEffect, useMemo, useRef, useState } from 'react'
import { Link, useLocation, useNavigate, useParams } from 'react-router-dom'
import { create } from 'zustand'
import { accountKey, api, ApiError, eventPath, inventory, inventoryKey, send, useAccount, useCapabilities } from '../api'
import { BookingSummary, ContinueButton } from '../components/BookingSummary'
import { EventHero } from '../components/EventHero'
import { SeatMap } from '../components/SeatMap'
import { Empty, ErrorPanel, Modal, notify, Pending, Stepper } from '../components/ui'
import { money, seatName } from '../format'
import type { Account, Admission, Hold, Seat } from '../model'

const none: number[] = []
const selection = create<{ event: string; ids: number[]; change: (event: string, ids: number[]) => void }>(
  set => ({ event: '', ids: none, change: (event, ids) => set({ event, ids }) }),
)

function useLiveInventory(event: string) {
  const client = useQueryClient()
  const [connectedEvent, setConnected] = useState('')
  const live = connectedEvent === event
  const query = useQuery({
    queryKey: inventoryKey(event), queryFn: ({ signal }) => inventory(event, signal), enabled: !!event,
    refetchInterval: live ? 20_000 : 5000,
  })
  const ready = query.isSuccess
  useEffect(() => {
    if (!ready || !event) return
    const stream = new EventSource('/api/v1' + eventPath(event) + '/availability/stream')
    const resync = () => void client.invalidateQueries({ queryKey: inventoryKey(event) })
    stream.onopen = () => { setConnected(event); resync() }
    stream.onerror = () => { setConnected('') }
    stream.addEventListener('inventory', resync)
    return () => { stream.close(); setConnected('') }
  }, [client, event, ready])
  return { query, live }
}

export default function SeatSelection() {
  const eventId = useParams().eventId ?? ''
  const navigate = useNavigate()
  const client = useQueryClient()
  const { pathname } = useLocation()
  const { data: account } = useAccount()
  const { data: capabilities } = useCapabilities()
  const { query, live } = useLiveInventory(eventId)
  const [signInOpen, setSignInOpen] = useState(false)
  const [summaryOpen, setSummaryOpen] = useState(false)
  const [selectionNote, setSelectionNote] = useState('')
  const ids = selection(state => state.event === eventId ? state.ids : none)
  const change = selection(state => state.change)
  const selected = useMemo(() => new Set(ids), [ids])
  const selectedSeats = query.data?.seats.filter(seat => selected.has(seat.id)) ?? []
  const saleOpen = !!query.data && Date.parse(query.data.event.saleStartsAt) <= Date.parse(query.data.serverTime)
    && Date.parse(query.data.event.startsAt) > Date.parse(query.data.serverTime)
  const requiresRoom = !!query.data?.event.roomCapacity
  const admission = useQuery({
    queryKey: ['admission', eventId, account?.id],
    queryFn: () => api<Admission>(eventPath(eventId) + '/admission'),
    enabled: requiresRoom && !!account, refetchInterval: 3000,
  })
  const room = useMutation({
    mutationFn: () => send<Admission>(eventPath(eventId) + '/admission'),
    onSuccess: data => client.setQueryData(['admission', eventId, account?.id], data),
  })
  const allowed = !requiresRoom || admission.data?.status === 'ADMITTED'
  const key = useRef({ payload: '', key: '' })
  const hold = useMutation({
    mutationFn: (seats: number[]) => {
      const payload = eventId + ':' + [...seats].sort((a, b) => a - b).join(',')
      if (key.current.payload !== payload) key.current = { payload, key: crypto.randomUUID() }
      return send<Hold>('/holds', { eventId, seatIds: seats }, 'POST', key.current.key)
    },
    onSuccess: data => {
      key.current = { payload: '', key: '' }
      change(eventId, [])
      void client.invalidateQueries({ queryKey: inventoryKey(eventId) })
      const names = data.seats.map(seatName)
      notify(names.length === 1 ? 'Seat reserved' : names.length + ' seats reserved', names.join(', '))
      navigate('/checkout/' + data.id)
    },
    onError: error => {
      if (error instanceof ApiError && error.code === 'SEAT_CONFLICT') {
        const taken = error.details.seatIds
        if (Array.isArray(taken)) change(eventId, ids.filter(id => !taken.includes(id)))
        void client.invalidateQueries({ queryKey: inventoryKey(eventId) })
      }
    },
  })
  const demo = useMutation({
    mutationFn: () => send<Account>('/auth/demo', { role: 'CUSTOMER' }),
    onSuccess: data => {
      client.setQueryData(accountKey, data)
      setSignInOpen(false)
      if (requiresRoom) void client.invalidateQueries({ queryKey: ['admission', eventId] })
      else hold.mutate(ids)
    },
  })

  function toggle(seat: Seat) {
    setSelectionNote('')
    hold.reset()
    if (selected.has(seat.id)) change(eventId, ids.filter(id => id !== seat.id))
    else if (ids.length >= 6) setSelectionNote('You can reserve up to six seats per event.')
    else change(eventId, [...ids, seat.id])
  }
  function chooseGroup(group: Seat[]) {
    setSelectionNote('')
    hold.reset()
    change(eventId, group.map(seat => seat.id))
    notify(group.length === 1 ? 'Seat selected' : group.length + ' seats selected', group.map(seatName).join(', '))
  }

  function proceed() {
    setSummaryOpen(false)
    if (account) hold.mutate(ids)
    else setSignInOpen(true)
  }

  if (query.isPending) return <main id="main-content" className="page-width page-content"><Pending label="Loading current seat availability" /></main>
  if (query.isError && !query.data) return <main id="main-content" className="page-width page-content"><ErrorPanel error={query.error} retry={() => void query.refetch()} /><Link to="/events">Browse events</Link></main>
  if (!query.data) return null
  const { event, seats, holdSeconds, convenienceMinor, platformMinor } = query.data
  const subtotal = selectedSeats.reduce((sum, seat) => sum + seat.priceMinor, 0)
  const fees = subtotal ? convenienceMinor + platformMinor : 0
  const summary = <BookingSummary event={event} seats={selectedSeats}
    remove={seat => { change(eventId, ids.filter(id => id !== seat.id)); hold.reset(); setSelectionNote('') }}
    totals={{ subtotalMinor: subtotal, convenienceMinor: subtotal ? convenienceMinor : 0, platformMinor: subtotal ? platformMinor : 0, totalMinor: subtotal + fees }}
    error={hold.error && <ErrorPanel error={hold.error} />}
    action={<ContinueButton label="Continue to Checkout" busy={hold.isPending}
      disabled={!selectedSeats.length || !saleOpen || !allowed} onClick={proceed} />}
  />

  return <main id="main-content" className="page-width booking-page">
    <EventHero event={event} />
    <div className="booking-layout">
      <div className="booking-main">
        <Stepper active={0} />
        {!saleOpen && <div className="info-line"><Clock3 size={18} /> This event is outside its booking window.</div>}
        {requiresRoom && !allowed && <div className="glass room-panel">
          <h2>Waiting room</h2>
          {admission.data?.status === 'QUEUED'
            ? <p role="status">Queue position {admission.data.position ?? 'updating'}. Keep this page open.</p>
            : account
              ? <button className="btn btn-primary" type="button" disabled={room.isPending} onClick={() => room.mutate()}>Join waiting room</button>
              : <button className="btn btn-primary" type="button" onClick={() => setSignInOpen(true)}>Sign in to join</button>}
          {(room.error || admission.error) && <ErrorPanel error={room.error ?? admission.error} />}
        </div>}
        {query.isError && <ErrorPanel error={query.error} retry={() => void query.refetch()} />}
        {seats.length
          ? <SeatMap seats={seats} selected={selected} onSelect={toggle} onGroup={chooseGroup} holdSeconds={holdSeconds}
              live={live} allowSelection={saleOpen && allowed && !hold.isPending} />
          : <Empty title="This event has no seats available" />}
        {selectionNote && <p className="info-line" role="status">{selectionNote}</p>}
        <footer className="map-foot">
          <Link to="/events"><ArrowLeft size={15} /> All events</Link>
          {!seats.some(seat => seat.state === 'AVAILABLE') && <Link to={'/waitlist?event=' + encodeURIComponent(eventId)}><Ticket size={16} /> Join the waitlist</Link>}
        </footer>
      </div>
      <aside className="desktop-summary">{summary}</aside>
    </div>
    <div className="mobile-summary-bar">
      <span><small>{selectedSeats.length ? selectedSeats.length + ' selected' : 'No seats selected'}</small><strong>{money(subtotal + fees)}</strong></span>
      <button type="button" className="btn btn-primary" onClick={() => setSummaryOpen(true)}>Review booking <ArrowRight size={17} /></button>
    </div>
    <Modal open={summaryOpen} onOpenChange={setSummaryOpen} title="Review booking" className="summary-modal">{summary}</Modal>
    <Modal open={signInOpen} onOpenChange={setSignInOpen} title="Sign in to hold your seats"
      description="Your seat choices stay on the map while you sign in.">
      <div className="action-row">
        <Link className="btn btn-primary" to={'/login?next=' + encodeURIComponent(pathname)}>Sign in</Link>
        <Link className="btn btn-secondary" to={'/register?next=' + encodeURIComponent(pathname)}>Create an account</Link>
      </div>
      {capabilities?.demo && <button className="btn btn-secondary demo-action" type="button" disabled={demo.isPending} onClick={() => demo.mutate()}>
        {demo.isPending ? 'Starting demo session…' : 'Continue as demo guest'}
      </button>}
      {demo.error && <ErrorPanel error={demo.error} />}
    </Modal>
  </main>
}

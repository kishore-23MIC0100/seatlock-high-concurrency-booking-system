import { useMutation, useQueryClient } from '@tanstack/react-query'
import { ArrowRight, Download, Plus, ScanLine } from 'lucide-react'
import { useState, type FormEvent } from 'react'
import { Link, Route, Routes, useNavigate, useParams } from 'react-router-dom'
import { eventPath, send } from '../../api'
import { Empty, ErrorPanel, notify } from '../../components/ui'
import { categoryNames, date, money, seatName } from '../../format'
import type { Event, Inventory, Seat } from '../../model'
import { DataTable, Panel, State, Stats, useWorkspace } from './shared'

type Venue = { id: string; name: string; city: string; zoneId: string; capacity: number }
type VenueSeat = Pick<Seat, 'id' | 'row' | 'number' | 'accessible' | 'section'>
type Report = { eventId: string; eventName: string; capacity: number; bookings: number; tickets: number; amountMinor: number; checkedIn: number }
type Attendee = { ticketId: string; eventName: string; row: string; number: number; displayName: string; state: string }
type Scan = { id: string; eventName: string; row: string; number: number; state: string }

const studio = '/organizer'
const eventsEndpoint = studio + '/events'
const venuesEndpoint = studio + '/venues'
const value = (form: FormData, name: string) => form.get(name)?.toString().trim() ?? ''
const number = (form: FormData, name: string) => value(form, name) ? Number(value(form, name)) : null
const amount = (form: FormData, name: string) => value(form, name) ? Math.round(Number(value(form, name)) * 100) : null
const instant = (form: FormData, name: string) => value(form, name) ? new Date(value(form, name)).toISOString() : null
function localTime(iso: string) {
  const value = new Date(iso)
  return new Date(value.getTime() - value.getTimezoneOffset() * 60_000).toISOString().slice(0, 16)
}

function Reports({ overview = false }: { overview?: boolean }) {
  const reports = useWorkspace<Report[]>(studio + '/reports', 20_000)
  const rows = reports.data ?? []
  const sum = (field: 'tickets' | 'checkedIn' | 'amountMinor') => rows.reduce((total, row) => total + row[field], 0)
  return <Panel title={overview ? 'Sales overview' : 'Sales reports'} pending={reports.isPending} error={reports.error}>
    {reports.data && <>
      <Stats items={[['Tickets issued', sum('tickets')], ['Live ticket revenue', money(sum('amountMinor'))], ['Checked in', sum('checkedIn')]]} />
      <div className="workspace-actions">
        <Link className="btn btn-primary" to="/organizer/events/new"><Plus size={17} /> Create event</Link>
        <a className="btn btn-secondary" href="/api/v1/organizer/reports/download"><Download size={17} /> Export CSV</a>
      </div>
      <DataTable columns={['Event','Capacity','Bookings','Tickets','Live revenue','Checked in']}
        rows={rows.map(row => [<Link className="text-link" to={eventsEndpoint + '/' + row.eventId}>{row.eventName}</Link>,
          row.capacity,row.bookings,row.tickets,money(row.amountMinor),row.checkedIn])} empty="Publish an event to track sales" />
    </>}
  </Panel>
}

function OrganizerEvents() {
  const query = useWorkspace<Event[]>(eventsEndpoint)
  return <Panel title="Your events" pending={query.isPending} error={query.error} retry={() => void query.refetch()}>
    <div className="workspace-actions"><Link className="btn btn-primary" to="/organizer/events/new"><Plus size={17} /> New event</Link></div>
    {query.data && <DataTable columns={['Event','Venue','Starts','Capacity','Status','Manage']} rows={query.data.map(event =>
      [event.name,event.venue,date(event.startsAt,event.zoneId),event.capacity,<State value={event.status} />,
       <Link className="text-link" to={eventsEndpoint + '/' + event.id}>Manage <ArrowRight size={15} /></Link>])} empty="Create your first event" />}
  </Panel>
}

function EventFields({ event }: { event?: Event }) {
  return <>
    <label>Event name<input name="name" required maxLength={100} defaultValue={event?.name} /></label>
    <label>Description<textarea name="description" required maxLength={1500} defaultValue={event?.description} /></label>
    <p className="muted">Enter dates and times in your device’s time zone.</p>
    <div className="form-grid">
      <label>Sales open<input type="datetime-local" name="sales" required defaultValue={localTime(event?.saleStartsAt ?? new Date(Date.now() + 120_000).toISOString())} /></label>
      <label>Event starts<input type="datetime-local" name="starts" required defaultValue={event && localTime(event.startsAt)} /></label>
      <label>Event ends<input type="datetime-local" name="ends" required defaultValue={event && localTime(event.endsAt)} /></label>
      <label>Waiting room capacity (optional)<input type="number" min="1" max="5000" name="room" defaultValue={event?.roomCapacity ?? ''} /></label>
    </div>
  </>
}

function eventBody(form: FormData) {
  return { name: value(form,'name'), description: value(form,'description'), startsAt: instant(form,'starts'),
    endsAt: instant(form,'ends'), saleStartsAt: instant(form,'sales'), roomCapacity: number(form,'room') }
}

function NewEvent() {
  const venues = useWorkspace<Venue[]>(venuesEndpoint)
  const client = useQueryClient()
  const navigate = useNavigate()
  const create = useMutation({
    mutationFn: (form: FormData) => send<Event>(eventsEndpoint, { ...eventBody(form), venueId: value(form,'venue'),
      category: value(form,'category'), priceMinor: amount(form,'price') ?? 0, premiumRows: number(form,'premiumRows') ?? 0,
      premiumPriceMinor: amount(form,'premiumPrice') }),
    onSuccess: result => { void client.invalidateQueries({ queryKey: ['workspace'] }); navigate(eventsEndpoint + '/' + result.id) },
  })
  return <Panel title="Create an event" pending={venues.isPending} error={venues.error}>
    {venues.data?.length === 0 && <Empty title="Create a venue first"><Link className="btn btn-primary" to={venuesEndpoint}>Add venue</Link></Empty>}
    {!!venues.data?.length && <form className="glass details-card form-stack" onSubmit={e => { e.preventDefault(); create.mutate(new FormData(e.currentTarget)) }}>
      <EventFields />
      <div className="form-grid">
        <label>Venue<select name="venue" aria-label="Venue" required>{venues.data.map(venue => <option value={venue.id} key={venue.id}>{venue.name} · {venue.city}</option>)}</select></label>
        <label>Category<select name="category" required>{Object.entries(categoryNames).map(([key, label]) => <option value={key} key={key}>{label}</option>)}</select></label>
        <label>Standard price (₹)<input name="price" type="number" min="0" max="1000000" step=".01" required /></label>
        <label>Premium price (₹)<input name="premiumPrice" type="number" min="0" max="1000000" step=".01" /></label>
        <label>Premium front rows<input name="premiumRows" type="number" min="0" max="26" defaultValue="0" /></label>
      </div>
      {create.error && <ErrorPanel error={create.error} />}
      <button className="btn btn-primary" type="submit" disabled={create.isPending}>Create draft</button>
    </form>}
  </Panel>
}

function ManageEvent() {
  const id = useParams().eventId ?? ''
  const endpoint = eventsEndpoint + '/' + id
  const query = useWorkspace<Inventory>(endpoint, 10_000)
  const client = useQueryClient()
  const [edit, setEdit] = useState(false)
  const [row, setRow] = useState('')
  const update = useMutation({
    mutationFn: (form: FormData) => send<Event>(endpoint, eventBody(form), 'PATCH'),
    onSuccess: () => { setEdit(false); void client.invalidateQueries({ queryKey: ['workspace'] }); notify('Draft updated') },
  })
  const publish = useMutation({
    mutationFn: () => send<Event>(endpoint + '/publish'),
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: ['workspace'] })
      void client.invalidateQueries({ queryKey: ['events'] })
      notify('Event published')
    },
  })
  const block = useMutation({
    mutationFn: (seat: Seat) => send<Inventory>(endpoint + '/seats/' + seat.id, { blocked: seat.state === 'AVAILABLE' }, 'PATCH'),
    onSuccess: () => { void query.refetch() },
  })
  const event = query.data?.event
  const rows = [...new Set(query.data?.seats.map(seat => seat.row))]
  const visible = query.data?.seats.filter(seat => seat.row === (row || rows[0])) ?? []
  return <Panel title={event?.name ?? 'Manage event'} pending={query.isPending} error={query.error} retry={() => void query.refetch()}>
    {event && <>
      <div className="workspace-actions"><State value={event.status} />
        {event.status === 'DRAFT' ? <>
          <button type="button" className="btn btn-primary" disabled={publish.isPending} onClick={() => publish.mutate()}>Publish event</button>
          <button type="button" className="btn btn-secondary" onClick={() => setEdit(value => !value)}>{edit ? 'Close editor' : 'Edit draft'}</button>
        </> : <Link className="btn btn-secondary" to={eventPath(id) + '/seats'}>Open public seat map</Link>}
      </div>
      {(publish.error || update.error || block.error) && <ErrorPanel error={publish.error ?? update.error ?? block.error} />}
      {edit && event.status === 'DRAFT' && <form className="glass details-card form-stack" onSubmit={e => { e.preventDefault(); update.mutate(new FormData(e.currentTarget)) }}>
        <EventFields event={event} /><button type="submit" className="btn btn-primary" disabled={update.isPending}>Save draft</button>
      </form>}
      <div className="workspace-actions"><label>Inventory row<select value={row || rows[0] || ''} onChange={e => setRow(e.target.value)}>
        {rows.map(item => <option key={item}>{item}</option>)}</select></label></div>
      <DataTable columns={['Seat','Section','Tier','Price','Status','Inventory action']} rows={visible.map(seat => [
        seatName(seat) + (seat.accessible ? ' · Accessible' : ''),seat.section,seat.tier,money(seat.priceMinor),<State value={seat.state} />,
        seat.state === 'AVAILABLE' || seat.state === 'BLOCKED' ? <button type="button" className="btn btn-secondary"
          disabled={block.isPending} onClick={() => block.mutate(seat)}>{seat.state === 'BLOCKED' ? 'Unblock' : 'Block'} {seatName(seat)}</button> : '—',
      ])} />
    </>}
  </Panel>
}

function Venues() {
  const query = useWorkspace<Venue[]>(venuesEndpoint)
  const [open, setOpen] = useState(false)
  const navigate = useNavigate()
  const create = useMutation({
    mutationFn: (form: FormData) => send<Venue>(venuesEndpoint, {
      name: value(form,'name'), city: value(form,'city'), section: value(form,'section'), zoneId: value(form,'zone'),
      rows: number(form,'rows'), seatsPerRow: number(form,'seats'), accessible: value(form,'accessible').split(/[ ,]+/).filter(Boolean),
    }),
    onSuccess: venue => { void query.refetch(); navigate(venuesEndpoint + '/' + venue.id + '/seats') },
  })
  return <Panel title="Venues" pending={query.isPending} error={query.error}>
    <div className="workspace-actions"><button className="btn btn-primary" type="button" onClick={() => setOpen(value => !value)}>
      <Plus size={17} /> {open ? 'Close venue form' : 'Add venue'}</button></div>
    {open && <form className="glass details-card form-stack" onSubmit={e => { e.preventDefault(); create.mutate(new FormData(e.currentTarget)) }}>
      <div className="form-grid">
        <label>Venue name<input name="name" required maxLength={100} /></label>
        <label>City<input name="city" required maxLength={70} /></label>
        <label>Time zone<input name="zone" defaultValue="Asia/Kolkata" required maxLength={70} /></label>
        <label>Section name<input name="section" defaultValue="Main Floor" required maxLength={60} /></label>
        <label>Rows<input name="rows" type="number" min="1" max="26" defaultValue="8" required /></label>
        <label>Seats per row<input name="seats" type="number" min="1" max="60" defaultValue="24" required /></label>
      </div>
      <label>Accessible seat labels<input name="accessible" placeholder="A1, B24" /></label>
      {create.error && <ErrorPanel error={create.error} />}
      <button className="btn btn-primary" type="submit" disabled={create.isPending}>Create venue</button>
    </form>}
    {query.data && <DataTable columns={['Venue','City','Time zone','Capacity','Layout']} rows={query.data.map(venue =>
      [venue.name,venue.city,venue.zoneId,venue.capacity,<Link className="text-link" to={venuesEndpoint + '/' + venue.id + '/seats'}>Edit and view seats</Link>])} empty="No venues yet" />}
  </Panel>
}

function VenueLayout() {
  const id = useParams().venueId ?? ''
  const endpoint = venuesEndpoint + '/' + id
  const venue = useWorkspace<Venue>(endpoint)
  const seats = useWorkspace<VenueSeat[]>(endpoint + '/seats')
  const client = useQueryClient()
  const save = useMutation({
    mutationFn: (form: FormData) => send<Venue>(endpoint, { name: value(form,'name'), city: value(form,'city') }, 'PATCH'),
    onSuccess: () => { void client.invalidateQueries({ queryKey: ['workspace'] }); notify('Venue saved') },
  })
  return <Panel title={venue.data?.name ?? 'Venue layout'} pending={venue.isPending || seats.isPending} error={venue.error ?? seats.error}>
    {venue.data && <form className="glass details-card form-stack" key={venue.data.id}
      onSubmit={e => { e.preventDefault(); save.mutate(new FormData(e.currentTarget)) }}>
      <div className="form-grid"><label>Venue name<input name="name" required maxLength={100} defaultValue={venue.data.name} /></label>
        <label>City<input name="city" required maxLength={70} defaultValue={venue.data.city} /></label></div>
      {save.error && <ErrorPanel error={save.error} />}
      <button className="btn btn-primary" type="submit" disabled={save.isPending}>Save venue</button>
    </form>}
    {seats.data && <div className="glass list-panel venue-layout">
      {[...new Set(seats.data.map(seat => seat.row))].map(row => <div key={row}><strong>Row {row}</strong>
        <ul className="layout-chips">{seats.data!.filter(seat => seat.row === row).map(seat =>
          <li title={seat.accessible ? 'Accessible seat ' + seatName(seat) : 'Seat ' + seatName(seat)} key={seat.id}>
            {seatName(seat)}{seat.accessible && ' ♿︎'}
          </li>)}</ul></div>)}
    </div>}
  </Panel>
}

function CheckIn() {
  const client = useQueryClient()
  const scan = useMutation({
    mutationFn: (payload: string) => send<Scan>(studio + '/check-in', { payload }),
    onSuccess: () => client.invalidateQueries({ queryKey: ['workspace'] }),
  })
  function submit(e: FormEvent<HTMLFormElement>) {
    e.preventDefault()
    const payload = value(new FormData(e.currentTarget),'payload')
    if (payload) scan.mutate(payload)
  }
  return <Panel title="Ticket check-in">
    <form className="glass details-card form-stack" onSubmit={submit}>
      <label>Ticket code<input name="payload" required autoComplete="off" placeholder="Paste a decoded SeatLock QR code" /></label>
      <button className="btn btn-primary" type="submit" disabled={scan.isPending}><ScanLine size={18} /> Validate and check in</button>
      {scan.error && <ErrorPanel error={scan.error} />}
      {scan.data && <p className="info-line" role="status">{scan.data.eventName} · Seat {seatName(scan.data)} checked in</p>}
    </form>
  </Panel>
}

function Attendees() {
  const query = useWorkspace<Attendee[]>(studio + '/attendees', 20_000)
  return <Panel title="Attendees" pending={query.isPending} error={query.error}>
    {query.data && <DataTable columns={['Attendee','Event','Seat','Status']} rows={query.data.map(row => [
      row.displayName,row.eventName,seatName(row),<State value={row.state} />,
    ])} empty="Issued tickets will appear here" />}
  </Panel>
}

export default function Studio() {
  return <Routes>
    <Route index element={<Reports overview />} /><Route path="reports" element={<Reports />} />
    <Route path="events" element={<OrganizerEvents />} /><Route path="events/new" element={<NewEvent />} />
    <Route path="events/:eventId" element={<ManageEvent />} /><Route path="venues" element={<Venues />} />
    <Route path="venues/:venueId/seats" element={<VenueLayout />} /><Route path="check-in" element={<CheckIn />} />
    <Route path="attendees" element={<Attendees />} />
    <Route path="*" element={<Empty title="Workspace page not found"><Link to="/organizer">Studio overview</Link></Empty>} />
  </Routes>
}

import { useQuery } from '@tanstack/react-query'
import { ArrowDownRight, ArrowRight, CalendarDays, MapPin, Search, Ticket } from 'lucide-react'
import { Navigate, Link, useParams, useSearchParams } from 'react-router-dom'
import { api, eventPath, useEvent } from '../api'
import { CalendarButton } from '../components/CalendarButton'
import { EventArt, EventHero } from '../components/EventHero'
import { Empty, ErrorPanel, Pending } from '../components/ui'
import { categoryNames, date, money } from '../format'
import type { Event } from '../model'

const catalogKey = ['events'] as const
function useEvents(params = '') {
  return useQuery({ queryKey: [...catalogKey, params], queryFn: ({ signal }) => api<Event[]>('/events' + params, { signal }) })
}

export function Featured() {
  const events = useEvents()
  if (events.isPending) return <main id="main-content" className="page-width page-content"><Pending label="Finding an event" /></main>
  if (events.error) return <main id="main-content" className="page-width page-content"><ErrorPanel error={events.error} retry={() => void events.refetch()} /></main>
  return <Navigate replace to={events.data?.some(event => event.id === 'neon-nights') ? '/events/neon-nights/seats' : '/discover'} />
}

function EventCard({ event }: { event: Event }) {
  return <article className="glass event-card">
    <Link to={eventPath(event.id)} aria-label={'About ' + event.name}><EventArt event={event} className="card-art" /></Link>
    <div className="event-card-body">
      <span className="event-category">{categoryNames[event.category]}</span>
      <h2><Link to={eventPath(event.id)}>{event.name}</Link></h2>
      <p><CalendarDays size={16} />{date(event.startsAt, event.zoneId)}</p>
      <p><MapPin size={16} />{event.venue}, {event.city}</p>
      <div className="card-footer"><small>From <strong>{money(event.minPriceMinor)}</strong></small>
        <Link className="text-link" to={eventPath(event.id) + '/seats'}>Find seats <ArrowRight size={16} /></Link></div>
    </div>
  </article>
}

export function Discover() {
  const events = useEvents()
  if (events.isPending) return <main id="main-content" className="page-width page-content"><Pending label="Loading events" /></main>
  if (events.error) return <main id="main-content" className="page-width page-content"><ErrorPanel error={events.error} retry={() => void events.refetch()} /></main>
  const featured = events.data?.[0]
  return <main id="main-content" className="page-width page-content">
    {featured ? <section className="discover-hero glass">
      <EventArt event={featured} />
      <div className="discover-copy"><span className="event-category">{categoryNames[featured.category]}</span>
        <h1>{featured.name}</h1><p>{featured.description}</p>
        <Link className="btn btn-primary" to={eventPath(featured.id) + '/seats'}>Find seats <ArrowRight size={19} /></Link>
      </div>
    </section> : <Empty title="New events will appear here" />}
    <div className="section-heading"><h2>Upcoming events</h2><Link className="text-link" to="/events">Browse all <ArrowDownRight size={17} /></Link></div>
    <div className="event-grid">{events.data?.map(event => <EventCard event={event} key={event.id} />)}</div>
  </main>
}

export function Events() {
  const [params, update] = useSearchParams()
  const query = useEvents(params.size ? '?' + params.toString() : '')
  const search = params.get('q') ?? ''
  return <main id="main-content" className="page-width page-content">
    <div className="content-title"><h1>Upcoming events</h1></div>
    <form className="glass filters" key={search} onSubmit={event => {
      event.preventDefault()
      const q = new FormData(event.currentTarget).get('q')?.toString().trim() ?? ''
      update(previous => { const next = new URLSearchParams(previous); if (q) next.set('q', q); else next.delete('q'); return next })
    }}>
      <label><span>Search events</span><input name="q" defaultValue={search} placeholder="Event, venue, city, or YYYY-MM-DD" /></label>
      <label><span>Category</span><select value={params.get('category') ?? ''} onChange={event => {
        const value = event.target.value
        update(previous => { const next = new URLSearchParams(previous); if (value) next.set('category', value); else next.delete('category'); return next })
      }}><option value="">All categories</option>{Object.entries(categoryNames).map(([key, name]) => <option key={key} value={key}>{name}</option>)}</select></label>
      <label><span>Sort by</span><select value={params.get('sort') ?? ''} onChange={event => {
        const value = event.target.value
        update(previous => { const next = new URLSearchParams(previous); if (value) next.set('sort', value); else next.delete('sort'); return next })
      }}><option value="">Soonest first</option><option value="price">Lowest price</option><option value="latest">Latest date</option></select></label>
      <button type="submit" className="btn btn-primary"><Search size={17} /> Search</button>
    </form>
    {query.isPending && <Pending label="Searching events" />}
    {query.error && <ErrorPanel error={query.error} retry={() => void query.refetch()} />}
    {query.isSuccess && !query.data.length && <Empty title="No matching events"><button type="button" className="btn btn-secondary" onClick={() => update({})}>Clear filters</button></Empty>}
    {query.data && <div className="event-grid">{query.data.map(event => <EventCard event={event} key={event.id} />)}</div>}
  </main>
}

export function EventDetails() {
  const id = useParams().eventId ?? ''
  const event = useEvent(id)
  if (event.isPending) return <main id="main-content" className="page-width page-content"><Pending label="Loading event details" /></main>
  if (event.isError) return <main id="main-content" className="page-width page-content"><ErrorPanel error={event.error} retry={() => void event.refetch()} /></main>
  return <main id="main-content" className="page-width page-content event-page">
    <EventHero event={event.data} coverLink={false} />
    <div className="glass details-card">
      <h2>Event tickets</h2><p>Prices from <strong>{money(event.data.minPriceMinor)}</strong></p>
      <div className="action-row"><Link className="btn btn-primary" to={eventPath(id) + '/seats'}><Ticket size={18} /> Choose seats <ArrowRight size={18} /></Link>
        <CalendarButton event={event.data} />
        <Link className="text-link" to="/events">All events</Link></div>
    </div>
  </main>
}

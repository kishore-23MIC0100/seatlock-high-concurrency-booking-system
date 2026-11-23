import { CalendarDays, Clock3, MapPin, UsersRound } from 'lucide-react'
import { Link } from 'react-router-dom'
import { artwork, categoryNames, date, time } from '../format'
import type { Event } from '../model'
import { eventPath } from '../api'

export function EventArt({ event, className = '' }: { event: Event; className?: string }) {
  const src = artwork(event.artKey)
  return <div className={'event-art ' + className}>
    {src && <img src={src} alt="" />}
  </div>
}
export function EventHero({ event, coverLink = true }: { event: Event; coverLink?: boolean }) {
  const words = event.name.split(' ')
  const last = words.pop()
  const src = artwork(event.artKey)
  const cover = <EventArt event={event} className="hero-cover" />
  return <>
    {src && <div aria-hidden="true" className="hero-background"
      style={{ backgroundImage: 'var(--hero-fade-x), var(--hero-fade-y), url(' + src + ')' }} />}
    <section className="event-hero" aria-labelledby="event-heading">
      {coverLink ? <Link className="hero-cover-link" to={eventPath(event.id)} aria-label={'About ' + event.name}>{cover}</Link> : cover}
      <div className="hero-text">
        <div className="hero-tags"><span className="event-category">{categoryNames[event.category]}</span>
          {event.demo && <span className="demo-label" title="Seeded development event">Demo event</span>}</div>
        <h1 id="event-heading">{words.join(' ')} {last && <span>{last}</span>}</h1>
        <p>{event.description}</p>
        <ul className="event-metadata" aria-label="Event information">
          <li><CalendarDays size={17} />{date(event.startsAt, event.zoneId)}</li>
          <li><Clock3 size={17} />{time(event.startsAt, event.zoneId)} – {time(event.endsAt, event.zoneId)}</li>
          <li><MapPin size={17} />{event.venue}, {event.city}</li>
          <li><UsersRound size={17} />{event.capacity.toLocaleString('en-IN')} seats</li>
        </ul>
      </div>
    </section>
  </>
}

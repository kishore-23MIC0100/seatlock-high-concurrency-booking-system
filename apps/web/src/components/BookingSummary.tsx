import { Armchair, ArrowRight, CalendarDays, Clock3, LoaderCircle, LockKeyhole, MapPin, Ticket, X } from 'lucide-react'
import { useId, type ReactNode } from 'react'
import { date, money, seatName, time } from '../format'
import type { Event, HeldSeat } from '../model'
import { EventArt } from './EventHero'

export type Totals = { subtotalMinor: number; convenienceMinor: number; platformMinor: number; totalMinor: number }

export function BookingSummary({ event, seats, totals, countdown, remove, action, error }: {
  event: Event; seats: HeldSeat[]; totals: Totals; countdown?: string; remove?: (seat: HeldSeat) => void
  action?: ReactNode; error?: ReactNode
}) {
  const heading = useId()
  const total = money(totals.totalMinor)
  const singlePrice = seats.length > 0 && seats.every(seat => seat.priceMinor === seats[0].priceMinor)
  return <section className="glass summary" aria-labelledby={heading}>
    <h2 id={heading}><Ticket size={24} /> Booking Summary</h2>
    <div className="summary-body">
      <div className="summary-event">
        <EventArt event={event} className="summary-thumb" />
        <div><h3>{event.name}</h3>
          <p><CalendarDays size={14} /> {date(event.startsAt, event.zoneId)} · {time(event.startsAt, event.zoneId)}</p>
          <p><MapPin size={14} /> {event.venue}, {event.city}</p>
        </div>
      </div>
      <div className="summary-selected">
        <Armchair size={20} />
        <div><h3>Selected Seats{seats.length ? ' (' + seats.length + ')' : ''}</h3>
          {seats.length ? <ul className="chip-list">{seats.map(seat => <li key={seat.id}>
            {seatName(seat)}
            {remove && <button type="button" aria-label={'Remove seat ' + seatName(seat)} onClick={() => remove(seat)}><X size={13} /></button>}
          </li>)}</ul> : <p className="muted">Choose seats on the map.</p>}
        </div>
      </div>
      {countdown && <div className="summary-timer"><Clock3 size={21} /><span>Seats reserved for</span><output aria-live="off">{countdown}</output></div>}
      <dl className="price-breakdown">
        <div><dt>Ticket price{singlePrice && ' (' + money(seats[0].priceMinor) + ' × ' + seats.length + ')'}</dt><dd>{money(totals.subtotalMinor)}</dd></div>
        <div><dt>Convenience fee</dt><dd>{money(totals.convenienceMinor)}</dd></div>
        <div><dt>Platform fee</dt><dd>{money(totals.platformMinor)}</dd></div>
      </dl>
      <div className="summary-total"><span>Total Payable</span><strong className="total-value" key={total}>{total}</strong></div>
      {error}
      {action}
    </div>
  </section>
}

export function ContinueButton({ label, busy = false, disabled, onClick }: {
  label: string; busy?: boolean; disabled?: boolean; onClick: () => void
}) {
  return <button type="button" className="btn btn-primary continue-button" disabled={disabled || busy} onClick={onClick}>
    {busy ? <LoaderCircle size={17} className="spinner" /> : <LockKeyhole size={17} />} {busy ? 'Reserving your seats…' : label} <ArrowRight size={18} />
  </button>
}

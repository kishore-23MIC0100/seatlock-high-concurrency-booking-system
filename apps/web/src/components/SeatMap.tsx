import { Accessibility, Armchair, Check, Info, Maximize, Minus, Plus, UsersRound } from 'lucide-react'
import { useEffect, useMemo, useRef, useState, type CSSProperties, type KeyboardEvent } from 'react'
import { money, seatName } from '../format'
import type { Seat } from '../model'
import { groupSeatRows } from '../seatLayout'
import { SeatFinder } from './SeatFinder'
import { Modal } from './ui'

type MapProps = {
  seats: Seat[]; selected: Set<number>; onSelect: (seat: Seat) => void
  holdSeconds: number; live: boolean; allowSelection: boolean
}

export function SeatMap({ onGroup, ...props }: MapProps & { onGroup: (seats: Seat[]) => void }) {
  const { seats, holdSeconds, live, allowSelection } = props
  const [expanded, setExpanded] = useState(false)
  const [reveal, setReveal] = useState<{ id: number }>()
  const available = seats.filter(seat => seat.state === 'AVAILABLE').length
  const duration = holdSeconds % 60 === 0 ? holdSeconds / 60 + ' minutes' : holdSeconds + ' seconds'
  return <section className="glass map-panel" aria-labelledby="map-heading">
    <div className="map-heading">
      <div className="map-heading-copy"><span className="feature-icon"><Armchair size={24} /></span>
        <div><h2 id="map-heading">Choose Your Seats</h2>
          <p>Pick your seats. Confirmed holds last {duration}. <Info size={14} aria-hidden="true" /></p></div>
      </div>
      <div className="map-tools">
        <div className="map-status">
          <span title={live ? 'Live availability stream connected' : 'The seat map refreshes periodically'}
            className={'live-indicator' + (live ? '' : ' reconnecting')}><i />{live ? 'Live seat availability' : 'Reconnecting'}</span>
          <span className="seats-left"><UsersRound size={16} /> {available.toLocaleString('en-IN')} seats left</span>
        </div>
        <SeatFinder seats={seats} disabled={!allowSelection || !available} onChoose={group => {
          onGroup(group)
          setReveal({ id: group[0].id })
        }} />
      </div>
    </div>
    <Canvas {...props} reveal={reveal} expand={() => setExpanded(true)} />
    <Legend duration={duration} />
    <Modal open={expanded} onOpenChange={setExpanded} title="Seat map" className="expanded-map">
      <Canvas {...props} />
      <Legend duration={duration} />
    </Modal>
  </section>
}

function Legend({ duration }: { duration: string }) {
  return <ul className="seat-legend" aria-label="Seat map legend">
    <li><span className="legend-seat available" />Available</li>
    <li><span className="legend-seat selected" />Selected</li>
    <li><span className="legend-seat taken" />Taken</li>
    <li><Accessibility size={21} />Accessible</li>
    <li className="legend-time"><Info size={17} /> Holds last {duration}</li>
  </ul>
}

function Canvas({ seats, selected, onSelect, allowSelection, reveal, expand }: MapProps & {
  reveal?: { id: number }; expand?: () => void
}) {
  const [zoom, setZoom] = useState(1)
  const [focus, setFocus] = useState<number>()
  const buttons = useRef(new Map<number, HTMLButtonElement>())
  const viewport = useRef<HTMLDivElement>(null)
  const groups = useMemo(() => groupSeatRows(seats), [seats])

  useEffect(() => {
    const map = viewport.current
    const first = reveal && buttons.current.get(reveal.id)
    if (!map || !first) return
    const position = first.getBoundingClientRect().left - map.getBoundingClientRect().left + map.scrollLeft
    map.scrollTo({
      left: position - (map.clientWidth - first.offsetWidth) / 2,
      behavior: matchMedia('(prefers-reduced-motion: reduce)').matches ? 'instant' : 'smooth',
    })
  }, [reveal])
  const focusId = focus ?? seats.find(seat => seat.state === 'AVAILABLE')?.id ?? seats[0]?.id

  function navigate(event: KeyboardEvent, row: number, column: number) {
    let next: Seat | undefined
    if (event.key === 'ArrowLeft') next = groups[row][column - 1]
    if (event.key === 'ArrowRight') next = groups[row][column + 1]
    if (event.key === 'ArrowUp') next = groups[row - 1]?.[column]
    if (event.key === 'ArrowDown') next = groups[row + 1]?.[column]
    if (event.key === 'Home') next = groups[row][0]
    if (event.key === 'End') next = groups[row].at(-1)
    if (next) {
      event.preventDefault()
      buttons.current.get(next.id)?.focus()
    }
  }

  return <div className="map-area">
    <div className="stage" aria-label="Stage">
      <div className="stage-depth" aria-hidden="true"><i /><i /><i /><i /></div>
      <span>STAGE</span>
    </div>
    <div className="map-viewport" ref={viewport} tabIndex={0} role="region" aria-label="Scrollable seat map">
      <div className="map-canvas" style={{ '--map-zoom': zoom } as CSSProperties}>
        <div className="seat-rows" aria-label="Seating rows">
          {groups.map((row, rowIndex) => <div className="seat-row" key={row[0].id} role="group" aria-label={row[0].section + ' ' + row[0].row}>
            <span className="row-label" aria-hidden="true">{row[0].row}</span>
            {row.map((seat, index) => {
              const ratio = row.length > 1 ? (index * 2) / (row.length - 1) - 1 : 0
              const isSelected = selected.has(seat.id)
              const unavailable = seat.state !== 'AVAILABLE'
              const status = unavailable ? 'Taken' : isSelected ? 'Selected' : 'Available'
              return <button type="button" key={seat.id} data-seat={seatName(seat)}
                ref={element => { if (element) buttons.current.set(seat.id, element); else buttons.current.delete(seat.id) }}
                className={'seat ' + (unavailable ? 'taken' : isSelected ? 'selected' : 'available')}
                aria-label={'Seat ' + seatName(seat) + ', ' + seat.section + ', ' + (seat.accessible ? 'wheelchair accessible, ' : '') + money(seat.priceMinor) + ', ' + status}
                aria-pressed={isSelected} aria-disabled={!allowSelection || (unavailable && !isSelected)} tabIndex={focusId === seat.id ? 0 : -1}
                onFocus={() => setFocus(seat.id)} onKeyDown={event => navigate(event, rowIndex, index)}
                style={{ '--seat-arc': (1 - ratio * ratio) * 13 + 'px', '--seat-angle': -ratio * 5 + 'deg' } as CSSProperties}
                onClick={() => { if (allowSelection && (!unavailable || isSelected)) onSelect(seat) }}>
                {seat.accessible ? <Accessibility size={14} aria-hidden="true" />
                  : isSelected && !unavailable ? <Check size={13} className="seat-check" aria-hidden="true" /> : null}
                <span className="seat-tooltip" aria-hidden="true">{seatName(seat)} · {unavailable ? 'Taken' : seat.tier + ' · ' + money(seat.priceMinor)}</span>
              </button>
            })}
          </div>)}
        </div>
      </div>
    </div>
    <div className="map-controls" aria-label="Map controls">
      <div className="zoom-group">
        <button type="button" aria-label="Zoom in" disabled={zoom >= 1.59} onClick={() => setZoom(value => Math.min(1.6, value + .2))}><Plus size={20} /></button>
        <button type="button" aria-label="Zoom out" disabled={zoom <= .61} onClick={() => setZoom(value => Math.max(.6, value - .2))}><Minus size={20} /></button>
      </div>
      {expand && <button type="button" className="zoom-expand" aria-label="Expand seat map" onClick={expand}><Maximize size={17} /></button>}
    </div>
  </div>
}

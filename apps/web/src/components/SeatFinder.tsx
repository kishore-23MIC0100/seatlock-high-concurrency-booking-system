import { Accessibility, ArrowRight, Sparkles } from 'lucide-react'
import { useState, type FormEvent } from 'react'
import type { Seat } from '../model'
import { suggestSeats } from '../seatLayout'
import { Modal } from './ui'

export function SeatFinder({ seats, disabled, onChoose }: {
  seats: Seat[]; disabled: boolean; onChoose: (group: Seat[]) => void
}) {
  const [open, setOpen] = useState(false)
  const [noMatch, setNoMatch] = useState(false)

  function updateOpen(next: boolean) {
    setOpen(next)
    setNoMatch(false)
  }

  function find(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (disabled) return
    const form = new FormData(event.currentTarget)
    const group = suggestSeats(seats, Number(form.get('quantity')), form.get('preference') === 'price' ? 'price' : 'view', form.has('accessible'))
    if (!group.length) {
      setNoMatch(true)
      return
    }
    onChoose(group)
    updateOpen(false)
  }

  return <Modal open={open} onOpenChange={updateOpen} title="Find seats together"
    description="Choose an adjacent group from the live seat map. You can review the selection before reserving."
    trigger={<button type="button" className="seat-finder-button" disabled={disabled}>
      <Sparkles size={15} aria-hidden="true" /> Find seats together
    </button>}>
    <form className="form-stack finder-form" onSubmit={find} onChange={() => setNoMatch(false)}>
      <div className="form-grid">
        <label>Number of seats<select name="quantity" defaultValue="2">
          {[1, 2, 3, 4, 5, 6].map(quantity => <option key={quantity} value={quantity}>{quantity}</option>)}
        </select></label>
        <label>Seat preference<select name="preference" defaultValue="view">
          <option value="view">Closest to stage</option>
          <option value="price">Lowest price</option>
        </select></label>
      </div>
      <label className="checkbox accessible-choice"><input type="checkbox" name="accessible" />
        <Accessibility size={18} aria-hidden="true" /> Include a wheelchair-accessible seat
      </label>
      {noMatch && <p className="info-line" role="status">No adjacent group matches. Try selecting fewer seats.</p>}
      <button type="submit" className="btn btn-primary" disabled={disabled}>Select matching seats <ArrowRight size={17} /></button>
    </form>
  </Modal>
}

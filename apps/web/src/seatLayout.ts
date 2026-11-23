import type { Seat } from './model'

export function groupSeatRows(seats: Seat[]) {
  const rows = new Map<string, Seat[]>()
  for (const seat of seats) {
    const key = JSON.stringify([seat.section, seat.row])
    const row = rows.get(key)
    if (row) row.push(seat)
    else rows.set(key, [seat])
  }
  return [...rows.values()].map(row => row.sort((a, b) => a.number - b.number))
}

type Preference = 'view' | 'price'

export function suggestSeats(seats: Seat[], quantity: number, preference: Preference, needsAccessible: boolean): Seat[] {
  if (!Number.isInteger(quantity) || quantity < 1 || quantity > 6) return []
  const candidates: { seats: Seat[]; accessible: number; total: number; row: number; offset: number }[] = []
  groupSeatRows(seats).forEach((row, rowIndex) => {
    const center = (row[0].number + row[row.length - 1].number) / 2
    for (let start = 0; start <= row.length - quantity; start++) {
      const group = row.slice(start, start + quantity)
      if (!group.every((seat, i) => seat.state === 'AVAILABLE' && seat.number === group[0].number + i)) continue
      const accessible = group.filter(seat => seat.accessible).length
      if (needsAccessible && !accessible) continue
      candidates.push({
        seats: group,
        accessible,
        total: group.reduce((total, seat) => total + seat.priceMinor, 0),
        row: rowIndex,
        offset: Math.abs(group[0].number + (quantity - 1) / 2 - center),
      })
    }
  })
  candidates.sort((a, b) => a.accessible - b.accessible
    || (preference === 'price' ? a.total - b.total : 0)
    || a.row - b.row || a.offset - b.offset || a.total - b.total)
  return candidates[0]?.seats ?? []
}

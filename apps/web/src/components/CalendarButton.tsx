import { CalendarPlus } from 'lucide-react'
import type { Event } from '../model'

const encoder = new TextEncoder()
const calendarDate = (date: string) => new Date(date).toISOString().replace(/[-:]/g, '').replace(/\.\d{3}Z$/, 'Z')
const calendarText = (value: string) => value.replaceAll('\\', '\\\\')
  .replace(/\r\n|\r|\n/g, '\\n').replaceAll(',', '\\,').replaceAll(';', '\\;')

function foldCalendarLine(line: string) {
  const chunks: string[] = []
  let current = ''
  let bytes = 0
  for (const character of line) {
    const size = encoder.encode(character).byteLength
    if (bytes + size > 75) {
      chunks.push(current)
      current = ' '
      bytes = 1
    }
    current += character
    bytes += size
  }
  chunks.push(current)
  return chunks.join('\r\n')
}

function downloadCalendar(event: Event) {
  const lines = [
    'BEGIN:VCALENDAR', 'VERSION:2.0', 'PRODID:-//SeatLock//Event Calendar//EN', 'CALSCALE:GREGORIAN',
    'BEGIN:VEVENT', 'UID:' + calendarText(event.id) + '@seatlock',
    'DTSTAMP:' + calendarDate(new Date().toISOString()),
    'DTSTART:' + calendarDate(event.startsAt), 'DTEND:' + calendarDate(event.endsAt),
    'SUMMARY:' + calendarText(event.name),
    'LOCATION:' + calendarText(event.venue + ', ' + event.city),
    'DESCRIPTION:' + calendarText(event.description),
    'END:VEVENT', 'END:VCALENDAR',
  ]
  const url = URL.createObjectURL(new Blob([lines.map(foldCalendarLine).join('\r\n') + '\r\n'], { type: 'text/calendar;charset=utf-8' }))
  const link = document.createElement('a')
  link.href = url
  link.download = event.id + '.ics'
  document.body.append(link)
  link.click()
  link.remove()
  window.setTimeout(() => URL.revokeObjectURL(url), 1000)
}

export function CalendarButton({ event }: { event: Event }) {
  return <button type="button" className="btn btn-secondary" onClick={() => downloadCalendar(event)}>
    <CalendarPlus size={17} aria-hidden="true" /> Add to calendar
  </button>
}

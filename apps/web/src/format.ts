const currency = new Intl.NumberFormat('en-IN', { style: 'currency', currency: 'INR', minimumFractionDigits: 0 })
export const money = (minor: number) => currency.format(minor / 100)
export const date = (instant: string, timeZone?: string) => new Intl.DateTimeFormat('en-GB', {
  weekday: 'short', day: 'numeric', month: 'short', year: 'numeric', timeZone,
}).format(new Date(instant))
export const time = (instant: string, timeZone?: string) => new Intl.DateTimeFormat('en-IN', {
  hour: 'numeric', minute: '2-digit', hour12: true, timeZone,
}).format(new Date(instant)).toUpperCase()
export const seatName = (seat: { row: string; number: number }) => seat.row + seat.number
export const categoryNames = { MUSIC: 'Live concert', COMEDY: 'Comedy', THEATRE: 'Theatre', SPORTS: 'Sports' }
export const artwork = (key: string) => {
  const files: Record<string, string> = { neon: '/assets/neon-nights.jpg', afterglow: '/assets/afterglow.jpg', midnight: '/assets/midnight.jpg' }
  return files[key] ?? null
}

export function internalRedirect(value: string | null, fallback = '/') {
  return value?.startsWith('/') && !value.startsWith('//') && !value.includes('\\') ? value : fallback
}

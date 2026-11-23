import { readFile } from 'node:fs/promises'
import { expect, test, type APIRequestContext, type Page } from '@playwright/test'

const seatPage = '/events/neon-nights/seats'
const api = '/api/v1'
const selected = (page: Page, seat: string) => page.locator('button[data-seat="' + seat + '"]')
const available = (page: Page) => page.locator('button[data-seat^="B"][aria-label$=", Available"]')

async function auth(request: APIRequestContext, role = 'CUSTOMER') {
  const response = await request.get(api + '/auth/csrf')
  expect(response.ok()).toBeTruthy()
  const csrf = await response.json() as { token: string; headerName: string }
  const session = await request.post(api + '/auth/demo', { headers: { [csrf.headerName]: csrf.token }, data: { role } })
  expect(session.ok(), await session.text()).toBeTruthy()
  const refresh = await request.get(api + '/auth/csrf')
  const token = await refresh.json() as { token: string; headerName: string }
  return { [token.headerName]: token.token }
}

test('SeatLock Pay completes a reviewed demo booking and issues a marked QR ticket', async ({ page, browser, baseURL }) => {
  const observer = await browser.newContext({ baseURL })
  const other = await observer.newPage()
  let bookingId: string | undefined
  try {
    await Promise.all([page.goto('/'), other.goto(seatPage)])
    await expect(page.getByRole('heading', { name: 'Choose Your Seats' })).toBeVisible()
    await expect(other.getByText('Live seat availability')).toBeVisible()
    const seats: string[] = []
    for (let i = 0; i < 2; i++) {
      const seat = available(page).first()
      seats.push((await seat.getAttribute('data-seat'))!)
      await seat.click()
    }
    await expect(page.getByText('Selected Seats (2)')).toBeVisible()
    await expect(page.getByText('₹4,206', { exact: true }).filter({ visible: true })).toBeVisible()
    await page.getByRole('button', { name: 'Continue to Checkout' }).click()
    await page.getByRole('button', { name: 'Continue as demo guest' }).click()
    await expect(page.getByRole('heading', { name: 'Review your booking' })).toBeVisible()
    await expect(page.getByRole('region', { name: 'SeatLock Pay' })).toHaveCount(0)
    await expect(page.getByText('Seats reserved for')).toBeVisible()
    const holdResponse = await page.request.get(api + '/holds/' + page.url().split('/').at(-1))
    expect(holdResponse.ok()).toBeTruthy()
    const hold = await holdResponse.json() as { createdAt: string; expiresAt: string }
    expect(Date.parse(hold.expiresAt)).toBeGreaterThan(Date.parse(hold.createdAt))
    const timer = page.getByRole('timer', { name: 'Time remaining' })
    await expect(timer).toHaveText(/^\d{2}:\d{2}$/)
    const initialTime = await timer.textContent()
    await expect.poll(() => timer.textContent()).not.toBe(initialTime)
    await expect(selected(other, seats[0])).toHaveAttribute('aria-label', /Taken$/)
    await page.getByRole('button', { name: 'Continue to Payment' }).click()
    await expect(page.getByRole('heading', { name: 'Secure payment' })).toBeVisible()
    await expect(page.getByRole('region', { name: 'SeatLock Pay' })).toBeVisible()
    await expect(page.getByRole('button', { name: 'Dismiss notification' })).toHaveCount(0)
    await expect(page.getByText('No real money moves.')).toBeVisible()
    const holdId = page.url().split('/').at(-1)
    const chargePath = api + '/holds/' + holdId + '/payment/demo'
    const headers = await auth(other.request)
    expect((await other.request.get(api + '/holds/' + holdId + '/payment')).status()).toBe(404)
    expect((await other.request.post(chargePath, { headers })).status()).toBe(404)
    expect((await other.request.get(api + '/admin/bookings')).status()).toBe(403)
    const webhook = await other.request.post(api + '/payments/razorpay/webhook', { headers, data: { event: 'payment.captured' } })
    expect(webhook.status()).toBe(403)
    const payment = page.waitForResponse(response => response.url().endsWith(chargePath) && response.request().method() === 'POST')
    await page.getByRole('button', { name: 'Pay ₹4,206 in demo' }).click()
    const paid = await payment
    expect(paid.ok(), await paid.text()).toBeTruthy()
    const state = await paid.json() as { mode: string; status: string; bookingId: string }
    bookingId = state.bookingId
    expect(state).toMatchObject({ mode: 'DEMO', status: 'APPROVED' })
    await expect(page.getByRole('heading', { name: 'Booking confirmed' })).toBeVisible()
    const booking = await (await page.request.get(api + '/me/bookings/' + bookingId)).json() as {
      provider: string; amountMinor: number; paymentId: string; tickets: { id: string; demo: boolean }[]
    }
    expect(booking).toMatchObject({ provider: 'SEATLOCK_DEMO', amountMinor: 420600 })
    expect(booking.tickets).toHaveLength(2)
    await expect(page.getByText(booking.paymentId, { exact: true })).toBeVisible()
    await page.getByRole('link', { name: 'View ticket' }).first().click()
    await expect(page.getByRole('heading', { name: 'Demo ticket' })).toBeVisible()
    const qr = page.getByRole('img', { name: 'Your demo ticket QR code' })
    await expect.poll(() => qr.evaluate((image: HTMLImageElement) => image.naturalWidth)).toBe(220)
    const downloadable = await page.request.get(api + '/me/tickets/' + booking.tickets[0].id + '/download')
    expect(await downloadable.text()).toContain('SeatLock / Demo ticket')
    const admin = await auth(other.request, 'ADMIN')
    const refund = await other.request.post(api + '/admin/bookings/' + bookingId + '/refund', {
      headers: admin, data: { reason: 'Browser test fixture cleanup' },
    })
    expect(refund.ok(), await refund.text()).toBeTruthy()
    bookingId = undefined
    await expect(selected(other, seats[0])).toHaveAttribute('aria-label', /Available$/)
  } finally {
    if (bookingId) {
      const admin = await auth(other.request, 'ADMIN')
      await other.request.post(api + '/admin/bookings/' + bookingId + '/refund', {
        headers: admin, data: { reason: 'Browser test fixture cleanup' },
      })
    }
    await observer.close()
  }
})

test('a complimentary event issues a real ticket and calendar download without collecting a payment', async ({ page, request }) => {
  const headers = await auth(request, 'ORGANIZER')
  const venues = await (await request.get(api + '/organizer/venues')).json() as { id: string }[]
  const now = Date.now()
  const name = 'Test complimentary concert ' + now
  const created = await request.post(api + '/organizer/events', { headers, data: {
    venueId: venues[0].id, name, description: 'Private test fixture for complimentary booking.',
    category: 'MUSIC', saleStartsAt: new Date(now - 60_000).toISOString(),
    startsAt: new Date(now + 12_000_000).toISOString(), endsAt: new Date(now + 15_600_000).toISOString(),
    priceMinor: 0, premiumRows: 0,
  } })
  expect(created.status(), await created.text()).toBe(201)
  const event = await created.json() as { id: string }
  expect((await request.post(api + '/organizer/events/' + event.id + '/publish', { headers })).status()).toBe(200)
  await page.goto('/events/' + event.id + '/seats')
  await page.locator('button[data-seat][aria-label$=", Available"]').first().click()
  await page.getByRole('button', { name: 'Continue to Checkout' }).click()
  await page.getByRole('button', { name: 'Continue as demo guest' }).click()
  await expect(page.getByRole('heading', { name: 'Review your booking' })).toBeVisible()
  await page.getByRole('button', { name: 'Continue to Confirmation' }).click()
  await page.getByRole('button', { name: 'Confirm free booking' }).click()
  await expect(page.getByRole('heading', { name: 'Booking confirmed' })).toBeVisible()
  await expect(page.getByText('Complimentary booking')).toBeVisible()
  const calendarDownload = page.waitForEvent('download')
  await page.getByRole('button', { name: 'Add to calendar' }).click()
  const calendar = await calendarDownload
  expect(calendar.suggestedFilename()).toBe(event.id + '.ics')
  const calendarContent = await readFile(await calendar.path(), 'utf8')
  expect(calendarContent).toMatch(/BEGIN:VCALENDAR\r\n/)
  expect(calendarContent).toMatch(/DTSTART:\d{8}T\d{6}Z\r\n/)
  expect(calendarContent).toContain('SUMMARY:' + name)
  await expect.poll(async () => {
    const notices = await page.request.get(api + '/me/notifications')
    return JSON.stringify(await notices.json())
  }, { timeout: 12_000 }).toContain('Your tickets are ready')
  await page.getByRole('link', { name: 'View ticket' }).first().click()
  const qr = page.getByRole('img', { name: 'Your unique admission QR code' })
  await expect.poll(() => qr.evaluate((image: HTMLImageElement) => image.naturalWidth)).toBe(220)
  const download = page.waitForEvent('download')
  await page.getByRole('link', { name: 'Download ticket' }).click()
  expect((await download).suggestedFilename()).toMatch(/^seatlock-.*\.html$/)
})

test('mobile map scrolls inside the viewport and the review drawer exposes the selection', async ({ browser, baseURL }) => {
  const context = await browser.newContext({ baseURL, viewport: { width: 390, height: 844 }, hasTouch: true, isMobile: true })
  const page = await context.newPage()
  try {
    await page.goto(seatPage)
    await expect(page.getByRole('heading', { name: 'Choose Your Seats' })).toBeVisible()
    const viewport = page.getByRole('region', { name: 'Scrollable seat map' })
    expect(await viewport.evaluate(element => element.scrollWidth > element.clientWidth)).toBe(true)
    expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(390)
    const target = available(page).first()
    const id = (await target.getAttribute('data-seat'))!
    await target.tap()
    await page.getByRole('button', { name: 'Review booking' }).click()
    const drawer = page.getByRole('dialog', { name: 'Review booking' })
    await expect(drawer.getByRole('button', { name: 'Remove seat ' + id })).toBeVisible()
    await drawer.getByRole('button', { name: 'Close' }).click()
    await expect(drawer).toBeHidden()
    await page.setViewportSize({ width: 320, height: 780 })
    const [brand, appearance] = await Promise.all([
      page.getByRole('link', { name: 'SeatLock home' }).boundingBox(),
      page.getByRole('button', { name: 'Appearance' }).boundingBox(),
    ])
    expect(Math.abs(brand!.y + brand!.height / 2 - appearance!.y - appearance!.height / 2)).toBeLessThan(4)
    expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(320)
  } finally {
    await context.close()
  }
})

test('the map supports keyboard navigation, zoom and reconnect reconciliation', async ({ page, context, browser, baseURL }) => {
  const other = await browser.newContext({ baseURL })
  try {
    await page.goto(seatPage)
    await expect(page.getByText('Live seat availability')).toBeVisible()
    const seat = available(page).first()
    await seat.focus()
    await page.keyboard.press('ArrowRight')
    await expect(seat).not.toBeFocused()
    await page.keyboard.press('Enter')
    const map = page.getByRole('region', { name: 'Scrollable seat map' })
    const before = await map.evaluate(element => element.scrollWidth)
    await page.getByRole('button', { name: 'Zoom in' }).click()
    await page.getByRole('button', { name: 'Zoom in' }).click()
    expect(await map.evaluate(element => element.scrollWidth)).toBeGreaterThan(before)
    await context.setOffline(true)
    const inventory = await (await other.request.get(api + seatPage)).json() as { seats: { id: number; row: string; number: number; state: string }[] }
    const target = inventory.seats.find(item => item.row === 'B' && item.state === 'AVAILABLE')!
    const headers = await auth(other.request)
    const hold = await other.request.post(api + '/holds', { headers: { ...headers, 'Idempotency-Key': crypto.randomUUID() },
      data: { eventId: 'neon-nights', seatIds: [target.id] } })
    expect(hold.status(), await hold.text()).toBe(201)
    await context.setOffline(false)
    await expect(selected(page, target.row + target.number)).toHaveAttribute('aria-label', /Taken$/)
    const owned = await hold.json() as { id: string }
    await other.request.delete(api + '/holds/' + owned.id, { headers })
    await expect(selected(page, target.row + target.number)).toHaveAttribute('aria-label', /Available$|Selected$/)
  } finally {
    await context.setOffline(false)
    await other.close()
  }
})

test('admin and organizer routes require the correct session', async ({ page, request }) => {
  expect((await request.post(api + '/auth/demo', { data: { role: 'ADMIN' } })).status()).toBe(403)
  await page.goto('/ops')
  await expect(page.getByRole('heading', { name: 'Sign in to SeatLock' })).toBeVisible()
  await page.getByRole('button', { name: 'Admin demo' }).click()
  await expect(page.getByRole('heading', { name: 'Administration', exact: true })).toBeVisible()
  await page.getByRole('link', { name: 'Open Live Operations' }).click()
  await expect(page.getByRole('heading', { name: 'Operations overview' })).toBeVisible()
  await expect(page.getByText('Database connections')).toBeVisible()
  expect((await page.request.get(api + '/organizer/events')).status()).toBe(403)
  await page.goto('/login')
  await page.getByRole('button', { name: 'Organizer demo' }).click()
  await expect(page.getByRole('heading', { name: 'Organizer Studio' })).toBeVisible()
  await expect(page.getByText('Tickets issued')).toBeVisible()
  expect((await page.request.get(api + '/admin/bookings')).status()).toBe(403)
})

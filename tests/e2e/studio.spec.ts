import { expect, test, type APIRequestContext } from '@playwright/test'

const api = '/api/v1'
const inputTime = (value: Date) => new Date(value.getTime() - value.getTimezoneOffset() * 60_000).toISOString().slice(0, 16)

async function session(request: APIRequestContext) {
  const before = await (await request.get(api + '/auth/csrf')).json()
  expect((await request.post(api + '/auth/demo', { headers: { [before.headerName]: before.token },
    data: { role: 'CUSTOMER' } })).status()).toBe(200)
  const after = await (await request.get(api + '/auth/csrf')).json()
  return { [after.headerName]: after.token }
}

test('organizer publishes its own venue and released inventory goes to the waitlist', async ({ page, browser, baseURL }) => {
  const name = 'Test concert ' + Date.now()
  await page.goto('/login')
  await page.getByRole('button', { name: 'Organizer demo' }).click()
  await page.getByRole('link', { name: 'Venues', exact: true }).click()
  await page.getByRole('button', { name: 'Add venue' }).click()
  await page.getByLabel('Venue name').fill(name + ' venue')
  await page.getByLabel('City').fill('Bengaluru')
  await page.getByLabel('Rows', { exact: true }).fill('1')
  await page.getByLabel('Seats per row').fill('2')
  await page.getByLabel('Accessible seat labels').fill('A2')
  await page.getByRole('button', { name: 'Create venue' }).click()
  await expect(page.getByRole('heading', { name: name + ' venue' })).toBeVisible()
  const venue = page.url().split('/').at(-2)!
  await page.goto('/organizer/events/new')
  await page.getByLabel('Event name').fill(name)
  await page.getByLabel('Description', { exact: true }).fill('Private test fixture for event booking.')
  await page.getByLabel('Venue', { exact: true }).selectOption(venue)
  const now = Date.now()
  await page.getByLabel('Sales open').fill(inputTime(new Date(now - 3600_000)))
  await page.getByLabel('Event starts').fill(inputTime(new Date(now + 3 * 3600_000)))
  await page.getByLabel('Event ends').fill(inputTime(new Date(now + 4 * 3600_000)))
  await page.getByLabel('Standard price (₹)').fill('850')
  await page.getByRole('button', { name: 'Create draft' }).click()
  await expect(page.getByRole('heading', { name })).toBeVisible()
  await page.getByRole('button', { name: 'Publish event' }).click()
  await expect(page.getByRole('link', { name: 'Open public seat map' })).toBeVisible()
  const eventId = page.url().split('/').at(-1)!
  const path = '/events/' + eventId + '/seats'
  const snapshot = await (await page.request.get(api + path)).json()
  expect(snapshot.event.capacity).toBe(2)
  expect(snapshot.seats[1].accessible).toBe(true)

  const buyer = await browser.newContext({ baseURL })
  const queued = await browser.newContext({ baseURL })
  try {
    const [buyHeaders, waitHeaders] = await Promise.all([session(buyer.request), session(queued.request)])
    const held = await buyer.request.post(api + '/holds', {
      headers: { ...buyHeaders, 'Idempotency-Key': crypto.randomUUID() },
      data: { eventId, seatIds: snapshot.seats.map((seat: { id: number }) => seat.id) },
    })
    expect(held.status(), await held.text()).toBe(201)
    const joined = await queued.request.post(api + '/waitlist', { headers: waitHeaders,
      data: { eventId, quantity: 1, needsAccessible: true } })
    expect(joined.status(), await joined.text()).toBe(201)
    expect((await joined.json()).position).toBe(1)
    const holdId = (await held.json()).id
    expect((await buyer.request.delete(api + '/holds/' + holdId, { headers: buyHeaders })).status()).toBe(200)
    const waitPage = await queued.newPage()
    await waitPage.goto('/waitlist')
    await waitPage.getByRole('link', { name: 'Accept offer' }).click()
    await expect(waitPage.getByText(/A2 held for/)).toBeVisible()
  } finally {
    await buyer.close()
    await queued.close()
  }
})

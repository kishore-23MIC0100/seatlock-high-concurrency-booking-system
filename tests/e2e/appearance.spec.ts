import { expect, test } from '@playwright/test'

const seatPage = '/events/neon-nights/seats'

test('appearance defaults to the system and keeps an override across tabs and reloads', async ({ page, context }) => {
  await page.emulateMedia({ colorScheme: 'dark' })
  await page.goto(seatPage)
  await expect(page.locator('html')).toHaveAttribute('data-theme', 'dark')
  await page.getByRole('button', { name: 'Appearance' }).click()
  const chooser = page.getByRole('dialog', { name: 'Appearance' })
  await expect(chooser.getByRole('radio', { name: /^System/ })).toBeChecked()
  await chooser.getByText('Light', { exact: true }).click()
  await expect(page.locator('html')).toHaveAttribute('data-theme', 'light')
  await page.keyboard.press('Escape')
  await expect(page.getByRole('button', { name: 'Appearance' })).toBeFocused()

  const second = await context.newPage()
  await second.emulateMedia({ colorScheme: 'dark' })
  await second.goto(seatPage)
  await expect(second.locator('html')).toHaveAttribute('data-theme', 'light')
  await page.reload()
  await expect(page.locator('html')).toHaveAttribute('data-theme', 'light')
  await page.getByRole('button', { name: 'Appearance' }).click()
  await chooser.getByText('System', { exact: true }).click()
  await expect(second.locator('html')).toHaveAttribute('data-theme', 'dark')
  await page.emulateMedia({ colorScheme: 'light' })
  await expect(page.locator('html')).toHaveAttribute('data-theme', 'light')
  await expect(page.locator('html')).toHaveCSS('color-scheme', 'light')
  await second.close()
})

test('the initial theme is set before the React bundle runs', async ({ page }) => {
  await page.emulateMedia({ colorScheme: 'light' })
  await page.route('**/*', route => {
    const request = route.request()
    return request.resourceType() === 'script' && !request.url().endsWith('/appearance.js') ? route.abort() : route.continue()
  })
  await page.goto(seatPage)
  await expect(page.locator('html')).toHaveAttribute('data-theme', 'light')
  await expect(page.locator('meta[name="theme-color"]')).toHaveAttribute('content', '#f5f3fc')
})

test('seat finder suggests adjacent wheelchair-accessible seating and respects reduced motion', async ({ page }) => {
  await page.emulateMedia({ colorScheme: 'light', reducedMotion: 'no-preference' })
  await page.goto(seatPage)
  await page.getByRole('button', { name: 'Find seats together' }).click()
  const finder = page.getByRole('dialog', { name: 'Find seats together' })
  await finder.locator('select[name="quantity"]').selectOption('3')
  await finder.locator('select[name="preference"]').selectOption('price')
  await finder.getByRole('checkbox', { name: /wheelchair-accessible/ }).check()
  await finder.getByRole('button', { name: 'Select matching seats' }).click()
  await expect(page.getByText('Selected Seats (3)', { exact: true })).toBeVisible()
  const selected = page.locator('button[data-seat][aria-pressed="true"]')
  await expect(selected).toHaveCount(3)
  const seats = await selected.evaluateAll(buttons => buttons.map(button => ({
    label: button.getAttribute('aria-label'), seat: button.getAttribute('data-seat'),
  })))
  expect(seats.some(seat => seat.label?.includes('wheelchair accessible'))).toBe(true)
  expect(new Set(seats.map(seat => seat.seat?.charAt(0))).size).toBe(1)
  const numbers = seats.map(seat => Number(seat.seat?.slice(1)))
  expect(numbers).toEqual([numbers[0], numbers[0] + 1, numbers[0] + 2])
  const ripple = () => selected.first().evaluate(seat => getComputedStyle(seat, '::after').animationName)
  expect(await ripple()).toBe('seat-ripple')
  await page.emulateMedia({ reducedMotion: 'reduce' })
  await expect.poll(ripple).toBe('none')
  const result = await page.request.get('/api/v1' + seatPage)
  const inventory = await result.json() as { seats: { row: string; number: number; state: string }[] }
  for (const seat of seats) {
    expect(inventory.seats.find(item => item.row + item.number === seat.seat)?.state).toBe('AVAILABLE')
  }
})

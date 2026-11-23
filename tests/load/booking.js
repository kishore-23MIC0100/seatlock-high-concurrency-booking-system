import http from 'k6/http'
import { check, sleep } from 'k6'
import { Counter, Rate, Trend } from 'k6/metrics'

const origin = __ENV.SEATLOCK_WEB_URL || 'http://127.0.0.1:8180'
if (!/^http:\/\/(localhost|127\.0\.0\.1):\d+$/.test(origin)) {
  throw new Error('Run this workload against a local SeatLock demo instance.')
}
const api = origin + '/api/v1'
const id = 'neon-nights'
const attempts = new Counter('hold_attempts')
const conflicts = new Rate('hold_conflicts')
const latency = new Trend('hold_latency', true)

export const options = {
  vus: 8,
  duration: '15s',
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(95)', 'p(99)'],
  thresholds: { checks: ['rate==1'], http_req_failed: ['rate<0.01'] },
}

function csrf() {
  const response = http.get(api + '/auth/csrf', { tags: { name: 'csrf' } })
  if (response.status !== 200) throw new Error('Unable to get a local CSRF token.')
  return { [response.json('headerName')]: response.json('token') }
}

export function setup() {
  const sessions = []
  const jar = http.cookieJar()
  const vus = Number(__ENV.SEATLOCK_LOAD_USERS ?? 8)
  if (!Number.isInteger(vus) || vus < 1 || vus > 20) throw new Error('Set SEATLOCK_LOAD_USERS between 1 and 20.')
  for (let i = 0; i < vus; i++) {
    jar.clear(origin)
    const response = http.post(api + '/auth/demo', JSON.stringify({ role: 'CUSTOMER' }),
      { headers: { ...csrf(), 'Content-Type': 'application/json' }, tags: { name: 'session' } })
    if (response.status !== 200) throw new Error('The local API must run with demo sessions enabled.')
    sessions.push({ cookie: jar.cookiesForURL(origin).SEATLOCK_SESSION[0], csrf: csrf() })
  }
  return { sessions, run: Date.now() }
}

export default function ({ sessions, run }) {
  const session = sessions[__VU - 1]
  if (!session) throw new Error('Set SEATLOCK_LOAD_USERS to the number of virtual users.')
  http.cookieJar().set(origin, 'SEATLOCK_SESSION', session.cookie)
  const headers = { ...session.csrf, 'Content-Type': 'application/json' }
  if (__ITER % 4 === 0) {
    const catalog = http.get(api + '/events', { tags: { name: 'catalog' } })
    check(catalog, { 'catalog returned 200': response => response.status === 200 })
  }
  const snapshot = http.get(api + '/events/' + id + '/seats', { tags: { name: 'inventory' } })
  if (!check(snapshot, { 'inventory returned 200': response => response.status === 200 })) return
  if (__ITER % 3 === 0) {
    const seats = snapshot.json('seats').filter(seat => seat.state === 'AVAILABLE' && !seat.accessible).slice(0, 12)
    if (!seats.length) { sleep(.3); return }
    const seat = seats[Math.floor(Math.random() * seats.length)]
    const key = 'load-' + run + '-' + __VU + '-' + __ITER
    const held = http.post(api + '/holds', JSON.stringify({ eventId: id, seatIds: [seat.id] }), {
      headers: { ...headers, 'Idempotency-Key': key }, tags: { name: 'hold' },
      responseCallback: http.expectedStatuses(201, 409),
    })
    attempts.add(1)
    conflicts.add(held.status === 409)
    latency.add(held.timings.duration)
    check(held, { 'hold accepted or reported seat contention': response => response.status === 201 ||
      (response.status === 409 && response.json('code') === 'SEAT_CONFLICT') })
    if (held.status === 201) {
      const holdId = held.json('id')
      const released = http.del(api + '/holds/' + holdId, null, { headers, tags: { name: 'release' } })
      check(released, { 'hold released': response => response.status === 200 && response.json('status') === 'CANCELLED' })
    }
  }
  sleep(.35)
}

export function handleSummary(data) {
  const rows = ['http_reqs','http_req_duration','http_req_failed','checks','hold_attempts','hold_conflicts','hold_latency']
  const summary = Object.fromEntries(rows.filter(key => data.metrics[key]).map(key => [key, data.metrics[key].values]))
  const output = JSON.stringify(summary, null, 2) + '\n'
  return __ENV.SEATLOCK_LOAD_RESULTS ? {stdout: output, [__ENV.SEATLOCK_LOAD_RESULTS]: output} : {stdout: output}
}

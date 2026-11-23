import { CartesianGrid, Line, LineChart, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts'
import { Link, Route, Routes } from 'react-router-dom'
import { Empty } from '../../components/ui'
import { date, time } from '../../format'
import { AuditTable, type AuditRow } from './Admin'
import { DataTable, Panel, State, Stats, useWorkspace } from './shared'

type Bucket = { startsAt: string; requests: number; clientErrors: number; serverErrors: number; p95Ms: number | null }
type Traffic = { requests: number; requestsPerSecond: number; clientErrors: number; serverErrors: number; p50Ms: number | null;
  p95Ms: number | null; p99Ms: number | null; trend: Bucket[]; capped: boolean }
type OpsStatus = {
  service: 'UP'; attempts: number; conflicts: number; workerFailures: number; databaseActive: number | null; databaseMaximum: number | null
  queueReady: number | null; queueDead: number | null; traffic: Traffic; serverTime: string
  inventory: { activeHolds: number; expiredHolds: number; validTickets: number; waitlistEntries: number; roomWaiting: number
    outboxPending: number; outboxFailed: number; paymentPending: number; paymentFailed: number
    oldestOutboxSeconds: number | null; oldestOverdueHoldSeconds: number | null }
}
type Reservation = { id: string; eventId: string; status: string; createdAt: string; expiresAt: string; seats: number }
type QueueEvent = { id: number; type: string; attempts: number; createdAt: string; nextAttemptAt: string; lastError: string | null }
type Incident = { id: string; stage: string; error: string; attempts: number; failedAt: string }
const millis = (value: number | null) => value === null ? '—' : value.toFixed(1) + ' ms'

function TrafficView({ traffic }: { traffic: Traffic }) {
  if (!traffic.requests) return <Empty title="No API requests in the last minute" />
  const points = traffic.trend.map(point => ({ ...point,
    timeLabel: new Date(point.startsAt).toLocaleTimeString(undefined, { minute: '2-digit', second: '2-digit' }),
  }))
  return <>
    <Stats items={[[traffic.capped ? 'Requests / sec (sample cap)' : 'Requests / sec', traffic.requestsPerSecond.toFixed(2)],
      ['p50 latency',millis(traffic.p50Ms)],['p95 latency',millis(traffic.p95Ms)],['p99 latency',millis(traffic.p99Ms)],
      ['4xx responses', traffic.clientErrors], ['5xx responses', traffic.serverErrors]]} />
    <div className="glass traffic-chart"><h3>p95 latency by 10-second interval (ms)</h3>
      <ResponsiveContainer width="100%" height={220}>
        <LineChart data={points} margin={{ top: 15, bottom: 3, right: 15, left: 3 }} accessibilityLayer>
          <CartesianGrid stroke="var(--line)" vertical={false} />
          <XAxis dataKey="timeLabel" tick={{ fill: 'var(--muted)', fontSize: 11 }} />
          <YAxis tick={{ fill: 'var(--muted)', fontSize: 11 }} width={47} />
          <Tooltip itemStyle={{ color: 'var(--ink)' }}
            contentStyle={{ background: 'var(--surface)', borderColor: 'var(--line)', color: 'var(--ink)' }} />
          <Line type="monotone" dataKey="p95Ms" name="p95 (ms)" stroke="var(--teal)" strokeWidth={2} isAnimationActive={false} dot />
        </LineChart>
      </ResponsiveContainer>
    </div>
  </>
}

function Overview() {
  const query = useWorkspace<OpsStatus>('/ops', 7000)
  const status = query.data
  return <Panel title="Operations overview" pending={query.isPending} error={query.error} retry={() => void query.refetch()}>
    {status && <>
      <div className="workspace-actions"><State value={'API ' + status.service} /><span className="muted">Updated {time(status.serverTime)}</span></div>
      <Stats items={[['Active holds',status.inventory.activeHolds],['Expired holds',status.inventory.expiredHolds],
        ['Valid tickets',status.inventory.validTickets],['Hold attempts',status.attempts],['Seat conflicts',status.conflicts],
        ['Database connections',status.databaseMaximum === null ? '—' : status.databaseActive + ' / ' + status.databaseMaximum],
        ['Outbox pending',status.inventory.outboxPending],['Checkouts and refunds pending',status.inventory.paymentPending],
        ['Incident count',status.inventory.outboxFailed + status.inventory.paymentFailed],['Worker failures',status.workerFailures],
        ['Waiting room accounts',status.inventory.roomWaiting],['Waitlist entries',status.inventory.waitlistEntries],
        ['RabbitMQ ready',status.queueReady ?? 'Unavailable'],['RabbitMQ dead-letter',status.queueDead ?? 'Unavailable'],
        ['Oldest outbox item',status.inventory.oldestOutboxSeconds === null ? '—' : status.inventory.oldestOutboxSeconds.toFixed(1) + ' s'],
        ['Oldest overdue hold',status.inventory.oldestOverdueHoldSeconds === null ? '—' : status.inventory.oldestOverdueHoldSeconds.toFixed(1) + ' s']]} />
      <TrafficView traffic={status.traffic} />
    </>}
  </Panel>
}

function Reservations() {
  const query = useWorkspace<Reservation[]>('/ops/reservations', 6000)
  return <Panel title="Reservations" pending={query.isPending} error={query.error}>
    {query.data && <DataTable columns={['Hold','Event','Seats','Status','Expires']} rows={query.data.map(row =>
      [<code>{row.id}</code>,row.eventId,row.seats,<State value={row.status} />,date(row.expiresAt) + ' ' + time(row.expiresAt)])} empty="No reservations recorded" />}
  </Panel>
}

function Queues() {
  const query = useWorkspace<QueueEvent[]>('/ops/queues', 6000)
  return <Panel title="Pending outbox events" pending={query.isPending} error={query.error}>
    {query.data && <DataTable columns={['Event','Type','Attempts','Next attempt','Last error']} rows={query.data.map(row =>
      [row.id,row.type,row.attempts,time(row.nextAttemptAt),row.lastError ?? '—'])} empty="The transactional outbox is caught up" />}
  </Panel>
}

function Latency() {
  const query = useWorkspace<Traffic>('/ops/latency', 5000)
  return <Panel title="API latency · Last 60 seconds" pending={query.isPending} error={query.error}>
    {query.data && <TrafficView traffic={query.data} />}
  </Panel>
}

function Incidents() {
  const query = useWorkspace<Incident[]>('/ops/incidents', 7000)
  return <Panel title="Failed workflows" pending={query.isPending} error={query.error}>
    {query.data && <DataTable columns={['Workflow','Stage','Error','Attempts','Failed at']} rows={query.data.map(row =>
      [row.id,row.stage,row.error,row.attempts,date(row.failedAt) + ' ' + time(row.failedAt)])} empty="No failed workflows recorded" />}
  </Panel>
}

function Logs() {
  const query = useWorkspace<AuditRow[]>('/ops/logs', 7000)
  return <Panel title="Operational audit log" pending={query.isPending} error={query.error}>{query.data && <AuditTable rows={query.data} />}</Panel>
}

export default function Ops() {
  return <Routes>
    <Route index element={<Overview />} /><Route path="reservations" element={<Reservations />} />
    <Route path="queues" element={<Queues />} /><Route path="latency" element={<Latency />} />
    <Route path="incidents" element={<Incidents />} /><Route path="logs" element={<Logs />} />
    <Route path="*" element={<Empty title="Workspace page not found"><Link to="/ops">Operations overview</Link></Empty>} />
  </Routes>
}

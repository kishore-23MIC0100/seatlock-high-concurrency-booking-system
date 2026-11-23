import { useMutation } from '@tanstack/react-query'
import { ArrowRight } from 'lucide-react'
import { useState } from 'react'
import { Link, Route, Routes } from 'react-router-dom'
import { send } from '../../api'
import { Empty, ErrorPanel, Modal, Pending, notify } from '../../components/ui'
import { date, money, time } from '../../format'
import type { BookingRow, Payment, Role } from '../../model'
import { DataTable, Panel, State, Stats, useWorkspace } from './shared'

type User = { id: string; email: string; displayName: string; role: Role; enabled: boolean }
type Application = { userId: string; name: string; email: string; status: 'PENDING' | 'APPROVED' | 'REJECTED' }
export type AuditRow = { id: number; actorId: string | null; action: string; target: string; detail: string | null; createdAt: string }
type Inspection = { booking: BookingRow; payments: Payment[]; timeline: AuditRow[] }

function AdminOverview() {
  const users = useWorkspace<User[]>('/admin/users')
  const organizers = useWorkspace<Application[]>('/admin/organizers')
  const bookings = useWorkspace<BookingRow[]>('/admin/bookings')
  return <Panel title="Administration overview" pending={users.isPending || organizers.isPending || bookings.isPending}
    error={users.error ?? organizers.error ?? bookings.error}>
    {users.data && organizers.data && bookings.data && <Stats items={[
      ['Recent accounts', users.data.length],
      ['Pending organizer applications', organizers.data.filter(application => application.status === 'PENDING').length],
      ['Recent bookings', bookings.data.length],
    ]} />}
    <Link className="text-link" to="/ops">Open Live Operations <ArrowRight size={17} /></Link>
  </Panel>
}

function Users() {
  const query = useWorkspace<User[]>('/admin/users')
  const change = useMutation({
    mutationFn: (user: User) => send<User>('/admin/users/' + user.id, { enabled: !user.enabled }, 'PATCH'),
    onSuccess: () => { void query.refetch(); notify('Account updated') },
  })
  return <Panel title="Accounts" pending={query.isPending} error={query.error}>
    {change.error && <ErrorPanel error={change.error} />}
    {query.data && <DataTable columns={['Account','Email','Role','Status','Action']} rows={query.data.map(user =>
      [user.displayName,user.email,user.role,<State value={user.enabled ? 'ENABLED' : 'DISABLED'} />,
        user.role !== 'ADMIN' ? <button type="button" className="btn btn-secondary" disabled={change.isPending}
          onClick={() => change.mutate(user)}>{user.enabled ? 'Disable' : 'Enable'} {user.displayName}</button> : '—'])} />}
  </Panel>
}

function Organizers() {
  const query = useWorkspace<Application[]>('/admin/organizers')
  const decide = useMutation({
    mutationFn: ({ id, approved }: { id: string; approved: boolean }) => send('/admin/organizers/' + id + '/decision', { approved }),
    onSuccess: () => { void query.refetch(); notify('Application reviewed') },
  })
  return <Panel title="Organizer applications" pending={query.isPending} error={query.error}>
    {decide.error && <ErrorPanel error={decide.error} />}
    {query.data && <DataTable columns={['Studio','Email','Status','Decision']} rows={query.data.map(row =>
      [row.name,row.email,<State value={row.status} />,row.status === 'PENDING' ? <div className="action-row">
        <button className="btn btn-primary" disabled={decide.isPending} onClick={() => decide.mutate({ id: row.userId, approved:true })}>Approve</button>
        <button className="btn btn-secondary" disabled={decide.isPending} onClick={() => decide.mutate({ id: row.userId, approved:false })}>Reject</button>
      </div> : '—'])} empty="No organizer applications" />}
  </Panel>
}

export function AuditTable({ rows }: { rows: AuditRow[] }) {
  return <DataTable columns={['Time','Action','Target','Actor','Detail']} rows={rows.map(row => [
    date(row.createdAt) + ' ' + time(row.createdAt), row.action, <code>{row.target}</code>,
    row.actorId ? <code>{row.actorId}</code> : 'Worker', row.detail ?? '—',
  ])} empty="No audit events recorded" />
}

function Audit() {
  const query = useWorkspace<AuditRow[]>('/admin/audit', 10000)
  return <Panel title="Audit log" pending={query.isPending} error={query.error}>{query.data && <AuditTable rows={query.data} />}</Panel>
}

function InspectBooking({ id }: { id: string }) {
  const query = useWorkspace<Inspection>('/admin/bookings/' + id, 5000)
  if (query.isPending) return <Pending label="Loading booking details" />
  if (query.error) return <ErrorPanel error={query.error} retry={() => void query.refetch()} />
  return <div className="inspection">
    <p>{query.data.booking.eventName} · <State value={query.data.booking.status} /> · {money(query.data.booking.amountMinor)}</p>
    <p className="muted">Customer <code>{query.data.booking.userId}</code></p>
    <h3>Payment attempts</h3>
    <DataTable columns={['Provider','Status','Amount']} rows={query.data.payments.map(row =>
      [row.provider === 'RAZORPAY' ? 'Razorpay · ' + row.mode : row.provider === 'SEATLOCK_DEMO' ? 'SeatLock Pay demo'
        : row.provider === 'FREE' ? 'Complimentary' : 'Historical demo',
        <State value={row.status} />,money(row.amountMinor)])} />
    <h3>Audit timeline</h3><AuditTable rows={query.data.timeline} />
  </div>
}

function Bookings({ refunds = false }: { refunds?: boolean }) {
  const query = useWorkspace<BookingRow[]>('/admin/bookings', 15_000)
  const [inspect, setInspect] = useState<string>()
  const [refunding, setRefunding] = useState<BookingRow>()
  const refund = useMutation({
    mutationFn: ({ id, reason }: { id: string; reason: string }) => send<BookingRow>('/admin/bookings/' + id + '/refund', { reason }),
    onSuccess: result => {
      void query.refetch(); setRefunding(undefined)
      notify(result.status === 'REFUND_PENDING' ? 'Refund requested' : result.provider === 'RAZORPAY' ? 'Refund processed' : 'Booking cancelled')
    },
  })
  return <Panel title={refunds ? 'Refunds' : 'Bookings'} pending={query.isPending} error={query.error}>
    {query.data && <DataTable columns={['Event','Reference','Date','Amount','Status','Action']} rows={query.data.map(row =>
      [row.eventName,<code>{row.id}</code>,date(row.createdAt),money(row.amountMinor),<State value={row.status} />,
        <div className="action-row"><button type="button" className="btn btn-secondary" onClick={() => setInspect(row.id)}>Inspect</button>
          {refunds && row.status === 'CONFIRMED' && <button type="button" className="btn btn-primary" onClick={() => {
            refund.reset(); setRefunding(row)
          }}>{row.provider === 'RAZORPAY' ? 'Refund' : 'Cancel'}</button>}</div>])} empty="No bookings to review" />}
    <Modal open={!!inspect} onOpenChange={open => { if (!open) setInspect(undefined) }} title="Booking details" className="wide-modal">
      {inspect && <InspectBooking id={inspect} />}
    </Modal>
    <Modal open={!!refunding} onOpenChange={open => { if (!open) setRefunding(undefined) }}
      title={refunding?.provider === 'RAZORPAY' ? 'Request a Razorpay refund' : 'Cancel booking'}
      description={refunding ? refunding.eventName + ' · ' + money(refunding.amountMinor) : undefined}>
      <form className="form-stack" onSubmit={e => {
        e.preventDefault()
        const reason = new FormData(e.currentTarget).get('reason')?.toString().trim()
        if (reason && refunding) refund.mutate({ id: refunding.id, reason })
      }}>
        <label>Reason<textarea name="reason" required maxLength={400} /></label>
        {refund.error && <ErrorPanel error={refund.error} />}
        <button className="btn btn-primary" type="submit" disabled={refund.isPending}>
          {refunding?.provider === 'RAZORPAY' ? 'Confirm refund request' : 'Confirm cancellation'}
        </button>
      </form>
    </Modal>
  </Panel>
}

export default function Admin() {
  return <Routes>
    <Route index element={<AdminOverview />} /><Route path="users" element={<Users />} />
    <Route path="organizers" element={<Organizers />} /><Route path="bookings" element={<Bookings key="bookings" />} />
    <Route path="refunds" element={<Bookings key="refunds" refunds />} /><Route path="audit" element={<Audit />} />
    <Route path="*" element={<Empty title="Workspace page not found"><Link to="/admin">Administration overview</Link></Empty>} />
  </Routes>
}

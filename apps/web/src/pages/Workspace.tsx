import { Activity, PanelsTopLeft, ShieldCheck } from 'lucide-react'
import { lazy } from 'react'
import { NavLink } from 'react-router-dom'

const Studio = lazy(() => import('./workspace/Studio'))
const Admin = lazy(() => import('./workspace/Admin'))
const Ops = lazy(() => import('./workspace/Ops'))

const menus = {
  organizer: { title: 'Organizer Studio', icon: PanelsTopLeft, component: Studio, tabs: [
    ['','Overview'],['events','Events'],['venues','Venues'],['attendees','Attendees'],['check-in','Check-in'],['reports','Reports'],
  ] },
  admin: { title: 'Administration', icon: ShieldCheck, component: Admin, tabs: [
    ['','Overview'],['users','Users'],['organizers','Organizers'],['bookings','Bookings'],['refunds','Refunds'],['audit','Audit log'],
  ] },
  ops: { title: 'Live Operations', icon: Activity, component: Ops, tabs: [
    ['','Overview'],['reservations','Reservations'],['queues','Queues'],['latency','Latency'],['incidents','Incidents'],['logs','Audit log'],
  ] },
}

export default function Workspace({ kind }: { kind: keyof typeof menus }) {
  const { title, icon: Icon, component: View, tabs } = menus[kind]
  return <main id="main-content" className="page-width page-content workspace">
    <header className="content-title workspace-heading"><span className="feature-icon"><Icon size={24}/></span><h1>{title}</h1></header>
    <nav className="workspace-nav" aria-label={title + ' navigation'}>{tabs.map(([path, label]) =>
      <NavLink key={path} end={!path} to={'/' + kind + (path ? '/' + path : '')}>{label}</NavLink>)}</nav>
    <View />
  </main>
}

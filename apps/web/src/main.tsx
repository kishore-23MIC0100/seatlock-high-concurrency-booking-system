import '@fontsource-variable/inter'
import '@fontsource-variable/space-grotesk'
import './styles.css'
import './pages.css'
import './appearance.css'
import './motion.css'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { StrictMode, Suspense, lazy } from 'react'
import { createRoot } from 'react-dom/client'
import { BrowserRouter, Link, Navigate, Outlet, Route, Routes, useLocation } from 'react-router-dom'
import { useAccount } from './api'
import { Empty, ErrorPanel, Pending, Toast } from './components/ui'
import { Nav } from './components/Nav'
import type { Role } from './model'
import { Discover, EventDetails, Events, Featured } from './pages/Events'
import SeatSelection from './pages/SeatSelection'

const Checkout = lazy(() => import('./pages/Checkout'))
const Auth = lazy(() => import('./pages/Auth').then(module => ({ default: module.Auth })))
const Customer = lazy(() => import('./pages/Customer').then(module => ({
  default: function CustomerRoutes() {
    return <Routes>
      <Route path="booking/:bookingId/confirmation" element={<module.Confirmation />} />
      <Route path="my-tickets" element={<module.MyTickets />} />
      <Route path="my-tickets/:ticketId" element={<module.TicketDetails />} />
      <Route path="my-bookings" element={<module.MyBookings />} />
      <Route path="waitlist" element={<module.WaitlistPage />} />
      <Route path="profile" element={<module.Profile />} />
      <Route path="settings" element={<module.Profile settings />} />
      <Route path="*" element={<NotFound />} />
    </Routes>
  },
})))
const Workspace = lazy(() => import('./pages/Workspace'))
const client = new QueryClient({ defaultOptions: { queries: { retry: 1, staleTime: 5000 } } })

function Require({ role }: { role?: Role }) {
  const account = useAccount()
  const location = useLocation()
  if (account.isPending) return <main id="main-content" className="page-width page-content"><Pending label="Checking your session" /></main>
  if (account.error) return <main id="main-content" className="page-width page-content"><ErrorPanel error={account.error} retry={() => void account.refetch()} /></main>
  if (!account.data) return <Navigate replace to={'/login?next=' + encodeURIComponent(location.pathname + location.search)} />
  if (role && role !== account.data.role) return <main id="main-content" className="page-width page-content">
    <Empty title="You don't have access to this workspace"><Link className="btn btn-secondary" to="/discover">Back to Discover</Link></Empty>
  </main>
  return <Outlet />
}

function NotFound() {
  return <main id="main-content" className="page-width page-content"><Empty title="Page not found">
    <Link className="btn btn-primary" to="/discover">Explore events</Link>
  </Empty></main>
}

function App() {
  return <>
    <Nav />
    <Suspense fallback={<main id="main-content" className="page-width page-content"><Pending /></main>}>
      <Routes>
        <Route index element={<Featured />} />
        <Route path="discover" element={<Discover />} />
        <Route path="events" element={<Events />} />
        <Route path="events/:eventId" element={<EventDetails />} />
        <Route path="events/:eventId/seats" element={<SeatSelection />} />
        <Route path="login" element={<Auth key="login" />} />
        <Route path="register" element={<Auth key="register" register />} />
        <Route element={<Require />}>
          <Route path="checkout/:holdId" element={<Checkout />} />
          <Route path="*" element={<Customer />} />
        </Route>
        <Route element={<Require role="ORGANIZER" />}><Route path="organizer/*" element={<Workspace kind="organizer" />} /></Route>
        <Route element={<Require role="ADMIN" />}>
          <Route path="admin/*" element={<Workspace kind="admin" />} />
          <Route path="ops/*" element={<Workspace kind="ops" />} />
        </Route>
      </Routes>
    </Suspense>
    <Toast />
  </>
}

createRoot(document.getElementById('root')!).render(<StrictMode>
  <QueryClientProvider client={client}><BrowserRouter><App /></BrowserRouter></QueryClientProvider>
</StrictMode>)

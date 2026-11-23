import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Bell, CalendarDays, ChevronDown, Compass, Search, Ticket, UserRound } from 'lucide-react'
import { useEffect, useState, type FormEvent } from 'react'
import { Link, useLocation, useNavigate } from 'react-router-dom'
import { accountKey, api, send, useAccount } from '../api'
import { date } from '../format'
import type { Notice } from '../model'
import { Empty, ErrorPanel, Logo, Modal } from './ui'
import { AppearanceButton } from './Appearance'

export function Nav() {
  const { data: account } = useAccount()
  const client = useQueryClient()
  const navigate = useNavigate()
  const { pathname } = useLocation()
  const [searchOpen, setSearchOpen] = useState(false)
  const [noticesOpen, setNoticesOpen] = useState(false)
  const [accountOpen, setAccountOpen] = useState(false)
  const [search, setSearch] = useState('')
  const notices = useQuery({
    queryKey: ['notifications', account?.id], queryFn: () => api<Notice[]>('/me/notifications'),
    enabled: !!account, refetchInterval: 20_000,
  })
  const unread = notices.data?.some(notice => !notice.readAt) ?? false
  const markRead = useMutation({
    mutationFn: () => send('/me/notifications/read'),
    onSuccess: () => client.invalidateQueries({ queryKey: ['notifications'] }),
  })
  const logout = useMutation({
    mutationFn: () => send('/auth/logout'),
    onSuccess: () => {
      client.removeQueries({ predicate: query => query.queryKey[0] !== 'capabilities' })
      client.setQueryData(accountKey, null)
      setAccountOpen(false)
      navigate('/')
    },
  })
  useEffect(() => {
    function open(event: KeyboardEvent) {
      if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'k') {
        event.preventDefault()
        setSearchOpen(true)
      }
    }
    window.addEventListener('keydown', open)
    return () => window.removeEventListener('keydown', open)
  }, [])

  function runSearch(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    setSearchOpen(false)
    navigate('/events?q=' + encodeURIComponent(search.trim()))
  }

  const selectSeats = pathname.endsWith('/seats')
  const navItems = [
    { to: '/discover', label: 'Discover', icon: Compass, active: pathname === '/' || pathname === '/discover' || selectSeats },
    { to: '/events', label: 'Events', icon: CalendarDays, active: pathname.startsWith('/events') && !selectSeats },
    { to: '/my-tickets', label: 'My Tickets', icon: Ticket, active: pathname.startsWith('/my-tickets') },
  ]
  return <header className="navbar">
    <a className="skip-link" href="#main-content">Skip to content</a>
    <div className="nav-inner page-width">
      <Logo />
      <nav aria-label="Main navigation" className="nav-links">
        {navItems.map(({ to, label, icon: Icon, active }) => <Link key={to} to={to}
          className={active ? 'nav-link active' : 'nav-link'} aria-current={active ? 'page' : undefined}>
          <Icon size={19} /><span>{label}</span></Link>)}
      </nav>
      <div className="nav-actions">
        <button className="nav-search" type="button" onClick={() => setSearchOpen(true)} aria-label="Search events">
          <Search size={19} /><span>Search events, artists, venues...</span><kbd>Ctrl K</kbd>
        </button>
        <AppearanceButton />
        {account && <button type="button" className="icon-button bell-button" onClick={() => setNoticesOpen(true)} aria-label="Notifications">
          <Bell size={20} />{unread && <i />}
        </button>}
        {account
          ? <button type="button" className="profile-button" onClick={() => setAccountOpen(true)} aria-label="Account menu">
            <span className="avatar">{account.displayName.split(' ').filter(Boolean).slice(0, 2).map(name => name[0]).join('').toUpperCase()}</span>
            <ChevronDown className="profile-chevron" size={15} />
          </button>
          : <Link className="sign-in" to={'/login?next=' + encodeURIComponent(pathname)}><UserRound size={16} /><span>Sign in</span></Link>}
      </div>
    </div>
    <Modal open={searchOpen} onOpenChange={setSearchOpen} title="Find an event">
      <form className="search-form" onSubmit={runSearch}>
        <input aria-label="Event, artist, venue, or date" placeholder="Artist, venue, event or date" value={search} onChange={e => setSearch(e.target.value)} />
        <button type="submit" className="btn btn-primary"><Search size={17} /> Search</button>
      </form>
    </Modal>
    {account && <>
      <Modal open={noticesOpen} onOpenChange={setNoticesOpen} title="Notifications">
        {unread && <button type="button" className="btn btn-secondary" onClick={() => markRead.mutate()}>Mark all as read</button>}
        {notices.error && <ErrorPanel error={notices.error} retry={() => void notices.refetch()} />}
        {markRead.error && <ErrorPanel error={markRead.error} />}
        {notices.isSuccess && !notices.data.length && <Empty title="You're all caught up" />}
        {notices.data && <ul className="notice-list">{notices.data.map(notice => <li key={notice.id}>
          <strong>{notice.message}</strong><small>{date(notice.createdAt)}{notice.readAt ? '' : ' · Unread'}</small>
        </li>)}</ul>}
      </Modal>
      <Modal open={accountOpen} onOpenChange={setAccountOpen} title={account.displayName}>
        <div className="account-links">
          <Link onClick={() => setAccountOpen(false)} to="/profile">My profile</Link>
          <Link onClick={() => setAccountOpen(false)} to="/my-bookings">My bookings</Link>
          <Link onClick={() => setAccountOpen(false)} to="/waitlist">My waitlist</Link>
          <Link onClick={() => setAccountOpen(false)} to="/settings">Settings</Link>
          {account.role === 'ORGANIZER' && <Link onClick={() => setAccountOpen(false)} to="/organizer">Organizer Studio</Link>}
          {account.role === 'ADMIN' && <Link onClick={() => setAccountOpen(false)} to="/admin">Administration</Link>}
          {account.role === 'ADMIN' && <Link onClick={() => setAccountOpen(false)} to="/ops">Live Operations</Link>}
          <button type="button" className="btn btn-secondary" disabled={logout.isPending} onClick={() => logout.mutate()}>Sign out</button>
          {logout.error && <ErrorPanel error={logout.error} />}
        </div>
      </Modal>
    </>}
  </header>
}

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { ArrowLeft, ArrowRight, CircleCheck, CreditCard, ExternalLink, LoaderCircle, LockKeyhole, Ticket, WalletCards } from 'lucide-react'
import { useEffect, useRef, useState } from 'react'
import { Link, Navigate, useNavigate, useParams } from 'react-router-dom'
import { api, eventPath, send, useAccount, useEvent } from '../api'
import { BookingSummary } from '../components/BookingSummary'
import { EventHero } from '../components/EventHero'
import { formatHoldTime, HoldTimer } from '../components/HoldTimer'
import { dismissNotification, ErrorPanel, Modal, Pending, Stepper } from '../components/ui'
import { money, seatName } from '../format'
import type { CheckoutPayment, Hold } from '../model'
import { createCheckout, loadRazorpay, type PaymentOrder, type PaymentProof, type RazorpayCheckout } from '../razorpay'

const paymentNotes: Partial<Record<CheckoutPayment['status'], string>> = {
  REFUND_PENDING: 'Razorpay captured the payment after your reservation ended. A refund has been requested.',
  REFUND_FAILED: 'Your payment needs review. Contact SeatLock with your order reference.',
  REFUNDED: 'Razorpay processed your refund.',
}

function useRemaining(hold: Hold | undefined, fetched: number) {
  const [now, setNow] = useState(Date.now)
  const ticking = hold?.status === 'ACTIVE'
  useEffect(() => {
    if (!ticking) return
    const timer = window.setInterval(() => setNow(Date.now()), 250)
    return () => window.clearInterval(timer)
  }, [ticking])
  if (!hold) return 0
  const elapsed = Math.max(0, now - fetched)
  return Math.max(0, Math.ceil((Date.parse(hold.expiresAt) - Date.parse(hold.serverTime) - elapsed) / 1000))
}

export default function Checkout() {
  const id = useParams().holdId ?? ''
  const client = useQueryClient()
  const navigate = useNavigate()
  const account = useAccount()
  const [phase, setPhase] = useState<'review' | 'payment'>('review')
  const [cancelOpen, setCancelOpen] = useState(false)
  const [checkoutOpen, setCheckoutOpen] = useState(false)
  const [notice, setNotice] = useState('')
  const gateway = useRef<RazorpayCheckout | null>(null)
  const holdKey = ['hold', id, account.data?.id]
  const paymentKey = ['checkout-payment', id, account.data?.id]
  const endpoint = '/holds/' + id + '/payment'

  const query = useQuery({
    queryKey: holdKey, queryFn: ({ signal }) => api<Hold>('/holds/' + id, { signal }), refetchInterval: 4500,
  })
  const payment = useQuery({
    queryKey: paymentKey, queryFn: ({ signal }) => api<CheckoutPayment>(endpoint, { signal }),
    enabled: query.isSuccess,
    refetchInterval: current => ['AWAITING_PAYMENT','REFUND_PENDING'].includes(current.state.data?.status ?? '') ? 4000 : false,
  })
  const event = useEvent(query.data?.eventId ?? '')
  const hold = query.data
  const remaining = useRemaining(hold, query.dataUpdatedAt)
  const active = hold?.status === 'ACTIVE' && remaining > 0
  const state = payment.data

  useEffect(() => {
    if (!active) gateway.current?.close()
  }, [active])
  useEffect(() => () => gateway.current?.close(), [])

  function refreshPayment(result: CheckoutPayment) {
    client.setQueryData(paymentKey, result)
    void client.invalidateQueries({ queryKey: holdKey })
    if (result.bookingId) navigate('/booking/' + result.bookingId + '/confirmation', { replace: true })
  }

  const verify = useMutation({
    mutationFn: (proof: PaymentProof) => send<CheckoutPayment>(endpoint + '/verify', proof),
    onSuccess: result => {
      refreshPayment(result)
      if (result.status === 'AWAITING_PAYMENT') setNotice('Razorpay is confirming your payment.')
    },
  })
  const sync = useMutation({
    mutationFn: () => send<CheckoutPayment>(endpoint + '/sync'),
    onSuccess: result => {
      refreshPayment(result)
      if (result.status === 'AWAITING_PAYMENT') setNotice('Razorpay hasn’t confirmed a payment for this order yet.')
    },
  })
  const localPayment = useMutation({
    mutationFn: (mode: 'free' | 'demo') => send<CheckoutPayment>(endpoint + '/' + mode),
    onSuccess: refreshPayment,
  })
  const online = useMutation({
    mutationFn: async () => {
      const Checkout = await loadRazorpay()
      const order = await send<PaymentOrder>(endpoint + '/order')
      return { Checkout, order }
    },
    onSuccess: ({ Checkout, order }) => {
      if (hold && (order.amountMinor !== hold.totalMinor || order.currency !== 'INR')) {
        setNotice('The payment amount changed. Refresh your booking review and try again.')
        return
      }
      void payment.refetch()
      try {
        const instance = createCheckout(Checkout, order, event.data?.name ?? 'Event tickets', {
          success: proof => {
            gateway.current = null
            setCheckoutOpen(false)
            setNotice('Verifying payment with Razorpay…')
            verify.mutate(proof)
          },
          dismiss: () => {
            gateway.current = null
            setCheckoutOpen(false)
            void payment.refetch()
          },
          failure: () => setNotice('Payment did not complete. You can retry in Razorpay.'),
        })
        gateway.current = instance
        setCheckoutOpen(true)
        instance.open()
      } catch {
        gateway.current = null
        setCheckoutOpen(false)
        setNotice('Razorpay checkout could not open. Try again.')
      }
    },
  })
  const cancel = useMutation({
    mutationFn: () => send<Hold>('/holds/' + id, undefined, 'DELETE'),
    onSuccess: data => {
      client.setQueryData(holdKey, data)
      void client.invalidateQueries({ queryKey: ['active-holds'] })
      setCancelOpen(false)
      navigate(eventPath(data.eventId) + '/seats', { replace: true })
    },
  })

  const booking = hold?.bookingId ?? state?.bookingId
  if (booking) return <Navigate replace to={'/booking/' + booking + '/confirmation'} />
  if (query.error && !hold) return <main id="main-content" className="page-width page-content"><ErrorPanel error={query.error} retry={() => void query.refetch()} /></main>
  if (event.isError) return <main id="main-content" className="page-width page-content"><ErrorPanel error={event.error} retry={() => void event.refetch()} /></main>
  if (!hold || !event.data) return <main id="main-content" className="page-width page-content"><Pending label="Checking your seat hold" /></main>
  const duration = hold.createdAt ? Math.max(1, (Date.parse(hold.expiresAt) - Date.parse(hold.createdAt)) / 1000) : 600
  const busy = online.isPending || localPayment.isPending || checkoutOpen || verify.isPending || sync.isPending
  const isFree = hold.totalMinor === 0
  const isDemo = state?.mode === 'DEMO'
  const isTest = state?.mode === 'TEST'
  const total = money(hold.totalMinor)
  const note = state && paymentNotes[state.status]
  const paymentError = online.error ?? verify.error ?? sync.error ?? localPayment.error
  const canPay = state?.enabled && !note
  const payLabel = isFree ? 'Confirm free booking' : isDemo ? 'Pay ' + total + ' in demo'
    : 'Pay ' + total + (isTest ? ' in Test Mode' : ' with Razorpay')
  const checkStatus = state?.orderId && <button type="button" className="btn btn-secondary" disabled={busy}
    onClick={() => { setNotice(''); sync.mutate() }}>Check payment status</button>

  function submitPayment() {
    setNotice('')
    verify.reset()
    sync.reset()
    if (isFree || isDemo) localPayment.mutate(isFree ? 'free' : 'demo')
    else online.mutate()
  }

  return <main id="main-content" className="page-width booking-page checkout-page">
    <EventHero event={event.data} />
    <div className="booking-layout checkout-layout">
      <div className="booking-main">
        <Stepper active={phase === 'review' ? 1 : 2} />
        <section className="glass checkout-card" data-checkout-phase={active ? phase : 'expired'}>
          <header className="checkout-intro">
            <div>
              <span className="feature-icon">{phase === 'review' ? <LockKeyhole size={23} /> : <CreditCard size={23} />}</span>
              <h2 key={active ? phase : 'expired'}>{!active ? 'Your seat hold has ended' : phase === 'review' ? 'Review your booking' : isFree ? 'Confirm your booking' : 'Secure payment'}</h2>
            </div>
            {active && <HoldTimer seconds={remaining} duration={duration} />}
          </header>
          {!active ? <div className="form-stack">
            <p className="muted">Choose available seats to start a new reservation.</p>
            {note && <p className="info-line" role="status">{note}</p>}
            {paymentError && <ErrorPanel error={paymentError} />}
            {notice && !note && <p className="info-line" role="status">{notice}</p>}
            <div className="action-row">
              <Link className="btn btn-primary" to={eventPath(hold.eventId) + '/seats'}>Find seats again <ArrowRight size={17} /></Link>
              {checkStatus}
            </div>
            {state?.orderId && <p className="muted payment-reference">Order reference <code>{state.orderId}</code></p>}
          </div> : phase === 'review' ? <div key="review" className="form-stack checkout-phase">
            <p className="reserved-note"><CircleCheck size={18} /> {hold.seats.map(seatName).join(', ')} held for {account.data?.displayName}.</p>
            <p>Check your seats, fees, and total before continuing.</p>
            <div className="action-row"><button type="button" className="btn btn-primary" onClick={() => {
              dismissNotification()
              setPhase('payment')
            }}>
              {isFree ? 'Continue to Confirmation' : 'Continue to Payment'} <ArrowRight size={18} />
            </button><button type="button" className="btn btn-secondary" onClick={() => setCancelOpen(true)}>Release seats</button></div>
          </div> : <div key="payment" className="form-stack checkout-phase">
            {!state ? payment.isPending && <Pending label="Loading payment options" />
              : isFree ? <p>No payment is due for these tickets.</p> : isDemo ? <section className="seatlock-pay" aria-label="SeatLock Pay">
              <div className="seatlock-pay-heading">
                <span className="seatlock-pay-mark"><Ticket size={23} aria-hidden="true" /></span>
                <div><strong>SeatLock Pay</strong><small>Demo checkout</small></div>
                <div className="seatlock-pay-total"><small>Test total</small><strong>{total}</strong></div>
              </div>
              <div className="seatlock-pay-method">
                <WalletCards size={21} aria-hidden="true" />
                <div><strong>SeatLock demo card</strong><small>No real money moves.</small></div>
                <CircleCheck className="seatlock-pay-check" size={18} aria-hidden="true" />
              </div>
            </section> : <div className="payment-gateway-card">
              <span className="feature-icon"><CreditCard size={21} /></span>
              <div><strong>{isTest ? 'Razorpay Demo Gateway' : 'Razorpay Checkout'}</strong>
                <small>{isTest ? 'Test Mode · No real money will be charged.' : 'Choose from the payment methods available at checkout.'}</small></div>
              <strong>{total}</strong>
            </div>}
            {isTest && state.enabled && <a className="text-link"
              href="https://razorpay.com/docs/payments/payment-gateway/web-integration/standard/integration-steps#2-test-integration"
              target="_blank" rel="noreferrer">Razorpay test payment details <ExternalLink size={14} aria-hidden="true" /></a>}
            {state && !state.enabled && <p className="info-line" role="status">
              {isTest && !state.orderId ? 'Demo checkout needs Razorpay Test Mode keys.' : 'Online payments are currently unavailable.'}
            </p>}
            {note && <p className="info-line" role="status">{note}</p>}
            {notice && !note && <p className="info-line" role="status">{notice}</p>}
            {paymentError && <ErrorPanel error={paymentError} />}
            {payment.error && <ErrorPanel error={payment.error} retry={() => void payment.refetch()} />}
            <div className="action-row">
              {state && <button type="button" className="btn btn-primary" disabled={!canPay || busy} onClick={submitPayment}>
                {busy ? <LoaderCircle size={17} className="spinner" /> : <LockKeyhole size={17} />}
                {verify.isPending ? 'Verifying payment…' : online.isPending || checkoutOpen ? 'Opening Razorpay…'
                  : localPayment.isPending ? (isDemo ? 'Processing demo payment…' : 'Confirming booking…') : payLabel}
              </button>}
              <button type="button" className="btn btn-secondary" disabled={busy} onClick={() => setPhase('review')}><ArrowLeft size={16} /> Back to review</button>
              {checkStatus}
            </div>
            {state?.orderId && <p className="muted payment-reference">Order reference <code>{state.orderId}</code></p>}
          </div>}
          {query.error && <ErrorPanel error={query.error} retry={() => void query.refetch()} />}
        </section>
      </div>
      <aside className="checkout-summary">
        <BookingSummary event={event.data} seats={hold.seats} totals={hold} countdown={active ? formatHoldTime(remaining) : undefined} />
      </aside>
    </div>
    <Modal open={cancelOpen} onOpenChange={setCancelOpen} title="Release these seats?" description="The seats will return to the event map.">
      <div className="action-row"><button type="button" className="btn btn-primary" disabled={cancel.isPending} onClick={() => cancel.mutate()}>Release seats</button>
        <button type="button" className="btn btn-secondary" onClick={() => setCancelOpen(false)}>Keep reservation</button></div>
      {cancel.error && <ErrorPanel error={cancel.error} />}
    </Modal>
  </main>
}

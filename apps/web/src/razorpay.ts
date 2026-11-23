export type PaymentOrder = {
  key: string
  orderId: string
  amountMinor: number
  currency: 'INR'
  timeoutSeconds: number
}

type RazorpayResult = { razorpay_order_id: string; razorpay_payment_id: string; razorpay_signature: string }
export type PaymentProof = { orderId: string; paymentId: string; signature: string }
export type RazorpayCheckout = { open(): void; close(): void; on(event: 'payment.failed', callback: () => void): void }
type Options = {
  key: string
  order_id: string
  amount: number
  currency: 'INR'
  timeout: number
  name: string
  description: string
  theme: { color: string }
  modal: { ondismiss: () => void }
  handler: (result: RazorpayResult) => void
}
type RazorpayConstructor = new (options: Options) => RazorpayCheckout

declare global {
  interface Window { Razorpay?: RazorpayConstructor }
}

let pending: Promise<RazorpayConstructor> | undefined
export function loadRazorpay(): Promise<RazorpayConstructor> {
  if (window.Razorpay) return Promise.resolve(window.Razorpay)
  pending ??= new Promise<RazorpayConstructor>((resolve, reject) => {
    const script = document.createElement('script')
    script.src = 'https://checkout.razorpay.com/v1/checkout.js'
    script.async = true
    const timer = window.setTimeout(fail, 12_000)
    function fail() {
      window.clearTimeout(timer)
      script.remove()
      reject(new Error('Razorpay checkout could not load. Check your connection and try again.'))
    }
    script.onload = () => {
      window.clearTimeout(timer)
      if (window.Razorpay) resolve(window.Razorpay)
      else fail()
    }
    script.onerror = fail
    document.head.append(script)
  }).catch((error: unknown) => {
    pending = undefined
    throw error
  })
  return pending
}

export function createCheckout(
  Checkout: RazorpayConstructor, order: PaymentOrder, description: string,
  callbacks: { success: (proof: PaymentProof) => void; dismiss: () => void; failure: () => void },
) {
  const checkout = new Checkout({
    key: order.key,
    order_id: order.orderId,
    amount: order.amountMinor,
    currency: order.currency,
    timeout: order.timeoutSeconds,
    name: 'SeatLock',
    description,
    theme: { color: '#6535b5' },
    modal: { ondismiss: callbacks.dismiss },
    handler: result => callbacks.success({
      orderId: result.razorpay_order_id, paymentId: result.razorpay_payment_id, signature: result.razorpay_signature,
    }),
  })
  checkout.on('payment.failed', callbacks.failure)
  return checkout
}

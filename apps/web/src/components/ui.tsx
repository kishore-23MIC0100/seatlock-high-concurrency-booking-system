import * as Dialog from '@radix-ui/react-dialog'
import { AlertCircle, Check, ChevronRight, LoaderCircle, Ticket, X } from 'lucide-react'
import { useEffect, type ReactNode } from 'react'
import { Link } from 'react-router-dom'
import { create } from 'zustand'

export function Logo() {
  return <Link className="brand" to="/" aria-label="SeatLock home">
    <span className="brand-mark"><Ticket size={27} strokeWidth={1.8} /></span>
    <span>Seat<span className="brand-accent">Lock</span></span>
  </Link>
}

export function Pending({ label = 'Loading SeatLock' }: { label?: string }) {
  return <div className="glass pending" role="status"><LoaderCircle className="spinner" size={22} />{label}</div>
}

export function ErrorPanel({ error, retry }: { error: unknown; retry?: () => void }) {
  return <div className="error-panel" role="alert"><AlertCircle size={19} />
    <span>{error instanceof Error ? error.message : 'Something went wrong. Please try again.'}</span>
    {retry && <button type="button" className="btn btn-secondary" onClick={retry}>Try again</button>}
  </div>
}

export function Empty({ title, children }: { title: string; children?: ReactNode }) {
  return <div className="glass empty"><Ticket size={28} /><h2>{title}</h2>{children}</div>
}

export function Modal({ open, onOpenChange, title, description, children, trigger, className = '' }: {
  open: boolean; onOpenChange: (open: boolean) => void; title: string; description?: string; children: ReactNode; trigger?: ReactNode; className?: string
}) {
  return <Dialog.Root open={open} onOpenChange={onOpenChange}>
    {trigger && <Dialog.Trigger asChild>{trigger}</Dialog.Trigger>}
    <Dialog.Portal>
      <Dialog.Overlay className="modal-overlay" />
      <Dialog.Content className={'modal glass ' + className}
        {...(!description && { 'aria-describedby': undefined })}>
        <div className="modal-heading">
          <Dialog.Title>{title}</Dialog.Title>
          <Dialog.Close className="icon-button" aria-label="Close"><X size={20} /></Dialog.Close>
        </div>
        {description && <Dialog.Description className="muted modal-description">{description}</Dialog.Description>}
        {children}
      </Dialog.Content>
    </Dialog.Portal>
  </Dialog.Root>
}

const steps = ['Select Seats', 'Review Booking', 'Payment', 'Confirmation']
export function Stepper({ active }: { active: number }) {
  return <nav aria-label="Booking progress" className="stepper"><ol>
    {steps.map((label, index) => <li key={label} className={index === active ? 'current' : index < active ? 'complete' : ''}>
      <span className="step-label" aria-current={index === active ? 'step' : undefined}>
        <span className="step-number">{index < active ? <Check size={16} /> : index + 1}</span>
        <span>{label}</span>
      </span>
      {index < steps.length - 1 && <ChevronRight className="step-chevron" size={16} aria-hidden="true" />}
    </li>)}
  </ol></nav>
}

type ToastState = { message: string; detail?: string; version: number }
const useToast = create<{ item: ToastState | null; show: (message: string, detail?: string) => void; clear: () => void }>(
  set => ({
    item: null,
    show: (message, detail) => set(state => ({ item: { message, detail, version: (state.item?.version ?? 0) + 1 } })),
    clear: () => set({ item: null }),
  }),
)
export const notify = (message: string, detail?: string) => useToast.getState().show(message, detail)
export const dismissNotification = () => useToast.getState().clear()

export function Toast() {
  const { item, clear } = useToast()
  useEffect(() => {
    if (!item) return
    const timer = window.setTimeout(clear, 5500)
    return () => window.clearTimeout(timer)
  }, [item, clear])
  if (!item) return null
  return <div className="toast glass" key={item.version} role="status">
    <span className="toast-icon"><Check size={20} /></span>
    <div><strong>{item.message}</strong>{item.detail && <p>{item.detail}</p>}</div>
    <button type="button" className="icon-button" onClick={clear} aria-label="Dismiss notification"><X size={17} /></button>
  </div>
}

import type { CSSProperties } from 'react'

export function formatHoldTime(seconds: number) {
  return [Math.floor(seconds / 60), seconds % 60].map(value => String(value).padStart(2, '0')).join(':')
}

export function HoldTimer({ seconds, duration }: { seconds: number; duration: number }) {
  const urgent = seconds <= 60
  const progress = Math.max(0, Math.min(100, seconds / duration * 100))
  return <div className="hold-timer" data-urgent={urgent}>
    <div className="hold-ring" style={{ '--hold-progress': progress + '%' } as CSSProperties}>
      <div className="hold-ring-face">
        <span className="hold-countdown" role="timer" aria-live="off" aria-label="Time remaining">{formatHoldTime(seconds)}</span>
        <span className="hold-ring-caption" aria-hidden="true">TIME LEFT</span>
      </div>
    </div>
    {urgent && <span className="sr-only" role="status">Your hold expires in one minute or less.</span>}
  </div>
}

import { Check, Monitor, Moon, Sun } from 'lucide-react'
import { useEffect, useLayoutEffect, useState } from 'react'
import { Modal } from './ui'

const key = 'seatlock.appearance'
const query = '(prefers-color-scheme: dark)'
const options = [
  { mode: 'system', label: 'System', description: 'Follows your device', icon: Monitor },
  { mode: 'light', label: 'Light', description: 'Soft lavender and white', icon: Sun },
  { mode: 'dark', label: 'Dark', description: 'Midnight and neon', icon: Moon },
] as const
type Appearance = typeof options[number]['mode']

function readPreference(): Appearance {
  try {
    const choice = localStorage.getItem(key)
    if (choice === 'light' || choice === 'dark') return choice
  } catch {
    // Apply any in-memory changes even if this browser cannot save them.
  }
  return 'system'
}

export function AppearanceButton() {
  const [open, setOpen] = useState(false)
  const [preference, setPreference] = useState(readPreference)
  const [systemDark, setSystemDark] = useState(() => matchMedia(query).matches)
  const theme = preference === 'system' ? (systemDark ? 'dark' : 'light') : preference
  const ModeIcon = preference === 'system' ? Monitor : theme === 'dark' ? Moon : Sun

  useEffect(() => {
    const media = matchMedia(query)
    const updateSystem = () => setSystemDark(media.matches)
    const updatePreference = (event: StorageEvent) => {
      if (event.key === key || event.key === null) setPreference(readPreference())
    }
    updateSystem()
    media.addEventListener('change', updateSystem)
    window.addEventListener('storage', updatePreference)
    return () => {
      media.removeEventListener('change', updateSystem)
      window.removeEventListener('storage', updatePreference)
    }
  }, [])

  useLayoutEffect(() => {
    document.documentElement.dataset.theme = theme
    document.querySelector('meta[name="theme-color"]')?.setAttribute('content', theme === 'dark' ? '#090c1b' : '#f5f3fc')
  }, [theme])

  function choose(mode: Appearance) {
    setPreference(mode)
    try {
      localStorage.setItem(key, mode)
    } catch {
      // The choice still applies to this tab.
    }
  }

  return <Modal open={open} onOpenChange={setOpen} title="Appearance" className="appearance-dialog"
    trigger={<button type="button" className="icon-button appearance-trigger" aria-label="Appearance" title="Appearance">
      <ModeIcon size={18} aria-hidden="true" />
    </button>}>
    <fieldset className="appearance-options">
      <legend className="sr-only">Color theme</legend>
      {options.map(({ mode, label, description, icon: Icon }) => <label key={mode} className="appearance-choice">
        <input className="sr-only" type="radio" name="appearance" value={mode} checked={preference === mode} onChange={() => choose(mode)} />
        <span className="appearance-choice-content">
          <span className="theme-preview" data-preview={mode} aria-hidden="true"><Icon size={19} /></span>
          <span><strong>{label}</strong><small>{description}</small></span>
          {preference === mode && <Check size={17} className="appearance-check" aria-hidden="true" />}
        </span>
      </label>)}
    </fieldset>
  </Modal>
}

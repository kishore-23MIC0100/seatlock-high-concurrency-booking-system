// Set the colors before the app stylesheet paints.
(() => {
  let preference
  try {
    preference = localStorage.getItem('seatlock.appearance')
  } catch {
    // A restricted browser can still follow its system preference.
  }
  const system = matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light'
  const theme = preference === 'light' || preference === 'dark' ? preference : system
  document.documentElement.dataset.theme = theme
  document.querySelector('meta[name="theme-color"]')?.setAttribute('content', theme === 'dark' ? '#090c1b' : '#f5f3fc')
})()

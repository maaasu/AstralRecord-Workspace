export const isAndroidEditor = () => new URLSearchParams(window.location.search).get('mobile') === 'android'

export function flagAndroidSessionExpiry(response: Response) {
  if (response.status === 401 && isAndroidEditor()) window.astralMobileSessionExpired = true
}

const dialogs = new Map<HTMLElement, () => void>()

export function registerAndroidDialog(dialog: HTMLElement, close: () => void) {
  if (!isAndroidEditor()) return () => {}
  dialogs.set(dialog, close)
  return () => { dialogs.delete(dialog) }
}

export function handleAndroidDialogBack(): boolean {
  const active = [...dialogs.entries()].reverse().find(([dialog]) => dialog.isConnected && !dialog.closest('[hidden]'))
  if (!active) return false
  active[1]()
  return true
}

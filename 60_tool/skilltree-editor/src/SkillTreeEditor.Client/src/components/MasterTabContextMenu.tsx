import { useEffect, useLayoutEffect, useRef } from 'react'
import { createPortal } from 'react-dom'

export interface MasterTabCommand { label: string; disabled: boolean; run: () => void }

export function MasterTabContextMenu({ x, y, commands, onDismiss }: { x: number; y: number; commands: MasterTabCommand[]; onDismiss: (restoreFocus?: boolean) => void }) {
  const menu = useRef<HTMLDivElement>(null)
  useLayoutEffect(() => {
    const element = menu.current
    if (!element) return
    const rect = element.getBoundingClientRect()
    element.style.left = `${Math.max(8, Math.min(x, window.innerWidth - rect.width - 8))}px`
    element.style.top = `${Math.max(8, Math.min(y, window.innerHeight - rect.height - 8))}px`
    const focusTarget = element.querySelector<HTMLButtonElement>('button:not(:disabled)') ?? element
    focusTarget.focus()
  }, [x, y])
  useEffect(() => {
    const outside = (event: Event) => { if (!menu.current?.contains(event.target as Node)) onDismiss() }
    const dismiss = () => onDismiss()
    document.addEventListener('pointerdown', outside)
    // Observe the capture phase so mounting this menu cannot consume its opening event.
    document.addEventListener('contextmenu', outside, true)
    window.addEventListener('resize', dismiss)
    document.addEventListener('scroll', dismiss, true)
    return () => {
      document.removeEventListener('pointerdown', outside)
      document.removeEventListener('contextmenu', outside, true)
      window.removeEventListener('resize', dismiss)
      document.removeEventListener('scroll', dismiss, true)
    }
  }, [onDismiss])
  return createPortal(<div ref={menu} className="master-tab-context-menu" role="menu" tabIndex={-1} aria-label="タブの操作" style={{ left: x, top: y }} onKeyDown={(event) => {
    if (event.key === 'Escape' || event.key === 'Tab') { event.preventDefault(); onDismiss(true); return }
    const buttons = Array.from(menu.current?.querySelectorAll<HTMLButtonElement>('button:not(:disabled)') ?? [])
    const index = buttons.indexOf(document.activeElement as HTMLButtonElement)
    const next = event.key === 'ArrowDown' ? (index + 1) % buttons.length : event.key === 'ArrowUp' ? (index - 1 + buttons.length) % buttons.length : event.key === 'Home' ? 0 : event.key === 'End' ? buttons.length - 1 : -1
    if (next >= 0) { event.preventDefault(); buttons[next]?.focus() }
  }}>{commands.map((command) => <button type="button" role="menuitem" key={command.label} disabled={command.disabled} onClick={() => { onDismiss(true); command.run() }}>{command.label}</button>)}</div>, document.body)
}

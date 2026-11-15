import { useId, useLayoutEffect, useRef, useState, type KeyboardEvent, type ReactNode } from 'react'
import { createPortal } from 'react-dom'

export type DockCommand = { label: string; run: () => void; disabled?: boolean }
type CommandGroup = { label?: string; commands: readonly DockCommand[] }

const menuInset = 8
const menuGap = 4

export function DockCommandMenu({ label, children, groups, className, align = 'end' }: {
  label: string
  children: ReactNode
  groups: readonly CommandGroup[]
  className: string
  align?: 'start' | 'end'
}) {
  const id = useId()
  const opener = useRef<HTMLButtonElement>(null)
  const menu = useRef<HTMLDivElement>(null)
  const [open, setOpen] = useState<{ document: Document; focusLast: boolean }>()
  const items = () => Array.from(menu.current?.querySelectorAll<HTMLButtonElement>('button:not(:disabled)') ?? [])
  const close = (restoreFocus: boolean) => {
    setOpen(undefined)
    if (restoreFocus) opener.current?.focus({ preventScroll: true })
  }

  useLayoutEffect(() => {
    if (!open || !opener.current || !menu.current) return
    const doc = open.document
    const view = doc.defaultView
    if (!view) return
    const trigger = opener.current
    const popup = menu.current
    const place = () => {
      const triggerRect = trigger.getBoundingClientRect()
      const { width } = popup.getBoundingClientRect()
      const viewportWidth = doc.documentElement.clientWidth || view.innerWidth
      const viewportHeight = doc.documentElement.clientHeight || view.innerHeight
      const above = triggerRect.top - menuGap - menuInset
      const below = viewportHeight - triggerRect.bottom - menuGap - menuInset
      const menuHeight = Math.min(popup.scrollHeight + 2, 510)
      const placeAbove = menuHeight > below && above > below
      const left = align === 'start' ? triggerRect.left : triggerRect.right - width
      popup.style.left = `${Math.max(menuInset, Math.min(left, viewportWidth - width - menuInset))}px`
      popup.style.maxHeight = `${Math.max(0, Math.min(510, placeAbove ? above : below))}px`
      popup.style.top = placeAbove ? 'auto' : `${triggerRect.bottom + menuGap}px`
      popup.style.bottom = placeAbove ? `${viewportHeight - triggerRect.top + menuGap}px` : 'auto'
    }
    const dismiss = (event: PointerEvent) => {
      const target = event.target as Node
      if (!trigger.contains(target) && !popup.contains(target)) close(true)
    }
    const dismissWindow = () => close(true)
    place()
    const options = items()
    const initialItem = open.focusLast ? options.at(-1) : options[0]
    initialItem?.focus()
    doc.addEventListener('pointerdown', dismiss)
    view.addEventListener('resize', place)
    view.addEventListener('blur', dismissWindow)
    return () => {
      doc.removeEventListener('pointerdown', dismiss)
      view.removeEventListener('resize', place)
      view.removeEventListener('blur', dismissWindow)
    }
  }, [align, open])

  const onMenuKey = (event: KeyboardEvent<HTMLDivElement>) => {
    if (event.key === 'Tab') {
      // The popup lives at the end of the owner document. Resume Tab at the trigger.
      close(true)
      return
    }
    if (event.key === 'Escape') {
      event.preventDefault()
      event.stopPropagation()
      close(true)
      return
    }
    if (!['ArrowDown', 'ArrowUp', 'Home', 'End'].includes(event.key)) return
    event.preventDefault()
    event.stopPropagation()
    const options = items()
    const index = options.findIndex(item => item === event.target)
    if (event.key === 'Home') options[0]?.focus()
    else if (event.key === 'End') options.at(-1)?.focus()
    else {
      const offset = event.key === 'ArrowDown' ? 1 : -1
      options[(index + offset + options.length) % options.length]?.focus()
    }
  }

  return <>
    <button type="button" ref={opener} className={className} title={label} aria-label={label} aria-haspopup="menu"
      aria-expanded={Boolean(open)} aria-controls={open ? id : undefined}
      onClick={event => {
        if (open) close(true)
        else setOpen({ document: event.currentTarget.ownerDocument, focusLast: false })
      }}
      onKeyDown={event => {
        if (event.key !== 'ArrowDown' && event.key !== 'ArrowUp') return
        event.preventDefault()
        event.stopPropagation()
        setOpen({ document: event.currentTarget.ownerDocument, focusLast: event.key === 'ArrowUp' })
      }}>{children}</button>
    {open && createPortal(<div id={id} className="dock-menu" role="menu" aria-label={label} ref={menu}
      onKeyDown={onMenuKey} onBlur={event => {
        if (!event.currentTarget.contains(event.relatedTarget) && event.relatedTarget !== opener.current) close(false)
      }}>
      {groups.map(({ label: groupLabel, commands }) => <div className="dock-menu-group" key={groupLabel ?? commands[0]?.label}
        role={groupLabel ? 'group' : 'none'} aria-label={groupLabel}>
        {groupLabel && <span className="dock-menu-heading" aria-hidden="true">{groupLabel}</span>}
        {commands.map(command => <button key={command.label} type="button" role="menuitem" tabIndex={-1} disabled={command.disabled} onClick={() => {
          close(true)
          command.run()
        }}>{command.label}</button>)}
      </div>)}
    </div>, open.document.body)}
  </>
}

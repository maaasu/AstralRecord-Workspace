import { useEffect, useRef, type ReactNode } from 'react'

interface ModalProps {
  children: ReactNode
  ariaLabel: string
  className?: string
  onClose: () => void
  closeDisabled?: boolean
}

/** Keep modal keyboard operations and focus inside the active dialog. */
export function Modal({ children, ariaLabel, className = '', onClose, closeDisabled = false }: ModalProps) {
  const dialogRef = useRef<HTMLElement>(null)
  const closeRef = useRef({ onClose, closeDisabled })
  closeRef.current = { onClose, closeDisabled }
  useEffect(() => {
    const dialog = dialogRef.current
    if (!dialog) return
    const previousFocus = document.activeElement instanceof HTMLElement ? document.activeElement : null
    const previousOverflow = document.body.style.overflow
    document.body.style.overflow = 'hidden'
    const focusable = () => Array.from(dialog.querySelectorAll<HTMLElement>(
      'button, input, textarea, select, a[href], summary, [tabindex]',
    )).filter(element => {
      const summary = element.tagName === 'SUMMARY' && !element.hasAttribute('tabindex')
      if (element.matches(':disabled') || element.tabIndex < 0 && !summary || element.closest('[hidden], [aria-hidden="true"]')) return false
      for (let ancestor = element.parentElement; ancestor && ancestor !== dialog; ancestor = ancestor.parentElement) {
        if (ancestor.tagName === 'DETAILS' && !ancestor.hasAttribute('open') && !ancestor.querySelector(':scope > summary')?.contains(element)) return false
      }
      return true
    })
    const firstFocus = () => (focusable()[0] ?? dialog).focus()
    firstFocus()
    const containFocus = (event: FocusEvent) => {
      // A nested modal (for example the material picker) manages its own focus.
      if (event.target instanceof HTMLElement && event.target.closest('[role="dialog"]') !== dialog) {
        if (event.target.closest('[role="dialog"]')) return
        firstFocus()
      }
    }
    const keyboard = (event: KeyboardEvent) => {
      if (event.target instanceof HTMLElement && event.target.closest('[role="dialog"]') !== dialog && event.target.closest('[role="dialog"]')) return
      if (event.key === 'Escape') {
        event.preventDefault(); event.stopImmediatePropagation()
        if (!closeRef.current.closeDisabled) closeRef.current.onClose()
      } else if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 's') {
        event.preventDefault(); event.stopImmediatePropagation()
      } else if (event.key === 'Tab') {
        const elements = focusable()
        event.preventDefault()
        const index = elements.findIndex(element => element === document.activeElement)
        const next = index < 0 ? event.shiftKey ? elements.length - 1 : 0 : (index + (event.shiftKey ? -1 : 1) + elements.length) % elements.length
        ;(elements[next] ?? dialog).focus()
      }
    }
    document.addEventListener('keydown', keyboard, true)
    document.addEventListener('focusin', containFocus)
    return () => {
      document.removeEventListener('keydown', keyboard, true)
      document.removeEventListener('focusin', containFocus)
      document.body.style.overflow = previousOverflow
      if (previousFocus?.isConnected) previousFocus.focus()
    }
  }, [])
  return <div className="modal-backdrop" role="presentation" onKeyDown={event => event.stopPropagation()}>
    <section ref={dialogRef} className={`modal ${className}`} role="dialog" aria-modal="true" aria-label={ariaLabel} tabIndex={-1}>
      {children}
    </section>
  </div>
}

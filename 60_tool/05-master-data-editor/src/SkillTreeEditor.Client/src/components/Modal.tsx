import { useEffect, useRef, type ReactNode } from 'react'
import { registerAndroidDialog } from '../androidEditor'
import { usePanelDocument, usePanelVisibility } from '../docking/dockPanel'

interface ModalProps {
  children: ReactNode
  ariaLabel: string
  className?: string
  onClose: () => void
  onAndroidBack?: () => void
  closeDisabled?: boolean
  ownerDocument?: Document
}

/** Keep modal keyboard operations and focus inside the active dialog. */
export function Modal({ children, ariaLabel, className = '', onClose, onAndroidBack = onClose, closeDisabled = false, ownerDocument }: ModalProps) {
  const panelDocument = usePanelDocument()
  const document = ownerDocument ?? panelDocument
  const visible = usePanelVisibility()
  const dialogRef = useRef<HTMLElement>(null)
  const closeRef = useRef({ onClose, onAndroidBack, closeDisabled })
  closeRef.current = { onClose, onAndroidBack, closeDisabled }
  useEffect(() => {
    const dialog = dialogRef.current
    if (!dialog || !visible) return
    const unregisterBack = registerAndroidDialog(dialog, () => {
      if (!closeRef.current.closeDisabled) closeRef.current.onAndroidBack()
    })
    const previousFocus = document.activeElement as HTMLElement | null
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
      const element = event.target as Element | null
      if (element?.nodeType === 1 && !element.closest('[role="dialog"]')) firstFocus()
    }
    const keyboard = (event: KeyboardEvent) => {
      const element = event.target as Element | null
      const targetDialog = element?.nodeType === 1 ? element.closest('[role="dialog"]') : null
      if (targetDialog && targetDialog !== dialog) return
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
      unregisterBack()
      document.removeEventListener('keydown', keyboard, true)
      document.removeEventListener('focusin', containFocus)
      document.body.style.overflow = previousOverflow
      if (previousFocus?.isConnected) previousFocus.focus()
    }
  }, [document, visible])
  return <div className="modal-backdrop" role="presentation" onKeyDown={event => event.stopPropagation()}>
    <section ref={dialogRef} className={`modal ${className}`} role="dialog" aria-modal="true" aria-label={ariaLabel} tabIndex={-1}>
      {children}
    </section>
  </div>
}

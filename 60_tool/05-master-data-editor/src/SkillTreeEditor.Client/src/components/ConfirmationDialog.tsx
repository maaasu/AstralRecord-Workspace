import { useCallback, useEffect, useRef, useState } from 'react'
import { createPortal } from 'react-dom'
import { usePanelDocument, usePanelVisibility } from '../docking/dockPanel'
import { Modal } from './Modal'
import './confirmationDialog.css'

interface Confirmation {
  message: string
  title: string
  confirmLabel: string
  document: Document
  panelDocument: Document
  resolve: (accepted: boolean) => void
}

export function useConfirmationDialog() {
  const panelDocument = usePanelDocument()
  const visible = usePanelVisibility()
  const [pending, setPending] = useState<Confirmation | null>(null)
  const current = useRef<Confirmation | null>(null)
  const finish = useCallback((accepted: boolean) => {
    current.current?.resolve(accepted)
    current.current = null
    setPending(null)
  }, [])
  useEffect(() => {
    if (!visible) finish(false)
  }, [visible, finish])
  useEffect(() => {
    if (current.current && current.current.panelDocument !== panelDocument) finish(false)
  }, [panelDocument, finish])
  useEffect(() => {
    const owner = pending?.document.defaultView
    if (!owner) return
    const cancel = () => finish(false)
    owner.addEventListener('pagehide', cancel)
    owner.addEventListener('beforeunload', cancel)
    return () => {
      owner.removeEventListener('pagehide', cancel)
      owner.removeEventListener('beforeunload', cancel)
    }
  }, [pending, finish])
  useEffect(() => () => {
    current.current?.resolve(false)
    current.current = null
  }, [])
  const confirm = useCallback((message: string, options: {
    title?: string; confirmLabel?: string; document?: Document
  } = {}) => {
    if (current.current) return Promise.resolve(false)
    return new Promise<boolean>(resolve => {
      const request = {
        message, resolve, title: options.title ?? '未保存の編集内容',
        confirmLabel: options.confirmLabel ?? '変更を破棄',
        document: options.document ?? panelDocument, panelDocument,
      }
      current.current = request
      setPending(request)
    })
  }, [panelDocument])
  const body = pending?.document.body
  const dialog = pending && body && createPortal(
    <Modal ariaLabel={pending.title} className="confirmation-dialog" ownerDocument={pending.document} onClose={() => finish(false)}>
      <header className="modal-header"><h2>{pending.title}</h2></header>
      <div className="modal-body"><p>{pending.message}</p></div>
      <footer className="modal-footer">
        <button type="button" className="button" onClick={() => finish(false)}>キャンセル</button>
        <button type="button" className="button danger" onClick={() => finish(true)}>{pending.confirmLabel}</button>
      </footer>
    </Modal>, body,
  )
  return { confirm, dialog }
}

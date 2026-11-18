import { usePanelDocument, usePanelVisibility } from '../docking/dockPanel'
import { lazy, Suspense, useEffect, useRef, useState } from 'react'
import { createPortal } from 'react-dom'
import { registerAndroidDialog } from '../androidEditor'
import { minecraftMaterialSuggestions } from '../data/nodeFieldSuggestions'
import { MinecraftIcon } from './MinecraftIcon'
import { SuggestionInput } from './SuggestionInput'
import type { JsonValue } from '../types/editor'
import './materialIconInput.css'

const MaterialIconPickerDialog = lazy(() => import('./MaterialIconPickerDialog').then(module => ({ default: module.MaterialIconPickerDialog })))

interface MaterialIconInputProps {
  value: string
  onChange: (material: string) => void
  id?: string
  ariaLabel?: string
  disabled?: boolean
  suggestions?: readonly string[]
  revision?: number
  placeholder?: string
  customModelData?: JsonValue
  iconTexture?: JsonValue
  iconModel?: JsonValue
}

/** Keep free text and the original saved value; selection supplies a canonical Material ID. */
export function MaterialIconInput({ value, onChange, id, ariaLabel = id ? undefined : 'Minecraft Material', disabled = false, suggestions = minecraftMaterialSuggestions, revision = 0, placeholder, customModelData, iconTexture, iconModel }: MaterialIconInputProps) {
  const document = usePanelDocument()
  const visibleInLayout = usePanelVisibility()
  const [open, setOpen] = useState(false)
  const [retry, setRetry] = useState(0)
  const opener = useRef<HTMLButtonElement>(null)
  useEffect(() => { if (disabled) setOpen(false) }, [disabled])
  const select = (material: string) => {
    // Portal controls do not inherit the editor's disabled fieldset during a save.
    if (disabled || !opener.current || opener.current.matches(':disabled')) { setOpen(false); return }
    onChange(material)
    setOpen(false)
  }
  return <div className="material-icon-input">
    <span className="material-icon-input-preview" role="img" aria-label={value ? `素材アイコン: ${value}` : '素材アイコン未設定'}>
      <MinecraftIcon icon={value} customModelData={customModelData} iconTexture={iconTexture} iconModel={iconModel} revision={revision + retry} className="material-field-icon" />
    </span>
    <div className="material-icon-input-controls">
      <SuggestionInput id={id} aria-label={ariaLabel} value={value} disabled={disabled} suggestions={suggestions} placeholder={placeholder} onChange={event => onChange(event.target.value)} />
      <div className="material-icon-input-actions">
        <button ref={opener} className="button subtle" type="button" disabled={disabled} aria-haspopup="dialog" onClick={() => setOpen(true)}>画像一覧から選択</button>
        <button className="button subtle compact" type="button" disabled={disabled || (!value.trim() && !iconModel)} onClick={() => setRetry(current => current + 1)} title="取得に失敗したプレビューを再読込">画像を再読込</button>
      </div>
    </div>
    {open && visibleInLayout && createPortal(<Suspense fallback={<MaterialPickerLoading onClose={() => setOpen(false)} />}>
      <MaterialIconPickerDialog value={value} materials={suggestions} revision={revision + retry} onSelect={select} onClose={() => setOpen(false)} />
    </Suspense>, document.body)}
  </div>
}

/** Protect the draft even before the lazy dialog module finishes loading. */
export function MaterialPickerLoading({ onClose }: { onClose: () => void }) {
  const document = usePanelDocument()
  const cancel = useRef<HTMLButtonElement>(null)
  const loadingDialog = useRef<HTMLElement>(null)
  const closeRef = useRef(onClose)
  closeRef.current = onClose
  useEffect(() => {
    const unregisterBack = loadingDialog.current ? registerAndroidDialog(loadingDialog.current, () => closeRef.current()) : undefined
    const previousFocus = document.activeElement as HTMLElement | null
    const previousOverflow = document.body.style.overflow
    document.body.style.overflow = 'hidden'
    cancel.current?.focus()
    const keyboard = (event: KeyboardEvent) => {
      event.stopImmediatePropagation()
      if (event.key === 'Escape') { event.preventDefault(); closeRef.current() }
      else if (event.key === 'Tab') { event.preventDefault(); cancel.current?.focus() }
      else if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 's') event.preventDefault()
    }
    const containFocus = () => { if (document.activeElement !== cancel.current) cancel.current?.focus() }
    document.addEventListener('keydown', keyboard, true)
    document.addEventListener('focusin', containFocus)
    return () => {
      unregisterBack?.()
      document.removeEventListener('keydown', keyboard, true)
      document.removeEventListener('focusin', containFocus)
      document.body.style.overflow = previousOverflow
      if (previousFocus?.isConnected) previousFocus.focus()
    }
  }, [document])
  return <div className="material-picker-loading" onClick={event => { event.stopPropagation(); if (event.target === event.currentTarget) onClose() }}>
    <section ref={loadingDialog} role="dialog" aria-modal="true" aria-label="素材一覧を読込中" className="material-picker-loading-panel">
      <p role="status">素材一覧を読み込んでいます…</p>
      <button ref={cancel} type="button" className="button subtle" onClick={onClose}>キャンセル</button>
    </section>
  </div>
}

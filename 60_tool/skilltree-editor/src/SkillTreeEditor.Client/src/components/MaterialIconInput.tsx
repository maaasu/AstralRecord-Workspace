import { lazy, Suspense, useEffect, useRef, useState } from 'react'
import { createPortal } from 'react-dom'
import { minecraftMaterialSuggestions } from '../data/nodeFieldSuggestions'
import { MinecraftIcon } from './MinecraftIcon'
import { SuggestionInput } from './SuggestionInput'
import './materialIconInput.css'

const MaterialIconPickerDialog = lazy(() => import('./MaterialIconPickerDialog').then(module => ({ default: module.MaterialIconPickerDialog })))

interface MaterialIconInputProps {
  value: string
  onChange: (material: string) => void
  ariaLabel?: string
  disabled?: boolean
  suggestions?: readonly string[]
  revision?: number
  placeholder?: string
}

/** Keep free text and the original saved value; selection supplies a canonical Material ID. */
export function MaterialIconInput({ value, onChange, ariaLabel = 'Minecraft Material', disabled = false, suggestions = minecraftMaterialSuggestions, revision = 0, placeholder }: MaterialIconInputProps) {
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
      <MinecraftIcon icon={value} revision={revision + retry} className="material-field-icon" />
    </span>
    <div className="material-icon-input-controls">
      <SuggestionInput aria-label={ariaLabel} value={value} disabled={disabled} suggestions={suggestions} placeholder={placeholder} onChange={event => onChange(event.target.value)} />
      <div className="material-icon-input-actions">
        <button ref={opener} className="button subtle" type="button" disabled={disabled} onClick={() => setOpen(true)}>画像一覧から選択</button>
        <button className="button subtle compact" type="button" disabled={disabled || !value.trim()} onClick={() => setRetry(current => current + 1)} title="取得に失敗したプレビューを再読込">画像を再読込</button>
      </div>
    </div>
    {open && createPortal(<Suspense fallback={<MaterialPickerLoading onClose={() => setOpen(false)} />}>
      <MaterialIconPickerDialog value={value} materials={suggestions} revision={revision + retry} onSelect={select} onClose={() => setOpen(false)} />
    </Suspense>, document.body)}
  </div>
}

/** Protect the draft even before the lazy dialog module finishes loading. */
export function MaterialPickerLoading({ onClose }: { onClose: () => void }) {
  const cancel = useRef<HTMLButtonElement>(null)
  const closeRef = useRef(onClose)
  closeRef.current = onClose
  useEffect(() => {
    const previousFocus = document.activeElement instanceof HTMLElement ? document.activeElement : null
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
      document.removeEventListener('keydown', keyboard, true)
      document.removeEventListener('focusin', containFocus)
      document.body.style.overflow = previousOverflow
      if (previousFocus?.isConnected) previousFocus.focus()
    }
  }, [])
  return <div className="material-picker-loading" onClick={event => { event.stopPropagation(); if (event.target === event.currentTarget) onClose() }}>
    <section role="dialog" aria-modal="true" aria-label="素材一覧を読込中" className="material-picker-loading-panel">
      <p role="status">素材一覧を読み込んでいます…</p>
      <button ref={cancel} type="button" className="button subtle" onClick={onClose}>キャンセル</button>
    </section>
  </div>
}

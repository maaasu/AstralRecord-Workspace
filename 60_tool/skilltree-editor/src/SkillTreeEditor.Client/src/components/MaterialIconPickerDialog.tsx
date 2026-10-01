import { useEffect, useId, useMemo, useRef, useState } from 'react'
import minecraftMaterials from '../data/minecraft-materials.1.21.11.json'
import { MinecraftIcon } from './MinecraftIcon'
import './materialIconPicker.css'

interface MaterialIconPickerDialogProps {
  value: string
  onSelect: (material: string) => void
  onClose: () => void
  materials?: readonly string[]
  revision?: number
}

const PAGE_SIZE = 48

function canonicalMaterial(value: string): string {
  return value.trim().replace(/^minecraft:/i, '').toUpperCase()
}

export function MaterialIconPickerDialog({
  value,
  onSelect,
  onClose,
  materials = minecraftMaterials,
  revision = 0,
}: MaterialIconPickerDialogProps) {
  const titleId = useId()
  const searchId = useId()
  const dialogRef = useRef<HTMLElement>(null)
  const searchRef = useRef<HTMLInputElement>(null)
  const closeRef = useRef(onClose)
  closeRef.current = onClose
  const allMaterials = useMemo(
    () => [...new Set(materials.map(canonicalMaterial).filter(Boolean))],
    [materials],
  )
  const selectedMaterial = canonicalMaterial(value)
  const [query, setQuery] = useState('')
  const [page, setPage] = useState(() => Math.floor(Math.max(0, allMaterials.indexOf(selectedMaterial)) / PAGE_SIZE))
  const [reloadRevision, setReloadRevision] = useState(0)
  const filteredMaterials = useMemo(() => {
    const tokens = canonicalMaterial(query).split(/[\s_]+/).filter(Boolean)
    return allMaterials.filter((material) => tokens.every((token) => material.includes(token)))
  }, [allMaterials, query])
  const pageCount = Math.max(1, Math.ceil(filteredMaterials.length / PAGE_SIZE))
  const currentPage = Math.min(page, pageCount - 1)
  const pageMaterials = filteredMaterials.slice(currentPage * PAGE_SIZE, (currentPage + 1) * PAGE_SIZE)

  useEffect(() => {
    const previousFocus = document.activeElement instanceof HTMLElement ? document.activeElement : null
    const previousOverflow = document.body.style.overflow
    document.body.style.overflow = 'hidden'
    searchRef.current?.focus()

    const keyboard = (event: KeyboardEvent) => {
      if (event.key === 'Escape' || ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 's')) {
        event.preventDefault()
        event.stopImmediatePropagation()
        if (event.key === 'Escape') closeRef.current()
        return
      }
      if (event.key !== 'Tab') return
      const dialog = dialogRef.current
      const focusable = dialog?.querySelectorAll<HTMLElement>('button:not([disabled]), input:not([disabled]), [tabindex="0"]')
      if (!dialog || !focusable?.length) return
      const first = focusable[0]
      const last = focusable[focusable.length - 1]
      const outside = !dialog.contains(document.activeElement)
      if (event.shiftKey && (document.activeElement === first || outside)) {
        event.preventDefault()
        last.focus()
      } else if (!event.shiftKey && (document.activeElement === last || outside)) {
        event.preventDefault()
        first.focus()
      }
    }
    const containFocus = (event: FocusEvent) => {
      if (event.target instanceof Node && !dialogRef.current?.contains(event.target)) searchRef.current?.focus()
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

  return (
    <div
      className="material-picker-backdrop"
      onClick={(event) => {
        event.stopPropagation()
        if (event.target === event.currentTarget) onClose()
      }}
      onKeyDown={(event) => {
        event.stopPropagation()
        if (event.key === 'Enter' && event.target === searchRef.current) event.preventDefault()
      }}
    >
      <section ref={dialogRef} className="material-picker-dialog" role="dialog" aria-modal="true" aria-labelledby={titleId}>
        <header className="material-picker-header">
          <div>
            <h2 id={titleId}>素材アイコンを選択</h2>
            <p className="muted">画像とMaterial IDを確認して選択できます。</p>
          </div>
          <button type="button" className="icon-button" aria-label="素材アイコン選択を閉じる" onClick={onClose}>×</button>
        </header>
        <div className="material-picker-tools">
          <label htmlFor={searchId}>Materialを検索</label>
          <input
            ref={searchRef}
            id={searchId}
            type="search"
            value={query}
            placeholder="例: iron sword / oak"
            onChange={(event) => {
              setQuery(event.target.value)
              setPage(0)
            }}
          />
          <div className="material-picker-summary">
            <span role="status">
              {filteredMaterials.length}件
              {filteredMaterials.length > 0 && `・${currentPage * PAGE_SIZE + 1}〜${currentPage * PAGE_SIZE + pageMaterials.length}件を表示`}
            </span>
            <button type="button" className="button subtle" onClick={() => setReloadRevision((current) => current + 1)} disabled={!pageMaterials.length}>
              このページの画像を再読込
            </button>
          </div>
        </div>
        <div className="material-picker-results">
          {pageMaterials.length ? (
            <ul className="material-picker-grid" aria-label="Material一覧">
              {pageMaterials.map((material) => (
                <li key={material}>
                  <button
                    type="button"
                    className={`material-picker-card${material === selectedMaterial ? ' selected' : ''}`}
                    aria-label={material}
                    aria-pressed={material === selectedMaterial}
                    onClick={() => onSelect(material)}
                  >
                    <MinecraftIcon icon={material} revision={revision + reloadRevision} className="material-picker-icon" />
                    <code>{material}</code>
                    {material === selectedMaterial && <span className="material-picker-selected">選択中</span>}
                  </button>
                </li>
              ))}
            </ul>
          ) : <p className="material-picker-empty">該当するMaterialがありません。検索語を変更してください。</p>}
        </div>
        <footer className="material-picker-footer">
          <span className="material-picker-current">現在の値: <code>{value || '未設定'}</code></span>
          <div className="material-picker-pagination" aria-label="Material一覧のページ切替">
            <button type="button" className="button subtle" disabled={currentPage === 0} onClick={() => setPage(currentPage - 1)}>前のページ</button>
            <span>{currentPage + 1} / {pageCount}</span>
            <button type="button" className="button subtle" disabled={currentPage + 1 >= pageCount} onClick={() => setPage(currentPage + 1)}>次のページ</button>
          </div>
          <button type="button" className="button subtle" onClick={onClose}>キャンセル</button>
        </footer>
      </section>
    </div>
  )
}

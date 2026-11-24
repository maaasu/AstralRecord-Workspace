import { usePanelDocument, usePanelVisibility } from '../docking/dockPanel'
import { useEffect, useId, useMemo, useRef, useState, type KeyboardEvent, type MouseEvent } from 'react'
import { createPortal } from 'react-dom'
import { isAndroidEditor, registerAndroidDialog } from '../androidEditor'
import { plainMasterFilterName } from '../data/masterDataPresentation'
import { stripMinecraftFormatting } from '../utils/minecraft'
import { MinecraftIcon } from './MinecraftIcon'
import { MasterReferenceButton, type ReferencePreview } from './MasterReferenceButton'
import './masterEntityPicker.css'

export interface MasterEntityPickerChoice {
  id: string
  name: string
  category?: string
  description?: string
  icon?: string | null
  customModelData?: number | null
  iconTexture?: string | null
  iconModel?: string | null
  symbol?: string
  disabledReason?: string
  preview?: ReferencePreview
}

interface MasterEntityPickerProps {
  choices: readonly MasterEntityPickerChoice[]
  value?: string | null
  title: string
  onSelect: (id: string) => void
  onChoiceContextMenu?: (choice: MasterEntityPickerChoice, event: MouseEvent<HTMLLIElement>) => void
  onClose: () => void
  loading?: boolean
  error?: string | null
  onRetry?: () => void
}

const PAGE_SIZE = 40
const comparable = (text: string) => text.normalize('NFKC').toLocaleLowerCase()
const isPlainKey = (event: KeyboardEvent) => !event.nativeEvent.isComposing && event.keyCode !== 229
  && !event.altKey && !event.ctrlKey && !event.metaKey && !event.shiftKey

export function MasterEntityPicker({
  choices, value, title, onSelect, onChoiceContextMenu, onClose, loading, error, onRetry,
}: MasterEntityPickerProps) {
  const titleId = useId()
  const dialog = useRef<HTMLElement>(null)
  const search = useRef<HTMLInputElement>(null)
  const results = useRef<HTMLDivElement>(null)
  const buttons = useRef(new Map<string, HTMLButtonElement>())
  const pendingFocus = useRef<string | null>(null)
  const composing = useRef(false)
  const close = useRef(onClose)
  close.current = onClose

  const [query, setQuery] = useState('')
  const [focusedId, setFocusedId] = useState<string | null>(null)
  const [category, setCategory] = useState('')
  const [showIncompatible, setShowIncompatible] = useState(false)
  // A null page follows the current value when the catalog arrives after opening.
  const [page, setPage] = useState<number | null>(null)
  const prepared = useMemo(() => choices.map(choice => {
    const name = plainMasterFilterName(choice.name, choice.id)
    const description = stripMinecraftFormatting(choice.description ?? '').trim()
    const category = stripMinecraftFormatting(choice.category ?? '').trim()
    return { choice, name, description, category, searchText: comparable([name, choice.id, category, description].join(' ')) }
  }), [choices])
  const categories = useMemo(
    () => [...new Set(prepared.map(entry => entry.category).filter(Boolean))].sort((a, b) => a.localeCompare(b, 'ja')),
    [prepared],
  )
  const selected = prepared.find(entry => entry.choice.id === value)
  const canShowResults = !loading && !error
  const matches = useMemo(() => {
    const tokens = comparable(stripMinecraftFormatting(query)).split(/\s+/u).filter(Boolean)
    return prepared.filter(entry => (!category || entry.category === category) && tokens.every(token => entry.searchText.includes(token)))
  }, [category, prepared, query])
  const hiddenCount = matches.filter(({ choice }) => choice.disabledReason && choice.id !== value).length
  const entries = useMemo(
    () => canShowResults ? matches.filter(({ choice }) => showIncompatible || !choice.disabledReason || choice.id === value) : [],
    [canShowResults, matches, showIncompatible, value],
  )
  const selectable = entries.filter(({ choice }) => !choice.disabledReason)
  const selectedIndex = entries.findIndex(entry => entry.choice.id === value)
  const pageCount = Math.max(1, Math.ceil(entries.length / PAGE_SIZE))
  const currentPage = Math.min(page ?? Math.floor(Math.max(0, selectedIndex) / PAGE_SIZE), pageCount - 1)
  const start = currentPage * PAGE_SIZE
  const visible = entries.slice(start, start + PAGE_SIZE)
  const visibleSelectable = visible.filter(({ choice }) => !choice.disabledReason)
  const firstAvailable = visibleSelectable[0]
  const tabEntry = visible.find(({ choice }) => choice.id === focusedId && !choice.disabledReason) ?? firstAvailable

  const document = usePanelDocument()
  const visibleInLayout = usePanelVisibility()
  useEffect(() => {
    const section = dialog.current
    if (!section || !visibleInLayout) return
    const unregisterBack = registerAndroidDialog(section, () => close.current())
    const opener = document.activeElement as HTMLElement | null
    const overflow = document.body.style.overflow
    document.body.style.overflow = 'hidden'
    if (isAndroidEditor()) section.focus()
    else search.current?.focus()

    const keepFocus = (event: FocusEvent) => {
      if ((event.target as Node | null)?.nodeType === 1 && !(event.target as HTMLElement).closest('[role="dialog"]')) search.current?.focus()
    }
    const handleKey = (event: globalThis.KeyboardEvent) => {
      if ((event.target as Node | null)?.nodeType === 1 && (event.target as HTMLElement).closest('[role="dialog"]') !== section) return
      if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 's') {
        event.preventDefault()
        event.stopImmediatePropagation()
      } else if (event.key === 'Escape' && !composing.current && !event.isComposing && event.keyCode !== 229) {
        event.preventDefault()
        event.stopImmediatePropagation()
        close.current()
      } else if (event.key === 'Tab' && !composing.current && !event.isComposing && event.keyCode !== 229) {
        const controls = Array.from(section.querySelectorAll<HTMLElement>(
          'button, input, select',
        )).filter(control => !control.matches(':disabled') && control.tabIndex >= 0)
        const index = controls.indexOf(document.activeElement as HTMLElement)
        const step = event.shiftKey ? -1 : 1
        const next = index < 0 ? (event.shiftKey ? controls.length - 1 : 0) : (index + step + controls.length) % controls.length
        event.preventDefault()
        ;(controls[next] ?? section).focus()
      }
    }

    document.addEventListener('keydown', handleKey, true)
    document.addEventListener('focusin', keepFocus)
    return () => {
      unregisterBack()
      document.removeEventListener('keydown', handleKey, true)
      document.removeEventListener('focusin', keepFocus)
      document.body.style.overflow = overflow
      if (opener?.isConnected) opener.focus()
    }
  }, [document, visibleInLayout])

  useEffect(() => {
    if (results.current) results.current.scrollTop = 0
    const target = pendingFocus.current
    pendingFocus.current = null
    if (target) (buttons.current.get(target) ?? search.current)?.focus()
    else if (document.activeElement === document.body) search.current?.focus()
  }, [currentPage, entries, document])

  const focusChoice = (id: string | undefined) => {
    if (!id) return
    const button = buttons.current.get(id)
    if (button) button.focus()
    else {
      pendingFocus.current = id
      setPage(Math.floor(entries.findIndex(entry => entry.choice.id === id) / PAGE_SIZE))
    }
  }

  const changePage = (nextPage: number) => {
    const first = entries.slice(nextPage * PAGE_SIZE, (nextPage + 1) * PAGE_SIZE).find(({ choice }) => !choice.disabledReason)
    if (first) focusChoice(first.choice.id)
    else {
      setPage(nextPage)
      search.current?.focus()
    }
  }

  return createPortal(
    <div hidden={!visibleInLayout} className="modal-backdrop master-entity-picker-backdrop"
      onKeyDown={event => event.stopPropagation()}
      onClick={event => {
        event.stopPropagation()
        if (event.target === event.currentTarget) onClose()
      }}>
      <section ref={dialog} className="modal master-entity-picker" role="dialog" aria-modal="true" aria-labelledby={titleId} tabIndex={-1}>
        <header className="master-entity-picker-header">
          <h2 id={titleId}>{title}</h2>
          <button type="button" className="icon-button" aria-label="閉じる" onClick={onClose}>×</button>
        </header>

        <div className="master-entity-picker-tools">
          <label className="master-entity-picker-search">
            名前・IDで検索
            <input ref={search} type="search" value={query} placeholder="空白で複数のキーワードを指定"
              onChange={event => { setQuery(event.target.value); setPage(0) }}
              onCompositionStart={() => { composing.current = true }}
              onCompositionEnd={() => { composing.current = false }}
              onKeyDown={event => {
                if (composing.current || !isPlainKey(event)) return
                if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
                  event.preventDefault()
                  focusChoice((event.key === 'ArrowDown' ? firstAvailable : visibleSelectable.at(-1))?.choice.id)
                } else if (event.key === 'Enter') {
                  event.preventDefault()
                  if (firstAvailable) onSelect(firstAvailable.choice.id)
                }
              }}
            />
          </label>
          {categories.length > 0 && <label>
            カテゴリ
            <select value={category} onChange={event => { setCategory(event.target.value); setPage(0) }}>
              <option value="">すべて</option>
              {categories.map(option => <option key={option} value={option}>{option}</option>)}
            </select>
          </label>}
        </div>

        <div className="master-entity-picker-summary">
          <span role="status">
            {loading ? '候補を読み込み中…' : !error && (
              entries.length ? `${entries.length}件 · ${start + 1}〜${start + visible.length}件を表示` : '0件'
            )}
          </span>
          {canShowResults && (hiddenCount > 0 || showIncompatible) && <label>
            <input type="checkbox" checked={showIncompatible}
              onChange={event => { setShowIncompatible(event.target.checked); setPage(0) }}
            />
            選択できない候補も表示{hiddenCount > 0 && `（${hiddenCount}件）`}
          </label>}
        </div>

        <div ref={results} className="master-entity-picker-results" aria-busy={loading || undefined}>
          {canShowResults && (visible.length > 0 ? <ul aria-label="選択候補">
            {visible.map(({ choice, name, category, description }) => <li key={choice.id}
              onContextMenu={onChoiceContextMenu && (event => onChoiceContextMenu(choice, event))}>
              <MasterReferenceButton type="button" className="master-entity-picker-choice" preview={choice.preview}
                ref={element => {
                  if (element) buttons.current.set(choice.id, element)
                  else buttons.current.delete(choice.id)
                }}
                disabled={!!choice.disabledReason}
                aria-pressed={choice.id === value}
                tabIndex={choice.id === tabEntry?.choice.id ? 0 : -1}
                onFocus={() => setFocusedId(choice.id)}
                onClick={() => onSelect(choice.id)}
                onKeyDown={event => {
                  if (!isPlainKey(event)) return
                  const index = selectable.findIndex(entry => entry.choice.id === choice.id)
                  const next = event.key === 'ArrowDown' ? selectable[index + 1]
                    : event.key === 'ArrowUp' ? selectable[index - 1]
                    : event.key === 'Home' ? selectable[0]
                    : event.key === 'End' ? selectable.at(-1) : undefined
                  if (next) {
                    event.preventDefault()
                    focusChoice(next.choice.id)
                  } else if (event.key === 'ArrowUp') {
                    event.preventDefault()
                    search.current?.focus()
                  }
                }}
              >
                <span aria-hidden="true" className="master-entity-picker-art">
                  {choice.symbol && !choice.icon && !choice.iconModel
                    ? <span className="minecraft-icon master-reference-symbol">{choice.symbol}</span>
                    : <MinecraftIcon icon={choice.icon ?? null} customModelData={choice.customModelData} iconTexture={choice.iconTexture} iconModel={choice.iconModel} />}
                </span>
                <span className="master-entity-picker-details">
                  <span className="master-entity-picker-name">
                    <strong>{name}</strong>
                    {choice.id === value && <span className="master-entity-picker-selected">選択中</span>}
                  </span>
                  <span className="master-entity-picker-meta">
                    <code>{choice.id}</code>
                    {category && <span>{category}</span>}
                  </span>
                  {description && <span className="master-entity-picker-description">{description}</span>}
                  {choice.disabledReason && <span className="master-entity-picker-reason">{choice.disabledReason}</span>}
                </span>
              </MasterReferenceButton>
            </li>)}
          </ul> : <p className="master-entity-picker-empty">条件に一致する候補がありません。</p>)}
          {!loading && error && <div className="master-entity-picker-empty">
            <p role="alert">{error}</p>
            {onRetry && <button type="button" className="button" onClick={() => { search.current?.focus(); onRetry() }}>再試行</button>}
          </div>}
        </div>

        <footer className="master-entity-picker-footer">
          <div className="master-entity-picker-current">
            <span>現在の選択</span>
            <strong title={value ?? undefined}>{selected?.name ?? (value || '未選択')}</strong>
          </div>
          {pageCount > 1 && <div className="master-entity-picker-pages" aria-label="候補のページ切替">
            <button type="button" className="button" disabled={currentPage === 0} onClick={() => changePage(currentPage - 1)} aria-label="前のページ">‹</button>
            <span>{currentPage + 1} / {pageCount}</span>
            <button type="button" className="button" disabled={currentPage + 1 === pageCount} onClick={() => changePage(currentPage + 1)} aria-label="次のページ">›</button>
          </div>}
          <button type="button" className="button" onClick={onClose}>キャンセル</button>
        </footer>
      </section>
    </div>,
    document.body,
  )
}

export default MasterEntityPicker

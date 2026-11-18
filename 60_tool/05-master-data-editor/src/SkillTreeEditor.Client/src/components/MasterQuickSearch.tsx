import { useEffect, useId, useMemo, useRef, useState } from 'react'
import { isAndroidEditor, registerAndroidDialog } from '../androidEditor'
import { masterDataApi } from '../api/masterDataApi'
import { createMasterNameSearchCache } from '../data/masterDataFilters'
import type { MasterFileSummary } from '../types/masterData'
import { MinecraftText } from './MinecraftText'
import './masterQuickSearch.css'

/** Filebase-wide quick open, independent of the file browser's current filters. */
export function MasterQuickSearch({ onOpen }: { onOpen: (path: string) => void }) {
  const [open, setOpen] = useState(false)
  const [query, setQuery] = useState('')
  const [files, setFiles] = useState<MasterFileSummary[]>([])
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')
  const [attempt, setAttempt] = useState(0)
  const [selected, setSelected] = useState(0)
  const [limit, setLimit] = useState(50)
  const input = useRef<HTMLInputElement>(null)
  const host = useRef<HTMLDivElement>(null)
  const previousFocus = useRef<HTMLElement | null>(null)
  const listId = useId()
  const search = useMemo(() => createMasterNameSearchCache(), [files])
  const matches = useMemo(() => {
    const match = search.matcher(query)
    return files.filter(file => match(file.name, file.id, file.path))
  }, [files, query, search])
  const activeIndex = Math.min(selected, Math.max(0, matches.length - 1))
  const visible = matches.slice(0, Math.max(limit, activeIndex + 1))

  useEffect(() => {
    const shortcut = (event: KeyboardEvent) => {
      if (event.isComposing || event.keyCode === 229 || event.altKey || event.shiftKey
        || !(event.ctrlKey || event.metaKey) || event.key.toLowerCase() !== 'p') return
      event.preventDefault()
      if (document.activeElement !== input.current) previousFocus.current = document.activeElement as HTMLElement | null
      input.current?.focus()
      input.current?.select()
      setOpen(true)
    }
    window.addEventListener('keydown', shortcut)
    return () => window.removeEventListener('keydown', shortcut)
  }, [])

  useEffect(() => {
    if (!open) return
    let live = true
    setLoading(true)
    setError('')
    setFiles([])
    // Refresh on every opening so files created, saved or deleted elsewhere appear here.
    void masterDataApi.files().then(result => {
      if (live) { setFiles(result); setSelected(0) }
    }).catch((reason: unknown) => {
      if (live) setError(reason instanceof Error ? reason.message : String(reason))
    }).finally(() => { if (live) setLoading(false) })
    return () => { live = false }
  }, [open, attempt])

  useEffect(() => {
    if (open) document.getElementById(`${listId}-${activeIndex}`)?.scrollIntoView?.({ block: 'nearest' })
  }, [open, activeIndex, listId])

  useEffect(() => {
    if (!open || !host.current) return
    return registerAndroidDialog(host.current, () => {
      setOpen(false)
      input.current?.blur()
    })
  }, [open])

  const openFile = (path: string) => {
    setOpen(false)
    input.current?.blur()
    onOpen(path)
  }
  return <div ref={host} className="master-quick-search" onBlur={event => {
    if (!event.currentTarget.contains(event.relatedTarget as Node | null)) setOpen(false)
  }} onKeyDown={event => {
    if (event.key !== 'Escape' || event.nativeEvent.isComposing || event.keyCode === 229) return
    event.preventDefault(); setOpen(false)
    if (previousFocus.current?.isConnected) previousFocus.current.focus()
    else (event.target as HTMLElement).blur()
  }}>
    <div className="master-quick-search-field">
      <svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="10.5" cy="10.5" r="6.5" /><path d="m16 16 4.5 4.5" /></svg>
      <input ref={input} role="combobox" aria-label="マスターをクイック検索" aria-autocomplete="list"
        aria-expanded={open} aria-controls={open ? listId : undefined}
        aria-activedescendant={open && !loading && !error && matches.length ? `${listId}-${activeIndex}` : undefined}
        placeholder="マスターを検索（名前・ID・ファイル名）" value={query}
        onFocus={event => {
          if (event.relatedTarget instanceof HTMLElement && !host.current?.contains(event.relatedTarget)) previousFocus.current = event.relatedTarget
          setOpen(true)
        }}
        onClick={() => setOpen(true)}
        onChange={event => { setQuery(event.target.value); setSelected(0); setLimit(50); setOpen(true) }}
        onKeyDown={event => {
          if (event.nativeEvent.isComposing || event.keyCode === 229) return
          if (event.ctrlKey || event.metaKey || event.altKey || event.shiftKey) return
          if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
            event.preventDefault(); setOpen(true)
            setSelected(index => Math.max(0, Math.min(matches.length - 1, index + (event.key === 'ArrowDown' ? 1 : -1))))
          }
          if (event.key === 'Enter' && open && !loading && !error && matches[activeIndex]) {
            event.preventDefault(); openFile(matches[activeIndex].path)
          }
        }} />
      <kbd aria-hidden="true">Ctrl P</kbd>
    </div>
    {open && <div className="master-quick-search-popup">
      <div className="master-quick-search-heading"><span>Filebase 全体</span><span role="status">{loading ? '検索を準備中…' : error ? '一覧の取得に失敗' : `${matches.length.toLocaleString()} 件`}</span></div>
      {error && <div className="master-quick-search-message" role="alert"><p>{error}</p><button type="button" className="button compact" onClick={() => setAttempt(value => value + 1)}>再試行</button></div>}
      <div id={listId} role="listbox" aria-label="クイック検索の結果" aria-busy={loading} className="master-quick-search-results">
        {!loading && !error && visible.map((file, index) => <div key={file.path} id={`${listId}-${index}`}
          role="option" aria-selected={index === activeIndex} className="master-quick-search-result"
          onMouseDown={event => event.preventDefault()} onClick={() => openFile(file.path)}>
          <span className="master-quick-search-name"><strong><MinecraftText value={file.name || file.path.split('/').at(-1) || file.path} /></strong>{file.id && <code>{file.id}</code>}</span>
          <small title={file.path}>{file.path}</small>
        </div>)}
      </div>
      {!loading && !error && !matches.length && <p className="master-quick-search-message">一致するファイルがありません。検索語を変えてください。</p>}
      {!loading && !error && visible.length < matches.length && <button type="button" className="master-quick-search-more" onClick={() => setLimit(visible.length + 50)}>さらに50件を表示（{visible.length} / {matches.length}）</button>}
      <footer>{!isAndroidEditor() && '↑ ↓ で選択 · Enter で開く · Esc で閉じる'}<span>空白区切りで複数語検索</span></footer>
    </div>}
  </div>
}

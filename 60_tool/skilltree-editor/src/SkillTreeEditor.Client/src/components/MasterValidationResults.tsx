import { useEffect, useId, useMemo, useRef, useState, type Ref } from 'react'
import { createMasterNameMatcher } from '../data/masterDataFilters'
import type { ValidationReport } from '../types/editor'

interface Props {
  report: ValidationReport | null
  invalidInputs: Record<string, string>
  headingRef: Ref<HTMLButtonElement>
  active: boolean
  onSelect: (path?: string) => void
}
type Severity = 'all' | 'error' | 'warning'
const pageSize = 50

export function MasterValidationResults({ report, invalidInputs, headingRef, active, onSelect }: Props) {
  const entries = useMemo(() => [
    ...Object.entries(invalidInputs).map(([path, message]) => ({ key: `input:${path}`, code: '入力エラー', message, path, file: undefined, severity: 'error' as const })),
    ...(report?.issues ?? []).map((issue, index) => ({ ...issue, key: `report:${index}` })),
  ], [report, invalidInputs])
  const counts = useMemo(() => ({ errors: entries.filter(entry => entry.severity === 'error').length, warnings: entries.filter(entry => entry.severity === 'warning').length }), [entries])
  const [view, setView] = useState({ entries, query: '', severity: 'all' as Severity, page: 0, expanded: true })
  // New validation must not be hidden by a previous report's filters or page.
  const current = view.entries === entries ? view : { entries, query: '', severity: 'all' as Severity, page: 0, expanded: true }
  if (current !== view) setView(current)
  const matches = useMemo(() => {
    const match = createMasterNameMatcher(current.query)
    return entries.filter(entry => (current.severity === 'all' || entry.severity === current.severity) && match(entry.code, entry.path, entry.message, entry.file))
  }, [entries, current.query, current.severity])
  const pages = Math.max(1, Math.ceil(matches.length / pageSize))
  const page = Math.min(current.page, pages - 1)
  const visible = matches.slice(page * pageSize, (page + 1) * pageSize)
  const contentId = useId()
  const search = useRef<HTMLInputElement>(null)
  const list = useRef<HTMLUListElement>(null)
  const buttons = useRef<(HTMLButtonElement | null)[]>([])
  const focusedResult = useRef<HTMLButtonElement | null>(null)
  const focusAfterPage = useRef(false)
  useEffect(() => {
    if (!active) { focusAfterPage.current = false; return }
    if (focusAfterPage.current) {
      if (list.current) list.current.scrollTop = 0
      ;(buttons.current[0] ?? search.current)?.focus()
      focusAfterPage.current = false
    } else if (focusedResult.current && !focusedResult.current.isConnected && document.activeElement === document.body) search.current?.focus()
  }, [page, entries, active])
  const changePage = (next: number) => { focusAfterPage.current = true; setView({ ...current, page: next }) }
  return <section className="master-validation" aria-label="検証結果">
    <h3 className="master-validation-heading"><button type="button" className="text-button" ref={headingRef} aria-expanded={current.expanded} aria-controls={contentId} onClick={() => setView({ ...current, expanded: !current.expanded })}>
      {current.expanded ? '▾' : '▸'} 検証結果 {counts.errors || !report?.isValid ? '要確認' : '✓ 成功'} · エラー {counts.errors} 件 · 警告 {counts.warnings} 件
    </button></h3>
    <div id={contentId} className="master-validation-content" hidden={!current.expanded}>
      {entries.length > 0 && <>
        <div className="master-validation-tools">
          <label>種別<select aria-label="検証結果の種別" value={current.severity} onChange={event => setView({ ...current, severity: event.target.value as Severity, page: 0 })}><option value="all">すべて ({entries.length})</option><option value="error">エラー ({counts.errors})</option><option value="warning">警告 ({counts.warnings})</option></select></label>
          <label className="master-validation-search">検証結果を検索<input ref={search} aria-label="検証結果を検索" placeholder="メッセージ・パス・コード（空白でAND検索）" value={current.query} onChange={event => setView({ ...current, query: event.target.value, page: 0 })} onKeyDown={event => {
            if (event.nativeEvent.isComposing || event.keyCode === 229) return
            if ((event.key === 'ArrowDown' || event.key === 'Enter') && visible.length) { event.preventDefault(); buttons.current[0]?.focus() }
          }} /></label>
          <button type="button" className="button compact" disabled={!current.query && current.severity === 'all'} onClick={() => { setView({ ...current, query: '', severity: 'all', page: 0 }); search.current?.focus() }}>条件を解除</button>
        </div>
        <div className="master-validation-pages"><span role="status">{matches.length} / {entries.length} 件 · {matches.length ? page * pageSize + 1 : 0}–{Math.min((page + 1) * pageSize, matches.length)} 件を表示</span>{pages > 1 && <><button type="button" className="button compact" disabled={page === 0} onClick={() => changePage(page - 1)}>前の検証結果</button><span>{page + 1} / {pages}</span><button type="button" className="button compact" disabled={page + 1 >= pages} onClick={() => changePage(page + 1)}>次の検証結果</button></>}</div>
      </>}
      <ul ref={list} aria-label="検証結果の一覧">{visible.map((entry, index) => <li className={entry.severity} key={entry.key}>
        <span>{entry.severity === 'error' ? 'エラー' : '警告'}</span> <code>{entry.code}</code> <button type="button" className="text-button" ref={element => { buttons.current[index] = element }} onFocus={event => { focusedResult.current = event.currentTarget }} onClick={() => onSelect(entry.path)} onKeyDown={event => {
          if (event.key === 'Escape' || event.key === 'ArrowUp' && index === 0) { event.preventDefault(); search.current?.focus(); return }
          const next = event.key === 'ArrowDown' ? Math.min(index + 1, visible.length - 1) : event.key === 'ArrowUp' ? index - 1 : event.key === 'Home' ? 0 : event.key === 'End' ? visible.length - 1 : -1
          if (next >= 0) { event.preventDefault(); buttons.current[next]?.focus() }
        }}>{entry.path || '/'}</button> <span>{entry.message}</span>{entry.file && <small>{entry.file}</small>}
      </li>)}</ul>
      {!entries.length ? <p className={report?.isValid ? 'success-message' : 'master-muted'}>{report?.isValid ? '検証エラーはありません。' : '検証結果の詳細はありません。'}</p> : !matches.length && <p className="master-muted">条件に一致する検証結果はありません。検索語や種別を変更してください。</p>}
    </div>
  </section>
}

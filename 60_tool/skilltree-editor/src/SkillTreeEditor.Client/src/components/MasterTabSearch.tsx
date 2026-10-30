import { useEffect, useMemo, useRef, useState } from 'react'
import { createMasterNameMatcher } from '../data/masterDataFilters'
import type { MasterFileTab } from '../state/masterDataTabs'
import { MinecraftText } from './MinecraftText'
import { Modal } from './Modal'

interface MasterTabSearchProps {
  tabs: MasterFileTab[]
  activeId: string
  onSelect: (id: string) => void
  onClose: () => void
}
const pageSize = 50

export function MasterTabSearch({ tabs, activeId, onSelect, onClose }: MasterTabSearchProps) {
  const [query, setQuery] = useState('')
  const [dirtyOnly, setDirtyOnly] = useState(false)
  const [page, setPage] = useState(0)
  const search = useRef<HTMLInputElement>(null)
  const results = useRef<HTMLDivElement>(null)
  const buttons = useRef<(HTMLButtonElement | null)[]>([])
  const focusAfterPage = useRef(false)
  const matches = useMemo(() => {
    const match = createMasterNameMatcher(query)
    return tabs.filter(tab => (!dirtyOnly || tab.dirty) && match(tab.name, tab.path))
  }, [tabs, query, dirtyOnly])
  const pageCount = Math.max(1, Math.ceil(matches.length / pageSize))
  const currentPage = Math.min(page, pageCount - 1)
  const visible = matches.slice(currentPage * pageSize, (currentPage + 1) * pageSize)
  useEffect(() => { search.current?.focus() }, [])
  useEffect(() => {
    if (!focusAfterPage.current) return
    if (results.current) results.current.scrollTop = 0
    ;(buttons.current[0] ?? search.current)?.focus()
    focusAfterPage.current = false
  }, [currentPage])
  useEffect(() => {
    // A background save can remove the focused result from the unsaved filter.
    if (document.activeElement === document.body) search.current?.focus()
  }, [matches, currentPage])
  const changePage = (next: number) => { focusAfterPage.current = true; setPage(next) }

  return <Modal className="master-tab-search-dialog" ariaLabel="開いているファイルを検索" onClose={onClose}>
    <header className="modal-header"><div><h2>開いているファイル</h2><p className="master-muted">編集を保持したままタブを切り替えます。</p></div><button type="button" className="button" onClick={onClose}>閉じる</button></header>
    <div className="modal-body">
    <label className="master-candidate-search">名前・ファイル名・パスで検索<input ref={search} aria-label="開いているファイルの検索語" placeholder="日本語名やパスを空白で区切って検索" value={query} onChange={event => { setQuery(event.target.value); setPage(0) }} onKeyDown={event => {
      if (event.nativeEvent.isComposing || event.keyCode === 229) return
      if (event.key === 'ArrowDown' && visible.length) { event.preventDefault(); buttons.current[0]?.focus() }
      if (event.key === 'Enter' && visible.length) { event.preventDefault(); onSelect(visible[0].id) }
    }} /><small>複数語すべてに一致するタブを表示します。Enterで先頭へ移動、↓で結果を選択できます。</small></label>
    <div className="master-tab-search-filters"><label><input type="checkbox" checked={dirtyOnly} onChange={event => { setDirtyOnly(event.target.checked); setPage(0) }} />未保存のみ</label><span role="status">{matches.length} / {tabs.length} 件 · 未保存 {tabs.filter(tab => tab.dirty).length} 件</span></div>
    <div ref={results} className="master-tab-search-results" aria-label="開いているファイルの検索結果">
      {visible.map((tab, index) => <button type="button" className="master-candidate" key={tab.id} ref={element => { buttons.current[index] = element }} aria-current={tab.id === activeId ? 'true' : undefined} onClick={() => onSelect(tab.id)} onKeyDown={event => {
        if (event.key === 'ArrowUp' && index === 0) { event.preventDefault(); search.current?.focus(); return }
        const next = event.key === 'ArrowDown' ? Math.min(index + 1, visible.length - 1) : event.key === 'ArrowUp' ? index - 1 : event.key === 'Home' ? 0 : event.key === 'End' ? visible.length - 1 : -1
        if (next >= 0) { event.preventDefault(); buttons.current[next]?.focus() }
      }}>
        <span className="master-tab-search-title"><MinecraftText value={tab.name} /><small>{tab.id === activeId && '表示中 · '}{tab.dirty ? '未保存' : '保存済み'}{tab.busy && ' · 処理中'}</small></span>
        <code>{tab.path}</code>
      </button>)}
      {!matches.length && <p className="master-muted">一致するタブはありません。検索語や「未保存のみ」を変更してください。</p>}
    </div>
    </div>
    <footer className="modal-footer master-candidate-pages"><button type="button" className="button" disabled={currentPage === 0} onClick={() => changePage(currentPage - 1)}>前のタブ一覧</button><span>{currentPage + 1} / {pageCount} ページ · 50件ずつ表示</span><button type="button" className="button" disabled={currentPage + 1 >= pageCount} onClick={() => changePage(currentPage + 1)}>次のタブ一覧</button></footer>
  </Modal>
}

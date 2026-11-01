import { useEffect, useMemo, useRef, useState, type KeyboardEvent } from 'react'
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
const isPlainNavigationKey = (event: KeyboardEvent) => !event.nativeEvent.isComposing && event.keyCode !== 229
  && !event.ctrlKey && !event.metaKey && !event.altKey && !event.shiftKey

export function MasterTabSearch({ tabs, activeId, onSelect, onClose }: MasterTabSearchProps) {
  const [query, setQuery] = useState('')
  const [dirtyOnly, setDirtyOnly] = useState(false)
  const [page, setPage] = useState(0)
  const search = useRef<HTMLInputElement>(null)
  const results = useRef<HTMLDivElement>(null)
  const buttons = useRef<(HTMLButtonElement | null)[]>([])
  const focusAfterPage = useRef<string | null>(null)
  const matches = useMemo(() => {
    const match = createMasterNameMatcher(query)
    return tabs.filter(tab => (!dirtyOnly || tab.dirty) && match(tab.name, tab.path))
  }, [tabs, query, dirtyOnly])
  const pageCount = Math.max(1, Math.ceil(matches.length / pageSize))
  const currentPage = Math.min(page, pageCount - 1)
  const visible = matches.slice(currentPage * pageSize, (currentPage + 1) * pageSize)
  useEffect(() => { search.current?.focus() }, [])
  useEffect(() => {
    const targetId = focusAfterPage.current
    focusAfterPage.current = null
    if (!targetId) return
    const index = matches.slice(currentPage * pageSize, (currentPage + 1) * pageSize).findIndex(tab => tab.id === targetId)
    if (results.current) results.current.scrollTop = 0
    ;((index >= 0 ? buttons.current[index] : null) ?? search.current)?.focus()
  }, [currentPage, matches])
  useEffect(() => { if (page !== currentPage) setPage(currentPage) }, [page, currentPage])
  useEffect(() => {
    // A background save can remove the focused result from the unsaved filter.
    if (document.activeElement === document.body) search.current?.focus()
  }, [matches, currentPage])
  const focusResult = (index: number) => {
    if (!matches.length) return
    const targetIndex = Math.max(0, Math.min(index, matches.length - 1))
    const targetPage = Math.floor(targetIndex / pageSize)
    if (targetPage === currentPage) {
      focusAfterPage.current = null
      buttons.current[targetIndex % pageSize]?.focus()
    } else focusAfterPage.current = matches[targetIndex].id
    setPage(targetPage)
  }
  const changePage = (next: number) => focusResult(next * pageSize)

  return <Modal className="master-tab-search-dialog" ariaLabel="開いているファイルを検索" onClose={onClose}>
    <header className="modal-header"><div><h2>開いているファイル</h2><p className="master-muted">編集を保持したままタブを切り替えます。</p></div><button type="button" className="button" onClick={onClose}>閉じる</button></header>
    <div className="modal-body">
    <label className="master-candidate-search">名前・ファイル名・パスで検索<input ref={search} aria-label="開いているファイルの検索語" placeholder="日本語名やパスを空白で区切って検索" value={query} onChange={event => { focusAfterPage.current = null; setQuery(event.target.value); setPage(0) }} onKeyDown={event => {
      if (!isPlainNavigationKey(event)) return
      if (event.key === 'ArrowDown' && visible.length) { event.preventDefault(); buttons.current[0]?.focus() }
      if (event.key === 'Enter' && visible.length) { event.preventDefault(); onSelect(visible[0].id) }
    }} /><small>複数語すべてに一致するタブを表示します。検索欄のEnterで現在のページの先頭のタブへ切り替え、↓で結果へフォーカスします。結果内は↑↓でページをまたいで移動、Home・Endで全結果の先頭・末尾へ移動できます。Enter・Spaceでタブを切り替えます。</small></label>
    <div className="master-tab-search-filters"><label><input type="checkbox" checked={dirtyOnly} onChange={event => { focusAfterPage.current = null; setDirtyOnly(event.target.checked); setPage(0) }} />未保存のみ</label><span role="status">{matches.length} / {tabs.length} 件 · 未保存 {tabs.filter(tab => tab.dirty).length} 件</span></div>
    <div ref={results} className="master-tab-search-results" aria-label="開いているファイルの検索結果">
      {visible.map((tab, index) => <button type="button" className="master-candidate" key={tab.id} ref={element => { buttons.current[index] = element }} aria-current={tab.id === activeId ? 'true' : undefined} onClick={() => onSelect(tab.id)} onKeyDown={event => {
        if (!isPlainNavigationKey(event)) return
        const resultIndex = currentPage * pageSize + index
        if (event.key === 'ArrowUp' && resultIndex === 0) { event.preventDefault(); search.current?.focus(); return }
        const next = event.key === 'ArrowDown' ? resultIndex + 1 : event.key === 'ArrowUp' ? resultIndex - 1 : event.key === 'Home' ? 0 : event.key === 'End' ? matches.length - 1 : -1
        if (next >= 0) { event.preventDefault(); focusResult(next) }
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

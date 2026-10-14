import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import type { MasterDataWorkspace } from '../state/masterDataWorkspace'
import { MasterCategoryTree } from './MasterCategoryTree'
import { MasterFilterDialog, masterFacetValueLabel } from './MasterFilterDialog'
import { MinecraftIcon } from './MinecraftIcon'
import { MinecraftText } from './MinecraftText'
import { Modal } from './Modal'
import { emptyMasterListFilters, filterMasterFiles, type MasterListFilters } from '../data/masterFileQuery'
import { facetLabels, inCategory, isDropSourceDirectory, isItemDefinitionDirectory, isSkillDirectory, numericFilterLabels } from '../data/masterDataFilters'
import { fileNameIncludesId } from '../data/masterDataPresentation'
import './masterFileBrowser.css'

interface MasterFileBrowserProps {
  workspace: MasterDataWorkspace
  selectedPath?: string
  busy: boolean
  active: boolean
  onOpen: (path: string) => void
  onNew: () => void
  onImport: () => void
  onDialogChange: (open: boolean) => void
}

export function MasterFileBrowser({ workspace: w, selectedPath, busy, active, onOpen, onNew, onImport, onDialogChange }: MasterFileBrowserProps) {
  const [filtersOpen, setFiltersOpen] = useState(false)
  const [pathsOpen, setPathsOpen] = useState(false)
  const fileList = useRef<HTMLDivElement>(null)
  const more = useRef<HTMLDivElement>(null)
  const batchRequested = useRef(false)
  const category = w.catalog?.categories.find((entry) => entry.id === w.categoryId)
  const directory = category?.directory ?? ''
  const scopeLabel = w.searchScope === 'all' ? 'Filebase 全体' : category?.label ?? 'フォルダを選択'
  const files = useMemo(() => w.searchScope === 'all' ? w.files : w.files.filter((file) => directory && inCategory(file.path, directory)), [w.files, directory, w.searchScope])
  const filters = useMemo<MasterListFilters>(() => ({ format: w.format, sourceQuery: w.sourceQuery, dropSourcePath: w.dropSourcePath, skillTag: w.skillTag, dropItemQuery: w.dropItemQuery, dropItemId: w.dropItemId, dropPresence: w.dropPresence, facetFilters: w.facetFilters, fileStatus: w.fileStatus, numericFilters: w.numericFilters }), [w.format, w.sourceQuery, w.dropSourcePath, w.skillTag, w.dropItemQuery, w.dropItemId, w.dropPresence, w.facetFilters, w.fileStatus, w.numericFilters])
  const visibleFiles = useMemo(() => filterMasterFiles(files, w.filterIndex, filters, w.query), [files, w.filterIndex, filters, w.query])
  const indexedFiles = useMemo(() => new Map(w.filterIndex?.entries?.map((entry) => [entry.path, entry]) ?? []), [w.filterIndex])
  const apply = (next: MasterListFilters) => {
    w.setFormat(next.format); w.setSourceQuery(next.sourceQuery); w.setDropSourcePath(next.dropSourcePath); w.setSkillTag(next.skillTag)
    w.setDropItemQuery(next.dropItemQuery); w.setDropItemId(next.dropItemId); w.setDropPresence(next.dropPresence)
    w.setFacetFilters(next.facetFilters); w.setFileStatus(next.fileStatus)
    w.setNumericFilters(Object.fromEntries(Object.entries(next.numericFilters).filter(([, range]) => range.min.trim() || range.max.trim())))
  }
  const changeCategory = (id: string) => {
    const nextDirectory = w.catalog?.categories.find((entry) => entry.id === id)?.directory ?? ''
    w.setCategoryId(id); w.setSearchScope('folder'); w.setBrowserView('files')
    if (!isItemDefinitionDirectory(nextDirectory)) { w.setSourceQuery(''); w.setDropSourcePath('') }
    if (!isSkillDirectory(nextDirectory)) w.setSkillTag('')
    if (w.format && !w.files.some((entry) => entry.format === w.format && inCategory(entry.path, nextDirectory))) w.setFormat('')
  }
  const requestMore = useCallback(() => {
    if (batchRequested.current) return
    batchRequested.current = true
    w.setVisibleCount((count) => Math.min(count + 50, visibleFiles.length))
  }, [w.setVisibleCount, visibleFiles.length])
  useEffect(() => {
    if (fileList.current) fileList.current.scrollTop = 0
    batchRequested.current = false
  }, [w.categoryId, w.searchScope, w.query, filters])
  useEffect(() => { batchRequested.current = false }, [w.visibleCount])
  useEffect(() => {
    if (!active || !fileList.current || !more.current || w.visibleCount >= visibleFiles.length || typeof IntersectionObserver === 'undefined') return
    const observer = new IntersectionObserver((entries) => { if (entries.some((entry) => entry.isIntersecting)) requestMore() }, { root: fileList.current, rootMargin: '80px' })
    observer.observe(more.current)
    return () => observer.disconnect()
  }, [active, w.sidebarCollapsed, w.browserView, w.visibleCount, visibleFiles.length, requestMore])
  useEffect(() => { onDialogChange(filtersOpen || pathsOpen) }, [filtersOpen, pathsOpen, onDialogChange])
  const chips: { key: string; label: string; clear: () => void }[] = []
  if (filters.format) chips.push({ key: 'format', label: `形式: ${filters.format.toUpperCase()}`, clear: () => w.setFormat('') })
  if (filters.fileStatus) chips.push({ key: 'fileStatus', label: `状態: ${{ editable: '編集可能', readOnly: '閲覧のみ', error: '解析エラーあり' }[filters.fileStatus] ?? filters.fileStatus}`, clear: () => w.setFileStatus('') })
  if (filters.dropSourcePath) chips.push({ key: 'source', label: `ドロップ元: ${w.filterIndex?.sources.find((source) => source.path === filters.dropSourcePath)?.name ?? filters.dropSourcePath}`, clear: () => { w.setDropSourcePath(''); w.setSourceQuery('') } })
  if (filters.skillTag) chips.push({ key: 'skillTag', label: `スキルタグ: ${w.filterIndex?.tags.find((tag) => tag.id === filters.skillTag)?.name ?? filters.skillTag}`, clear: () => w.setSkillTag('') })
  if (filters.dropItemId) chips.push({ key: 'dropItem', label: `ドロップ品: ${w.files.find((file) => file.id === filters.dropItemId && inCategory(file.path, '10.features.item'))?.name ?? filters.dropItemId}`, clear: () => { w.setDropItemId(''); w.setDropItemQuery('') } })
  if (filters.dropPresence) chips.push({ key: 'dropPresence', label: `ドロップ品: ${filters.dropPresence === 'has' ? 'あり' : 'なし'}`, clear: () => w.setDropPresence('') })
  for (const [key, value] of Object.entries(filters.facetFilters)) chips.push({ key: `facet:${key}`, label: `${facetLabels[key] ?? key}: ${masterFacetValueLabel(key, value, w.filterIndex, w.candidates)}`, clear: () => w.setFacetFilters((current) => { const next = { ...current }; delete next[key]; return next }) })
  for (const [key, range] of Object.entries(filters.numericFilters)) if (range.min.trim() || range.max.trim()) chips.push({ key: `number:${key}`, label: `${numericFilterLabels[key] ?? key}: ${range.min || '下限なし'}〜${range.max || '上限なし'}`, clear: () => w.setNumericFilters((current) => { const next = { ...current }; delete next[key]; return next }) })
  return <><aside className="master-sidebar master-browser" hidden={w.sidebarCollapsed} data-browser-view={w.browserView} aria-label="マスターファイルを探す">
    <header className="master-browser-heading"><h2>マスターファイル</h2><div><button type="button" className="button compact subtle" disabled={busy} onClick={onNew}>＋ 新規</button><button type="button" className="button compact subtle" disabled={busy} onClick={onImport}>原稿を読込</button><button type="button" className="button compact subtle" disabled={w.refreshing} aria-label="一覧と定義を再読込" title="一覧と定義メタデータを再読込" onClick={() => void w.refresh().catch((reason) => w.setError(reason instanceof Error ? reason.message : String(reason)))}>↻</button><button type="button" className="button compact subtle" aria-label="ナビを閉じる" onClick={() => w.setSidebarCollapsed(true)}>×</button></div></header>
    <div className="master-browser-switch" role="group" aria-label="ファイル一覧の表示"><button type="button" className="button" aria-pressed={w.browserView === 'folders'} onClick={() => w.setBrowserView('folders')}>フォルダ</button><button type="button" className="button" aria-pressed={w.browserView === 'files'} onClick={() => w.setBrowserView('files')}>ファイル・検索結果</button></div>
    <div className="master-browser-columns">
      <section className="master-folder-pane" aria-label="フォルダ一覧"><MasterCategoryTree categories={w.catalog?.categories ?? []} files={w.files} selectedId={w.categoryId} onSelect={changeCategory} expanded={w.expandedCategories} onExpandedChange={w.setExpandedCategories} /><footer><span>← → で開閉・Enterで選択</span><button type="button" className="text-button" onClick={() => setPathsOpen(true)}>読込先・バックアップ先</button></footer></section>
      <section className="master-results-pane" aria-label="ファイル検索と結果">
        <header className="master-result-search"><label>ファイルを検索<div className="master-search-input"><span aria-hidden="true">⌕</span><input aria-label="マスター検索" value={w.query} onChange={(event) => w.setQuery(event.target.value)} placeholder="名前・ID・ファイル名" />{w.query && <button type="button" aria-label="ファイル検索をクリア" onClick={() => w.setQuery('')}>×</button>}</div></label><div className="master-search-scope"><label>検索範囲<select aria-label="検索範囲" value={w.searchScope} onChange={(event) => w.setSearchScope(event.target.value as 'folder' | 'all')}><option value="folder">選択フォルダ以下</option><option value="all">Filebase 全体</option></select></label><button type="button" className={'button master-open-filters' + (chips.length ? ' active' : '')} aria-haspopup="dialog" onClick={() => setFiltersOpen(true)}>詳細フィルター{chips.length > 0 && <b>{chips.length}</b>}</button></div></header>
        <div className="master-results-heading"><div><span className="master-result-eyebrow">{w.query.trim() || chips.length ? '検索結果' : 'フォルダのファイル'}</span><h3 title={w.searchScope === 'all' ? 'Filebase 全体' : `${scopeLabel}\n${directory}`}>{scopeLabel}</h3></div><strong aria-label="検索結果の件数">{visibleFiles.length}<small> / {files.length} 件</small></strong></div>
        {chips.length > 0 && <div className="master-applied-filters"><div aria-label="適用中のフィルター">{chips.map((chip) => <button type="button" key={chip.key} className="master-filter-chip" title={chip.label} aria-label={`${chip.label} を解除`} onClick={chip.clear}><span>{chip.label}</span><b aria-hidden="true">×</b></button>)}</div><button type="button" className="text-button" onClick={() => apply(emptyMasterListFilters())}>すべて解除</button></div>}
        <div ref={fileList} className="master-file-list" aria-label="マスターの検索結果">{visibleFiles.slice(0, w.visibleCount).map((entry) => {
          const name = entry.path.split('/').at(-1) ?? entry.path
          const indexed = indexedFiles.get(entry.path)
          const level = indexed?.numbers?.mobLevel, requirement = indexed?.numbers?.requiredLevel
          return <button key={entry.path} type="button" disabled={busy} className={`master-file ${selectedPath === entry.path ? 'selected' : ''}`} aria-current={selectedPath === entry.path ? 'true' : undefined} title={entry.path + (entry.id ? `\nID: ${entry.id}` : '')} onClick={() => onOpen(entry.path)}>
            <span className="master-file-identity">{entry.icon && <MinecraftIcon icon={entry.icon} className="master-list-icon" />}<strong><MinecraftText value={entry.name || name} /></strong></span><span className="master-result-badges">{level?.length ? <small>Lv {level.join(' / ')}</small> : null}{requirement?.length ? <small>必要Lv {requirement.join(' / ')}</small> : null}{indexed?.facets.unTradeable?.includes('true') && <small className="restricted">トレード禁止</small>}{entry.parseError && <b className="master-required">解析エラー</b>}{entry.readOnly && <small>閲覧のみ</small>}</span>
            {entry.id && !fileNameIncludesId(entry.path, entry.id) && <code>ID: {entry.id}</code>}<small className="master-result-path">{name}</small>{w.searchScope === 'all' && <small className="master-result-location">{entry.path.slice(0, -(name.length + 1)) || 'Filebase 直下'}</small>}
          </button>
        })}{!visibleFiles.length && <div className="master-no-results"><strong>一致するファイルがありません</strong><p>検索文字やフィルターの範囲を変えてください。</p><button type="button" className="button" onClick={() => { w.setQuery(''); apply(emptyMasterListFilters()) }}>検索・条件を解除</button></div>}{w.visibleCount < visibleFiles.length && <div ref={more} className="master-load-more"><button type="button" className="button" onClick={requestMore}>さらに表示 ({Math.min(w.visibleCount, visibleFiles.length)} / {visibleFiles.length})</button><small>下までスクロールすると続きを表示します</small></div>}</div>
        <footer className="master-results-footer">保存済みファイルを検索 · 名前を選択して編集</footer>
      </section>
    </div>
  </aside>
    {active && filtersOpen && <MasterFilterDialog value={filters} files={files} allFiles={w.files} index={w.filterIndex} candidates={w.candidates} query={w.query} scopeLabel={scopeLabel} itemScope={w.searchScope === 'all' || isItemDefinitionDirectory(directory)} dropScope={w.searchScope === 'all' || isDropSourceDirectory(directory)} skillScope={w.searchScope === 'all' || isSkillDirectory(directory)} onCancel={() => setFiltersOpen(false)} onApply={(next) => { apply(next); setFiltersOpen(false) }} />}
    {active && pathsOpen && <div className="master-paths-dialog"><MasterPaths root={w.catalog?.root ?? ''} backups={w.catalog?.backups ?? ''} onClose={() => setPathsOpen(false)} /></div>}
  </>
}

function MasterPaths({ root, backups, onClose }: { root: string; backups: string; onClose: () => void }) {
  return <Modal ariaLabel="読込先とバックアップ先" className="master-paths-modal" onClose={onClose}><header className="modal-header"><h2>読込先とバックアップ先</h2><button className="button" onClick={onClose}>閉じる</button></header><dl className="modal-body"><dt>Filebase の読込先</dt><dd><code>{root}</code></dd><dt>保存前のバックアップ先</dt><dd><code>{backups}</code></dd></dl></Modal>
}

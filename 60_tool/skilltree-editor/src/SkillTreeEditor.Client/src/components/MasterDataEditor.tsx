import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { ApiError } from '../api/editorApi'
import { masterDataApi } from '../api/masterDataApi'
import { useHistory } from '../state/history'
import type { JsonObject, JsonValue, ValidationIssue, ValidationReport } from '../types/editor'
import type { MasterCatalog, MasterCategory, MasterDocument, MasterDraft, MasterFileSummary, MasterReference } from '../types/masterData'
import { MasterDataDiff } from './MasterDataDiff'
import { masterDefault } from './MasterDataForm'
import { MasterDataDetails, MasterDataOutline } from './MasterDataNavigation'
import { closestContainerPointer, parentPointer, valueAtPointer } from '../data/masterDataNavigation'
import { MinecraftIcon } from './MinecraftIcon'
import { MinecraftText } from './MinecraftText'
import { stripMinecraftFormatting } from '../utils/minecraft'

interface DraftState { raw: string; content: JsonValue; source: 'raw' | 'form' }
const emptyDraft: DraftState = { raw: '', content: null, source: 'raw' }
const baseName = (path: string) => path.split('/').at(-1) ?? path
const slugOf = (text: string) => text.normalize('NFKC').toLowerCase().replace(/[^a-z0-9_-]+/g, '-').replace(/^-+|-+$/g, '')
const asObject = (value: JsonValue | undefined): JsonObject | undefined => value !== null && typeof value === 'object' && !Array.isArray(value) ? value : undefined

interface MasterDataEditorProps { active?: boolean; onDirtyChange?: (dirty: boolean) => void; onOpenSkillTree?: () => void }

export function MasterDataEditor({ active = true, onDirtyChange, onOpenSkillTree }: MasterDataEditorProps) {
  const [catalog, setCatalog] = useState<MasterCatalog | null>(null)
  const [files, setFiles] = useState<MasterFileSummary[]>([])
  const [categoryId, setCategoryId] = useState('')
  const [query, setQuery] = useState('')
  const [format, setFormat] = useState('')
  const [subdirectory, setSubdirectory] = useState('')
  const [page, setPage] = useState(0)
  const [document, setDocument] = useState<MasterDocument | null>(null)
  const [references, setReferences] = useState<MasterReference[]>([])
  const [candidates, setCandidates] = useState<MasterReference[]>([])
  const [loading, setLoading] = useState(true)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [notice, setNotice] = useState('')
  const [report, setReport] = useState<ValidationReport | null>(null)
  const [renderWarnings, setRenderWarnings] = useState<string[]>([])
  const [invalidInputs, setInvalidInputs] = useState<Record<string, string>>({})
  const [diffRaw, setDiffRaw] = useState<string | null>(null)
  const [createMode, setCreateMode] = useState<'new' | 'copy' | 'import' | null>(null)
  const [importText, setImportText] = useState('')
  const [showReferences, setShowReferences] = useState(false)
  const [referenceQuery, setReferenceQuery] = useState('')
  const [referencePage, setReferencePage] = useState(0)
  const [documentation, setDocumentation] = useState<{ path: string; raw: string } | null>(null)
  const [sidebarView, setSidebarView] = useState<'files' | 'outline'>('files')
  const [sidebarCollapsed, setSidebarCollapsed] = useState(false)
  const [formPath, setFormPath] = useState('')
  const [focusPath, setFocusPath] = useState<{ path: string } | null>(null)
  const rememberedPaths = useRef<Record<string, string>>({})
  const editorElement = useRef<HTMLElement>(null)
  const importInput = useRef<HTMLInputElement>(null)
  const operationId = useRef(0)
  const latestRenderWarnings = useRef<string[]>([])
  const reviewedRaw = useRef('')
  const history = useHistory(emptyDraft)
  const draft = history.present
  const selectedFormPath = closestContainerPointer(draft.content, formPath)
  const category = catalog?.categories.find((entry) => entry.id === categoryId) ?? null
  const documentCategory = catalog?.categories.filter((entry) => document?.path.startsWith(`${entry.directory}/`)).sort((a, b) => b.directory.length - a.directory.length)[0] ?? null
  const savedIcon = asObject(document?.content)?.icon
  const displayedIcon = draft.source === 'form' ? asObject(draft.content)?.icon : savedIcon
  const displayedName = asObject(draft.source === 'form' ? draft.content : document?.content)?.name
  const readOnly = files.find((entry) => entry.path === document?.path)?.readOnly === true
  const nodeDocument = Boolean(document?.path.includes('/nodes/'))
  const schema = useMemo(() => {
    const schemas = documentCategory?.jsonSchemas ?? []
    const reference = asObject(draft.content)?.$schema
    return schemas.find((entry) => typeof reference === 'string' && baseName(entry.path) === baseName(reference))?.schema
      ?? (schemas.length === 1 ? schemas[0].schema : {})
  }, [documentCategory, draft.content])
  const dirty = Boolean(document) && (draft.source === 'form'
    ? JSON.stringify(draft.content) !== JSON.stringify(document?.content)
    : draft.raw !== document?.raw) || Object.keys(invalidInputs).length > 0
  const visibleFiles = useMemo(() => {
    const search = query.toLocaleLowerCase()
    return files.filter((entry) => (!categoryId || entry.category === categoryId) && (!format || entry.format === format)
      && (!subdirectory || entry.path.startsWith(`${subdirectory}/`))
      && (!search || [entry.path, entry.id, entry.name, stripMinecraftFormatting(entry.name ?? '')].some((value) => value?.toLocaleLowerCase().includes(search))))
  }, [files, categoryId, format, query, subdirectory])
  const subdirectories = useMemo(() => [...new Set(files.filter((entry) => !categoryId || entry.category === categoryId).filter((entry) => entry.path.includes('/')).map((entry) => entry.path.slice(0, entry.path.lastIndexOf('/'))))].sort(), [files, categoryId])
  const filteredReferences = useMemo(() => references.filter((entry) => `${entry.path} ${entry.pointer} ${entry.value} ${stripMinecraftFormatting(entry.label ?? '')}`.toLocaleLowerCase().includes(referenceQuery.toLocaleLowerCase())), [references, referenceQuery])
  const referencePages = Math.max(1, Math.ceil(filteredReferences.length / 50))
  const currentReferencePage = Math.min(referencePage, referencePages - 1)
  useEffect(() => { setPage(0) }, [categoryId, format, query, subdirectory])
  const pageCount = Math.max(1, Math.ceil(visibleFiles.length / 50))
  const currentPage = Math.min(page, pageCount - 1)
  const handleInvalid = useCallback((path: string, message: string | null) => setInvalidInputs((current) => {
    if (current[path] === message || !message && !Object.hasOwn(current, path)) return current
    const next = { ...current }
    if (message) next[path] = message
    else delete next[path]
    return next
  }), [])
  const showError = useCallback((reason: unknown) => {
    const payload = reason instanceof ApiError && reason.payload && typeof reason.payload === 'object'
      ? reason.payload as { issues?: ValidationIssue[] } : undefined
    if (payload?.issues) setReport({ isValid: false, issues: payload.issues })
    setError(reason instanceof Error ? reason.message : String(reason))
  }, [])
  const refresh = useCallback(async () => {
    const [nextCatalog, nextFiles] = await Promise.all([masterDataApi.catalog(), masterDataApi.files()])
    setCatalog(nextCatalog); setFiles(nextFiles)
    void masterDataApi.candidates().then(setCandidates).catch(() => { /* Free input works while optional candidates are unavailable. */ })
  }, [])
  useEffect(() => {
    void refresh().catch(showError).finally(() => setLoading(false))
  }, [refresh, showError])
  useEffect(() => { onDirtyChange?.(dirty) }, [dirty, onDirtyChange])
  useEffect(() => {
    const unload = (event: BeforeUnloadEvent) => { if (dirty) { event.preventDefault(); event.returnValue = '' } }
    window.addEventListener('beforeunload', unload)
    return () => window.removeEventListener('beforeunload', unload)
  }, [dirty])
  const allowDiscard = () => !dirty || window.confirm('未保存の編集内容を破棄して切り替えますか？')
  const loadDocument = async (path: string) => {
    if (busy || !allowDiscard()) return
    const requestId = ++operationId.current
    setBusy(true); setError(''); setNotice('')
    try {
      const loaded = await masterDataApi.file(path)
      if (requestId !== operationId.current) return
      setDocument(loaded); setInvalidInputs({}); history.reset({ raw: loaded.raw, content: loaded.content, source: loaded.content === null ? 'raw' : 'form' })
      setFormPath(closestContainerPointer(loaded.content, rememberedPaths.current[path] ?? '')); setFocusPath(null)
      setSidebarView(loaded.content === null ? 'files' : 'outline')
      if (window.innerWidth <= 640) setSidebarCollapsed(true)
      setReport({ isValid: !loaded.issues.some((entry) => entry.severity === 'error'), issues: loaded.issues }); setRenderWarnings([])
      latestRenderWarnings.current = []; reviewedRaw.current = ''
      setReferences([])
      void masterDataApi.references(path).then((next) => { if (requestId === operationId.current) setReferences(next) }).catch(() => {
        if (requestId === operationId.current) setNotice('参照元を取得できませんでした。編集は継続できます。')
      })
    } catch (reason) { showError(reason) } finally { if (requestId === operationId.current) setBusy(false) }
  }
  const currentRaw = async () => {
    if (!document) return ''
    if (draft.source === 'raw') return draft.raw
    const rendered = await masterDataApi.render(document.path, draft.content, draft.raw)
    const warnings = [...(rendered.warnings ?? [])]
    if (!rendered.commentsPreserved && !warnings.length) warnings.push('構造の変更により、すべてのコメントを保持できない可能性があります。保存前に差分を確認してください。')
    setRenderWarnings(warnings)
    latestRenderWarnings.current = warnings
    return rendered.raw
  }
  const changeMode = async (source: DraftState['source']) => {
    if (!document || source === draft.source || busy || Object.keys(invalidInputs).length) return
    setBusy(true); setError('')
    try {
      if (source === 'raw') history.replace({ ...draft, source, raw: await currentRaw() })
      else {
        const parsed = await masterDataApi.parse(document.path, draft.raw)
        setReport({ isValid: !parsed.issues.some((entry) => entry.severity === 'error'), issues: parsed.issues })
        if (parsed.issues.some((entry) => entry.severity === 'error')) { setError('原稿を解析できません。エラーを修正してからフォームへ切り替えてください。'); return }
        history.replace({ ...draft, content: parsed.content, source })
      }
    } catch (reason) { showError(reason) } finally { setBusy(false) }
  }
  const validate = async () => {
    if (!document || busy || Object.keys(invalidInputs).length) return
    setBusy(true); setError('')
    try { const validation = await masterDataApi.validate(document.path, await currentRaw()); setReport(validation); setNotice(validation.isValid ? '検証に成功しました。' : '検証で問題が見つかりました。') }
    catch (reason) { showError(reason) } finally { setBusy(false) }
  }
  const save = async () => {
    if (!document || readOnly || busy || Object.keys(invalidInputs).length) return
    setBusy(true); setError(''); setNotice('')
    try {
      const raw = await currentRaw()
      if (latestRenderWarnings.current.length && reviewedRaw.current !== raw) {
        setDiffRaw(raw); setNotice('原稿の変換に警告があります。差分を確認して閉じた後、もう一度「保存」を押してください。'); return
      }
      const validation = await masterDataApi.validate(document.path, raw)
      setReport(validation)
      if (!validation.isValid) { setError('検証エラーがあるため保存しませんでした。'); return }
      const saved = await masterDataApi.save(document.path, raw, document.revision)
      setDocument(saved); history.reset({ raw: saved.raw, content: saved.content, source: draft.source }); setRenderWarnings([])
      setNotice(`${baseName(saved.path)} を保存しました。`)
      void refresh().catch(() => setNotice(`${baseName(saved.path)} は保存済みです。一覧を再読込できませんでした。`))
    } catch (reason) { showError(reason) } finally { setBusy(false) }
  }
  const moveHistory = (direction: 'undo' | 'redo') => {
    if (Object.keys(invalidInputs).length) { setNotice('編集中の数値を修正してから履歴を移動してください。'); return }
    if (direction === 'undo' ? !history.canUndo : !history.canRedo) return
    // Array positions can refer to another element after a structural undo.
    let parent = selectedFormPath
    while (parent) {
      parent = parentPointer(parent)
      if (Array.isArray(valueAtPointer(draft.content, parent))) {
        setFormPath(parent)
        if (document) rememberedPaths.current[document.path] = parent
        break
      }
    }
    setFocusPath(null)
    if (direction === 'undo') history.undo(); else history.redo()
  }
  useEffect(() => {
    if (!active) return
    const keyboard = (event: KeyboardEvent) => {
      if (!(event.ctrlKey || event.metaKey) || createMode || diffRaw !== null || busy) return
      if (event.key.toLowerCase() === 's') { event.preventDefault(); void save() }
      if ((event.target as HTMLElement | null)?.closest('input, textarea, select, [contenteditable]')) return
      if (event.key.toLowerCase() === 'z') { event.preventDefault(); moveHistory(event.shiftKey ? 'redo' : 'undo') }
      if (event.key.toLowerCase() === 'y') { event.preventDefault(); moveHistory('redo') }
    }
    window.addEventListener('keydown', keyboard)
    return () => window.removeEventListener('keydown', keyboard)
  })
  const showDiff = async () => {
    if (!document || busy || Object.keys(invalidInputs).length) return
    setBusy(true)
    try { setDiffRaw(await currentRaw()) } catch (reason) { showError(reason) } finally { setBusy(false) }
  }
  const deleteDocument = async () => {
    if (!document || readOnly || busy || !window.confirm(`${document.path}\nこのファイルを削除しますか？${dirty ? '\n未保存の編集内容も破棄されます。' : ''}`)) return
    setBusy(true); setError('')
    try {
      await masterDataApi.delete(document.path, document.revision)
      operationId.current++
      setNotice(`${baseName(document.path)} を削除しました。`); setDocument(null); history.reset(emptyDraft); setReport(null); setInvalidInputs({}); void refresh().catch(showError)
    } catch (reason) { showError(reason) } finally { setBusy(false) }
  }
  const exportDraft = async () => {
    if (!document || busy || Object.keys(invalidInputs).length) return
    setBusy(true)
    try {
      const url = URL.createObjectURL(new Blob([await currentRaw()], { type: document.format === 'json' ? 'application/json;charset=utf-8' : 'text/yaml;charset=utf-8' }))
      const anchor = window.document.createElement('a'); anchor.href = url; anchor.download = baseName(document.path); anchor.click(); URL.revokeObjectURL(url)
    } catch (reason) { showError(reason) } finally { setBusy(false) }
  }
  const create = async (newDraft: MasterDraft) => {
    setBusy(true); setError('')
    try {
      const created = await masterDataApi.create(newDraft)
      const requestId = ++operationId.current
      setDocument(created); history.reset({ raw: created.raw, content: created.content, source: created.content === null ? 'raw' : 'form' }); setInvalidInputs({}); setReferences([]); setReport({ isValid: !created.issues.some((issue) => issue.severity === 'error'), issues: created.issues }); setRenderWarnings([]); setCreateMode(null)
      setFormPath(''); setFocusPath(null); setSidebarView(created.content === null ? 'files' : 'outline')
      setNotice(`${created.path} を作成しました。`); void refresh().catch(() => setNotice(`${created.path} は作成済みです。一覧を再読込できませんでした。`))
      void masterDataApi.references(created.path).then((next) => { if (requestId === operationId.current) setReferences(next) }).catch(() => { /* Free text stays available. */ })
    } catch (reason) { showError(reason); throw reason } finally { setBusy(false) }
  }
  const replaceRaw = async (file: File) => {
    try {
      const raw = await file.text()
      if (document && !readOnly && window.confirm(`読み込んだ原稿で ${baseName(document.path)} の編集内容を置き換えますか？（保存はまだ行いません）`)) {
        history.record({ raw, content: null, source: 'raw' }); setInvalidInputs({}); setReport(null); setRenderWarnings([]); setNotice('原稿を読み込みました。検証してから保存してください。')
      } else if (allowDiscard()) { setImportText(raw); setCreateMode('import') }
    } catch (reason) { showError(reason) }
  }
  const selectFormPath = (path: string) => {
    if (busy || Object.keys(invalidInputs).length) return
    const next = closestContainerPointer(draft.content, path)
    setFormPath(next); setFocusPath(null)
    if (document) rememberedPaths.current[document.path] = next
  }
  const focusIssue = (path?: string) => {
    if (busy) return
    if (path && (!path.startsWith('/') || /~(?![01])/.test(path))) { setNotice('この検証結果には移動できる項目パスがありません。原稿を確認してください。'); return }
    const next = { path: path ?? '' }
    if (draft.source === 'raw') void changeMode('form').then(() => setFocusPath(next))
    else setFocusPath(next)
  }
  useEffect(() => {
    if (!focusPath || draft.source !== 'form' || busy) return
    const targetValue = valueAtPointer(draft.content, focusPath.path)
    const container = closestContainerPointer(draft.content, targetValue !== null && typeof targetValue === 'object' ? focusPath.path : parentPointer(focusPath.path))
    if (container !== selectedFormPath) {
      if (Object.keys(invalidInputs).length) { setNotice('編集中の数値を修正してから別の項目へ移動してください。'); setFocusPath(null); return }
      setFormPath(container); setSidebarView('outline')
      if (document) rememberedPaths.current[document.path] = container
      return
    }
    const target = Array.from(editorElement.current?.querySelectorAll<HTMLInputElement>('.master-node-editor input, .master-node-editor textarea, .master-node-editor select') ?? []).find((entry) => entry.getAttribute('aria-label') === (focusPath.path || '/'))
    if (target) {
      for (let parent = target.parentElement; parent; parent = parent.parentElement) if (parent instanceof HTMLDetailsElement) parent.open = true
      target.focus(); target.scrollIntoView?.({ block: 'center' })
    } else editorElement.current?.querySelector<HTMLElement>('[data-node-heading]')?.focus()
    setFocusPath(null)
  }, [focusPath, selectedFormPath, draft.content, draft.source, busy, invalidInputs, document])
  if (loading) return <div className="loading-screen"><p>Filebaseのカテゴリとファイルを読み込んでいます…</p></div>
  return <main ref={editorElement} className={'master-editor ' + (sidebarCollapsed ? 'master-sidebar-collapsed' : '')}>
    <aside className="master-sidebar" hidden={sidebarCollapsed}>
      <div className="master-sidebar-header"><h2>Filebase マスター</h2><button type="button" className="button compact subtle" aria-label="ナビを閉じる" onClick={() => setSidebarCollapsed(true)}>×</button></div>
      <div className="master-sidebar-tabs"><button type="button" className={'button compact ' + (sidebarView === 'files' ? 'active' : '')} aria-pressed={sidebarView === 'files'} onClick={() => setSidebarView('files')}>ファイル一覧</button><button type="button" className={'button compact ' + (sidebarView === 'outline' ? 'active' : '')} aria-pressed={sidebarView === 'outline'} disabled={!document || draft.source !== 'form'} onClick={() => setSidebarView('outline')}>レコード内の構造</button></div>
      <div className="master-toolbar"><button className="button" disabled={busy} onClick={() => { if (allowDiscard()) setCreateMode('new') }}>＋ 新規</button><button className="button subtle" disabled={busy} onClick={() => importInput.current?.click()}>原稿を読込</button><button className="button subtle" disabled={busy} onClick={() => { void refresh().catch(showError) }} title="一覧と定義メタデータを再読込">↻</button></div>
      <input hidden ref={importInput} type="file" accept=".yml,.yaml,.json,text/yaml,application/json" onChange={(event) => { const file = event.target.files?.[0]; if (file) void replaceRaw(file); event.target.value = '' }} />
      <div className="master-sidebar-files" hidden={sidebarView !== 'files' && draft.source === 'form' && Boolean(document)}>
      <label>カテゴリ<select aria-label="マスターのカテゴリ" value={categoryId} onChange={(event) => { setCategoryId(event.target.value); setSubdirectory('') }}><option value="">すべて ({files.length})</option>{catalog?.categories.map((entry) => <option key={entry.id} value={entry.id}>{entry.label} ({entry.fileCount})</option>)}</select></label>
      {subdirectories.length > 1 && <label>サブフォルダ<select aria-label="サブフォルダ" value={subdirectory} onChange={(event) => setSubdirectory(event.target.value)}><option value="">すべてのフォルダ</option>{subdirectories.map((entry) => <option value={entry} key={entry}>{entry}</option>)}</select></label>}
      <label>検索<input aria-label="マスター検索" value={query} onChange={(event) => setQuery(event.target.value)} placeholder="名前 / ID / ファイルパス" /></label>
      <label>形式<select aria-label="ファイル形式" value={format} onChange={(event) => setFormat(event.target.value)}><option value="">すべて</option>{[...new Set(files.map((entry) => entry.format))].map((entry) => <option key={entry} value={entry}>{entry.toUpperCase()}</option>)}</select></label>
      <div className="master-list-heading">{visibleFiles.length} 件 / {files.length} 件</div>
      <div className="master-file-list">{visibleFiles.slice(currentPage * 50, (currentPage + 1) * 50).map((entry) => <button key={entry.path} type="button" disabled={busy} className={`master-file ${document?.path === entry.path ? 'selected' : ''}`} onClick={() => void loadDocument(entry.path)}><span className="master-file-identity">{entry.icon && <MinecraftIcon icon={entry.icon} className="master-list-icon" />}<strong><MinecraftText value={entry.name || baseName(entry.path)} /></strong></span><span>{entry.id && <code>{entry.id}</code>}{entry.parseError && <b className="master-required">解析エラー</b>}{entry.readOnly && <small>閲覧のみ</small>}</span><small>{entry.path}</small></button>)}{!visibleFiles.length && <p className="master-muted">一致するファイルがありません。</p>}</div>
      <div className="master-pagination"><button className="button compact" disabled={currentPage === 0} onClick={() => setPage(currentPage - 1)}>前へ</button><span>{currentPage + 1} / {pageCount}</span><button className="button compact" disabled={currentPage >= pageCount - 1} onClick={() => setPage(currentPage + 1)}>次へ</button></div>
      <details className="master-paths"><summary>読込先 / バックアップ先</summary><small>{catalog?.root}</small><small>{catalog?.backups}</small></details>
      </div>
      {document && draft.source === 'form' && <div className="master-sidebar-outline" hidden={sidebarView !== 'outline'}><MasterDataOutline key={document.path} value={draft.content} fields={documentCategory?.fields ?? []} schema={schema} selectedPath={selectedFormPath} disabled={busy || Object.keys(invalidInputs).length > 0} onSelect={selectFormPath} onFocus={focusIssue} /></div>}
    </aside>
    <section className="master-workspace">
      <header className="master-document-header"><button type="button" className="button compact subtle" aria-expanded={!sidebarCollapsed} onClick={() => setSidebarCollapsed((current) => !current)}>{sidebarCollapsed ? 'ナビを表示' : 'ナビを隠す'}</button>{typeof displayedIcon === 'string' && <span role="img" aria-label={`${draft.source === 'form' ? '編集中' : '保存済み'}のアイコン: ${displayedIcon}`}><MinecraftIcon icon={displayedIcon} className="master-header-icon" /></span>}<div><h2>{typeof displayedName === 'string' && displayedName ? <MinecraftText value={displayedName} /> : document ? baseName(document.path) : 'ファイルを選択してください'}</h2><code>{document?.path ?? 'YAML / JSONをフォームまたは原稿で編集できます。'}</code></div><span className={`save-state ${dirty ? 'dirty' : ''}`}>{readOnly ? '閲覧のみ' : dirty ? '未保存' : '保存済み'}</span></header>
      <div className="master-toolbar master-main-toolbar"><button className={`button ${draft.source === 'form' ? 'active' : ''}`} disabled={!document || busy || Object.keys(invalidInputs).length > 0} onClick={() => void changeMode('form')}>日本語フォーム</button><button className={`button ${draft.source === 'raw' ? 'active' : ''}`} disabled={!document || busy || Object.keys(invalidInputs).length > 0} onClick={() => void changeMode('raw')}>原稿 {document?.format.toUpperCase()}</button><button className="button" title="元に戻す Ctrl+Z" disabled={!history.canUndo || busy || Object.keys(invalidInputs).length > 0} onClick={() => moveHistory('undo')}>↶</button><button className="button" title="やり直す Ctrl+Y" disabled={!history.canRedo || busy || Object.keys(invalidInputs).length > 0} onClick={() => moveHistory('redo')}>↷</button><button className="button" disabled={!document || busy || Object.keys(invalidInputs).length > 0} onClick={() => void showDiff()}>差分</button><button className="button" disabled={!document || busy || Object.keys(invalidInputs).length > 0} onClick={() => void validate()}>検証</button><span className="master-spacer" /><button className="button primary" disabled={!document || readOnly || !dirty || busy || Object.keys(invalidInputs).length > 0} onClick={() => void save()}>{busy ? '処理中…' : '保存 (Ctrl+S)'}</button></div>
      {(error || notice) && <div className={`master-message ${error ? 'error' : 'success'}`} role={error ? 'alert' : 'status'}><span>{error || notice}</span><button className="button compact subtle" onClick={() => { setError(''); setNotice('') }}>閉じる</button></div>}
      {renderWarnings.length > 0 && <div className="master-message warning"><div><strong>原稿の変換を確認してください</strong>{renderWarnings.map((warning, index) => <p key={index}>{warning}</p>)}</div><button className="button" disabled={busy} onClick={() => void showDiff()}>差分を確認</button></div>}
      <div className="master-edit-area">{document ? <>
        <div className="master-secondary-toolbar"><button className="button subtle" disabled={busy || readOnly || nodeDocument} onClick={() => { if (allowDiscard()) { setCategoryId(documentCategory?.id ?? ''); setCreateMode('copy') } }}>保存済みファイルを複製</button><button className="button subtle" disabled={busy || Object.keys(invalidInputs).length > 0} onClick={() => void exportDraft()}>現在の原稿を書出</button><a className="button subtle" href={masterDataApi.exportUrl(document.path)} download>保存済みを書出</a><button className="button subtle" onClick={() => setShowReferences((current) => !current)}>参照一覧 ({references.length})</button><button className="button subtle" disabled={busy} onClick={() => void loadDocument(document.path)}>ファイルを再読込</button><span className="master-spacer" /><button className="button danger subtle" disabled={busy || readOnly} onClick={() => void deleteDocument()}>削除</button></div>
        {readOnly && <p className="master-muted">このファイルは閲覧専用です。Schemaや採番情報の変更は、実装・定義書と同期して管理します。</p>}
        {nodeDocument && <p className="master-muted">ノードの新規作成・複製は専用スキルツリーで自動採番します。{onOpenSkillTree && <button className="button subtle" onClick={onOpenSkillTree}>スキルツリーを開く</button>}</p>}
        {showReferences && <details open className="master-references"><summary>このファイルを参照している項目</summary><label>参照元を検索<input aria-label="参照元を検索" value={referenceQuery} onChange={(event) => { setReferenceQuery(event.target.value); setReferencePage(0) }} /></label><p>{filteredReferences.length}件 / {references.length}件 · 50件ずつ表示</p><table><thead><tr><th>種類</th><th>ID / 名前</th><th>参照元</th></tr></thead><tbody>{filteredReferences.slice(currentReferencePage * 50, (currentReferencePage + 1) * 50).map((entry, index) => <tr key={index}><td>{entry.kind}</td><td><code>{entry.value}</code> <MinecraftText value={entry.label ?? ''} /></td><td><button className="text-button" disabled={busy} onClick={() => void loadDocument(entry.path)}>{entry.path}</button> <small>{entry.pointer}</small></td></tr>)}</tbody></table><div className="master-pagination"><button className="button compact" disabled={currentReferencePage === 0} onClick={() => setReferencePage(currentReferencePage - 1)}>前の参照</button><span>{currentReferencePage + 1} / {referencePages}</span><button className="button compact" disabled={currentReferencePage + 1 >= referencePages} onClick={() => setReferencePage(currentReferencePage + 1)}>次の参照</button></div>{!references.length && <p className="master-muted">検出された参照元はありません。</p>}</details>}
        {Boolean(documentCategory?.documents.length) && <details className="master-doc-links"><summary>このカテゴリの定義書</summary>{documentCategory?.documents.map((entry) => <button className="button subtle" key={entry.path} disabled={busy} onClick={() => { void masterDataApi.documentation(entry.path).then(setDocumentation).catch(showError) }}>{entry.title}</button>)}</details>}
        {draft.source === 'raw' ? <><p className="master-muted">固有名詞・追加キーを含む全原稿を編集できます。YAMLのコメントは直接編集できます。</p><textarea className="master-raw" aria-label="マスター原稿" disabled={busy} readOnly={readOnly} spellCheck={false} value={draft.raw} onChange={(event) => { history.record({ raw: event.target.value, content: null, source: 'raw' }); setReport(null) }} onKeyDown={(event) => {
          if (readOnly) return
          if (event.key !== 'Tab') return
          event.preventDefault(); const target = event.currentTarget; const start = target.selectionStart; const end = target.selectionEnd
          history.record({ raw: `${draft.raw.slice(0, start)}  ${draft.raw.slice(end)}`, content: null, source: 'raw' }); requestAnimationFrame(() => { target.selectionStart = target.selectionEnd = start + 2 })
        }} /></> : <MasterDataDetails value={draft.content} path={selectedFormPath} fields={documentCategory?.fields ?? []} schema={schema} references={candidates} disabled={busy || readOnly} navigationDisabled={busy || Object.keys(invalidInputs).length > 0} onSelect={selectFormPath} onInvalid={handleInvalid} onChange={(content) => { history.record({ ...draft, content, source: 'form' }); setReport(null) }} />}
      </> : <div className="master-empty"><h2>マスターデータを編集</h2><p>左の一覧からファイルを選択するか、新規作成・原稿の読込で開始します。</p>{category && <p>{category.label} · {category.directory}</p>}</div>}</div>
      {(report || Object.keys(invalidInputs).length > 0) && <section className="master-validation"><details open><summary>検証結果 {report?.isValid ? '✓ 成功' : ''} {report?.issues.length ? `${report.issues.length} 件` : ''}</summary>{report?.isValid && !report.issues.length && <p className="success-message">検証エラーはありません。</p>}<ul>{Object.entries(invalidInputs).map(([path, message]) => <li className="error" key={path}><button className="text-button" onClick={() => focusIssue(path)}>{path}</button> {message}</li>)}{report?.issues.map((issue, index) => <li key={index} className={issue.severity}><code>{issue.code}</code> <button className="text-button" onClick={() => focusIssue(issue.path)}>{issue.path || '/'}</button> {issue.message}</li>)}</ul></details></section>}
    </section>
    {diffRaw !== null && document && <MasterDataDiff before={document.raw} after={diffRaw} onClose={() => { reviewedRaw.current = diffRaw; setDiffRaw(null) }} />}
    {documentation && <div className="modal-backdrop"><section className="modal master-doc-dialog" role="dialog" aria-modal="true" aria-label="マスター定義書"><header className="modal-header"><h2>{baseName(documentation.path)}</h2><button className="button" onClick={() => setDocumentation(null)}>閉じる</button></header><pre className="modal-body">{documentation.raw}</pre></section></div>}
    {createMode && <MasterCreateDialog mode={createMode} category={category ?? documentCategory} categories={catalog?.categories ?? []} source={document} importedRaw={importText} busy={busy} onCancel={() => setCreateMode(null)} onCreate={create} />}
  </main>
}

function MasterCreateDialog({ mode, category, categories, source, importedRaw, busy, onCancel, onCreate }: { mode: 'new' | 'copy' | 'import'; category: MasterCategory | null; categories: MasterCategory[]; source: MasterDocument | null; importedRaw: string; busy: boolean; onCancel: () => void; onCreate: (draft: MasterDraft) => Promise<void> }) {
  const [categoryId, setCategoryId] = useState(category?.id ?? categories[0]?.id ?? '')
  const selected = categories.find((entry) => entry.id === categoryId)
  const [slug, setSlug] = useState('')
  const [filePath, setFilePath] = useState('')
  const [autoPath, setAutoPath] = useState(true)
  const [autoItemId, setAutoItemId] = useState(Boolean(category?.itemCategoryCode))
  const [autoName, setAutoName] = useState(category?.id === 'class' || Boolean(category?.directory.includes('features.class')))
  const [debug, setDebug] = useState(false)
  const [templateIndex, setTemplateIndex] = useState(0)
  const [raw, setRaw] = useState(mode === 'import' ? importedRaw : mode === 'copy' ? source?.raw ?? '' : selected?.templates[0]?.raw ?? '')
  const categoryFormat = (entry?: MasterCategory): 'json' | 'yaml' => entry?.templates.length
    ? entry.templates[0].path.toLowerCase().endsWith('.json') ? 'json' : 'yaml'
    : entry?.jsonSchemas.length ? 'json' : 'yaml'
  const [draftFormat, setDraftFormat] = useState<'json' | 'yaml'>(() => {
    if (mode === 'copy') return source?.format === 'json' ? 'json' : 'yaml'
    if (mode === 'import') {
      try { JSON.parse(importedRaw); return 'json' } catch { return 'yaml' }
    }
    return categoryFormat(selected)
  })
  const [error, setError] = useState('')
  const isItem = Boolean(selected?.itemCategoryCode)
  const jsonFormat = draftFormat === 'json'
  const directory = selected?.directory === '.' ? '' : (selected?.directory ?? '').replace(/\/+$/, '')
  let content: JsonObject | undefined
  try { content = asObject(JSON.parse(raw)) } catch { /* YAML preview uses only root scalars; the server validates the final document. */ }
  const yamlScalar = (key: string) => raw.match(new RegExp(`^${key}:\\s*["']?([^\\s"'#]+)`, 'm'))?.[1]
  const previewId = String(content?.id ?? content?.nodeId ?? content?.structureId ?? yamlScalar('id') ?? '')
  const previewVersion = String(content?.schemaVersion ?? yamlScalar('schemaVersion') ?? '')
  const stem = slugOf(slug) || slugOf(previewId) || 'new-master'
  const generatedPath = `${directory ? `${directory}/` : ''}${!jsonFormat && previewVersion && /^\d+$/.test(previewVersion) ? `v${previewVersion}.` : ''}${stem}.${jsonFormat ? 'json' : 'yml'}`
  const path = autoItemId && isItem || autoName ? directory : autoPath ? generatedPath : filePath
  const selectCategory = (id: string) => {
    setCategoryId(id); setTemplateIndex(0)
    const next = categories.find((entry) => entry.id === id)
    setAutoItemId(Boolean(next?.itemCategoryCode)); setAutoName(Boolean(next?.directory.includes('features.class')))
    if (mode === 'new') { setRaw(next?.templates[0]?.raw ?? ''); setDraftFormat(categoryFormat(next)) }
  }
  const createFromSchema = async () => {
    if (!selected?.jsonSchemas[0]) return
    try {
      const rendered = await masterDataApi.render(generatedPath, masterDefault(selected.jsonSchemas[0].schema, 'object'))
      setRaw(rendered.raw)
    } catch (reason) { setError(reason instanceof Error ? reason.message : String(reason)) }
  }
  return <div className="modal-backdrop"><form className="modal master-create-dialog" role="dialog" aria-modal="true" aria-label="マスターファイル作成" onSubmit={(event) => {
    event.preventDefault(); setError('')
    if (busy) return
    if (!path.trim() || !raw.trim()) { setError('保存先と原稿を入力してください。'); return }
    if (path.includes('/nodes/')) { setError('ノードの新規作成・複製は、スキルツリー画面から行ってください。IDは専用の採番で管理します。'); return }
    if (isItem && autoItemId && !slugOf(slug)) { setError('ファイル名に使う英数字のslugを入力してください。'); return }
    void onCreate({ path, raw, autoItemId: isItem && autoItemId, slug: slugOf(slug), group: debug ? 'z' : undefined, autoName }).catch((reason) => setError(reason instanceof Error ? reason.message : String(reason)))
  }}><header className="modal-header"><div><h2>{mode === 'copy' ? '保存済みファイルの複製' : mode === 'import' ? '原稿から新規作成' : 'マスターファイル作成'}</h2><p className="master-muted">保存前に定義と重複を検証します。</p></div></header><fieldset disabled={busy} className="modal-body master-create-fields master-form-lock">
    <label>カテゴリ<select value={categoryId} onChange={(event) => selectCategory(event.target.value)}>{categories.map((entry) => <option key={entry.id} value={entry.id}>{entry.label}</option>)}</select></label>
    <label>原稿の形式<select aria-label="新規マスターの形式" value={draftFormat} onChange={(event) => setDraftFormat(event.target.value as 'json' | 'yaml')}><option value="yaml">YAML</option><option value="json">JSON</option></select><small>原稿と一致する形式を選びます。自動提案する保存先の拡張子にも反映します。</small></label>
    <label>ファイル名用 slug（小文字英数字）<input value={slug} onChange={(event) => setSlug(event.target.value)} placeholder="iron-sword" /><small>提案: {slugOf(slug) || 'new-master'}</small></label>
    {isItem && <><label className="master-checkbox"><input type="checkbox" checked={autoItemId} onChange={(event) => setAutoItemId(event.target.checked)} />カテゴリに沿ったアイテムID・ファイル名を自動採番</label>{autoItemId && <label className="master-checkbox"><input type="checkbox" checked={debug} onChange={(event) => setDebug(event.target.checked)} />デバッグ用 group z を使用</label>}</>}
    {selected?.directory.includes('features.class') && <label className="master-checkbox"><input type="checkbox" checked={autoName} onChange={(event) => setAutoName(event.target.checked)} />schemaVersion / order / id からファイル名を生成</label>}
    {!autoItemId && !autoName && <label className="master-checkbox"><input type="checkbox" checked={autoPath} onChange={(event) => { setAutoPath(event.target.checked); if (!filePath) setFilePath(generatedPath) }} />slugから保存先を自動提案</label>}
    <label>保存先（Filebase内の相対パス）<input value={path} disabled={autoPath || autoItemId && isItem || autoName} onChange={(event) => setFilePath(event.target.value)} /><small>{autoItemId && isItem ? 'ID・グループの採番後、確定したファイル名を表示します。' : autoName ? '原稿の定義から確定したファイル名を表示します。' : 'サブフォルダや既存の命名規則も直接指定できます。'}</small></label>
    {mode === 'new' && <div className="master-add-row">{selected?.templates.length ? <label>テンプレート<select value={templateIndex} onChange={(event) => { const index = Number(event.target.value); setTemplateIndex(index); setRaw(selected.templates[index].raw); setDraftFormat(selected.templates[index].path.toLowerCase().endsWith('.json') ? 'json' : 'yaml') }}>{selected.templates.map((entry, index) => <option key={entry.path} value={index}>{baseName(entry.path)}</option>)}</select></label> : null}{Boolean(selected?.jsonSchemas.length) && <button type="button" className="button" onClick={() => void createFromSchema()}>Schemaから必須項目を生成</button>}</div>}
    <label>原稿<textarea className="master-raw" aria-label="新規マスター原稿" spellCheck={false} value={raw} onChange={(event) => setRaw(event.target.value)} /></label>
    {mode === 'copy' && <p className="master-muted">読み込んだ保存済み原稿から新しいファイルを作成します。アイテムの自動採番以外は、原稿のIDを新しい一意の値へ変更してください。</p>}
    {error && <p className="error-message" role="alert">{error}</p>}
  </fieldset><footer className="modal-footer"><button type="button" className="button" disabled={busy} onClick={onCancel}>キャンセル</button><span className="master-spacer" /><button type="submit" className="button primary" disabled={busy}>{busy ? '作成中…' : '検証して作成'}</button></footer></form></div>
}

import { useCallback, useEffect, useId, useMemo, useReducer, useRef, useState } from 'react'
import { ApiError } from '../api/editorApi'
import { masterDataApi } from '../api/masterDataApi'
import { useHistory } from '../state/history'
import { useMasterDataWorkspace, type MasterDataWorkspace } from '../state/masterDataWorkspace'
import { emptyMasterTabs, masterTabsReducer, type MasterTabStatus } from '../state/masterDataTabs'
import { listMasterDrafts, readMasterDraft, removeMasterDraft, writeMasterDraft, type MasterDraftState, type RecoverableMasterDraft } from '../state/masterDataRecovery'
import type { JsonObject, JsonValue, ValidationIssue, ValidationReport } from '../types/editor'
import type { MasterCategory, MasterDocument, MasterDraft, MasterFileSummary, MasterReference } from '../types/masterData'
import { MasterDataDiff } from './MasterDataDiff'
import { masterDefault } from './MasterDataForm'
import { MasterDataDetails } from './MasterDataNavigation'
import { MasterTabContextMenu } from './MasterTabContextMenu'
import { Modal } from './Modal'
import { masterDisplayName } from '../data/masterDataPresentation'
import { closestContainerPointer, parentPointer, valueAtPointer } from '../data/masterDataNavigation'
import { inCategory } from '../data/masterDataFilters'
import { MasterFileBrowser } from './MasterFileBrowser'
import { MinecraftIcon } from './MinecraftIcon'
import { MinecraftText } from './MinecraftText'
import { stripMinecraftFormatting } from '../utils/minecraft'

type DraftState = MasterDraftState
const emptyDraft: DraftState = { raw: '', content: null, source: 'raw' }
const baseName = (path: string) => path.split('/').at(-1) ?? path
const referenceKindLabel = (kind?: string) => ({ item: 'アイテム', class: 'クラス', skill: 'スキル', mob: 'モブ', gathering: '採集', world: 'ワールド', buff: 'バフ', quest: 'クエスト', loot: 'ドロップ報酬', node: 'ノード', structure: '構造', pet: 'ペット', fishing_fish: '釣り魚', fishing_area: '釣りエリア', fishing_rules: '釣りルール', loot_table: 'ドロップ報酬テーブル', loot_pool: 'ドロップ報酬プール', set_effect: 'セット効果', recipe: 'レシピ', dungeon: 'ダンジョン', guide: 'ガイド', spawner: 'スポナー', status: 'ステータス', tag: 'タグ', mail: 'メール', enchant: 'エンチャント', mob_spawner: 'モブスポナー', gathering_spawner: '採集スポナー', quest_board: 'クエスト掲示板', schema: '定義スキーマ', skilltree: 'スキルツリー' }[kind ?? ''] ?? kind ?? '参照')
const slugOf = (text: string) => text.normalize('NFKC').toLowerCase().replace(/[^a-z0-9_-]+/g, '-').replace(/^-+|-+$/g, '')
const asObject = (value: JsonValue | undefined): JsonObject | undefined => value !== null && typeof value === 'object' && !Array.isArray(value) ? value : undefined
const categoryOptionLabel = (entry: MasterCategory, count: number, categories: MasterCategory[]) => {
  const parts = entry.directory.split('/')
  const ancestor = categories.filter((candidate) => candidate.id !== entry.id && entry.directory.startsWith(`${candidate.directory}/`))
    .sort((a, b) => b.directory.length - a.directory.length)[0]
  const depth = ancestor?.directory.split('/').length ?? 0
  const segment = parts[depth] ?? parts.at(-1) ?? entry.directory
  const number = segment.match(/^(\d+)\./)?.[1]
  const name = entry.label.split(' / ').slice(depth).join(' / ') || entry.label
  return `${'　'.repeat(depth)}${depth ? '└ ' : ''}${number ? `${number} ` : ''}${name} (${count})`
}

interface MasterDataEditorProps { active?: boolean; onDirtyChange?: (dirty: boolean) => void; onOpenSkillTree?: () => void }

export function MasterDataEditor({ active = true, onDirtyChange, onOpenSkillTree }: MasterDataEditorProps) {
  const workspace = useMasterDataWorkspace()
  const [tabsState, dispatch] = useReducer(masterTabsReducer, emptyMasterTabs)
  const tabPrefix = useId()
  const tabButtons = useRef(new Map<string, HTMLButtonElement>())
  const editorHost = useRef<HTMLDivElement>(null)
  const focusAfterClose = useRef(false)
  const latestTabs = useRef(tabsState)
  latestTabs.current = tabsState
  const [welcomeDirty, setWelcomeDirty] = useState(false)
  const [welcomeBusy, setWelcomeBusy] = useState(false)
  const [browserDialogOpen, setBrowserDialogOpen] = useState(false)
  const browserActions = useRef(new Map<string, BrowserActions>())
  const registerBrowserActions = useCallback((id: string, actions: BrowserActions | null) => {
    if (actions) browserActions.current.set(id, actions); else browserActions.current.delete(id)
  }, [])
  const [recoverableDrafts, setRecoverableDrafts] = useState<RecoverableMasterDraft[]>([])
  const recoveryRoot = useRef('')
  const [tabMenu, setTabMenu] = useState<{ id: string; x: number; y: number } | null>(null)
  const latestWelcomeDirty = useRef(false)
  const dirty = welcomeDirty || tabsState.tabs.some((tab) => tab.dirty)
  const openFile = useCallback((path: string, document?: MasterDocument) => {
    if (!document && latestWelcomeDirty.current && !window.confirm('未保存の新規原稿を破棄してファイルを開きますか？')) return
    latestWelcomeDirty.current = false
    setWelcomeDirty(false)
    dispatch({ type: 'open', path, document })
  }, [])
  const updateStatus = useCallback((id: string, status: MasterTabStatus) => {
    if (id === 'welcome') { latestWelcomeDirty.current = status.dirty; setWelcomeDirty(status.dirty); setWelcomeBusy(status.busy) }
    else dispatch({ type: 'status', id, status })
  }, [])
  const recoveryHandled = useCallback((path: string) => setRecoverableDrafts((current) => current.filter((entry) => entry.document.path !== path)), [])
  const closeTab = (id: string, deleted = false) => {
    const tab = latestTabs.current.tabs.find((entry) => entry.id === id)
    if (!tab || !deleted && (tab.busy || tab.dirty && !window.confirm(`${tab.path}\n未保存の編集内容を破棄してタブを閉じますか？`))) return
    if (latestTabs.current.tabs.length === 1) workspace.setSidebarCollapsed(false)
    if (workspace.catalog) removeMasterDraft(workspace.catalog.root, tab.path)
    setRecoverableDrafts((current) => current.filter((entry) => entry.document.path !== tab.path))
    if (!deleted || latestTabs.current.activeId === id) focusAfterClose.current = true
    dispatch({ type: 'close', id })
  }
  const closeTabs = (ids: string[]) => {
    const targets = latestTabs.current.tabs.filter((tab) => ids.includes(tab.id))
    if (!targets.length || targets.some((tab) => tab.busy)) return
    const unsaved = targets.filter((tab) => tab.dirty)
    if (unsaved.length && !window.confirm(`未保存の編集内容を破棄してタブを閉じますか？\n${unsaved.map((tab) => `${stripMinecraftFormatting(tab.name) || baseName(tab.path)} · ${tab.path}`).join('\n')}`)) return
    if (targets.length === latestTabs.current.tabs.length) workspace.setSidebarCollapsed(false)
    if (workspace.catalog) targets.forEach((tab) => removeMasterDraft(workspace.catalog!.root, tab.path))
    setRecoverableDrafts((current) => current.filter((entry) => !targets.some((tab) => tab.path === entry.document.path)))
    focusAfterClose.current = true
    dispatch({ type: 'closeMany', ids: targets.map((tab) => tab.id) })
  }
  const dismissTabMenu = useCallback((restoreFocus = false) => {
    if (restoreFocus && tabMenu) tabButtons.current.get(tabMenu.id)?.focus()
    setTabMenu(null)
  }, [tabMenu])
  useEffect(() => { if (!active) setTabMenu(null) }, [active])
  const menuIndex = tabsState.tabs.findIndex((tab) => tab.id === tabMenu?.id)
  const tabCommands = menuIndex < 0 ? [] : [
    { label: '閉じる', targets: [tabsState.tabs[menuIndex]] },
    { label: 'すべてのタブを閉じる', targets: tabsState.tabs },
    { label: 'このタブ以外を閉じる', targets: tabsState.tabs.filter((tab) => tab.id !== tabMenu?.id) },
    { label: '右側のタブを閉じる', targets: tabsState.tabs.slice(menuIndex + 1) },
    { label: '左側のタブを閉じる', targets: tabsState.tabs.slice(0, menuIndex) },
    { label: '保存済みのタブを閉じる', targets: tabsState.tabs.filter((tab) => !tab.dirty) },
  ].map(({ label, targets }) => ({ label, disabled: !targets.length || targets.some((tab) => tab.busy), run: () => closeTabs(targets.map((tab) => tab.id)) }))
  useEffect(() => { onDirtyChange?.(dirty) }, [dirty, onDirtyChange])
  useEffect(() => {
    if (!workspace.catalog || recoveryRoot.current === workspace.catalog.root) return
    recoveryRoot.current = workspace.catalog.root
    setRecoverableDrafts(listMasterDrafts(workspace.catalog.root))
  }, [workspace.catalog])
  useEffect(() => {
    const unload = (event: BeforeUnloadEvent) => { if (dirty) { event.preventDefault(); event.returnValue = '' } }
    window.addEventListener('beforeunload', unload)
    return () => window.removeEventListener('beforeunload', unload)
  }, [dirty])
  useEffect(() => { tabButtons.current.get(tabsState.activeId)?.scrollIntoView?.({ block: 'nearest', inline: 'nearest' }) }, [tabsState.activeId])
  useEffect(() => {
    if (!focusAfterClose.current) return
    const target = tabButtons.current.get(tabsState.activeId) ?? editorHost.current?.querySelector<HTMLButtonElement>('.master-browser button:not(:disabled)')
    target?.focus(); focusAfterClose.current = false
  }, [tabsState.activeId, tabsState.tabs.length])
  if (workspace.loading) return <div className="loading-screen"><p>Filebaseのカテゴリとファイルを読み込んでいます…</p></div>
  return <div ref={editorHost} className="master-tabbed-editor">
    {(workspace.error || workspace.notice) && <div className={`master-message master-global-message ${workspace.error ? 'error' : 'success'}`} role={workspace.error ? 'alert' : 'status'}><span>{workspace.error || workspace.notice}</span><button type="button" className="button compact subtle" onClick={() => { workspace.setError(''); workspace.setNotice('') }}>閉じる</button></div>}
    {workspace.error && !workspace.catalog && <div className="master-empty"><p>マスターを読み込めませんでした。接続を確認して再試行してください。</p><button type="button" className="button" disabled={workspace.refreshing} onClick={() => void workspace.refresh().catch((reason) => workspace.setError(reason instanceof Error ? reason.message : String(reason)))}>再試行</button></div>}
    {recoverableDrafts.length > 0 && <details className="master-recovery-list"><summary>復元できる未保存の下書き · {recoverableDrafts.length} 件</summary><p>同じブラウザタブの再読込前の編集です。ファイルを開いて復元または破棄できます。</p>{recoverableDrafts.map((entry) => <button type="button" className="button compact" key={entry.document.path} onClick={() => openFile(entry.document.path)}>{masterDisplayName(entry.draft.content) || baseName(entry.document.path)}</button>)}</details>}
    <div className={'master-workbench' + (workspace.sidebarCollapsed ? ' browser-hidden' : '')}>
      <MasterFileBrowser workspace={workspace} selectedPath={tabsState.tabs.find((tab) => tab.id === tabsState.activeId)?.path} busy={tabsState.tabs.find((tab) => tab.id === tabsState.activeId)?.busy ?? welcomeBusy} active={active} onOpen={openFile} onNew={() => browserActions.current.get(tabsState.activeId || 'welcome')?.create()} onImport={() => browserActions.current.get(tabsState.activeId || 'welcome')?.import()} onDialogChange={setBrowserDialogOpen} />
      <section className="master-document-area" aria-label="マスターの編集領域">
    {tabsState.tabs.length > 0 && <div className="master-document-tabs" role="tablist" aria-label="開いているマスターファイル">{tabsState.tabs.map((tab, index) => {
      const selected = tab.id === tabsState.activeId
      const name = tab.name || workspace.files.find((file) => file.path === tab.path)?.name || baseName(tab.path)
      return <div className={'master-document-tab ' + (selected ? 'selected' : '')} key={tab.id} role="presentation" onContextMenu={(event) => { event.preventDefault(); setTabMenu({ id: tab.id, x: event.clientX, y: event.clientY }) }} onAuxClick={(event) => { if (event.button === 1) { event.preventDefault(); closeTab(tab.id) } }}>
        <button type="button" role="tab" id={`${tabPrefix}-${tab.id}`} aria-controls={`${tabPrefix}-${tab.id}-panel`} aria-selected={selected} aria-label={`${stripMinecraftFormatting(name)}${tab.dirty ? ' · 未保存' : ''}`} tabIndex={selected ? 0 : -1}
          ref={(element) => { if (element) tabButtons.current.set(tab.id, element); else tabButtons.current.delete(tab.id) }}
          title={tab.path} onClick={() => dispatch({ type: 'select', id: tab.id })} onKeyDown={(event) => {
            if (event.key === 'ContextMenu' || event.shiftKey && event.key === 'F10') {
              event.preventDefault()
              const rect = event.currentTarget.getBoundingClientRect()
              setTabMenu({ id: tab.id, x: rect.left, y: rect.bottom }); return
            }
            const nextIndex = event.key === 'ArrowRight' ? (index + 1) % tabsState.tabs.length : event.key === 'ArrowLeft' ? (index - 1 + tabsState.tabs.length) % tabsState.tabs.length : event.key === 'Home' ? 0 : event.key === 'End' ? tabsState.tabs.length - 1 : -1
            if (nextIndex < 0) return
            event.preventDefault()
            const nextId = tabsState.tabs[nextIndex].id
            dispatch({ type: 'select', id: nextId }); tabButtons.current.get(nextId)?.focus()
          }}><span><MinecraftText value={name} />{tab.dirty && <b className="master-tab-dirty" aria-hidden="true">●</b>}</span><small>{baseName(tab.path)}</small></button>
        <button type="button" className="master-tab-close" aria-label={`${baseName(tab.path)} のタブを閉じる`} disabled={tab.busy} onClick={() => closeTab(tab.id)}>×</button>
      </div>
    })}</div>}
    {active && tabMenu && tabCommands.length > 0 && <MasterTabContextMenu x={tabMenu.x} y={tabMenu.y} commands={tabCommands} onDismiss={dismissTabMenu} />}
    {tabsState.tabs.length ? tabsState.tabs.map((tab) => <div className="master-editor-panel" key={tab.id} id={`${tabPrefix}-${tab.id}-panel`} role="tabpanel" aria-labelledby={`${tabPrefix}-${tab.id}`} hidden={tab.id !== tabsState.activeId}>
      <MasterDataEditorPane workspace={workspace} tabId={tab.id} initialPath={tab.path} initialDocument={tab.initialDocument} active={active && tab.id === tabsState.activeId} onOpenFile={openFile} onCloseTab={() => closeTab(tab.id, true)} onTabStatus={updateStatus} onRegisterBrowserActions={registerBrowserActions} browserDialogOpen={browserDialogOpen} onRecoveryHandled={recoveryHandled} onOpenSkillTree={onOpenSkillTree} />
    </div>) : <div className="master-editor-panel"><MasterDataEditorPane workspace={workspace} tabId="welcome" active={active} onOpenFile={openFile} onCloseTab={() => {}} onTabStatus={updateStatus} onRegisterBrowserActions={registerBrowserActions} browserDialogOpen={browserDialogOpen} onRecoveryHandled={recoveryHandled} onOpenSkillTree={onOpenSkillTree} /></div>}
      </section>
    </div>
  </div>
}

interface BrowserActions { create: () => void; import: () => void }

interface MasterDataEditorPaneProps {
  workspace: MasterDataWorkspace
  tabId: string
  initialPath?: string
  initialDocument?: MasterDocument
  active: boolean
  onOpenFile: (path: string, document?: MasterDocument) => void
  onCloseTab: () => void
  onTabStatus: (id: string, status: MasterTabStatus) => void
  onRegisterBrowserActions: (id: string, actions: BrowserActions | null) => void
  browserDialogOpen: boolean
  onRecoveryHandled: (path: string) => void
  onOpenSkillTree?: () => void
}

function MasterDataEditorPane({ workspace, tabId, initialPath, initialDocument, active, onOpenFile, onCloseTab, onTabStatus, onRegisterBrowserActions, browserDialogOpen, onRecoveryHandled, onOpenSkillTree }: MasterDataEditorPaneProps) {
  const { catalog, files, candidates, refresh, rememberedPaths, categoryId, setCategoryId, sidebarCollapsed, setSidebarCollapsed } = workspace
  const [document, setDocument] = useState<MasterDocument | null>(null)
  const [documentVersion, setDocumentVersion] = useState(0)
  const [references, setReferences] = useState<MasterReference[]>([])
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [notice, setNotice] = useState('')
  const [report, setReport] = useState<ValidationReport | null>(null)
  const [renderWarnings, setRenderWarnings] = useState<string[]>([])
  const [invalidInputs, setInvalidInputs] = useState<Record<string, string>>({})
  const [invalidValues, setInvalidValues] = useState<Record<string, string>>({})
  const [recovery, setRecovery] = useState<RecoverableMasterDraft | null>(null)
  const [requiresDiffReview, setRequiresDiffReview] = useState(false)
  const [conflictDocument, setConflictDocument] = useState<MasterDocument | null>(null)
  const [diffBefore, setDiffBefore] = useState<string | null>(null)
  const storageFailed = useRef(false)
  const [diffRaw, setDiffRaw] = useState<string | null>(null)
  const [createMode, setCreateMode] = useState<'new' | 'copy' | 'import' | null>(null)
  const [createDirty, setCreateDirty] = useState(false)
  const [importText, setImportText] = useState('')
  const [showReferences, setShowReferences] = useState(false)
  const [referenceQuery, setReferenceQuery] = useState('')
  const [referencePage, setReferencePage] = useState(0)
  const [documentation, setDocumentation] = useState<{ path: string; raw: string } | null>(null)
  const [formPath, setFormPath] = useState('')
  const [focusPath, setFocusPath] = useState<{ path: string } | null>(null)
  const editorElement = useRef<HTMLElement>(null)
  const importInput = useRef<HTMLInputElement>(null)
  const operationId = useRef(0)
  const latestRenderWarnings = useRef<string[]>([])
  const reviewedRaw = useRef('')
  const history = useHistory(emptyDraft)
  const paneBusy = useRef(busy)
  paneBusy.current = busy
  useEffect(() => {
    onRegisterBrowserActions(tabId, { create: () => { if (!paneBusy.current) setCreateMode('new') }, import: () => { if (!paneBusy.current) importInput.current?.click() } })
    return () => onRegisterBrowserActions(tabId, null)
  }, [tabId, onRegisterBrowserActions])
  const draft = history.present
  const selectedFormPath = closestContainerPointer(draft.content, formPath)
  const category = catalog?.categories.find((entry) => entry.id === categoryId) ?? null
  const documentCategory = catalog?.categories.filter((entry) => document?.path.startsWith(`${entry.directory}/`)).sort((a, b) => b.directory.length - a.directory.length)[0] ?? null
  const savedIcon = asObject(document?.content)?.icon
  const displayedIcon = draft.source === 'form' ? asObject(draft.content)?.icon : savedIcon
  const displayedName = masterDisplayName(draft.source === 'form' ? draft.content : document?.content)
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
  const filteredReferences = useMemo(() => references.filter((entry) => `${entry.path} ${entry.pointer} ${entry.value} ${stripMinecraftFormatting(entry.label ?? '')}`.toLocaleLowerCase().includes(referenceQuery.toLocaleLowerCase())), [references, referenceQuery])
  const referencePages = Math.max(1, Math.ceil(filteredReferences.length / 50))
  const currentReferencePage = Math.min(referencePage, referencePages - 1)
  const handleInvalid = useCallback((path: string, message: string | null, text?: string) => {
    setInvalidValues((current) => {
      if (message && text !== undefined) return current[path] === text ? current : { ...current, [path]: text }
      if (!Object.hasOwn(current, path)) return current
      const next = { ...current }; delete next[path]; return next
    })
    setInvalidInputs((current) => {
    if (current[path] === message || !message && !Object.hasOwn(current, path)) return current
    const next = { ...current }
    if (message) next[path] = message
    else delete next[path]
    return next
    })
  }, [])
  const showError = useCallback((reason: unknown) => {
    const payload = reason instanceof ApiError && reason.payload && typeof reason.payload === 'object'
      ? reason.payload as { issues?: ValidationIssue[] } : undefined
    if (payload?.issues) setReport({ isValid: false, issues: payload.issues })
    setError(reason instanceof Error ? reason.message : String(reason))
  }, [])
  useEffect(() => { onTabStatus(tabId, { dirty: dirty || createDirty || Boolean(recovery), busy, name: typeof displayedName === 'string' ? displayedName : '' }) }, [tabId, dirty, createDirty, recovery, busy, displayedName, onTabStatus])
  useEffect(() => {
    if (!document || !catalog || recovery || readOnly) return
    const success = dirty ? writeMasterDraft(catalog.root, { version: 1, document, draft, formPath: selectedFormPath, invalidValues, requiresDiffReview, updatedAt: new Date().toISOString() })
      : removeMasterDraft(catalog.root, document.path)
    if (!success && !storageFailed.current) setNotice('ブラウザへ一時下書きを保存できません。再読込する前に「現在の原稿を書出」で編集内容を保管してください。')
    storageFailed.current = !success
  }, [catalog, document, draft, dirty, recovery, readOnly, selectedFormPath, invalidValues, requiresDiffReview])
  const allowDiscard = () => !dirty || window.confirm('未保存の編集内容を破棄して再読込しますか？')
  const acceptDocument = useCallback((loaded: MasterDocument, offerRecovery = true) => {
    setDocument(loaded); setInvalidInputs({}); setInvalidValues({}); history.reset({ raw: loaded.raw, content: loaded.content, source: loaded.content === null ? 'raw' : 'form' })
    setRecovery(offerRecovery && catalog ? readMasterDraft(catalog.root, loaded.path) : null)
    setRequiresDiffReview(false)
    setConflictDocument(null); setDiffBefore(null)
    setDocumentVersion((version) => version + 1)
    setFormPath(closestContainerPointer(loaded.content, rememberedPaths.current[loaded.path] ?? '')); setFocusPath(null)
    if (window.innerWidth <= 1100) setSidebarCollapsed(true)
    setReport({ isValid: !loaded.issues.some((entry) => entry.severity === 'error'), issues: loaded.issues }); setRenderWarnings([])
    latestRenderWarnings.current = []; reviewedRaw.current = ''; setReferences([])
  }, [history.reset, setSidebarCollapsed, rememberedPaths, catalog?.root])
  const loadReferences = useCallback((path: string, requestId: number) => {
    void masterDataApi.references(path).then((next) => { if (requestId === operationId.current) setReferences(next) }).catch(() => {
      if (requestId === operationId.current) setNotice('参照元を取得できませんでした。編集は継続できます。')
    })
  }, [])
  useEffect(() => {
    if (!initialPath) return
    const requestId = ++operationId.current
    setBusy(true)
    void (async () => {
      try {
        const loaded = initialDocument ?? await masterDataApi.file(initialPath)
        if (requestId !== operationId.current) return
        acceptDocument(loaded); loadReferences(loaded.path, requestId)
      } catch (reason) { if (requestId === operationId.current) showError(reason) }
      finally { if (requestId === operationId.current) setBusy(false) }
    })()
    return () => { operationId.current++ }
  }, [initialPath, initialDocument, acceptDocument, loadReferences, showError])
  const loadDocument = async (path: string) => {
    if (busy || !allowDiscard()) return
    const requestId = ++operationId.current
    setBusy(true); setError(''); setNotice('')
    try {
      const loaded = await masterDataApi.file(path)
      if (requestId !== operationId.current) return
      if (document && catalog) removeMasterDraft(catalog.root, path)
      acceptDocument(loaded, !document); loadReferences(path, requestId)
    } catch (reason) { if (requestId === operationId.current) showError(reason) } finally { if (requestId === operationId.current) setBusy(false) }
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
    if (!document || readOnly || busy || recovery || Object.keys(invalidInputs).length) return
    setBusy(true); setError(''); setNotice('')
    try {
      const raw = await currentRaw()
      if ((requiresDiffReview || latestRenderWarnings.current.length > 0) && reviewedRaw.current !== raw) {
        setDiffRaw(raw); setNotice(requiresDiffReview ? '復元した下書きと最新の保存済み原稿の差分を確認してください。閉じた後、もう一度「保存」を押すと反映します。' : '原稿の変換に警告があります。差分を確認して閉じた後、もう一度「保存」を押してください。'); return
      }
      const validation = await masterDataApi.validate(document.path, raw)
      setReport(validation)
      if (!validation.isValid) { setError('検証エラーがあるため保存しませんでした。'); return }
      const saved = await masterDataApi.save(document.path, raw, document.revision)
      setDocument(saved); history.reset({ raw: saved.raw, content: saved.content, source: draft.source }); setRenderWarnings([]); setRequiresDiffReview(false)
      setNotice(`${baseName(saved.path)} を保存しました。`)
      void refresh().catch(() => setNotice(`${baseName(saved.path)} は保存済みです。一覧を再読込できませんでした。`))
    } catch (reason) {
      showError(reason)
      if (reason instanceof ApiError && reason.status === 409) {
        try {
          const latest = await masterDataApi.file(document.path)
          if (latest.revision !== document.revision) setConflictDocument(latest)
        } catch { setNotice('最新の原稿を取得できません。編集内容は保持しています。接続を確認して再試行してください。') }
      }
    } finally { setBusy(false) }
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
      if (!(event.ctrlKey || event.metaKey) || createMode || diffRaw !== null || documentation || recovery || browserDialogOpen || busy) return
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
      workspace.setNotice(`${baseName(document.path)} を削除しました。`)
      void refresh().catch((reason) => workspace.setError(reason instanceof Error ? reason.message : String(reason)))
      onCloseTab()
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
      setCreateMode(null)
      workspace.setNotice(`${created.path} を作成しました。`)
      void refresh().catch(() => workspace.setNotice(`${created.path} は作成済みです。一覧を再読込できませんでした。`))
      onOpenFile(created.path, created)
    } catch (reason) { showError(reason); throw reason } finally { setBusy(false) }
  }
  const replaceRaw = async (file: File) => {
    try {
      const raw = await file.text()
      if (document && !readOnly) {
        if (!window.confirm(`読み込んだ原稿で ${baseName(document.path)} の編集内容を置き換えますか？（保存はまだ行いません）`)) return
        history.record({ raw, content: null, source: 'raw' }); setInvalidInputs({}); setInvalidValues({}); setReport(null); setRenderWarnings([]); setNotice('原稿を読み込みました。検証してから保存してください。')
      } else { setImportText(raw); setCreateMode('import') }
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
    if (!active || !focusPath || draft.source !== 'form' || busy) return
    const targetValue = valueAtPointer(draft.content, focusPath.path)
    const container = closestContainerPointer(draft.content, targetValue !== null && typeof targetValue === 'object' ? focusPath.path : parentPointer(focusPath.path))
    if (container !== selectedFormPath) {
      if (Object.keys(invalidInputs).length) { setNotice('編集中の数値を修正してから別の項目へ移動してください。'); setFocusPath(null); return }
      setFormPath(container)
      if (document) rememberedPaths.current[document.path] = container
      return
    }
    const parent = parentPointer(focusPath.path)
    const focusLabels = [focusPath.path || '/']
    if (Array.isArray(valueAtPointer(draft.content, parent))) focusLabels.push(parent)
    const target = Array.from(editorElement.current?.querySelectorAll<HTMLInputElement | HTMLTextAreaElement | HTMLSelectElement>('.master-node-editor input, .master-node-editor textarea, .master-node-editor select') ?? []).find((entry) => focusLabels.includes(entry.getAttribute('aria-label') ?? ''))
    if (target) {
      for (let parent = target.parentElement; parent; parent = parent.parentElement) if (parent instanceof HTMLDetailsElement) parent.open = true
      target.focus(); target.scrollIntoView?.({ block: 'center' })
    } else editorElement.current?.querySelector<HTMLElement>('[data-node-heading]')?.focus()
    setFocusPath(null)
  }, [active, focusPath, selectedFormPath, draft.content, draft.source, busy, invalidInputs, document])
  return <main ref={editorElement} className="master-editor master-document-only">
    <input hidden ref={importInput} type="file" accept=".yml,.yaml,.json,text/yaml,application/json" onChange={(event) => { const file = event.target.files?.[0]; if (file) void replaceRaw(file); event.target.value = '' }} />
    <section className="master-workspace">
      <header className="master-document-header">{sidebarCollapsed && <button type="button" className="button compact subtle" aria-expanded={false} onClick={() => setSidebarCollapsed(false)}>ナビを表示</button>}{typeof displayedIcon === 'string' && <span role="img" aria-label={`${draft.source === 'form' ? '編集中' : '保存済み'}のアイコン: ${displayedIcon}`}><MinecraftIcon icon={displayedIcon} className="master-header-icon" /></span>}<div><h2>{displayedName ? <MinecraftText value={displayedName} /> : document ? baseName(document.path) : 'ファイルを選択してください'}</h2><code>{document?.path ?? 'YAML / JSONをフォームまたは原稿で編集できます。'}</code></div>{document && <span className={`save-state ${dirty ? 'dirty' : ''}`}>{readOnly ? '閲覧のみ' : dirty ? '未保存' : '保存済み'}</span>}</header>
      {recovery && document && <section className="master-recovery-panel" aria-label="未保存の下書きの復元"><strong>再読込前の下書きがあります</strong><p>{recovery.document.revision === document.revision && !recovery.requiresDiffReview ? '下書きを復元して編集を続けられます。ファイルへは明示保存するまで反映しません。' : '保存済みファイルは下書き作成後に変更されています。復元後の保存には最新原稿との差分確認が必要です。'}</p><div className="button-row"><button type="button" className="button primary" disabled={readOnly} onClick={() => {
        history.reset(recovery.draft); setInvalidValues(recovery.invalidValues); setInvalidInputs({})
        setFormPath(closestContainerPointer(recovery.draft.content, recovery.formPath))
        setRequiresDiffReview(recovery.requiresDiffReview === true || recovery.document.revision !== document.revision)
        setDocumentVersion((version) => version + 1); setReport(null); setRecovery(null); onRecoveryHandled(document.path)
        setNotice('下書きを復元しました。内容を確認してから保存してください。')
      }}>下書きを復元</button><button type="button" className="button" onClick={() => {
        if (!window.confirm('再読込前の未保存の下書きを破棄しますか？')) return
        if (catalog) removeMasterDraft(catalog.root, document.path)
        setRecovery(null); onRecoveryHandled(document.path)
      }}>下書きを破棄</button></div></section>}
      <fieldset className="master-recovery-lock" disabled={Boolean(recovery)}>
      <div className="master-toolbar master-main-toolbar"><button className={`button ${draft.source === 'form' ? 'active' : ''}`} disabled={!document || busy || Object.keys(invalidInputs).length > 0} onClick={() => void changeMode('form')}>日本語フォーム</button><button className={`button ${draft.source === 'raw' ? 'active' : ''}`} disabled={!document || busy || Object.keys(invalidInputs).length > 0} onClick={() => void changeMode('raw')}>原稿 {document?.format.toUpperCase()}</button><button className="button" title="元に戻す Ctrl+Z" disabled={!history.canUndo || busy || Object.keys(invalidInputs).length > 0} onClick={() => moveHistory('undo')}>↶</button><button className="button" title="やり直す Ctrl+Y" disabled={!history.canRedo || busy || Object.keys(invalidInputs).length > 0} onClick={() => moveHistory('redo')}>↷</button><button className="button" disabled={!document || busy || Object.keys(invalidInputs).length > 0} onClick={() => void showDiff()}>差分</button><button className="button" disabled={!document || busy || Object.keys(invalidInputs).length > 0} onClick={() => void validate()}>検証</button><span className="master-spacer" /><button className="button primary" disabled={!document || readOnly || !dirty || busy || Object.keys(invalidInputs).length > 0} onClick={() => void save()}>{busy ? '処理中…' : '保存 (Ctrl+S)'}</button></div>
      {(error || notice) && <div className={`master-message ${error ? 'error' : 'success'}`} role={error ? 'alert' : 'status'}><span>{error || notice}</span><button className="button compact subtle" onClick={() => { setError(''); setNotice('') }}>閉じる</button></div>}
      {conflictDocument && <section className="master-recovery-panel" aria-label="保存競合の解決"><strong>別の編集で保存済み原稿が変更されています</strong><p>現在の編集は保持しています。最新原稿との差分を確認してから、編集を続ける基準を更新できます。</p><div className="button-row"><button type="button" className="button" disabled={busy || Object.keys(invalidInputs).length > 0} onClick={() => {
        setBusy(true)
        void currentRaw().then((raw) => { setDiffBefore(conflictDocument.raw); setDiffRaw(raw) }).catch(showError).finally(() => setBusy(false))
      }}>最新原稿と比較</button><button type="button" className="button" disabled={busy} onClick={() => {
        setDocument(conflictDocument); setConflictDocument(null); setRequiresDiffReview(true); reviewedRaw.current = ''
        setError(''); setNotice('現在の編集を保持し、最新原稿を保存の基準にしました。保存時に差分確認が必要です。')
      }}>この編集を維持して最新を基準にする</button></div></section>}
      {renderWarnings.length > 0 && <div className="master-message warning"><div><strong>原稿の変換を確認してください</strong>{renderWarnings.map((warning, index) => <p key={index}>{warning}</p>)}</div><button className="button" disabled={busy} onClick={() => void showDiff()}>差分を確認</button></div>}
      <div className="master-edit-area">{document ? <>
        <div className="master-secondary-toolbar"><button className="button subtle" disabled={busy || readOnly || nodeDocument} onClick={() => { setCategoryId(documentCategory?.id ?? ''); setCreateMode('copy') }}>保存済みファイルを複製</button><button className="button subtle" disabled={busy || Object.keys(invalidInputs).length > 0} onClick={() => void exportDraft()}>現在の原稿を書出</button><a className="button subtle" href={masterDataApi.exportUrl(document.path)} download>保存済みを書出</a><button className="button subtle" onClick={() => setShowReferences((current) => !current)}>参照一覧 ({references.length})</button><button className="button subtle" disabled={busy} onClick={() => void loadDocument(document.path)}>ファイルを再読込</button><span className="master-spacer" /><button className="button danger subtle" disabled={busy || readOnly} onClick={() => void deleteDocument()}>削除</button></div>
        {readOnly && <p className="master-muted">このファイルは閲覧専用です。Schemaや採番情報の変更は、実装・定義書と同期して管理します。</p>}
        {nodeDocument && <p className="master-muted">ノードの新規作成・複製は専用スキルツリーで自動採番します。{onOpenSkillTree && <button className="button subtle" onClick={onOpenSkillTree}>スキルツリーを開く</button>}</p>}
        {showReferences && <details open className="master-references"><summary>{displayedName || baseName(document.path)} の参照元（{references.length}件）</summary><label>参照元を検索<input aria-label="参照元を検索" value={referenceQuery} onChange={(event) => { setReferenceQuery(event.target.value); setReferencePage(0) }} /></label><p>{filteredReferences.length}件 / {references.length}件 · 50件ずつ表示</p><table><thead><tr><th>種類</th><th>ID / 名前</th><th>参照元</th></tr></thead><tbody>{filteredReferences.slice(currentReferencePage * 50, (currentReferencePage + 1) * 50).map((entry, index) => <tr key={index}><td>{referenceKindLabel(entry.kind)}</td><td><code>{entry.value}</code> <MinecraftText value={entry.label ?? ''} /></td><td><button className="text-button" disabled={busy} onClick={() => onOpenFile(entry.path)}>{entry.path}</button> <small>{entry.pointer}</small></td></tr>)}</tbody></table><div className="master-pagination"><button className="button compact" disabled={currentReferencePage === 0} onClick={() => setReferencePage(currentReferencePage - 1)}>前の参照</button><span>{currentReferencePage + 1} / {referencePages}</span><button className="button compact" disabled={currentReferencePage + 1 >= referencePages} onClick={() => setReferencePage(currentReferencePage + 1)}>次の参照</button></div>{!references.length && <p className="master-muted">検出された参照元はありません。</p>}</details>}
        {Boolean(documentCategory?.documents.length) && <details className="master-doc-links"><summary>{documentCategory?.label} の定義書（{documentCategory?.documents.length}件）</summary>{documentCategory?.documents.map((entry) => <button className="button subtle" key={entry.path} disabled={busy} onClick={() => { void masterDataApi.documentation(entry.path).then(setDocumentation).catch(showError) }}>{entry.title}</button>)}</details>}
        {draft.source === 'raw' ? <><p className="master-muted">固有名詞・追加キーを含む全原稿を編集できます。YAMLのコメントは直接編集できます。</p><textarea className="master-raw" aria-label="マスター原稿" disabled={busy} readOnly={readOnly} spellCheck={false} value={draft.raw} onChange={(event) => { history.record({ raw: event.target.value, content: null, source: 'raw' }); setReport(null) }} onKeyDown={(event) => {
          if (readOnly) return
          if (event.key !== 'Tab') return
          event.preventDefault(); const target = event.currentTarget; const start = target.selectionStart; const end = target.selectionEnd
          history.record({ raw: `${draft.raw.slice(0, start)}  ${draft.raw.slice(end)}`, content: null, source: 'raw' }); requestAnimationFrame(() => { target.selectionStart = target.selectionEnd = start + 2 })
        }} /></> : <MasterDataDetails key={documentVersion} value={draft.content} path={selectedFormPath} fields={documentCategory?.fields ?? []} schema={schema} references={candidates} disabled={busy || readOnly} navigationDisabled={busy || Object.keys(invalidInputs).length > 0} onSelect={selectFormPath} onFocus={focusIssue} onInvalid={handleInvalid} invalidValues={invalidValues} onChange={(content) => { history.record({ ...draft, content, source: 'form' }); setReport(null) }} />}
      </> : <div className="master-empty"><h2>{initialPath ? busy ? 'ファイルを読み込んでいます…' : 'ファイルを読み込めませんでした' : 'マスターデータを編集'}</h2><p>左の一覧からファイルを選択するか、新規作成・原稿の読込で開始します。</p>{initialPath && <button type="button" className="button" disabled={busy} onClick={() => void loadDocument(initialPath)}>ファイルを再読込</button>}{category && <p>{category.label} · {category.directory}</p>}</div>}</div>
      {(report || Object.keys(invalidInputs).length > 0) && <section className="master-validation"><details open><summary>検証結果 {Object.keys(invalidInputs).length ? `入力エラー ${Object.keys(invalidInputs).length} 件` : report?.isValid ? '✓ 成功' : '要確認'} {report?.issues.length ? `${report.issues.length} 件` : ''}</summary>{report?.isValid && !report.issues.length && !Object.keys(invalidInputs).length && <p className="success-message">検証エラーはありません。</p>}<ul>{Object.entries(invalidInputs).map(([path, message]) => <li className="error" key={path}><button className="text-button" onClick={() => focusIssue(path)}>{path}</button> {message}</li>)}{report?.issues.map((issue, index) => <li key={index} className={issue.severity}><code>{issue.code}</code> <button className="text-button" onClick={() => focusIssue(issue.path)}>{issue.path || '/'}</button> {issue.message}</li>)}</ul></details></section>}
      </fieldset>
    </section>
    {diffRaw !== null && document && <MasterDataDiff before={diffBefore ?? document.raw} after={diffRaw} onClose={() => { reviewedRaw.current = diffRaw; setDiffRaw(null); setDiffBefore(null) }} />}
    {documentation && <Modal className="master-doc-dialog" ariaLabel="マスター定義書" onClose={() => setDocumentation(null)}><header className="modal-header"><h2>{baseName(documentation.path)}</h2><button className="button" onClick={() => setDocumentation(null)}>閉じる</button></header><pre className="modal-body">{documentation.raw}</pre></Modal>}
    {createMode && <MasterCreateDialog mode={createMode} category={category ?? documentCategory} categories={catalog?.categories ?? []} files={files} source={document} importedRaw={importText} busy={busy} onDirtyChange={setCreateDirty} onCancel={() => setCreateMode(null)} onCreate={create} />}
  </main>
}

function MasterCreateDialog({ mode, category, categories, files, source, importedRaw, busy, onDirtyChange, onCancel, onCreate }: { mode: 'new' | 'copy' | 'import'; category: MasterCategory | null; categories: MasterCategory[]; files: MasterFileSummary[]; source: MasterDocument | null; importedRaw: string; busy: boolean; onDirtyChange: (dirty: boolean) => void; onCancel: () => void; onCreate: (draft: MasterDraft) => Promise<void> }) {
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
  const [generating, setGenerating] = useState(false)
  const generation = useRef(0)
  const generationPending = useRef(false)
  const submitting = useRef(false)
  const snapshot = JSON.stringify({ categoryId, slug, filePath, autoPath, autoItemId, autoName, debug, templateIndex, raw, draftFormat })
  const initialSnapshot = useRef(snapshot)
  const initialRaw = useRef(raw)
  const dirty = mode !== 'new' || snapshot !== initialSnapshot.current || generating || busy
  useEffect(() => { onDirtyChange(dirty) }, [dirty, onDirtyChange])
  useEffect(() => () => {
    generation.current += 1
    generationPending.current = false
    onDirtyChange(false)
  }, [onDirtyChange])
  const allowReplace = () => raw === initialRaw.current || window.confirm('編集中の原稿を置き換えますか？')
  const cancel = () => {
    if (busy || submitting.current || dirty && !window.confirm('未保存の新規原稿を破棄しますか？')) return
    generation.current += 1
    generationPending.current = false
    onCancel()
  }
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
    if (generationPending.current || mode === 'new' && !allowReplace()) return
    setCategoryId(id); setTemplateIndex(0)
    const next = categories.find((entry) => entry.id === id)
    setAutoItemId(Boolean(next?.itemCategoryCode)); setAutoName(Boolean(next?.directory.includes('features.class')))
    if (mode === 'new') { setRaw(next?.templates[0]?.raw ?? ''); setDraftFormat(categoryFormat(next)) }
  }
  const createFromSchema = async () => {
    if (!selected?.jsonSchemas[0] || busy || generationPending.current || !allowReplace()) return
    const request = ++generation.current
    generationPending.current = true
    setGenerating(true); setError('')
    try {
      const rendered = await masterDataApi.render(generatedPath, masterDefault(selected.jsonSchemas[0].schema, 'object'))
      if (request === generation.current) setRaw(rendered.raw)
    } catch (reason) {
      if (request === generation.current) setError(reason instanceof Error ? reason.message : String(reason))
    } finally {
      if (request === generation.current) { generationPending.current = false; setGenerating(false) }
    }
  }
  return <Modal className="master-create-dialog" ariaLabel="マスターファイル作成" onClose={cancel} closeDisabled={busy}><form className="master-create-form" onSubmit={(event) => {
    event.preventDefault(); setError('')
    if (busy || submitting.current || generationPending.current) return
    if (!path.trim() || !raw.trim()) { setError('保存先と原稿を入力してください。'); return }
    if (path.includes('/nodes/')) { setError('ノードの新規作成・複製は、スキルツリー画面から行ってください。IDは専用の採番で管理します。'); return }
    if (isItem && autoItemId && !slugOf(slug)) { setError('ファイル名に使う英数字のslugを入力してください。'); return }
    submitting.current = true
    void onCreate({ path, raw, autoItemId: isItem && autoItemId, slug: slugOf(slug), group: debug ? 'z' : undefined, autoName }).catch((reason) => setError(reason instanceof Error ? reason.message : String(reason))).finally(() => { submitting.current = false })
  }}><header className="modal-header"><div><h2>{mode === 'copy' ? '保存済みファイルの複製' : mode === 'import' ? '原稿から新規作成' : 'マスターファイル作成'}</h2><p className="master-muted">保存前に定義と重複を検証します。</p></div></header><fieldset disabled={busy || generating} className="modal-body master-create-fields master-form-lock">
    <label>カテゴリ<select aria-label="カテゴリ" value={categoryId} onChange={(event) => selectCategory(event.target.value)}>{categories.map((entry) => <option key={entry.id} value={entry.id}>{categoryOptionLabel(entry, files.filter((file) => inCategory(file.path, entry.directory)).length, categories)}</option>)}</select><small>親フォルダは配下を含みます。新規アイテムの自動採番には子フォルダを選んでください。</small></label>
    <label>原稿の形式<select aria-label="新規マスターの形式" value={draftFormat} onChange={(event) => setDraftFormat(event.target.value as 'json' | 'yaml')}><option value="yaml">YAML</option><option value="json">JSON</option></select><small>原稿と一致する形式を選びます。自動提案する保存先の拡張子にも反映します。</small></label>
    <label>ファイル名用 slug（小文字英数字）<input value={slug} onChange={(event) => setSlug(event.target.value)} placeholder="iron-sword" /><small>提案: {slugOf(slug) || 'new-master'}</small></label>
    {isItem && <><label className="master-checkbox"><input type="checkbox" checked={autoItemId} onChange={(event) => setAutoItemId(event.target.checked)} />カテゴリに沿ったアイテムID・ファイル名を自動採番</label>{autoItemId && <label className="master-checkbox"><input type="checkbox" checked={debug} onChange={(event) => setDebug(event.target.checked)} />デバッグ用 group z を使用</label>}</>}
    {selected?.directory.includes('features.class') && <label className="master-checkbox"><input type="checkbox" checked={autoName} onChange={(event) => setAutoName(event.target.checked)} />schemaVersion / order / id からファイル名を生成</label>}
    {!autoItemId && !autoName && <label className="master-checkbox"><input type="checkbox" checked={autoPath} onChange={(event) => { setAutoPath(event.target.checked); if (!filePath) setFilePath(generatedPath) }} />slugから保存先を自動提案</label>}
    <label>保存先（Filebase内の相対パス）<input value={path} disabled={autoPath || autoItemId && isItem || autoName} onChange={(event) => setFilePath(event.target.value)} /><small>{autoItemId && isItem ? 'ID・グループの採番後、確定したファイル名を表示します。' : autoName ? '原稿の定義から確定したファイル名を表示します。' : 'サブフォルダや既存の命名規則も直接指定できます。'}</small></label>
    {mode === 'new' && <div className="master-add-row">{selected?.templates.length ? <label>テンプレート<select value={templateIndex} onChange={(event) => { if (generationPending.current || !allowReplace()) return; const index = Number(event.target.value); setTemplateIndex(index); setRaw(selected.templates[index].raw); setDraftFormat(selected.templates[index].path.toLowerCase().endsWith('.json') ? 'json' : 'yaml') }}>{selected.templates.map((entry, index) => <option key={entry.path} value={index}>{files.find((file) => file.path === entry.path)?.name ? `${stripMinecraftFormatting(files.find((file) => file.path === entry.path)!.name!)} · ` : ''}{baseName(entry.path)}</option>)}</select></label> : null}{Boolean(selected?.jsonSchemas.length) && <button type="button" className="button" onClick={() => void createFromSchema()}>Schemaから必須項目を生成</button>}</div>}
    <label>原稿<textarea className="master-raw" aria-label="新規マスター原稿" spellCheck={false} value={raw} onChange={(event) => setRaw(event.target.value)} /></label>
    {mode === 'copy' && <p className="master-muted">読み込んだ保存済み原稿から新しいファイルを作成します。アイテムの自動採番以外は、原稿のIDを新しい一意の値へ変更してください。</p>}
    {generating && <p role="status">Schemaから原稿を生成中です…</p>}
    {error && <p className="error-message" role="alert">{error}</p>}
  </fieldset><footer className="modal-footer"><button type="button" className="button" disabled={busy} onClick={cancel}>キャンセル</button><span className="master-spacer" /><button type="submit" className="button primary" disabled={busy || generating}>{busy ? '作成中…' : '検証して作成'}</button></footer></form></Modal>
}

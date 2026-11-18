import { lazy, Suspense, useEffect, useLayoutEffect, useState } from 'react'
import { editorApi } from './api/editorApi'
import { isAndroidEditor } from './androidEditor'
import { toolJobApi, type ToolCapabilities } from './api/toolJobApi'
import { DesktopToolRunHistoryProvider, DesktopToolWorkspace } from './components/DesktopToolWorkspace'
import { MasterQuickSearch } from './components/MasterQuickSearch'
import { toolIds, toolSpecs } from './data/desktopTools'
import { DockCommandMenu } from './docking/DockCommandMenu'
import { DockIcon } from './docking/DockIcon'
import { DockWindowButton, DockWorkspace, getDesktopBridge, workspaceLayoutKey, type DesktopBridge } from './docking/DockWorkspace'
import { DockPanelContent, dockPanels, editorPages, editorPageGroups, editorPageDescriptions, isPage, isToolPage, useDocking, type DockPanelId, type EditorPage, type Page } from './docking/dockPanel'
import './docking/editorCommandBar.css'
import './masterData.css'
import './androidEditor.css'

const MasterDataEditor = lazy(() => import('./components/MasterDataEditor').then((module) => ({ default: module.MasterDataEditor })))
const MasterAnalytics = lazy(() => import('./components/MasterAnalytics').then((module) => ({ default: module.MasterAnalytics })))
const SkillTreeWorkspace = lazy(() => import('./components/SkillTreeWorkspace').then((module) => ({ default: module.SkillTreeWorkspace })))
const SpawnerPlanningWorkspace = lazy(() => import('./components/SpawnerPlanningWorkspace').then((module) => ({ default: module.SpawnerPlanningWorkspace })))
const GameFlowWorkspace = lazy(() => import('./components/GameFlowWorkspace').then((module) => ({ default: module.GameFlowWorkspace })))
const ProgressionDesignWorkspace = lazy(() => import('./components/ProgressionDesignWorkspace').then((module) => ({ default: module.ProgressionDesignWorkspace })))

function AppIcon({ path }: { path: string }) {
  return <svg viewBox="0 0 24 24" aria-hidden="true"><path d={path} /></svg>
}

const filePanelIds = ['master-folders', 'master-files', 'master-validation'] as const
const toolPanelIds = [...toolIds, 'history'] as const

function EditorCommandBar({ desktop, workspaceRoot, branch, page, selectPage, onOpenMaster }: {
  desktop?: DesktopBridge
  workspaceRoot?: string
  branch?: string
  page: Page
  selectPage: (page: Page) => void
  onOpenMaster: (path: string) => void
}) {
  const { api, activeId, visible, open, show, close, reset } = useDocking()!
  const navigationVisible = visible.has('navigation')
  const panelCommand = (id: DockPanelId) => ({ label: dockPanels[id], run: () => isPage(id) ? selectPage(id) : show(id) })
  const workspaceName = workspaceRoot?.split(/[\\/]/).filter(Boolean).at(-1) ?? workspaceRoot
  const viewLabel = dockPanels[activeId ?? page]
  return <header className="editor-command-bar">
    {!desktop && <div className="editor-command-brand">
      <img src="/app-icon.svg" alt="" /><strong>AstralRecord マスターデータエディタ</strong>
    </div>}
    <div className="editor-command-row">
      <div className="editor-command-actions" role="group" aria-label="パネル表示">
        <button type="button" className="editor-command-button" title="編集ナビゲーション" aria-label="編集ナビゲーション" aria-pressed={navigationVisible}
          onClick={() => navigationVisible ? close('navigation') : show('navigation', open.has('navigation'))}>
          <DockIcon name="sidebar" /><span>編集ナビ</span>
        </button>
        <DockCommandMenu label="パネル" className="editor-command-button editor-command-panel" align="start" groups={[
          ...editorPageGroups.map(group => ({ label: group.label, commands: group.pages.map(panelCommand) })),
          { label: 'マスターの一覧・検証', commands: filePanelIds.map(panelCommand) },
          { label: 'ビルド・反映', commands: toolPanelIds.map(panelCommand) },
          { commands: [{ label: 'レイアウトを初期化', run: reset }] },
        ]}><span>パネル</span><DockIcon name="chevron" /></DockCommandMenu>
        <DockWindowButton api={api} panel={activeId ? api?.getPanel(activeId) : undefined} />
      </div>
      <div className="editor-command-context">
        <span className="editor-command-view" title={viewLabel}>{viewLabel}</span>
        <MasterQuickSearch onOpen={onOpenMaster} />
        {desktop && <button type="button" className="editor-command-button" aria-label="Android接続"
          onClick={() => desktop.postMessage('android-connections')}>Android接続</button>}
        <div className="editor-command-workspace">
          {desktop ? <button type="button" className="editor-command-button editor-command-workspace-button" title={workspaceRoot || 'ワークスペースを変更'}
            aria-label="ワークスペースを変更" onClick={() => desktop.postMessage('select-workspace')}>
            <DockIcon name="folder" /><span>ワークスペース</span>
            {workspaceName && <span className="editor-command-path">{workspaceName}</span>}
            <DockIcon name="chevron" />
          </button> : workspaceRoot && <span className="editor-command-path" title={workspaceRoot}>{workspaceRoot}</span>}
          {branch && <span className="editor-command-branch" title={branch}>{branch}</span>}
        </div>
      </div>
    </div>
  </header>
}

export default function App() {
  return isAndroidEditor() ? <AndroidWorkspace /> : <DockedEditor />
}

function AndroidWorkspace() {
  useLayoutEffect(() => {
    document.documentElement.dataset.editorPlatform = 'android'
    return () => { delete document.documentElement.dataset.editorPlatform }
  }, [])
  return <div className="android-editor-shell">
    <Suspense fallback={<div className="loading-screen" role="status">エディタを読み込んでいます…</div>}>
      <MasterDataEditor android />
    </Suspense>
  </div>
}

function DockedEditor() {
  const [workspaceRoot, setWorkspaceRoot] = useState('')
  const desktop = getDesktopBridge()
  useEffect(() => {
    let live = true
    void editorApi.metadata().then(metadata => { if (live) setWorkspaceRoot(metadata.workspaceRoot) }).catch(() => {})
    return () => { live = false }
  }, [])
  return <div className="master-app-shell">
    <DockWorkspace desktop={desktop} layoutKey={workspaceLayoutKey(workspaceRoot)}>
      <WorkspaceViews workspaceRoot={workspaceRoot} desktop={desktop} />
    </DockWorkspace>
  </div>
}

function WorkspaceViews({ workspaceRoot, desktop }: { workspaceRoot: string; desktop?: DesktopBridge }) {
  const docking = useDocking()!
  const { activeId, visited, visible, show } = docking
  const [lastPage, setLastPage] = useState<Page>('master')
  const page = activeId && isPage(activeId) ? activeId : lastPage
  useEffect(() => { if (activeId && isPage(activeId)) setLastPage(activeId) }, [activeId])

  const [masterRequest, setMasterRequest] = useState<{ path: string; pointer: string; requestId: number }>()
  const [masterDirty, setMasterDirty] = useState(false)
  const [treeDirty, setTreeDirty] = useState(false)
  const [designDirty, setDesignDirty] = useState(false)
  const [placementDirty, setPlacementDirty] = useState(false)
  const [flowDirty, setFlowDirty] = useState(false)
  const [capabilities, setCapabilities] = useState<ToolCapabilities>()
  const [capabilitiesError, setCapabilitiesError] = useState('')
  const [capabilitiesRequest, setCapabilitiesRequest] = useState(0)
  const [historyRunId, setHistoryRunId] = useState<string>()
  const dirtyByPage: Record<EditorPage, boolean> = {
    master: masterDirty,
    design: designDirty,
    skilltree: treeDirty,
    analytics: false,
    flow: flowDirty,
    spawner: placementDirty,
  }
  const dirty = masterDirty || treeDirty || designDirty || placementDirty || flowDirty
  useLayoutEffect(() => {
    window.astralEditorHasUnsavedChanges = () => dirty
    return () => { delete window.astralEditorHasUnsavedChanges }
  }, [dirty])
  useEffect(() => {
    let live = true
    void toolJobApi.capabilities().then(result => {
      if (!live) return
      setCapabilities(result)
      setCapabilitiesError('')
    }).catch((reason: unknown) => {
      if (live) setCapabilitiesError(reason instanceof Error ? reason.message : String(reason))
    })
    return () => { live = false }
  }, [capabilitiesRequest])

  const selectPage = (next: Page) => {
    show(next)
  }
  const retryCapabilities = () => { setCapabilitiesError(''); setCapabilitiesRequest(value => value + 1) }
  const displayRoot = workspaceRoot || capabilities?.workspaceRoot
  return <>
    <EditorCommandBar desktop={desktop} workspaceRoot={displayRoot} branch={capabilities?.workspaceBranch ?? undefined} page={page} selectPage={selectPage} onOpenMaster={path => {
      setMasterRequest(current => ({ path, pointer: '', requestId: (current?.requestId ?? 0) + 1 }))
      show('master')
    }} />
    <DockPanelContent id="navigation">
      <div id="workspace-navigation" className="master-app-sidebar">
        <nav aria-label="開発ツール">
          {editorPageGroups.map(group => <div key={group.label} role="group" aria-label={group.label}>
            <span className="workspace-nav-label">{group.label}</span>
            {group.pages.map(id => {
              const item = editorPages.find(item => item.id === id)!
              return <button key={id} type="button" className="workspace-nav-item" title={editorPageDescriptions[id]} aria-label={`${item.label}${dirtyByPage[id] ? ' ●' : ''}`} aria-pressed={page === id} aria-current={page === id ? 'page' : undefined} onClick={() => selectPage(id)}>
                <AppIcon path={item.icon} /><span>{item.label}</span>{dirtyByPage[id] && <b className="workspace-nav-dirty" aria-hidden="true">●</b>}
              </button>
            })}
          </div>)}
        </nav>
        <nav aria-label="ビルド・反映">
          <span className="workspace-nav-label">ビルド・反映</span>
          {toolIds.map(id => <button key={id} type="button" className="workspace-nav-item" aria-current={page === id ? 'page' : undefined} onClick={() => selectPage(id)}>
            <code>{id}</code><span>{toolSpecs[id].label}</span>
          </button>)}
        </nav>
        <button type="button" className="workspace-nav-item workspace-history-link" aria-current={page === 'history' ? 'page' : undefined} onClick={() => selectPage('history')}>
          <AppIcon path="M3 12a9 9 0 1 1 3 6.7 M3 5v7h7 M12 7v5l3 2" /><span>実行履歴</span>
        </button>
      </div>
    </DockPanelContent>
    <Suspense fallback={<DockPanelContent id="master"><div className="loading-screen">エディタを読み込んでいます…</div></DockPanelContent>}>
      <MasterDataEditor active={visible.has('master')} onDirtyChange={setMasterDirty} onOpenSkillTree={() => show('skilltree')} openRequest={masterRequest} />
    </Suspense>
    {visited.has('design') && <DockPanelContent id="design"><div className="master-app-content"><Suspense fallback={<div className="loading-screen">装備設計を読み込んでいます…</div>}><ProgressionDesignWorkspace active={visible.has('design')} onDirtyChange={setDesignDirty} onOpenMaster={(path, pointer = '') => { setMasterRequest({ path, pointer, requestId: Date.now() }); show('master') }} /></Suspense></div></DockPanelContent>}
    {visited.has('flow') && <DockPanelContent id="flow"><div className="master-app-content"><Suspense fallback={<div className="loading-screen">ゲーム進行フローを読み込んでいます…</div>}><GameFlowWorkspace active={visible.has('flow')} onDirtyChange={setFlowDirty} onOpenMaster={path => { setMasterRequest({ path, pointer: '', requestId: Date.now() }); show('master') }} /></Suspense></div></DockPanelContent>}
    {visited.has('skilltree') && <DockPanelContent id="skilltree"><div className="master-app-content"><Suspense fallback={<div className="loading-screen">スキルツリーを読み込んでいます…</div>}><SkillTreeWorkspace active={visible.has('skilltree')} onDirtyChange={setTreeDirty} /></Suspense></div></DockPanelContent>}
    {visited.has('analytics') && <DockPanelContent id="analytics"><div className="master-app-content"><Suspense fallback={<div className="loading-screen">比較データを読み込んでいます…</div>}><MasterAnalytics /></Suspense></div></DockPanelContent>}
    {visited.has('spawner') && <DockPanelContent id="spawner"><div className="master-app-content"><Suspense fallback={<div className="loading-screen">ワールド配置・編集を読み込んでいます…</div>}><SpawnerPlanningWorkspace active={visible.has('spawner')} onDirtyChange={setPlacementDirty} /></Suspense></div></DockPanelContent>}
    <DesktopToolRunHistoryProvider enabled={capabilities?.enabled === true && [...visited].some(isToolPage)}>
      {[...visited].filter(isToolPage).map(tool => <DockPanelContent key={tool} id={tool}><div className="master-app-content">
        <DesktopToolWorkspace page={tool} visible={visible.has(tool)} capabilities={capabilities} capabilitiesError={capabilitiesError} onRetryCapabilities={retryCapabilities} hasUnsavedSource={masterDirty || treeDirty || designDirty || flowDirty} historyRunId={tool === 'history' ? historyRunId : undefined} onOpenHistory={id => { setHistoryRunId(id); show('history') }} />
      </div></DockPanelContent>)}
    </DesktopToolRunHistoryProvider>
  </>
}

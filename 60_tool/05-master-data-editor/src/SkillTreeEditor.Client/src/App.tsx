import { lazy, Suspense, useEffect, useLayoutEffect, useState } from 'react'
import { editorApi } from './api/editorApi'
import { toolJobApi, type ToolCapabilities } from './api/toolJobApi'
import { DesktopToolRunHistoryProvider, DesktopToolWorkspace } from './components/DesktopToolWorkspace'
import { toolIds, toolSpecs } from './data/desktopTools'
import { DockMenu, DockWorkspace, getDesktopBridge, workspaceLayoutKey } from './docking/DockWorkspace'
import { DockPanelContent, editorPages, isPage, isToolPage, useDocking, type EditorPage, type Page } from './docking/dockPanel'
import './masterData.css'

const MasterDataEditor = lazy(() => import('./components/MasterDataEditor').then((module) => ({ default: module.MasterDataEditor })))
const MasterAnalytics = lazy(() => import('./components/MasterAnalytics').then((module) => ({ default: module.MasterAnalytics })))
const SkillTreeWorkspace = lazy(() => import('./components/SkillTreeWorkspace').then((module) => ({ default: module.SkillTreeWorkspace })))
const SpawnerPlanningWorkspace = lazy(() => import('./components/SpawnerPlanningWorkspace').then((module) => ({ default: module.SpawnerPlanningWorkspace })))
const ProgressionDesignWorkspace = lazy(() => import('./components/ProgressionDesignWorkspace').then((module) => ({ default: module.ProgressionDesignWorkspace })))

function AppIcon({ path }: { path: string }) {
  return <svg viewBox="0 0 24 24" aria-hidden="true"><path d={path} /></svg>
}

export default function App() {
  const [workspaceRoot, setWorkspaceRoot] = useState('')
  const desktop = getDesktopBridge()
  useEffect(() => {
    let live = true
    void editorApi.metadata().then(metadata => { if (live) setWorkspaceRoot(metadata.workspaceRoot) }).catch(() => {})
    return () => { live = false }
  }, [])
  return <div className="master-app-shell">
    <DockWorkspace desktop={desktop} layoutKey={workspaceLayoutKey(workspaceRoot)}>
      <WorkspaceViews workspaceRoot={workspaceRoot} native={Boolean(desktop)} />
    </DockWorkspace>
  </div>
}

function WorkspaceViews({ workspaceRoot, native }: { workspaceRoot: string; native: boolean }) {
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
  const [capabilities, setCapabilities] = useState<ToolCapabilities>()
  const [capabilitiesError, setCapabilitiesError] = useState('')
  const [capabilitiesRequest, setCapabilitiesRequest] = useState(0)
  const [historyRunId, setHistoryRunId] = useState<string>()
  const dirtyByPage: Record<EditorPage, boolean> = {
    master: masterDirty,
    design: designDirty,
    skilltree: treeDirty,
    analytics: false,
    spawner: placementDirty,
  }
  const dirty = masterDirty || treeDirty || designDirty || placementDirty
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
    const navDocument = docking.documents.get('navigation') ?? document
    if (next !== page && !isToolPage(page) && dirtyByPage[page] && !navDocument.defaultView?.confirm('未保存の編集があります。画面を切り替えますか？（編集内容は保持されます）')) return
    show(next)
  }
  const retryCapabilities = () => { setCapabilitiesError(''); setCapabilitiesRequest(value => value + 1) }
  const displayRoot = workspaceRoot || capabilities?.workspaceRoot
  return <>
    {!native && <header className="master-app-header">
      <div className="master-app-brand"><img className="master-app-emblem" src="/app-icon.svg" alt="" /><strong>AstralRecord マスターデータエディタ</strong></div>
      <code className="dock-workspace-path" title={displayRoot}>{displayRoot}</code>
      {capabilities?.workspaceBranch && <span className="workspace-branch">{capabilities.workspaceBranch}</span>}
      <button type="button" className="dock-header-button" title="編集ナビゲーション" aria-label="編集ナビゲーション" aria-pressed={visible.has('navigation')} onClick={() => visible.has('navigation') ? docking.close('navigation') : show('navigation', docking.open.has('navigation'))}>☰</button>
      <DockMenu label="表示メニュー" />
    </header>}
    <DockPanelContent id="navigation">
      <div id="workspace-navigation" className="master-app-sidebar">
        <nav aria-label="開発ツール">
          {editorPages.map(item => <button key={item.id} type="button" className="workspace-nav-item" aria-label={`${item.label}${dirtyByPage[item.id] ? ' ●' : ''}`} aria-pressed={page === item.id} aria-current={page === item.id ? 'page' : undefined} onClick={() => selectPage(item.id)}>
            <AppIcon path={item.icon} /><span>{item.label}</span>{dirtyByPage[item.id] && <b className="workspace-nav-dirty" aria-hidden="true">●</b>}
          </button>)}
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
    {visited.has('skilltree') && <DockPanelContent id="skilltree"><div className="master-app-content"><Suspense fallback={<div className="loading-screen">スキルツリーを読み込んでいます…</div>}><SkillTreeWorkspace active={visible.has('skilltree')} onDirtyChange={setTreeDirty} /></Suspense></div></DockPanelContent>}
    {visited.has('analytics') && <DockPanelContent id="analytics"><div className="master-app-content"><Suspense fallback={<div className="loading-screen">比較データを読み込んでいます…</div>}><MasterAnalytics /></Suspense></div></DockPanelContent>}
    {visited.has('spawner') && <DockPanelContent id="spawner"><div className="master-app-content"><Suspense fallback={<div className="loading-screen">ワールド配置・編集を読み込んでいます…</div>}><SpawnerPlanningWorkspace active={visible.has('spawner')} onDirtyChange={setPlacementDirty} /></Suspense></div></DockPanelContent>}
    <DesktopToolRunHistoryProvider enabled={capabilities?.enabled === true && [...visited].some(isToolPage)}>
      {[...visited].filter(isToolPage).map(tool => <DockPanelContent key={tool} id={tool}><div className="master-app-content">
        <DesktopToolWorkspace page={tool} visible={visible.has(tool)} capabilities={capabilities} capabilitiesError={capabilitiesError} onRetryCapabilities={retryCapabilities} hasUnsavedSource={masterDirty || treeDirty || designDirty} historyRunId={tool === 'history' ? historyRunId : undefined} onOpenHistory={id => { setHistoryRunId(id); show('history') }} />
      </div></DockPanelContent>)}
    </DesktopToolRunHistoryProvider>
  </>
}

import { lazy, Suspense, useEffect, useLayoutEffect, useRef, useState } from 'react'
import { editorApi } from './api/editorApi'
import { toolJobApi, type ToolCapabilities, type ToolPage } from './api/toolJobApi'
import { toolIds, toolSpecs } from './data/desktopTools'
import './masterData.css'

const MasterDataEditor = lazy(() => import('./components/MasterDataEditor').then((module) => ({ default: module.MasterDataEditor })))
const MasterAnalytics = lazy(() => import('./components/MasterAnalytics').then((module) => ({ default: module.MasterAnalytics })))
const SkillTreeWorkspace = lazy(() => import('./components/SkillTreeWorkspace').then((module) => ({ default: module.SkillTreeWorkspace })))
const SpawnerPlanningWorkspace = lazy(() => import('./components/SpawnerPlanningWorkspace').then((module) => ({ default: module.SpawnerPlanningWorkspace })))
const ProgressionDesignWorkspace = lazy(() => import('./components/ProgressionDesignWorkspace').then((module) => ({ default: module.ProgressionDesignWorkspace })))
const DesktopToolWorkspace = lazy(() => import('./components/DesktopToolWorkspace').then((module) => ({ default: module.DesktopToolWorkspace })))

type EditorPage = 'master' | 'design' | 'skilltree' | 'analytics' | 'spawner'
type Page = EditorPage | ToolPage

const editorPages = [
  { id: 'master', label: 'マスターデータ', icon: 'M4 3h10l4 4v14H4z M13 3v5h5 M8 12h6 M8 16h6' },
  { id: 'design', label: '装備・入手経路', icon: 'M3 3h6v6H3z M15 15h6v6h-6z M6 9v9h9 M15 3h6v6h-6z M9 6h6' },
  { id: 'skilltree', label: 'スキルツリー', icon: 'M12 6v5 M5 16v-5h14v5 M9 2h6v4H9z M2 16h6v5H2z M16 16h6v5h-6z' },
  { id: 'analytics', label: '比較・成長グラフ', icon: 'M3 3v18h18 M6 15l5-6 4 3 5-8' },
  { id: 'spawner', label: 'ワールド配置・編集', icon: 'M3 5l6-2 6 2 6-2v16l-6 2-6-2-6 2z M9 3v16 M15 5v16' },
] as const

const isToolPage = (page: Page): page is ToolPage => page === 'history' || toolIds.some(id => id === page)

function AppIcon({ path }: { path: string }) {
  return <svg viewBox="0 0 24 24" aria-hidden="true"><path d={path} /></svg>
}

export default function App() {
  const [workspace, setWorkspace] = useState<Page>('master')
  const [designOpened, setDesignOpened] = useState(false)
  const [treeOpened, setTreeOpened] = useState(false)
  const [analyticsOpened, setAnalyticsOpened] = useState(false)
  const [spawnerOpened, setSpawnerOpened] = useState(false)
  const [toolPage, setToolPage] = useState<ToolPage>()
  const [masterRequest, setMasterRequest] = useState<{ path: string; pointer: string; requestId: number }>()
  const [masterDirty, setMasterDirty] = useState(false)
  const [treeDirty, setTreeDirty] = useState(false)
  const [designDirty, setDesignDirty] = useState(false)
  const [placementDirty, setPlacementDirty] = useState(false)
  const [menuOpen, setMenuOpen] = useState(false)
  const [workspaceRoot, setWorkspaceRoot] = useState('')
  const [capabilities, setCapabilities] = useState<ToolCapabilities>()
  const [capabilitiesError, setCapabilitiesError] = useState('')
  const [capabilitiesRequest, setCapabilitiesRequest] = useState(0)
  const header = useRef<HTMLElement>(null)
  const sidebar = useRef<HTMLDivElement>(null)
  const menuButton = useRef<HTMLButtonElement>(null)
  const webView = (window as Window & { chrome?: { webview?: { postMessage: (message: string) => void } } }).chrome?.webview
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
    void editorApi.metadata().then(metadata => { if (live) setWorkspaceRoot(metadata.workspaceRoot) }).catch(() => {})
    return () => { live = false }
  }, [])
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
  useEffect(() => {
    if (!menuOpen) return
    const close = (event: PointerEvent) => {
      const target = event.target as Node
      if (!header.current?.contains(target) && !sidebar.current?.contains(target)) setMenuOpen(false)
    }
    window.addEventListener('pointerdown', close)
    return () => window.removeEventListener('pointerdown', close)
  }, [menuOpen])

  const selectWorkspace = (next: Page) => {
    if (next !== workspace) {
      if (!isToolPage(workspace) && dirtyByPage[workspace] && !window.confirm('未保存の編集があります。画面を切り替えますか？（編集内容は保持されます）')) return
      if (next === 'design') setDesignOpened(true)
      if (next === 'skilltree') setTreeOpened(true)
      if (next === 'analytics') setAnalyticsOpened(true)
      if (next === 'spawner') setSpawnerOpened(true)
      if (isToolPage(next)) setToolPage(next)
      setWorkspace(next)
    }
    if (menuOpen) menuButton.current?.focus()
    setMenuOpen(false)
  }
  const closeMenuOnEscape = (event: React.KeyboardEvent) => {
    if (event.key === 'Escape' && menuOpen) {
      setMenuOpen(false)
      menuButton.current?.focus()
    }
  }
  const currentLabel = isToolPage(workspace)
    ? workspace === 'history' ? '実行履歴' : toolSpecs[workspace].label
    : editorPages.find(item => item.id === workspace)?.label
  const displayRoot = workspaceRoot || capabilities?.workspaceRoot
  return <div className="master-app-shell">
    <header ref={header} className="master-app-header" onKeyDown={closeMenuOnEscape}>
      <div className="master-app-brand"><span className="master-app-emblem" aria-hidden="true">A</span><strong>AstralRecord</strong><span>マスターデータエディタ</span></div>
      <button ref={menuButton} type="button" className="button workspace-menu-toggle" aria-label="画面切替メニュー" aria-expanded={menuOpen} aria-controls="workspace-navigation" onClick={() => setMenuOpen(open => !open)}><span aria-hidden="true">☰</span><span>{currentLabel}</span></button>
    </header>
    <div className="master-app-location">
      <AppIcon path="M3 6h7l2 2h9v12H3z M3 11h18" />
      <span>ワークスペース</span>
      <code title={displayRoot}>{displayRoot || '—'}</code>
      {capabilities?.workspaceBranch && <span className="workspace-branch"><AppIcon path="M6 9v6 M18 9a6 6 0 0 1-6 6H9 M9 6a3 3 0 1 1-6 0 3 3 0 0 1 6 0z M21 6a3 3 0 1 1-6 0 3 3 0 0 1 6 0z M9 18a3 3 0 1 1-6 0 3 3 0 0 1 6 0z" />{capabilities.workspaceBranch}</span>}
      {webView && <button type="button" className="button compact" onClick={() => webView.postMessage('select-workspace')}>変更</button>}
    </div>
    <div className="master-app-layout">
      <div ref={sidebar} id="workspace-navigation" className="master-app-sidebar" data-open={menuOpen} onKeyDown={closeMenuOnEscape}>
        <nav aria-label="開発ツール">
          <span className="workspace-nav-label">編集</span>
          {editorPages.map(item => <button key={item.id} type="button" className="workspace-nav-item" aria-label={`${item.label}${dirtyByPage[item.id] ? ' ●' : ''}`} aria-pressed={workspace === item.id} aria-current={workspace === item.id ? 'page' : undefined} onClick={() => selectWorkspace(item.id)}>
            <AppIcon path={item.icon} /><span>{item.label}</span>{dirtyByPage[item.id] && <b className="workspace-nav-dirty" aria-hidden="true">●</b>}
          </button>)}
        </nav>
        <nav aria-label="ビルド・反映">
          <span className="workspace-nav-label">ビルド・反映</span>
          {toolIds.map(id => <button key={id} type="button" className="workspace-nav-item" aria-current={workspace === id ? 'page' : undefined} onClick={() => selectWorkspace(id)}>
            <code>{id}</code><span>{toolSpecs[id].label}</span>
          </button>)}
        </nav>
        <button type="button" className="workspace-nav-item workspace-history-link" aria-current={workspace === 'history' ? 'page' : undefined} onClick={() => selectWorkspace('history')}>
          <AppIcon path="M3 12a9 9 0 1 1 3 6.7 M3 5v7h7 M12 7v5l3 2" /><span>実行履歴</span>
        </button>
      </div>
      <div className="master-app-workspaces">
        <div className="master-app-content" hidden={workspace !== 'master'}><Suspense fallback={<div className="loading-screen">エディタを読み込んでいます…</div>}><MasterDataEditor active={workspace === 'master'} onDirtyChange={setMasterDirty} onOpenSkillTree={() => selectWorkspace('skilltree')} openRequest={masterRequest} /></Suspense></div>
        {designOpened && <div className="master-app-content" hidden={workspace !== 'design'}><Suspense fallback={<div className="loading-screen">装備設計を読み込んでいます…</div>}><ProgressionDesignWorkspace active={workspace === 'design'} onDirtyChange={setDesignDirty} onOpenMaster={(path, pointer = '') => { setMasterRequest({ path, pointer, requestId: Date.now() }); selectWorkspace('master') }} /></Suspense></div>}
        {treeOpened && <div className="master-app-content" hidden={workspace !== 'skilltree'}><Suspense fallback={<div className="loading-screen">スキルツリーを読み込んでいます…</div>}><SkillTreeWorkspace active={workspace === 'skilltree'} onDirtyChange={setTreeDirty} /></Suspense></div>}
        {analyticsOpened && <div className="master-app-content" hidden={workspace !== 'analytics'}><Suspense fallback={<div className="loading-screen">比較データを読み込んでいます…</div>}><MasterAnalytics /></Suspense></div>}
        {spawnerOpened && <div className="master-app-content" hidden={workspace !== 'spawner'}><Suspense fallback={<div className="loading-screen">ワールド配置・編集を読み込んでいます…</div>}><SpawnerPlanningWorkspace active={workspace === 'spawner'} onDirtyChange={setPlacementDirty} /></Suspense></div>}
        {toolPage && <div className="master-app-content" hidden={workspace !== toolPage}><Suspense fallback={<div className="loading-screen">実行画面を読み込んでいます…</div>}><DesktopToolWorkspace page={toolPage} visible={workspace === toolPage} capabilities={capabilities} capabilitiesError={capabilitiesError} onRetryCapabilities={() => { setCapabilitiesError(''); setCapabilitiesRequest(value => value + 1) }} hasUnsavedSource={masterDirty || treeDirty || designDirty} onOpenHistory={() => selectWorkspace('history')} /></Suspense></div>}
      </div>
    </div>
  </div>
}

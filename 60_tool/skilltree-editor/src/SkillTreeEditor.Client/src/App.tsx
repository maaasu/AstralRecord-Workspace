import { lazy, Suspense, useState } from 'react'
import './masterData.css'

const MasterDataEditor = lazy(() => import('./components/MasterDataEditor').then((module) => ({ default: module.MasterDataEditor })))
const MasterAnalytics = lazy(() => import('./components/MasterAnalytics').then((module) => ({ default: module.MasterAnalytics })))
const SkillTreeWorkspace = lazy(() => import('./components/SkillTreeWorkspace').then((module) => ({ default: module.SkillTreeWorkspace })))
const SpawnerPlanningWorkspace = lazy(() => import('./components/SpawnerPlanningWorkspace').then((module) => ({ default: module.SpawnerPlanningWorkspace })))

export default function App() {
  const [workspace, setWorkspace] = useState<'master' | 'skilltree' | 'analytics' | 'spawner'>('master')
  const [treeOpened, setTreeOpened] = useState(false)
  const [analyticsOpened, setAnalyticsOpened] = useState(false)
  const [spawnerOpened, setSpawnerOpened] = useState(false)
  const [masterDirty, setMasterDirty] = useState(false)
  const [treeDirty, setTreeDirty] = useState(false)
  const [placementDirty, setPlacementDirty] = useState(false)
  const workspaces = [
    { id: 'master', label: 'マスターデータ', description: 'ファイルを探して編集', icon: 'M4 3h10l4 4v14H4z M13 3v5h5 M8 12h6 M8 16h6', dirty: masterDirty },
    { id: 'skilltree', label: 'スキルツリー', description: 'ノードとつながりを設計', icon: 'M12 6v5 M5 16v-5h14v5 M9 2h6v4H9z M2 16h6v5H2z M16 16h6v5h-6z', dirty: treeDirty },
    { id: 'analytics', label: '比較・成長グラフ', description: '性能と育成を確かめる', icon: 'M3 3v18h18 M6 15l5-6 4 3 5-8', dirty: false },
    { id: 'spawner', label: 'ワールド配置・編集', description: '地図から配置を調整', icon: 'M3 5l6-2 6 2 6-2v16l-6 2-6-2-6 2z M9 3v16 M15 5v16', dirty: placementDirty },
  ] as const
  const selectWorkspace = (next: typeof workspace) => {
    if (next === workspace) return
    if ((workspace === 'master' ? masterDirty : workspace === 'skilltree' ? treeDirty : workspace === 'spawner' ? placementDirty : false) && !window.confirm('未保存の編集があります。画面を切り替えますか？（編集内容は保持されます）')) return
    if (next === 'skilltree') setTreeOpened(true)
    if (next === 'analytics') setAnalyticsOpened(true)
    if (next === 'spawner') setSpawnerOpened(true)
    setWorkspace(next)
  }
  return <div className="master-app-shell">
    <header className="master-app-header">
      <div className="master-app-brand"><span className="master-app-emblem" aria-hidden="true">A</span><strong>AstralRecord <span>マスターデータエディタ</span></strong><span className="master-app-environment">ローカル編集</span></div>
      <nav className="master-app-nav" aria-label="開発ツール">{workspaces.map((item) => <button key={item.id} type="button" className={`workspace-nav-item ${workspace === item.id ? 'active' : ''}`} aria-label={`${item.label}${item.dirty ? ' ●' : ''}`} aria-pressed={workspace === item.id} aria-current={workspace === item.id ? 'page' : undefined} onClick={() => selectWorkspace(item.id)}>
        <svg viewBox="0 0 24 24" aria-hidden="true"><path d={item.icon} /></svg><span><strong>{item.label}{item.dirty && <b className="workspace-nav-dirty" aria-label="未保存">●</b>}</strong><small>{item.description}</small></span>
      </button>)}</nav>
    </header>
    <div className="master-app-content" hidden={workspace !== 'master'}><Suspense fallback={<div className="loading-screen">エディタを読み込んでいます…</div>}><MasterDataEditor active={workspace === 'master'} onDirtyChange={setMasterDirty} onOpenSkillTree={() => selectWorkspace('skilltree')} /></Suspense></div>
    {treeOpened && <div className="master-app-content" hidden={workspace !== 'skilltree'}><Suspense fallback={<div className="loading-screen">スキルツリーを読み込んでいます…</div>}><SkillTreeWorkspace active={workspace === 'skilltree'} onDirtyChange={setTreeDirty} /></Suspense></div>}
    {analyticsOpened && <div className="master-app-content" hidden={workspace !== 'analytics'}><Suspense fallback={<div className="loading-screen">比較データを読み込んでいます…</div>}><MasterAnalytics /></Suspense></div>}
    {spawnerOpened && <div className="master-app-content" hidden={workspace !== 'spawner'}><Suspense fallback={<div className="loading-screen">ワールド配置・編集を読み込んでいます…</div>}><SpawnerPlanningWorkspace active={workspace === 'spawner'} onDirtyChange={setPlacementDirty} /></Suspense></div>}
  </div>
}

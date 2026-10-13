import { lazy, Suspense, useState } from 'react'
import './masterData.css'

const MasterDataEditor = lazy(() => import('./components/MasterDataEditor').then((module) => ({ default: module.MasterDataEditor })))
const MasterAnalytics = lazy(() => import('./components/MasterAnalytics').then((module) => ({ default: module.MasterAnalytics })))
const SkillTreeWorkspace = lazy(() => import('./components/SkillTreeWorkspace').then((module) => ({ default: module.SkillTreeWorkspace })))

export default function App() {
  const [workspace, setWorkspace] = useState<'master' | 'skilltree' | 'analytics'>('master')
  const [treeOpened, setTreeOpened] = useState(false)
  const [analyticsOpened, setAnalyticsOpened] = useState(false)
  const [masterDirty, setMasterDirty] = useState(false)
  const [treeDirty, setTreeDirty] = useState(false)
  const selectWorkspace = (next: typeof workspace) => {
    if (next === workspace) return
    if ((workspace === 'master' ? masterDirty : workspace === 'skilltree' ? treeDirty : false) && !window.confirm('未保存の編集があります。画面を切り替えますか？（編集内容は保持されます）')) return
    if (next === 'skilltree') setTreeOpened(true)
    if (next === 'analytics') setAnalyticsOpened(true)
    setWorkspace(next)
  }
  return <div className="master-app-shell">
    <nav className="master-app-nav" aria-label="開発ツール"><strong>AstralRecord マスターデータエディタ</strong><button className={`button ${workspace === 'master' ? 'active' : ''}`} aria-pressed={workspace === 'master'} onClick={() => selectWorkspace('master')}>マスターデータ{masterDirty ? ' ●' : ''}</button><button className={`button ${workspace === 'skilltree' ? 'active' : ''}`} aria-pressed={workspace === 'skilltree'} onClick={() => selectWorkspace('skilltree')}>スキルツリー{treeDirty ? ' ●' : ''}</button><button className={`button ${workspace === 'analytics' ? 'active' : ''}`} aria-pressed={workspace === 'analytics'} onClick={() => selectWorkspace('analytics')}>比較・成長グラフ</button><small>ローカル開発者向け · 変更は明示保存</small></nav>
    <div className="master-app-content" hidden={workspace !== 'master'}><Suspense fallback={<div className="loading-screen">エディタを読み込んでいます…</div>}><MasterDataEditor active={workspace === 'master'} onDirtyChange={setMasterDirty} onOpenSkillTree={() => selectWorkspace('skilltree')} /></Suspense></div>
    {treeOpened && <div className="master-app-content" hidden={workspace !== 'skilltree'}><Suspense fallback={<div className="loading-screen">スキルツリーを読み込んでいます…</div>}><SkillTreeWorkspace active={workspace === 'skilltree'} onDirtyChange={setTreeDirty} /></Suspense></div>}
    {analyticsOpened && <div className="master-app-content" hidden={workspace !== 'analytics'}><Suspense fallback={<div className="loading-screen">比較データを読み込んでいます…</div>}><MasterAnalytics /></Suspense></div>}
  </div>
}

import { useCallback, useEffect, useId, useMemo, useRef, useState, type CSSProperties } from 'react'
import { ResizeHandle, usePanelSize } from './ResizeHandle'
import { spawnerPlanningApi, type PlanningWorld } from '../api/spawnerPlanningApi'
import { challengeEntryApi, type ChallengeEntry } from '../api/challengeEntryApi'
import { gatheringSpawnerApi, type GatheringSpawnerMarker } from '../api/gatheringSpawnerApi'
import { habitatNames, levelColor, reasonColor, planningRoad, planningLevelStepMeters } from '../data/spawnerPlanning'
import type { PlanningPlan, PlanningSnapshot } from '../data/spawnerPlanning'
import { MinecraftText } from './MinecraftText'
import { SpawnerPlanningMap } from './SpawnerPlanningMap'
import type { PlanningLayer } from './SpawnerPlanningMap'
import { WorldPlacementEditor, type PlacementCatalog, type PlacementPoint, type WorldPlacementEditorHandle } from './WorldPlacementEditor'
import { ChallengeLocationEditor, type ChallengeLocationEditorHandle } from './ChallengeLocationEditor'
import { TeleporterList } from './TeleporterList'
import type { WorldPlacement } from '../api/worldPlacementApi'
import './spawnerPlanning.css'
import { placementSearchText, placementSearchWords } from '../data/placementSearch'
import { stripMinecraftFormatting } from '../utils/minecraft'

const number = (value: number) => value.toLocaleString('ja-JP')
const proposalPageSize = 40
type WorkspaceMode = 'placements' | 'proposals' | 'challenge'
export function SpawnerPlanningWorkspace({ active, onDirtyChange }: { active: boolean; onDirtyChange?: (dirty: boolean) => void }) {
  const [sidebarWidth, setSidebarWidth] = usePanelSize('planning-sidebar', 330, 260, 700)
  const searchHelpId = useId()
  const proposalListId = useId()
  const otherToolsId = useId()
  const proposalList = useRef<HTMLDivElement>(null)
  const listFocus = useRef<{ id: string | null; plan: PlanningPlan | null; region: string } | null>(null)
  const [listFocusKey, setListFocusKey] = useState(0)
  const [snapshot, setSnapshot] = useState<PlanningSnapshot | null>(null)
  const displayedWorld = useRef<string | null>(null)
  const [worlds, setWorlds] = useState<PlanningWorld[]>([])
  const [worldId, setWorldId] = useState<string | null>(null)
  const [plan, setPlan] = useState<PlanningPlan | null>(null)
  const [reload, setReload] = useState(0)
  const [loading, setLoading] = useState(true)
  const [planning, setPlanning] = useState(false)
  const [error, setError] = useState('')
  const [planningError, setPlanningError] = useState('')
  const [radius, setRadius] = useState(64)
  const [layer, setLayer] = useState<PlanningLayer>('terrain')
  const [region, setRegion] = useState('')
  const [reason, setReason] = useState('')
  const [radii, setRadii] = useState(false)
  const [otherToolsOpen, setOtherToolsOpen] = useState(false)
  const [selectedCell, setSelectedCell] = useState<number | null>(null)
  const [selectedProposal, setSelectedProposal] = useState<string | null>(null)
  const [focus, setFocus] = useState<{ key: number; x: number; z: number } | null>(null)
  const [proposalPage, setProposalPage] = useState(0)
  const [query, setQuery] = useState('')
  const [placements, setPlacements] = useState<WorldPlacement[]>([])
  const [placementsReady, setPlacementsReady] = useState(false)
  const [placementCatalog, setPlacementCatalog] = useState<PlacementCatalog>({ spawners: [], npcs: [], worlds: [] })
  const [selectedPlacement, setSelectedPlacement] = useState<string | null>(null)
  const [placementSelectionKey, setPlacementSelectionKey] = useState(0)
  const [placing, setPlacing] = useState(false)
  const [moving, setMoving] = useState(false)
  const [placementBusy, setPlacementBusy] = useState(false)
  const [placementDirty, setPlacementDirty] = useState(false)
  const [placementLoadVersion, setPlacementLoadVersion] = useState(0)
  const [placementPoint, setPlacementPoint] = useState<PlacementPoint | null>(null)
  const placementEditor = useRef<WorldPlacementEditorHandle>(null)
  const placementModeButton = useRef<HTMLButtonElement>(null)
  const [initialWaystoneId, setInitialWaystoneId] = useState<string | null>(null)
  const [mode, setMode] = useState<WorkspaceMode>('placements')
  const [showingTeleporters, setShowingTeleporters] = useState(false)
  const [teleportersOpened, setTeleportersOpened] = useState(false)
  const [teleporterDirty, setTeleporterDirty] = useState(false)
  const [teleporterBusy, setTeleporterBusy] = useState(false)
  const [showGatheringSpawners, setShowGatheringSpawners] = useState(false)
  const [gatheringLayer, setGatheringLayer] = useState<{ worldId: string; reload: number; markers: GatheringSpawnerMarker[]; error: string } | null>(null)
  const challengeEditor = useRef<ChallengeLocationEditorHandle>(null)
  const [challengeEntries, setChallengeEntries] = useState<{ worldId: string; entries: ChallengeEntry[]; error: string } | null>(null)
  const [challengeRevision, setChallengeRevision] = useState(0)
  const [selectedChallenge, setSelectedChallenge] = useState<string | null>(null)
  const [challengePoint, setChallengePoint] = useState<PlacementPoint | null>(null)
  const [challengePosition, setChallengePosition] = useState<{ x: number; y: number | null; z: number } | null>(null)
  const [challengePicking, setChallengePicking] = useState(false)
  const [challengeDirty, setChallengeDirty] = useState(false)
  const [challengeBusy, setChallengeBusy] = useState(false)
  const editing = mode === 'placements'
  const showingPlans = mode === 'proposals'
  const editingChallenge = mode === 'challenge'
  const listedWorld = worlds.find(world => world.id === worldId)
  const hasPlans = !!snapshot?.manifest.radiusOptions.length
  const capturedAt = snapshot?.manifest.capturedAt ? Date.parse(snapshot.manifest.capturedAt) : NaN
  const capturedLabel = Number.isFinite(capturedAt) ? new Date(capturedAt).toLocaleString('ja-JP', { timeZone: 'Asia/Tokyo' }) : ''
  const dirty = placementDirty || challengeDirty || teleporterDirty
  const editorBusy = placementBusy || challengeBusy
  const teleporterSwitchLocked = showingTeleporters && (teleporterDirty || teleporterBusy)
  const switchLocked = loading || (showingPlans && planning) || editorBusy || dirty
  const reloadLocked = loading || ((!worldId || !snapshot) && (dirty || editorBusy))
  const mapWorld = snapshot?.manifest.worldId
  const teleporterWorlds = snapshot && !worlds.some(world => world.id === mapWorld)
    ? [...worlds, { id: snapshot.manifest.worldId, name: snapshot.manifest.worldName }] : worlds
  const challengeMap = challengeEntries?.worldId === mapWorld ? challengeEntries : null
  const gatheringMap = showGatheringSpawners && gatheringLayer?.worldId === mapWorld && gatheringLayer?.reload === reload ? gatheringLayer : null
  const pickingPoint = !loading && ((editing && (placing || moving) && !placementBusy) || (editingChallenge && challengePicking && !challengeBusy))
  const focusActiveProposalMode = useCallback((element: HTMLButtonElement | null) => { element?.focus() }, [])
  const refreshChallengeEntries = useCallback(() => setChallengeRevision(value => value + 1), [])
  const resetWorldView = useCallback(() => {
    setPlan(null); setPlanning(false); setPlanningError('')
    setMode(current => current === 'proposals' ? 'placements' : current)
    setOtherToolsOpen(false)
    setSelectedPlacement(null); setInitialWaystoneId(null); setPlacementPoint(null); setPlacing(false); setMoving(false)
    setChallengePoint(null); setChallengePosition(null); setChallengePicking(false)
    setSelectedChallenge(null); setChallengeEntries(null)
    setPlacements([]); setPlacementsReady(false); setFocus(null); setLayer('terrain')
    setQuery(''); setProposalPage(0); listFocus.current = null
  }, [])
  useEffect(() => { onDirtyChange?.(dirty) }, [onDirtyChange, dirty])
  useEffect(() => {
    if (!challengeDirty && !teleporterDirty) return
    const listener = (event: BeforeUnloadEvent) => { event.preventDefault(); event.returnValue = '' }
    window.addEventListener('beforeunload', listener)
    return () => window.removeEventListener('beforeunload', listener)
  }, [challengeDirty, teleporterDirty])
  useEffect(() => {
    const controller = new AbortController()
    const request = spawnerPlanningApi.worlds?.(controller.signal) ?? Promise.resolve([])
    request.then(value => {
      if (controller.signal.aborted) return
      setWorlds(value)
      setWorldId(current => current ?? value.find(world => world.id === 'eriva_supercontinent')?.id ?? value[0]?.id ?? '')
    }).catch(() => { if (!controller.signal.aborted) setWorldId(current => current ?? '') })
    return () => controller.abort()
  }, [reload])
  useEffect(() => {
    if (worldId === null) return
    const controller = new AbortController()
    setLoading(true); setError(''); setPlan(null)
    const request = worldId ? spawnerPlanningApi.snapshot(controller.signal, worldId) : spawnerPlanningApi.snapshot(controller.signal)
    request.then(value => {
      if (controller.signal.aborted) return
      if (displayedWorld.current !== null && displayedWorld.current !== value.manifest.worldId) resetWorldView()
      displayedWorld.current = value.manifest.worldId
      setSnapshot(value); setRegion(''); setReason(''); setSelectedCell(null); setSelectedProposal(null)
      if (value.manifest.radiusOptions.length === 0) setMode(current => current === 'proposals' ? 'placements' : current)
      else setRadius(current => value.manifest.radiusOptions.includes(current) ? current : value.manifest.radiusOptions[0])
    }).catch(failure => { if (!controller.signal.aborted) setError(failure instanceof Error ? failure.message : '解析結果を読み込めませんでした。') })
      .finally(() => { if (!controller.signal.aborted) setLoading(false) })
    return () => controller.abort()
  }, [reload, worldId, resetWorldView])
  // A new snapshot (including an explicit reload) owns a fresh, bounded cache.
  const plansByRadius = useMemo(() => new Map<number, PlanningPlan>(), [snapshot, reload])
  useEffect(() => {
    if (!active || !mapWorld) return
    const controller = new AbortController()
    void challengeEntryApi.list(mapWorld, controller.signal).then(entries => {
      if (!controller.signal.aborted) setChallengeEntries({ worldId: mapWorld, entries, error: '' })
    }).catch(failure => {
      if (controller.signal.aborted) return
      const error = failure instanceof Error ? failure.message : '受付地点を取得できませんでした'
      setChallengeEntries(current => ({ worldId: mapWorld, entries: current?.worldId === mapWorld ? current.entries : [], error }))
    })
    return () => controller.abort()
  }, [active, mapWorld, challengeRevision, reload])
  useEffect(() => {
    if (!active || !mapWorld || !showGatheringSpawners) return
    const controller = new AbortController()
    void gatheringSpawnerApi.list(mapWorld, controller.signal).then(markers => {
      if (!controller.signal.aborted) setGatheringLayer({ worldId: mapWorld, reload, markers, error: '' })
    }).catch(failure => {
      if (controller.signal.aborted) return
      const error = failure instanceof Error ? failure.message : '採集スポナーを取得できませんでした。'
      setGatheringLayer({ worldId: mapWorld, reload, markers: [], error })
    })
    return () => controller.abort()
  }, [active, mapWorld, reload, showGatheringSpawners])
  useEffect(() => {
    if (!snapshot || !hasPlans || !showingPlans || loading) return
    const controller = new AbortController()
    setPlanning(true); setPlan(null); setPlanningError(''); setSelectedCell(null); setSelectedProposal(null); setProposalPage(0)
    const cached = plansByRadius.get(radius)
    if (cached) { setPlan(cached); setPlanning(false); return () => controller.abort() }
    const request = worldId ? spawnerPlanningApi.plan(snapshot, radius, controller.signal, worldId) : spawnerPlanningApi.plan(snapshot, radius, controller.signal)
    request.then(value => {
      if (controller.signal.aborted) return
      const oldest = plansByRadius.keys().next().value
      if (plansByRadius.size >= 3 && oldest !== undefined) plansByRadius.delete(oldest)
      plansByRadius.set(radius, value)
      setPlan(value)
    })
      .catch(failure => { if (!controller.signal.aborted) setPlanningError(failure instanceof Error ? failure.message : '配置案を読み込めませんでした。') })
      .finally(() => { if (!controller.signal.aborted) setPlanning(false) })
    return () => controller.abort()
  }, [snapshot, radius, showingPlans, loading, plansByRadius, worldId, hasPlans])
  const regionById = useMemo(() => new Map(snapshot?.regions.map(value => [value.id, value]) ?? []), [snapshot])
  const selectedRegion = regionById.get(region)
  const road = snapshot ? planningRoad(snapshot.manifest) : null
  const levelStep = snapshot ? planningLevelStepMeters(snapshot.manifest) : null
  const regionProposals = useMemo(() => plan?.proposals.filter(value => !region || value.regionId === region) ?? [], [plan, region])
  const proposalSearch = useMemo(() => new Map((plan?.proposals ?? []).map(value => [value,
    placementSearchText(`${value.proposalId} ${value.spawnerId} ${value.regionName} ${value.spawnMobs.map(mob => `${mob.name} ${mob.mobId}`).join(' ')} ${value.x} ${value.y} ${value.z}`),
  ])), [plan])
  const proposals = useMemo(() => {
    const words = placementSearchWords(query)
    return words.length ? regionProposals.filter(value => words.every(word => proposalSearch.get(value)!.includes(word))) : regionProposals
  }, [regionProposals, query, proposalSearch])
  const proposalPages = Math.max(1, Math.ceil(proposals.length / proposalPageSize))
  const currentProposalPage = Math.min(proposalPage, proposalPages - 1)
  const selectedProposalIndex = useMemo(() => proposals.findIndex(value => value.proposalId === selectedProposal), [proposals, selectedProposal])
  const selectedRegionIndex = useMemo(() => regionProposals.findIndex(value => value.proposalId === selectedProposal), [regionProposals, selectedProposal])
  const selectedOutsideSearch = selectedRegionIndex >= 0 && selectedProposalIndex < 0
  useEffect(() => { setProposalPage(current => Math.min(current, proposalPages - 1)) }, [proposalPages])
  useEffect(() => {
    const request = listFocus.current
    listFocus.current = null
    if (!request || !showingPlans || loading || planning || request.plan !== plan || request.region !== region) return
    const buttons = Array.from(proposalList.current?.querySelectorAll<HTMLButtonElement>('button[data-proposal-id]') ?? [])
    const target = request.id === null ? buttons[0] : buttons.find(button => button.dataset.proposalId === request.id)
    target?.focus({ preventScroll: true })
    target?.scrollIntoView?.({ block: 'nearest' })
  }, [currentProposalPage, listFocusKey, showingPlans, loading, planning, plan, region])
  function navigateProposals(page: number, id: string | null = null) {
    if (!plan || planning || loading) return
    listFocus.current = { id, plan, region }
    setProposalPage(page)
    setListFocusKey(value => value + 1)
  }
  function revealProposal() {
    if (selectedRegionIndex < 0 || !selectedProposal || !plan || planning || loading) return
    if (selectedOutsideSearch) setQuery('')
    navigateProposals(Math.floor((selectedOutsideSearch ? selectedRegionIndex : selectedProposalIndex) / proposalPageSize), selectedProposal)
  }
  const stats = useMemo(() => {
    const counts: Record<string, number> = {}
    if (snapshot && plan) snapshot.cells.forEach((cell, index) => {
      if (!region || cell.regionId === region) { const code = plan.reasonCodes[index]; counts[code] = (counts[code] ?? 0) + 1 }
    })
    const covered = counts.covered ?? 0
    const uncovered = Object.entries(counts).filter(([code]) => code === 'uncovered' || code === 'budget' || code.startsWith('uncovered_')).reduce((sum, [, value]) => sum + value, 0)
    return { counts, covered, uncovered, target: covered + uncovered }
  }, [snapshot, plan, region])
  const cell = selectedCell === null ? null : snapshot?.cells[selectedCell]
  const cellCode = selectedCell === null ? '' : plan?.reasonCodes[selectedCell] ?? cell?.reasonCode ?? ''
  const proposal = plan?.proposals.find(value => value.proposalId === selectedProposal)
  function selectRegion(value: string) { setRegion(value); setReason(''); setSelectedCell(null); setSelectedProposal(null); setProposalPage(0) }
  function selectProposal(id: string) {
    setSelectedProposal(id); setSelectedCell(null)
    const value = plan?.proposals.find(item => item.proposalId === id)
    if (value) setFocus(current => ({ key: (current?.key ?? 0) + 1, x: value.x + .5, z: value.z + .5 }))
  }
  function selectPlacement(id: string | null, location?: { x: number; z: number }) {
    setSelectedPlacement(id)
    if (!id) return
    setPlacementSelectionKey(current => current + 1)
    setSelectedCell(null); setSelectedProposal(null)
    const item = location ?? placements.find(value => value.placementId === id)
    if (item) setFocus(current => ({ key: (current?.key ?? 0) + 1, x: item.x, z: item.z }))
  }
  function selectWorld(next: string) {
    if (switchLocked || next === worldId || !worlds.some(world => world.id === next)) return
    setLoading(true); setSnapshot(null); setWorldId(next)
    displayedWorld.current = null
    resetWorldView()
  }
  function reloadAnalysis() {
    if (reloadLocked) return
    setLoading(true)
    if (!worldId) setWorldId(null)
    setReload(value => value + 1)
  }
  function selectMode(next: WorkspaceMode) {
    if (teleporterSwitchLocked) return
    if (showingTeleporters && next === mode) {
      setShowingTeleporters(false)
      return
    }
    setShowingTeleporters(false)
    setMode(next)
    setOtherToolsOpen(false)
    if (next === 'placements') setSelectedProposal(null)
    else { setPlacing(false); setMoving(false) }
  }
  function openTeleporterPlacement(placement: WorldPlacement, revision: string) {
    if (switchLocked || teleporterSwitchLocked || pickingPoint) return
    if (placement.worldId === mapWorld) {
      placementEditor.current?.openSavedWaystone(placement.placementId, revision)
    } else {
      if (!worlds.some(world => world.id === placement.worldId)) return
      if (placement.worldId === worldId) reloadAnalysis()
      else selectWorld(placement.worldId)
      setInitialWaystoneId(placement.placementId)
    }
    selectMode('placements')
    placementModeButton.current?.focus()
  }
  function selectChallengeEntry(entry: ChallengeEntry) {
    if (!active || loading || challengeBusy || pickingPoint) return
    challengeEditor.current?.selectEntry(entry, () => selectMode('challenge'))
  }
  function selectMapPoint(x: number, y: number | null, z: number) {
    if (editingChallenge && challengePicking && !challengeBusy) {
      setChallengePoint(current => ({ key: (current?.key ?? 0) + 1, x, y, z }))
    } else if (editing && !placementBusy) {
      setPlacementPoint(current => ({ key: (current?.key ?? 0) + 1, x, y, z }))
    }
  }
  function download() {
    if (!snapshot || !plan) return
    const file = new Blob([JSON.stringify({ ...plan, worldName: snapshot.manifest.worldName, progressionPolicy: snapshot.manifest.progressionPolicy, limitations: snapshot.manifest.limitations }, null, 2)], { type: 'application/json' })
    const url = URL.createObjectURL(file), anchor = document.createElement('a')
    anchor.href = url; anchor.download = `${snapshot.manifest.worldId}-preview-${radius}m.json`; anchor.click()
    setTimeout(() => URL.revokeObjectURL(url), 1000)
  }
  return <section className={`sp-workspace ${showingTeleporters ? 'sp-teleporter-workspace' : editing ? 'sp-editing-workspace' : ''}`} aria-label="ワールド配置・編集">
    <header className="sp-heading">
      <h1>{showingTeleporters ? 'ワールド配置・編集' : snapshot?.manifest.worldName ?? listedWorld?.name ?? 'ワールド地図'}</h1>
      {!showingTeleporters && <div className="sp-header-actions">
        <label className="sp-world-select">表示ワールド<select value={worldId ?? ''} disabled={switchLocked || worlds.length === 0} onChange={event => selectWorld(event.target.value)}>
          {!listedWorld && <option value={worldId ?? ''}>{snapshot?.manifest.worldName ?? (loading ? '読み込み中…' : '取り込み済みの地図なし')}</option>}
          {worlds.map(world => <option key={world.id} value={world.id}>{world.name}</option>)}
        </select></label>
        <button className="button" onClick={reloadAnalysis} disabled={reloadLocked}>地図を再読込</button>
      </div>}
    </header>
    <div className="sp-workspace-actions">
      <div className="sp-mode-switch" aria-label="編集内容">
        <button ref={placementModeButton} className="button" aria-pressed={editing && !showingTeleporters} disabled={teleporterSwitchLocked} onClick={() => selectMode('placements')}>配置編集</button>
        <button className="button" aria-pressed={editingChallenge && !showingTeleporters} disabled={teleporterSwitchLocked || (loading && !snapshot)} onClick={() => selectMode('challenge')}>ボス・ダンジョン受付</button>
        <button className="button" aria-pressed={showingTeleporters} onClick={() => { setTeleportersOpened(true); setShowingTeleporters(true); setOtherToolsOpen(false) }}>テレポーター一覧</button>
        {showingPlans && <button ref={focusActiveProposalMode} className="button" aria-pressed={!showingTeleporters} disabled={teleporterSwitchLocked} onClick={() => selectMode('proposals')}>AI 配置案</button>}
      </div>
      {!showingTeleporters && hasPlans && !showingPlans && <div className="sp-other-tools">
        <button type="button" className="button" aria-expanded={otherToolsOpen} aria-controls={otherToolsId}
          onClick={() => setOtherToolsOpen(value => !value)}>その他の機能</button>
        <div id={otherToolsId} hidden={!otherToolsOpen}>
          <button type="button" className="button" onClick={() => selectMode('proposals')}>AI 配置案</button>
        </div>
      </div>}
      {!showingTeleporters && <><label className="sp-map-range-switch"><input type="checkbox" checked={showGatheringSpawners} disabled={!snapshot}
        onChange={event => { setShowGatheringSpawners(event.target.checked); setGatheringLayer(null) }} />採集スポナーを表示</label>
      <details className="sp-import-help">
        <summary>地図の追加・更新</summary>
        <div className="sp-import-steps">
          <p>ワールド一覧には取り込み済みの地図が並びます。</p>
          <ol>
            <li>Dev の停止中に <code>refresh_world_map.py capture</code> で対象ワールドをコピーします。</li>
            <li>Dev の再開後、<code>refresh_world_map.py publish</code> で地図を生成します。</li>
            <li>「地図を再読込」を押して、表示ワールドを選びます。</li>
          </ol>
          <p>ワールド ID の例はミズガルズの緑野 <code>midgard_greenfields</code>、ヴェルディア <code>verdia</code> です。</p>
          <p>実行例はリポジトリの <code>60_tool/spawner-planner/README.md</code> にあります。</p>
          {capturedLabel && <p>表示中の地図の解析日時 <time dateTime={snapshot?.manifest.capturedAt}>{capturedLabel} JST</time></p>}
        </div>
      </details></>}
    </div>
    {teleportersOpened && <div className="sp-teleporter-shell" hidden={!showingTeleporters}>
      <TeleporterList worlds={teleporterWorlds} openDisabled={switchLocked || teleporterSwitchLocked || pickingPoint}
        editDisabled={placementDirty || editorBusy || placing || moving} onOpen={openTeleporterPlacement}
        onDirtyChange={setTeleporterDirty} onBusyChange={setTeleporterBusy}
        onSaved={() => {
          setInitialWaystoneId(null)
          setPlacementLoadVersion(version => version + 1)
        }} />
    </div>}
    {!showingTeleporters && <>
    {dirty && (worlds.length > 1 || !worldId || !snapshot) && <p className="sp-muted" role="status">編集内容を保存するか取り消すと、地図を切り替えられます。</p>}

    {showingPlans && <p className="sp-intro">{road ? `${levelStep ? `開始地点からの${road.terrainExtension ? '地形経路距離' : '道路距離'}${number(levelStep)}mごとにレベルを一段階上げます。` : '開始地点につながる道の距離でレベルを決めます。'}配置の上限はLv.45で、未到達の距離帯は配置しません。北東のLv.100超予定地を含む後続地域は未配置です。金色の線は16m区画で検出した道路網です。${road.terrainExtension ? '水色の点線は水域・斜面・足場を調べて延長した推定経路です。周辺のレベルも地表をたどる距離で決めています。実際の歩行可能性は未検証です。' : ''}` : '既存の配置を使わず、ブロック・バイオームとマスタの実レベルから作る全大陸の再配置案です。地域名と進行レベルは計画用の提案です。'}</p>}
    {error && (worldId || snapshot || !editingChallenge) && <div className="sp-error" role="alert"><strong>地図を読み込めません</strong><p>{error}</p><p>未取り込みのワールドは「地図の追加・更新」から手順を確認できます。</p></div>}
    {showingPlans && planningError && <div className="sp-error" role="alert"><strong>配置案を表示できません</strong><p>{planningError}</p></div>}
    {challengeMap?.error && <p className="sp-muted" role="status">{challengeMap.error} 「地図を再読込」で再取得できます。</p>}
    {snapshot && showGatheringSpawners && !gatheringMap?.markers.length && <p className="sp-muted" role="status">
      {!gatheringMap ? '採集スポナーを読み込んでいます…' : gatheringMap.error
        ? `${gatheringMap.error} 「地図を再読込」で再取得できます。` : 'このワールドに保存済みの採集スポナーはありません。'}
    </p>}
    {loading && <div className="sp-loading" role="status">ワールドの解析結果を読み込んでいます…</div>}
    {snapshot && <>
      {showingPlans && <div className="sp-controls">
        <label>表示レイヤー<select value={layer} onChange={event => setLayer(event.target.value as PlanningLayer)}><option value="terrain">地形と配置案</option><option value="levels">地域・配置目標レベル</option><option value="reasons">配置・未配置の理由</option></select></label>
        <label>地域<select value={region} onChange={event => selectRegion(event.target.value)}><option value="">全大陸</option>{snapshot.regions.map(value => <option key={value.id} value={value.id}>{value.name} · {value.levelMin == null ? 'レベル未選定' : `Lv.${value.levelMin}〜${value.levelMax}`}</option>)}</select></label>
        <label>出現半径<select value={radius} onChange={event => setRadius(Number(event.target.value))}>{snapshot.manifest.radiusOptions.map(value => <option key={value} value={value}>{value}m</option>)}</select></label>
        <label>配置状況<select value={reason} onChange={event => setReason(event.target.value)}><option value="">すべて</option>{Object.entries(stats.counts).map(([code, value]) => <option key={code} value={code}>{snapshot.manifest.reasons[code] ?? code}（{number(value)}）</option>)}</select></label>
        <button className="button" onClick={() => { const spawn = snapshot.manifest.spawn; setFocus(value => ({ key: (value?.key ?? 0) + 1, x: spawn.x, z: spawn.z })) }}>開始地点</button>
        <label className="sp-check"><input type="checkbox" checked={radii} onChange={event => setRadii(event.target.checked)} />出現範囲を重ねる</label>
      </div>}
      {showingPlans && <p className="sp-scope">出現範囲の一括表示は、地図に表示中の Dev スポナーと地域で絞り込んだ AI 配置案すべてが対象です。配置案一覧の検索では地図を絞り込みません。表示設定は配置編集と共通です。</p>}
      {showingPlans && <div className="sp-metrics" aria-live="polite">
        <div><strong>{plan ? number(regionProposals.length) : '—'}</strong><span>{selectedRegion ? '選択地域' : '全大陸'}の配置案</span></div>
        <div><strong>{plan ? `${(region ? stats.target ? stats.covered / stats.target * 100 : 0 : plan.coveragePercent).toFixed(1)}%` : '—'}</strong><span>配置対象のカバー率</span></div>
        <div><strong>{plan ? number(region ? stats.uncovered : plan.uncoveredCells) : '—'}</strong><span>対象内の未カバー区画</span></div>
        <div><strong>{plan ? number(stats.counts.failed ?? stats.counts.unknown ?? 0) : '—'}</strong><span>未完成・解析不可区画</span></div>
        <div><strong>{plan ? number(stats.counts.absent ?? 0) : '—'}</strong><span>未保存の区画</span></div>
      </div>}
    </>}
    </>}
    <div hidden={showingTeleporters} className={`sp-main ${editing || !snapshot ? 'sp-main-editing' : ''} ${editingChallenge ? 'sp-main-challenge' : ''}`} style={{ '--planning-sidebar-width': `${sidebarWidth}px` } as CSSProperties}>
        {snapshot && <div className="sp-map-column">
          <WorldPlacementEditor key={`${snapshot.manifest.worldId}:${placementLoadVersion}`} ref={placementEditor} initialWaystoneId={initialWaystoneId} editing={editing} snapshot={snapshot} point={placementPoint} selectedId={selectedPlacement} selectionKey={placementSelectionKey} onSelected={selectPlacement} onPlacements={(next, catalog, ready) => { setPlacements(next); setPlacementCatalog(catalog); setPlacementsReady(ready) }} placing={placing} onPlacing={setPlacing} moving={moving} onMoving={setMoving} onBusyChange={setPlacementBusy} onDirtyChange={setPlacementDirty} map={<>
            <SpawnerPlanningMap planningWorldId={worldId || undefined} compactControls={!showingPlans} snapshot={snapshot} plan={showingPlans ? plan : null}
              layer={showingPlans ? layer : 'terrain'} region={showingPlans ? region : ''} reason={showingPlans ? reason : ''}
              radii={!editingChallenge && radii} onRadiiChange={editing ? setRadii : undefined} active={active && !showingTeleporters}
              selectedCell={editingChallenge ? null : selectedCell} selectedProposal={showingPlans ? selectedProposal : null}
              showProposals={showingPlans} focus={focus} onCell={index => { if (!editingChallenge) { setSelectedCell(index); setSelectedProposal(null) } }} onProposal={selectProposal}
              placements={editingChallenge ? undefined : placements} placementsReady={placementsReady} placementCatalog={placementCatalog} selectedPlacement={editingChallenge ? null : selectedPlacement}
              gatheringSpawners={gatheringMap?.markers}
              onPlacement={editingChallenge ? undefined : id => { if (!editing) selectMode('placements'); selectPlacement(id) }}
              challengeOnly={editingChallenge} challengePosition={editingChallenge ? challengePosition : null} pickingChallenge={editingChallenge && challengePicking}
              challengeEntries={challengeMap?.entries} selectedChallenge={editingChallenge ? selectedChallenge : null} challengeDirty={editingChallenge && challengeDirty}
              onChallengeEntry={loading || challengeBusy ? undefined : selectChallengeEntry}
              placing={pickingPoint}
              onPlacePoint={selectMapPoint} />
            {showingPlans && <div className="sp-map-caption"><span><span className="sp-spawn-dot">●</span> 開始地点{road && '　金色実線: 道路 / 水色点線: 推定経路'}</span><span>{number(snapshot.manifest.bounds.width)} × {number(snapshot.manifest.bounds.height)}m · {snapshot.manifest.bounds.cellSize}m区画 · {number(snapshot.cells.length)}区画</span></div>}
          </>} />
          {showingPlans && layer === 'levels' && <div className="sp-level-key">配置目標レベル {[15, 20, 25, 30, 35, 40, 45].map(value => <span key={value}><i style={{ background: levelColor(value) }} />{value}</span>)}</div>}
          {showingPlans && <p className="sp-scope">カバー率の分母は、地形・足場・モブ条件を満たす配置対象です。全大陸すべてが配置可能という意味ではありません。区画の全256列を測定していますが、実際の移動経路や戦闘負荷は未検証です。</p>}
          {showingPlans && <section className="sp-region-section"><h2>地域ごとの計画</h2><p>地域を選ぶと地図と配置案の一覧を絞り込みます。</p><div className="sp-region-list">{snapshot.regions.map(value => <button key={value.id} className={`sp-region-row ${region === value.id ? 'selected' : ''}`} onClick={() => selectRegion(value.id)}><i style={{ background: levelColor(value.levelMin) }} /><span><strong>{value.name}</strong><small>{habitatNames[value.habitat] ?? value.habitat} · {number(value.cellCount)}区画</small></span><b>{value.levelMin == null ? '未選定' : `Lv.${value.levelMin}–${value.levelMax}`}</b><span>{number(value.coveredCellsByRadius?.[String(radius)] ?? 0)} / {number(value.eligibleCells)}<small>カバー / 対象</small></span></button>)}</div></section>}
        </div>}
        {snapshot && !editing && <ResizeHandle label={editingChallenge ? '地図とチャレンジ編集の幅を変更' : '地図と配置案の幅を変更'} size={sidebarWidth} min={260} max={700} defaultSize={330} minRemaining={360} direction={-1} onResize={setSidebarWidth} className="sp-planning-resizer" />}
        <aside key={snapshot?.manifest.worldId ?? 'no-map'} className="sp-sidebar" hidden={!editingChallenge}>
          {!snapshot && <p className="sp-muted">地図が表示できないときも、ワールドと X・Y・Z を指定して受付地点を保存できます。</p>}
          <ChallengeLocationEditor ref={challengeEditor} worldId={snapshot?.manifest.worldId ?? ''} worldName={snapshot?.manifest.worldName} mapEntries={challengeMap?.entries}
            point={challengePoint} active={active && editingChallenge && !showingTeleporters && (!!snapshot || !loading)} canPick={!!snapshot && !loading}
            onPickingChange={setChallengePicking} onDirtyChange={setChallengeDirty} onBusyChange={setChallengeBusy}
            onPositionChange={setChallengePosition} onSelectionChange={setSelectedChallenge} onSaved={refreshChallengeEntries} />
        </aside>
        {snapshot && showingPlans && <aside className="sp-sidebar">
          {planning && <p role="status" className="sp-loading">半径{radius}mの配置案を読み込んでいます…</p>}
          <section><div className="sp-section-heading"><h2>地点・スポナーの詳細</h2><button className="button" disabled={!plan || planning} onClick={download}>案をJSON保存</button></div>
            {!cell && !proposal && <p className="sp-muted">地図の地点か、下の配置案を選択してください。全域表示では地点、拡大後はスポナーの点も選択できます。</p>}
            {cell && <div className="sp-detail"><strong>X {cell.x} / Z {cell.z}</strong><p className="sp-reason"><i style={{ background: reasonColor(cellCode) }} />{snapshot.manifest.reasons[cellCode] ?? cellCode}</p><p>{regionById.get(cell.regionId)?.name ?? '地域未判定'}<br />{habitatNames[cell.habitat] ?? cell.habitat} / 配置目標 {cell.targetLevel == null ? '未定' : `Lv.${cell.targetLevel}`}</p><p>{cell.roadDistanceMeters != null && <>{road?.terrainExtension ? '地形経路距離' : '道に沿った距離'}: {number(Math.round(cell.roadDistanceMeters))}m<br /></>}{cell.distanceToRoadMeters != null && <>{road?.terrainExtension ? '道路・推定経路からの距離' : '道からの距離'}: {number(Math.round(cell.distanceToRoadMeters))}m<br /></>}安全な列: {cell.safeCount ?? 0} / 256<br />候補の足元Y: {cell.candidateY ?? '—'}<br />バイオーム: {cell.biome ?? '未読込'}<br />地表: {cell.surface ?? '未読込'}</p>{cell.reason && <p>{cell.reason}</p>}<p>この地点に届く出現範囲は、地図上の「選択区画の出現範囲」で確認できます。</p></div>}
            {proposal && <div className="sp-detail"><strong>{proposal.regionName}</strong><p>X {proposal.x} / Y {proposal.y} / Z {proposal.z}<br />半径 {proposal.radiusMeters}m · Lv.{proposal.level}</p><span className={`sp-definition ${proposal.definitionStatus === 'draft' ? 'draft' : ''}`}>{proposal.definitionStatus === 'draft' ? '未登録のスポナーマスタ案' : '既存スポナーマスタを使用'}</span><ul>{proposal.spawnMobs.map(mob => <li key={`${mob.mobId}:${mob.level}`}><MinecraftText value={mob.name} /> <b>Lv.{mob.level}</b><small>出現重み {mob.weight}</small></li>)}</ul><p>{proposal.reason}</p><p>{number(proposal.newCoveredCells)}区画を新たにカバー</p>{proposal.originalRadiusMeters != null && <p>現在のマスタ半径: {proposal.originalRadiusMeters}m{proposal.masterUpdateRequired ? '（変更案）' : ''}</p>}<code>{proposal.spawnerId}</code></div>}
          </section>
          {selectedRegion && <section><h2>{selectedRegion.name}</h2><p className="sp-muted">{selectedRegion.reason}</p><button className="button" onClick={() => selectRegion('')}>全大陸へ戻す</button></section>}
          <section><h2>配置・未配置の理由</h2><div className="sp-reason-list">{Object.entries(stats.counts).sort((a, b) => b[1] - a[1]).map(([code, value]) => <button key={code} onClick={() => { setReason(code); setLayer('reasons') }} aria-pressed={reason === code}><span><i style={{ background: reasonColor(code) }} />{snapshot.manifest.reasons[code] ?? code}</span><b>{number(value)}</b></button>)}</div></section>
          <section><h2>配置案 <small>{number(proposals.length)}件</small></h2>
            <input aria-label="配置案を検索" aria-describedby={searchHelpId} placeholder="モブ名・地域名・ID・座標" value={query} onChange={event => { setQuery(event.target.value); setProposalPage(0) }} />
            <p id={searchHelpId} className="sp-muted">空白区切りですべての語を含む案を検索します。例: オオカミ 草原。検索は一覧だけに反映します。</p>
            <div className="sp-proposal-navigation" role="group" aria-label="配置案一覧のページ">
              <span role="status">{proposals.length ? `${number(currentProposalPage * proposalPageSize + 1)}–${number(Math.min((currentProposalPage + 1) * proposalPageSize, proposals.length))}` : '0'} / {number(proposals.length)} 件 · {currentProposalPage + 1} / {proposalPages} ページ</span>
              <button className="button" type="button" aria-controls={proposalListId} disabled={loading || planning || currentProposalPage === 0} onClick={() => navigateProposals(currentProposalPage - 1)}>前の案ページ</button>
              <button className="button" type="button" aria-controls={proposalListId} disabled={loading || planning || currentProposalPage + 1 >= proposalPages} onClick={() => navigateProposals(currentProposalPage + 1)}>次の案ページ</button>
              <button className="button sp-proposal-reveal" type="button" aria-controls={proposalListId} disabled={loading || planning || selectedRegionIndex < 0} onClick={revealProposal}>{selectedOutsideSearch ? '検索を解除して選択案を表示' : '選択中の案を一覧に表示'}</button>
            </div>
            {selectedOutsideSearch && <p className="sp-muted" role="status">選択中の案は検索条件外です。表示する操作で一覧の検索だけを解除します。</p>}
            <div id={proposalListId} ref={proposalList} className="sp-proposal-list" aria-label="配置案の一覧">{proposals.slice(currentProposalPage * proposalPageSize, (currentProposalPage + 1) * proposalPageSize).map(value => <button type="button" key={value.proposalId} data-proposal-id={value.proposalId} onClick={() => selectProposal(value.proposalId)} aria-pressed={selectedProposal === value.proposalId}><strong>Lv.{value.level} · {value.regionName}</strong><span>{value.spawnMobs.map(mob => stripMinecraftFormatting(mob.name)).join(' / ')}</span><small>X {value.x} / Y {value.y} / Z {value.z}{value.definitionStatus === 'draft' ? ' · 未登録案' : ''}</small></button>)}{proposals.length === 0 && plan && <p className="sp-muted">この条件の配置案はありません。未配置理由を確認してください。</p>}</div>
          </section>
          <section className="sp-notes"><h2>解析と適用範囲</h2><ul>{snapshot.manifest.limitations.map((note, index) => <li key={index}>{note}</li>)}</ul><p>配置案は解析用です。保存済み配置の編集は Dev のみです。共有チャンネルへの公開は別工程です。</p></section>
        </aside>}
    </div>
  </section>
}

import { useEffect, useMemo, useState } from 'react'
import { spawnerPlanningApi } from '../api/spawnerPlanningApi'
import { habitatNames, levelColor, reasonColor, planningRoad, planningLevelStepMeters } from '../data/spawnerPlanning'
import type { PlanningPlan, PlanningSnapshot } from '../data/spawnerPlanning'
import { MinecraftText } from './MinecraftText'
import { SpawnerPlanningMap } from './SpawnerPlanningMap'
import type { PlanningLayer } from './SpawnerPlanningMap'
import { WorldPlacementEditor, type PlacementCatalog, type PlacementPoint } from './WorldPlacementEditor'
import type { WorldPlacement } from '../api/worldPlacementApi'
import './spawnerPlanning.css'

const number = (value: number) => value.toLocaleString('ja-JP')
export function SpawnerPlanningWorkspace({ active, onDirtyChange }: { active: boolean; onDirtyChange?: (dirty: boolean) => void }) {
  const [snapshot, setSnapshot] = useState<PlanningSnapshot | null>(null)
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
  const [selectedCell, setSelectedCell] = useState<number | null>(null)
  const [selectedProposal, setSelectedProposal] = useState<string | null>(null)
  const [focus, setFocus] = useState<{ key: number; x: number; z: number } | null>(null)
  const [visibleCount, setVisibleCount] = useState(40)
  const [query, setQuery] = useState('')
  const [placements, setPlacements] = useState<WorldPlacement[]>([])
  const [placementCatalog, setPlacementCatalog] = useState<PlacementCatalog>({ spawners: [], npcs: [], worlds: [] })
  const [selectedPlacement, setSelectedPlacement] = useState<string | null>(null)
  const [placementSelectionKey, setPlacementSelectionKey] = useState(0)
  const [placing, setPlacing] = useState(false)
  const [moving, setMoving] = useState(false)
  const [placementBusy, setPlacementBusy] = useState(false)
  const [placementPoint, setPlacementPoint] = useState<PlacementPoint | null>(null)
  const [editing, setEditing] = useState(true)
  useEffect(() => {
    const controller = new AbortController()
    setLoading(true); setError(''); setPlan(null)
    spawnerPlanningApi.snapshot(controller.signal).then(value => {
      if (controller.signal.aborted) return
      setSnapshot(value); setRegion(''); setReason(''); setSelectedCell(null); setSelectedProposal(null)
      setRadius(current => value.manifest.radiusOptions.includes(current) ? current : value.manifest.radiusOptions[0])
    }).catch(failure => { if (!controller.signal.aborted) setError(failure instanceof Error ? failure.message : '解析結果を読み込めませんでした。') })
      .finally(() => { if (!controller.signal.aborted) setLoading(false) })
    return () => controller.abort()
  }, [reload])
  useEffect(() => {
    if (!snapshot || editing) return
    const controller = new AbortController()
    setPlanning(true); setPlan(null); setPlanningError(''); setSelectedCell(null); setSelectedProposal(null); setVisibleCount(40)
    spawnerPlanningApi.plan(snapshot, radius, controller.signal).then(value => { if (!controller.signal.aborted) setPlan(value) })
      .catch(failure => { if (!controller.signal.aborted) setPlanningError(failure instanceof Error ? failure.message : '配置案を読み込めませんでした。') })
      .finally(() => { if (!controller.signal.aborted) setPlanning(false) })
    return () => controller.abort()
  }, [snapshot, radius, editing])
  const regionById = useMemo(() => new Map(snapshot?.regions.map(value => [value.id, value]) ?? []), [snapshot])
  const selectedRegion = regionById.get(region)
  const road = snapshot ? planningRoad(snapshot.manifest) : null
  const levelStep = snapshot ? planningLevelStepMeters(snapshot.manifest) : null
  const regionProposals = useMemo(() => plan?.proposals.filter(value => !region || value.regionId === region) ?? [], [plan, region])
  const proposals = useMemo(() => {
    const term = query.trim().toLocaleLowerCase('ja-JP')
    return term ? regionProposals.filter(value => `${value.spawnerId} ${value.regionName} ${value.spawnMobs.map(mob => `${mob.name} ${mob.mobId}`).join(' ')} ${value.x} ${value.z}`.toLocaleLowerCase('ja-JP').includes(term)) : regionProposals
  }, [regionProposals, query])
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
  const coversSelected = useMemo(() => selectedCell === null ? [] : plan?.proposals.filter(value => value.coveredCellIndices.includes(selectedCell)) ?? [], [plan, selectedCell])
  function selectRegion(value: string) { setRegion(value); setReason(''); setSelectedCell(null); setSelectedProposal(null); setVisibleCount(40) }
  function selectProposal(id: string) {
    setSelectedProposal(id); setSelectedCell(null)
    const value = plan?.proposals.find(item => item.proposalId === id)
    if (value) setFocus(current => ({ key: (current?.key ?? 0) + 1, x: value.x + .5, z: value.z + .5 }))
  }
  function selectPlacement(id: string | null) {
    setSelectedPlacement(id)
    if (!id) return
    setPlacementSelectionKey(current => current + 1)
    setSelectedCell(null); setSelectedProposal(null)
    const item = placements.find(value => value.placementId === id)
    if (item) setFocus(current => ({ key: (current?.key ?? 0) + 1, x: item.x, z: item.z }))
  }
  function download() {
    if (!snapshot || !plan) return
    const file = new Blob([JSON.stringify({ ...plan, worldName: snapshot.manifest.worldName, progressionPolicy: snapshot.manifest.progressionPolicy, limitations: snapshot.manifest.limitations }, null, 2)], { type: 'application/json' })
    const url = URL.createObjectURL(file), anchor = document.createElement('a')
    anchor.href = url; anchor.download = `${snapshot.manifest.worldId}-preview-${radius}m.json`; anchor.click()
    setTimeout(() => URL.revokeObjectURL(url), 1000)
  }
  return <section className="sp-workspace" aria-label="ワールド配置・編集">
    <header className="sp-heading"><div><span className="sp-eyebrow">WORLD PLACEMENTS</span><h1>{snapshot?.manifest.worldName ?? 'エリヴァ超大陸'} <small>ワールド配置・編集</small></h1></div><div className="sp-header-actions"><button className="button" onClick={() => setReload(value => value + 1)} disabled={loading}>解析結果を再読込</button></div></header>
    <div className="sp-mode-switch" aria-label="表示モード"><button className="button" aria-pressed={editing} onClick={() => { setEditing(true); setSelectedProposal(null) }}>配置編集</button><button className="button" aria-pressed={!editing} onClick={() => { setEditing(false); setPlacing(false); setMoving(false) }}>AI 配置案</button></div>
    {!editing && <p className="sp-intro">{road ? `${levelStep ? `開始地点からの${road.terrainExtension ? '地形経路距離' : '道路距離'}${number(levelStep)}mごとにレベルを一段階上げます。` : '開始地点につながる道の距離でレベルを決めます。'}配置の上限はLv.45で、未到達の距離帯は配置しません。北東のLv.100超予定地を含む後続地域は未配置です。金色の線は16m区画で検出した道路網です。${road.terrainExtension ? '水色の点線は水域・斜面・足場を調べて延長した推定経路です。周辺のレベルも地表をたどる距離で決めています。実際の歩行可能性は未検証です。' : ''}` : '既存の配置を使わず、ブロック・バイオームとマスタの実レベルから作る全大陸の再配置案です。地域名と進行レベルは計画用の提案です。'}</p>}
    {error && <div className="sp-error" role="alert"><strong>解析結果を表示できません</strong><p>{error}</p><p>全大陸の解析と配置計算の出力一式を用意してから、再読込してください。</p></div>}
    {!editing && planningError && <div className="sp-error" role="alert"><strong>配置案を表示できません</strong><p>{planningError}</p></div>}
    {loading && <div className="sp-loading" role="status">全大陸の解析結果を読み込んでいます…</div>}
    {snapshot && <>
      {!editing && <div className="sp-controls">
        <label>表示レイヤー<select value={layer} onChange={event => setLayer(event.target.value as PlanningLayer)}><option value="terrain">地形と配置案</option><option value="levels">地域・推奨レベル</option><option value="reasons">配置・未配置の理由</option></select></label>
        <label>地域<select value={region} onChange={event => selectRegion(event.target.value)}><option value="">全大陸</option>{snapshot.regions.map(value => <option key={value.id} value={value.id}>{value.name} · {value.levelMin == null ? 'レベル未選定' : `Lv.${value.levelMin}〜${value.levelMax}`}</option>)}</select></label>
        <label>出現半径<select value={radius} onChange={event => setRadius(Number(event.target.value))}>{snapshot.manifest.radiusOptions.map(value => <option key={value} value={value}>{value}m</option>)}</select></label>
        <label>配置状況<select value={reason} onChange={event => setReason(event.target.value)}><option value="">すべて</option>{Object.entries(stats.counts).map(([code, value]) => <option key={code} value={code}>{snapshot.manifest.reasons[code] ?? code}（{number(value)}）</option>)}</select></label>
        <button className="button" onClick={() => { const spawn = snapshot.manifest.spawn; setFocus(value => ({ key: (value?.key ?? 0) + 1, x: spawn.x, z: spawn.z })) }}>開始地点</button>
        <label className="sp-check"><input type="checkbox" checked={radii} onChange={event => setRadii(event.target.checked)} />出現範囲を重ねる</label>
      </div>}
      {!editing && <div className="sp-metrics" aria-live="polite">
        <div><strong>{plan ? number(regionProposals.length) : '—'}</strong><span>{selectedRegion ? '選択地域' : '全大陸'}の配置案</span></div>
        <div><strong>{plan ? `${(region ? stats.target ? stats.covered / stats.target * 100 : 0 : plan.coveragePercent).toFixed(1)}%` : '—'}</strong><span>配置対象のカバー率</span></div>
        <div><strong>{plan ? number(region ? stats.uncovered : plan.uncoveredCells) : '—'}</strong><span>対象内の未カバー区画</span></div>
        <div><strong>{plan ? number(stats.counts.failed ?? stats.counts.unknown ?? 0) : '—'}</strong><span>未完成・解析不可区画</span></div>
        <div><strong>{plan ? number(stats.counts.absent ?? 0) : '—'}</strong><span>未保存の区画</span></div>
      </div>}
      <div className={`sp-main ${editing ? 'sp-main-editing' : ''}`}>
        <div className="sp-map-column">
          <WorldPlacementEditor editing={editing} snapshot={snapshot} point={placementPoint} selectedId={selectedPlacement} selectionKey={placementSelectionKey} onSelected={selectPlacement} onPlacements={(next, catalog) => { setPlacements(next); setPlacementCatalog(catalog) }} placing={placing} onPlacing={setPlacing} moving={moving} onMoving={setMoving} onBusyChange={setPlacementBusy} onDirtyChange={onDirtyChange ?? (() => {})} map={<>
            <SpawnerPlanningMap snapshot={snapshot} plan={plan} layer={editing ? 'terrain' : layer} region={editing ? '' : region} reason={editing ? '' : reason} radii={radii} active={active} selectedCell={editing ? null : selectedCell} selectedProposal={editing ? null : selectedProposal} showProposals={!editing} focus={focus} onCell={index => { setSelectedCell(index); setSelectedProposal(null) }} onProposal={selectProposal} placements={placements} placementCatalog={placementCatalog} selectedPlacement={selectedPlacement} onPlacement={id => { if (!editing) setEditing(true); selectPlacement(id) }} placing={(placing || moving) && !placementBusy} onPlacePoint={(x, y, z) => { if (!placementBusy) setPlacementPoint(current => ({ key: (current?.key ?? 0) + 1, x, y, z })) }} />
            <div className="sp-map-caption"><span><span className="sp-spawn-dot">●</span> 開始地点　金色実線: 道路 / 水色点線: 推定経路</span><span>{number(snapshot.manifest.bounds.width)} × {number(snapshot.manifest.bounds.height)}m · {snapshot.manifest.bounds.cellSize}m区画 · {number(snapshot.cells.length)}区画</span></div>
          </>} />
          {!editing && layer === 'levels' && <div className="sp-level-key">推奨レベル {[15, 20, 25, 30, 35, 40, 45].map(value => <span key={value}><i style={{ background: levelColor(value) }} />{value}</span>)}</div>}
          {!editing && <p className="sp-scope">カバー率の分母は、地形・足場・モブ条件を満たす配置対象です。全大陸すべてが配置可能という意味ではありません。区画の全256列を測定していますが、実際の移動経路や戦闘負荷は未検証です。</p>}
          {!editing && <section className="sp-region-section"><h2>地域ごとの計画</h2><p>地域を選ぶと地図と配置案の一覧を絞り込みます。</p><div className="sp-region-list">{snapshot.regions.map(value => <button key={value.id} className={`sp-region-row ${region === value.id ? 'selected' : ''}`} onClick={() => selectRegion(value.id)}><i style={{ background: levelColor(value.levelMin) }} /><span><strong>{value.name}</strong><small>{habitatNames[value.habitat] ?? value.habitat} · {number(value.cellCount)}区画</small></span><b>{value.levelMin == null ? '未選定' : `Lv.${value.levelMin}–${value.levelMax}`}</b><span>{number(value.coveredCellsByRadius?.[String(radius)] ?? 0)} / {number(value.eligibleCells)}<small>カバー / 対象</small></span></button>)}</div></section>}
        </div>
        {!editing && <aside className="sp-sidebar">
          {planning && <p role="status" className="sp-loading">半径{radius}mの配置案を読み込んでいます…</p>}
          <section><div className="sp-section-heading"><h2>地点・スポナーの詳細</h2><button className="button" disabled={!plan || planning} onClick={download}>案をJSON保存</button></div>
            {!cell && !proposal && <p className="sp-muted">地図の地点か、下の配置案を選択してください。全域表示では地点、拡大後はスポナーの点も選択できます。</p>}
            {cell && <div className="sp-detail"><strong>X {cell.x} / Z {cell.z}</strong><p className="sp-reason"><i style={{ background: reasonColor(cellCode) }} />{snapshot.manifest.reasons[cellCode] ?? cellCode}</p><p>{regionById.get(cell.regionId)?.name ?? '地域未判定'}<br />{habitatNames[cell.habitat] ?? cell.habitat} / 推奨 {cell.targetLevel == null ? '未定' : `Lv.${cell.targetLevel}`}</p><p>{cell.roadDistanceMeters != null && <>{road?.terrainExtension ? '地形経路距離' : '道に沿った距離'}: {number(Math.round(cell.roadDistanceMeters))}m<br /></>}{cell.distanceToRoadMeters != null && <>{road?.terrainExtension ? '道路・推定経路からの距離' : '道からの距離'}: {number(Math.round(cell.distanceToRoadMeters))}m<br /></>}安全な列: {cell.safeCount ?? 0} / 256<br />候補の足元Y: {cell.candidateY ?? '—'}<br />バイオーム: {cell.biome ?? '未読込'}<br />地表: {cell.surface ?? '未読込'}</p>{cell.reason && <p>{cell.reason}</p>}{coversSelected.map(value => <button className="sp-link-button" key={value.proposalId} onClick={() => selectProposal(value.proposalId)}>この区画をカバーする配置案を見る · Lv.{value.level}</button>)}</div>}
            {proposal && <div className="sp-detail"><strong>{proposal.regionName}</strong><p>X {proposal.x} / Y {proposal.y} / Z {proposal.z}<br />半径 {proposal.radiusMeters}m · Lv.{proposal.level}</p><span className={`sp-definition ${proposal.definitionStatus === 'draft' ? 'draft' : ''}`}>{proposal.definitionStatus === 'draft' ? '未登録のスポナーマスタ案' : '既存スポナーマスタを使用'}</span><ul>{proposal.spawnMobs.map(mob => <li key={`${mob.mobId}:${mob.level}`}><MinecraftText value={mob.name} /> <b>Lv.{mob.level}</b><small>出現重み {mob.weight}</small></li>)}</ul><p>{proposal.reason}</p><p>{number(proposal.newCoveredCells)}区画を新たにカバー</p>{proposal.originalRadiusMeters != null && <p>現在のマスタ半径: {proposal.originalRadiusMeters}m{proposal.masterUpdateRequired ? '（変更案）' : ''}</p>}<code>{proposal.spawnerId}</code></div>}
          </section>
          {selectedRegion && <section><h2>{selectedRegion.name}</h2><p className="sp-muted">{selectedRegion.reason}</p><button className="button" onClick={() => selectRegion('')}>全大陸へ戻す</button></section>}
          <section><h2>配置・未配置の理由</h2><div className="sp-reason-list">{Object.entries(stats.counts).sort((a, b) => b[1] - a[1]).map(([code, value]) => <button key={code} onClick={() => { setReason(code); setLayer('reasons') }} aria-pressed={reason === code}><span><i style={{ background: reasonColor(code) }} />{snapshot.manifest.reasons[code] ?? code}</span><b>{number(value)}</b></button>)}</div></section>
          <section><h2>配置案 <small>{number(proposals.length)}件</small></h2><input aria-label="配置案を検索" placeholder="モブ名・地域名・ID・座標" value={query} onChange={event => { setQuery(event.target.value); setVisibleCount(40) }} /><div className="sp-proposal-list">{proposals.slice(0, visibleCount).map(value => <button key={value.proposalId} onClick={() => selectProposal(value.proposalId)} aria-pressed={selectedProposal === value.proposalId}><strong>Lv.{value.level} · {value.regionName}</strong><span>{value.spawnMobs.map(mob => mob.name.replace(/[&§][0-9a-fk-or]/gi, '')).join(' / ')}</span><small>X {value.x} / Y {value.y} / Z {value.z}{value.definitionStatus === 'draft' ? ' · 未登録案' : ''}</small></button>)}{proposals.length === 0 && plan && <p className="sp-muted">この条件の配置案はありません。未配置理由を確認してください。</p>}</div>{visibleCount < proposals.length && <button className="button sp-more" onClick={() => setVisibleCount(value => value + 40)}>さらに40件表示</button>}</section>
          <section className="sp-notes"><h2>解析と適用範囲</h2><p>取得: {snapshot.manifest.capturedAt ? new Date(snapshot.manifest.capturedAt).toLocaleString('ja-JP', { timeZone: 'Asia/Tokyo' }) : '記録なし'} JST</p><ul>{snapshot.manifest.limitations.map((note, index) => <li key={index}>{note}</li>)}</ul><p>配置案は解析用です。保存済み配置の編集は Dev のみです。共有チャンネルへの公開は別工程です。</p></section>
        </aside>}
      </div>
    </>}
  </section>
}

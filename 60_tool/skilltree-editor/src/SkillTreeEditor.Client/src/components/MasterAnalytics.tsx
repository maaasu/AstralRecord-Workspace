import { useEffect, useMemo, useState } from 'react'
import { STATUS_TYPES } from '../data/statusTypes.generated'
import { asList, asObject, classBonus, downloadCsv, equipmentRows, formatNumber, formatRange, normalizeStatus, numeric, plainName } from '../data/masterAnalytics'
import type { AnalyticsCatalog, GrowthSimulation } from '../data/masterAnalytics'
import './masterAnalytics.css'

const colors = ['#65c7f7', '#f5ad6b', '#99d9a5', '#de9fe9', '#f0d278', '#fa8d99', '#8bd9d4', '#a9b6fa', '#dfb289', '#e2e8f0', '#8bb592', '#d6a9cb']
const statusLabel = (id: string) => STATUS_TYPES.find(status => status.id === id)?.displayName ?? id

async function read<T>(url: string, signal?: AbortSignal): Promise<T> {
  const response = await fetch(url, { signal })
  const payload = await response.json()
  if (!response.ok) throw new Error(payload.title ?? payload.message ?? `HTTP ${response.status}`)
  return payload as T
}

interface ChartSeries { name: string; values: { x: number; y: number }[] }
function LineChart({ series, yLabel, xLabel }: { series: ChartSeries[]; yLabel: string; xLabel: string }) {
  const width = 950, height = 360, left = 75, top = 25, right = 20, bottom = 50
  const all = series.flatMap(item => item.values)
  const minX = all.length ? Math.min(...all.map(point => point.x)) : 1
  const maxX = Math.max(minX + 1, ...all.map(point => point.x))
  const minY = Math.min(0, ...all.map(point => point.y)), maxY = Math.max(1, ...all.map(point => point.y))
  const x = (value: number) => left + (value - minX) / (maxX - minX) * (width - left - right)
  const y = (value: number) => top + (maxY - value) / (maxY - minY) * (height - top - bottom)
  return <div className="analytics-chart">
    <svg viewBox={`0 0 ${width} ${height}`} role="img" aria-label={`${xLabel}に対する${yLabel}の比較グラフ`}>
      <title>{`${xLabel}に対する${yLabel}。数値は下の表で確認できます。`}</title>
      {Array.from({ length: 6 }, (_, index) => {
        const value = minY + (maxY - minY) * index / 5
        return <g key={index}><line className="chart-grid" x1={left} x2={width - right} y1={y(value)} y2={y(value)} /><text x={left - 10} y={y(value) + 4} textAnchor="end">{formatNumber(value)}</text></g>
      })}
      {Array.from({ length: 6 }, (_, index) => {
        const value = minX + (maxX - minX) * index / 5
        return <text key={index} x={x(value)} y={height - 23} textAnchor="middle">{formatNumber(value)}</text>
      })}
      {series.map((item, index) => <path key={item.name} fill="none" stroke={colors[index % colors.length]} strokeWidth="2.4" d={item.values.map((point, pointIndex) => `${pointIndex === 0 ? 'M' : 'L'}${x(point.x)},${y(point.y)}`).join(' ')}><title>{item.name}</title></path>)}
      <text x={left} y={14}>{yLabel}</text><text x={width - right} y={height - 2} textAnchor="end">{xLabel}</text>
    </svg>
    <div className="chart-legend">{series.map((item, index) => <span key={item.name}><i style={{ background: colors[index % colors.length] }} />{item.name}</span>)}</div>
  </div>
}

export function MasterAnalytics() {
  const [catalog, setCatalog] = useState<AnalyticsCatalog | null>(null)
  const [error, setError] = useState('')
  const [revision, setRevision] = useState(0)
  const [mode, setMode] = useState<'equipment' | 'growth'>('equipment')
  const [query, setQuery] = useState('')
  const [slot, setSlot] = useState('')
  const [tag, setTag] = useState('')
  const [level, setLevel] = useState(10)
  const [band, setBand] = useState(10)
  const [enhance, setEnhance] = useState(0)
  const [stat, setStat] = useState('MELEE_ATTACK')
  const [selectedPaths, setSelectedPaths] = useState<string[]>([])
  const [page, setPage] = useState(0)
  const [uuid, setUuid] = useState('00000000-0000-0000-0000-000000000001')
  const [startPlayer, setStartPlayer] = useState(1)
  const [startClass, setStartClass] = useState(1)
  const [maximum, setMaximum] = useState(100)
  const [classIds, setClassIds] = useState<string[]>(['adventurer', 'swordsman', 'hunter', 'mage'])
  const [simulation, setSimulation] = useState<GrowthSimulation | null>(null)
  const [growing, setGrowing] = useState(false)
  const [growthStat, setGrowthStat] = useState('MAX_HEALTH')
  const [growthView, setGrowthView] = useState<'level' | 'experience' | 'bonus'>('level')
  const [inspect, setInspect] = useState(10)
  useEffect(() => {
    const controller = new AbortController()
    setError('')
    void read<AnalyticsCatalog>('/api/master-analytics/catalog', controller.signal).then(setCatalog).catch(reason => { if (!controller.signal.aborted) setError(String(reason)) })
    return () => controller.abort()
  }, [revision])
  const rows = useMemo(() => equipmentRows(catalog?.equipment ?? [], enhance), [catalog, enhance])
  const filtered = rows.filter(row => Math.abs(row.level - level) <= band && (!slot || row.slot === slot) && (!tag || row.tag === tag) && `${row.name} ${row.id} ${row.path}`.toLowerCase().includes(query.toLowerCase())).sort((a, b) => a.level - b.level || a.id.localeCompare(b.id))
  const active = selectedPaths.length ? filtered.filter(row => selectedPaths.includes(row.path)) : filtered
  const pageCount = Math.max(1, Math.ceil(filtered.length / 50))
  const currentPage = Math.min(page, pageCount - 1)
  const visibleRows = filtered.slice(currentPage * 50, (currentPage + 1) * 50)
  const statuses = [...new Set(active.flatMap(row => Object.keys(row.stats)))].sort()
  const scale = Math.max(1, ...active.map(row => Math.max(Math.abs(row.stats[stat]?.min ?? 0), Math.abs(row.stats[stat]?.max ?? 0))))
  const growthStatuses = [...new Set((catalog?.classes ?? []).flatMap(master => [...asList(master.content.baseStats), ...asList(master.content.growthPerLevel)].map(asObject).map(entry => normalizeStatus(entry.status))))].sort()

  async function generate() {
    setGrowing(true); setError('')
    try {
      const params = new URLSearchParams({ uuid, maxPlayerLevel: String(maximum), startPlayerLevel: String(startPlayer), startClassLevel: String(startClass), classIds: classIds.join(',') })
      const result = await read<GrowthSimulation>(`/api/master-analytics/growth?${params}`)
      setSimulation(result); setInspect(Math.max(startPlayer, Math.min(maximum, inspect)))
    } catch (reason) { setError(String(reason)) } finally { setGrowing(false) }
  }
  const point = simulation?.points.find(value => value.playerLevel === inspect) ?? simulation?.points[0]
  const graph = simulation?.classes.map(definition => ({
    name: plainName(definition.name),
    values: simulation.points.map(item => {
      const current = item.classes.find(value => value.id === definition.id)!
      const master = catalog?.classes.find(value => value.content.id === definition.id)
      return { x: item.playerLevel, y: growthView === 'level' ? current.level : growthView === 'experience' ? current.totalExperience : master ? classBonus(master, growthStat, current.level) : 0 }
    }),
  })) ?? []

  if (!catalog) return <section className="master-analytics"><h1>比較・成長グラフ</h1><p role={error ? 'alert' : 'status'}>{error || 'マスターを読み込んでいます…'}</p><button className="button" onClick={() => setRevision(value => value + 1)}>再読込</button></section>
  return <section className="master-analytics">
    <header className="analytics-header"><div><h1>比較・成長グラフ</h1><p>保存済みのFilebaseから計算。変更後は再読込してください。</p></div><button className="button" onClick={() => { setRevision(value => value + 1); setSimulation(null) }}>マスター再読込</button></header>
    <div role="tablist" aria-label="分析の種類" className="analytics-tabs"><button role="tab" aria-selected={mode === 'equipment'} className="button" onClick={() => setMode('equipment')}>装備を比較</button><button role="tab" aria-selected={mode === 'growth'} className="button" onClick={() => setMode('growth')}>クラス成長</button></div>
    {error && <p role="alert" className="analytics-error">{error}</p>}
    {catalog.diagnostics.length > 0 && <details><summary>読み込めないマスター {catalog.diagnostics.length}件</summary>{catalog.diagnostics.map(item => <p key={item.path}>{item.path}: {item.message}</p>)}</details>}
    {mode === 'equipment' ? <>
      <div className="analytics-controls">
        <label>名前・ID<input value={query} onChange={event => setQuery(event.target.value)} placeholder="ノクス、20a00002…" /></label>
        <label>スロット<select value={slot} onChange={event => setSlot(event.target.value)}><option value="">すべて</option>{[...new Set(rows.map(row => row.slot))].sort().map(value => <option key={value}>{value}</option>)}</select></label>
        <label>装備タグ<select value={tag} onChange={event => setTag(event.target.value)}><option value="">すべて</option>{[...new Set(rows.map(row => row.tag))].sort().map(value => <option key={value}>{value}</option>)}</select></label>
        <label>要求Lvの中心<input type="number" min="0" value={level} onChange={event => setLevel(Math.max(0, numeric(event.target.value)))} /></label>
        <label>前後のLv幅<input type="number" min="0" value={band} onChange={event => setBand(Math.max(0, numeric(event.target.value)))} /></label>
        <label>強化Lv<input type="number" min="0" max="100" value={enhance} onChange={event => setEnhance(Math.max(0, Math.min(100, numeric(event.target.value))))} /></label>
        <label>グラフのステータス<select value={stat} onChange={event => setStat(event.target.value)}>{[...new Set([stat, ...statuses])].map(value => <option key={value} value={value}>{statusLabel(value)} ({value})</option>)}</select></label>
      </div>
      <p className="analytics-note">マスターの下限〜上限を比較します。強化は上限Lvまでの差分を累積し、同じ装備のFLAT合計 × 最後に採用されるSCALARを計算します。強化SCALARがある場合は基礎SCALARを置き換えます。SCALARだけのステータスは効果を持ちません。個体の乱数・エンチャント・ルーン・セット・超越・装備可否による無効化は含みません。</p>
      <div className="analytics-actions"><span>{active.length}件を比較 / 条件一致 {filtered.length}件</span><button className="button" onClick={() => setSelectedPaths([])}>選択を解除</button><button className="button" onClick={() => downloadCsv('equipment-comparison.csv', [['ID', '名前', 'スロット', 'タグ', '要求Lv', '進行度', ...statuses.flatMap(id => [`${id} 下限`, `${id} 上限`])], ...active.map(row => [row.id, row.name, row.slot, row.tag, row.level, row.progression, ...statuses.flatMap(id => [row.stats[id]?.min, row.stats[id]?.max])])])}>比較表をCSV出力</button></div>
      <p className="analytics-note">バーは比較対象の先頭30件を表示します。表は50件ずつ表示し、CSVには比較対象すべてを出力します。</p>
      <div className="equipment-bars" aria-label={`${statusLabel(stat)}の範囲比較`}>{active.filter(row => row.stats[stat]).slice(0, 30).map(row => <div key={row.path} className="equipment-bar"><span title={row.id}>{row.name}</span><div><i style={{ width: `${Math.max(Math.abs(row.stats[stat].min), Math.abs(row.stats[stat].max)) / scale * 100}%` }} /></div><strong>{formatRange(row.stats[stat])}</strong></div>)}</div>
      <div className="analytics-table-wrap"><table><caption>装備の横並び比較。チェックした装備だけに絞り込めます。</caption><thead><tr><th>選択</th><th>装備 / ID</th><th>スロット / タグ</th><th>要求Lv / 進行度</th><th>必要職</th>{statuses.map(id => <th title={id} key={id}>{statusLabel(id)}</th>)}</tr></thead><tbody>{visibleRows.map(row => <tr key={row.path} className={selectedPaths.includes(row.path) ? 'selected' : ''}><td><input type="checkbox" aria-label={`${row.name}を比較に選択`} checked={selectedPaths.includes(row.path)} onChange={event => setSelectedPaths(current => event.target.checked ? [...current, row.path] : current.filter(path => path !== row.path))} /></td><th scope="row"><span>{row.name}</span><small>{row.id} · {row.rarity}</small><details><summary>定義を確認</summary><code>{row.path}</code>{row.rawStats.map((definition, index) => <div key={index}>{definition}</div>)}</details></th><td>{row.slot}<small>{row.tag} · {row.hand}</small></td><td>{row.level}<small>進行度 {row.progression ?? '未定義'}</small></td><td>{row.requiredClasses || '制限なし'}</td>{statuses.map(id => <td key={id}>{formatRange(row.stats[id])}</td>)}</tr>)}</tbody></table>{!filtered.length && <p>条件に一致する装備がありません。レベル幅やスロットを変更してください。</p>}</div>
      <div className="analytics-actions"><button className="button" disabled={currentPage === 0} onClick={() => setPage(currentPage - 1)}>前の50件</button><span>{currentPage + 1} / {pageCount}ページ</span><button className="button" disabled={currentPage + 1 >= pageCount} onClick={() => setPage(currentPage + 1)}>次の50件</button></div>
    </> : <>
      <div className="analytics-controls">
        <label>アカウントUUID<input value={uuid} onChange={event => setUuid(event.target.value)} className="uuid-input" /></label>
        <label>開始プレイヤーLv<input type="number" min="1" max="200" value={startPlayer} onChange={event => setStartPlayer(numeric(event.target.value, 1))} /></label>
        <label>開始クラスLv<input type="number" min="1" max="1000" value={startClass} onChange={event => setStartClass(numeric(event.target.value, 1))} /></label>
        <label>表示上限プレイヤーLv<input type="number" min="1" max="200" value={maximum} onChange={event => setMaximum(numeric(event.target.value, 100))} /></label>
      </div>
      <fieldset className="class-selection"><legend>比較する職業（最大12件）</legend>{catalog.classes.map(master => { const id = String(master.content.id); return <label key={master.path} title={`解放プレイヤーLv ${numeric(master.content.unlockLevel, 1)} / expRate ${numeric(master.content.expRate, 100)} / maxLevel ${numeric(master.content.maxLevel, 100)}`}><input type="checkbox" checked={classIds.includes(id)} disabled={!classIds.includes(id) && classIds.length >= 12} onChange={event => setClassIds(current => event.target.checked ? [...current, id] : current.filter(value => value !== id))} />{plainName(master.content.name)}<small>{id}</small></label> })}</fieldset>
      <button className="button primary" disabled={growing || !classIds.length} onClick={() => void generate()}>{growing ? '計算中…' : 'グラフを計算'}</button>
      <p className="analytics-note">入力変更後は「グラフを計算」で反映します。開始Lvは到達直後として計算します。通常育成・転生なし、同じEXPをプレイヤーと1職へ加算する仮定です。各職の曲線はそれぞれ独立した育成シナリオです。</p>
      {simulation && <>
        <div className="analytics-controls"><label>グラフの種類<select value={growthView} onChange={event => setGrowthView(event.target.value as typeof growthView)}><option value="level">プレイヤーLv → クラスLv</option><option value="experience">プレイヤーLv → クラス累積EXP</option><option value="bonus">プレイヤーLv → クラスのステータス補正</option></select></label>{growthView === 'bonus' && <label>ステータス<select value={growthStat} onChange={event => setGrowthStat(event.target.value)}>{[...new Set([growthStat, ...growthStatuses])].map(id => <option key={id} value={id}>{statusLabel(id)} ({id})</option>)}</select></label>}</div>
        <LineChart series={graph} xLabel="プレイヤーLv" yLabel={growthView === 'level' ? 'クラスLv' : growthView === 'experience' ? 'クラス累積EXP' : `${statusLabel(growthStat)}（クラス補正のみ）`} />
        <label className="inspect-level">確認するプレイヤーLv: {inspect}<input type="range" min={simulation.startPlayerLevel} max={simulation.points.at(-1)?.playerLevel} step="1" value={inspect} onChange={event => setInspect(Number(event.target.value))} /></label>
        {point && <div className="analytics-table-wrap"><table><caption>プレイヤーLv {point.playerLevel} · 累積EXP {formatNumber(point.playerTotalExperience)}</caption><thead><tr><th>職業</th><th>クラスLv / 上限</th><th>累積クラスEXP</th><th>次Lvまで</th><th>expRate</th><th>{statusLabel(growthStat)}補正</th><th>1Lvの成長</th></tr></thead><tbody>{simulation.classes.map(definition => { const value = point.classes.find(item => item.id === definition.id)!; const master = catalog.classes.find(item => item.content.id === definition.id); return <tr key={definition.id}><th scope="row">{plainName(definition.name)}<small>{definition.id}</small></th><td>{value.level} / {definition.maxLevel}</td><td>{formatNumber(value.totalExperience)}</td><td>{value.level === definition.maxLevel ? 'MAX' : formatNumber(value.nextLevelExperience)}</td><td>{definition.expRate}</td><td>{master ? formatNumber(classBonus(master, growthStat, value.level)) : '—'}</td><td>{master ? formatNumber(numeric(asList(master.content.growthPerLevel).map(asObject).find(stat => normalizeStatus(stat.status) === growthStat)?.value)) : '—'}</td></tr> })}</tbody></table></div>}
        <button className="button" onClick={() => downloadCsv('class-growth.csv', [['プレイヤーLv', '累積プレイヤーEXP', ...simulation.classes.map(definition => `${definition.id} クラスLv`)], ...simulation.points.map(item => [item.playerLevel, item.playerTotalExperience, ...simulation.classes.map(definition => item.classes.find(value => value.id === definition.id)?.level)])])}>成長表をCSV出力</button>
        <details className="analytics-sources"><summary>計算の根拠と前提</summary><p>{simulation.assumption}</p><p>プレイヤー必要EXP = 500 + Lv²×120 + floor(Lv/10)×850 + 8段階wave + 5Lvごとの加算 + UUID由来の加算。クラス必要EXP = round((45 + Lv²×8 + 10Lvごとの加算) × max(expRate,10)/100)。クラス補正 = baseStats + growthPerLevel × (クラスLv−1)。</p>{simulation.sources.map(source => <code key={source}>{source}</code>)}<p>アカウントUUID: {simulation.accountUuid}。EXPERIENCE_GAIN_RATEは両方へ加算する前に適用されるため、この累積EXP対レベルの比較には影響しません。</p></details>
      </>}
    </>}
  </section>
}

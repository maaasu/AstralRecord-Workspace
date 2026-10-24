import { useEffect, useMemo, useRef, useState } from 'react'
import { BalanceApiError, cancelBalanceJob, getBalanceCatalog, getBalanceJob, startBalanceJob } from '../api/balanceLabApi'
import { asRecord, classifyMasters, clearScenario, conditionKey, createScenario, downloadText, loadScenario, masterId, masterName, resultCsv, saveScenario, skillCatalogForMasters, skillIsClassCandidate, summaryCsv, validateScenario } from '../data/balanceLab'
import type { BalanceBuild, BalanceCatalog, BalanceEconomy, BalanceEconomyResult, BalanceJob, BalanceMaster, BalanceResultRow, BalanceScenario, BalanceSkillCatalogEntry, BalanceTrial } from '../data/balanceLab'
import { MinecraftText } from './MinecraftText'
import { BalanceRuntimeTrace } from './BalanceRuntimeTrace'
import { BalanceRuntimeConditions } from './BalanceRuntimeConditions'
import './balanceLab.css'

const number = (value: string, fallback = 0) => Number.isFinite(Number(value)) ? Number(value) : fallback
const fmt = (value: unknown) => typeof value === 'number' && Number.isFinite(value) ? new Intl.NumberFormat('ja-JP', { maximumFractionDigits: 2 }).format(value) : '—'
const outcomeName: Record<BalanceTrial['outcome'], string> = { victory: '討伐', death: '死亡', timeout: '未討伐', invalid: '無効', unsupported: '未対応' }
const defaultEconomy = (): BalanceEconomy => ({ targetItemId: '', quantity: 1, enhanceItemId: '', fromEnhanceLevel: 0, targetEnhanceLevel: 0, sellItemIds: [], moveSeconds: 0, waitSeconds: 0, resupplySeconds: 0 })
const goldText = (value: unknown) => value == null ? '未確定' : `${fmt(value)}G`
const metricName: Record<string, string> = { meanDps: '平均DPS', medianDps: 'DPS中央値', stdDevDps: 'DPS標準偏差', successRate: '討伐率', meanElapsedSeconds: '平均経過（秒）', meanDamageDealt: '平均与ダメージ', meanDamageTaken: '平均被ダメージ' }
const statusName: Record<string, string> = { completed: '計算完了', unsupported: '未対応', invalid: '入力無効', unreachable: '到達不能', victory: '討伐', death: '死亡', timeout: '時間切れ' }
const costName: Record<string, string> = { orbProcurement: 'オーブ調達', equipmentProcurement: '装備調達' }
const jobKey = 'astralrecord.balance-lab.active-job.v1'

function MasterSelect({ label, masters, value, onChange, empty = '選択してください' }: { label: string; masters: BalanceMaster[]; value: string; onChange: (value: string) => void; empty?: string }) {
  return <label>{label}<select value={value} onChange={event => onChange(event.target.value)}><option value="">{empty}</option>{masters.map(master => <option key={master.path} value={masterId(master)}>{masterName(master).replace(/(?:&|§)[0-9a-fk-or]/gi, '')} ({masterId(master)})</option>)}</select></label>
}

function NumberInput({ label, value, min, max, step, onChange }: { label: string; value: number; min?: number; max?: number; step?: number; onChange: (value: number) => void }) {
  return <label>{label}<input type="number" value={value} min={min} max={max} step={step ?? 1} onChange={event => onChange(number(event.target.value))} /></label>
}

function SkillPicker({ build, classMaster, masters, entries, onChange }: { build: BalanceBuild; classMaster?: BalanceMaster; masters: BalanceMaster[]; entries: BalanceSkillCatalogEntry[]; onChange: (skillIds: string[], passiveSkillIds?: string[]) => void }) {
  const [query, setQuery] = useState('')
  const [classOnly, setClassOnly] = useState(false)
  const [supportedOnly, setSupportedOnly] = useState(false)
  const byId = new Map(masters.map(master => [masterId(master), master]))
  const byPath = new Map(masters.map(master => [master.path, master]))
  const search = query.trim().toLocaleLowerCase()
  const visible = entries.filter(entry => {
    const master = byPath.get(entry.path)
    return (!classOnly || skillIsClassCandidate(entry, classMaster)) && (!supportedOnly || entry.supported)
      && (!search || `${entry.id} ${master ? masterName(master) : entry.name} ${entry.implementationId} ${entry.path}`.toLocaleLowerCase().includes(search))
  })
  const supportedCount = entries.filter(entry => entry.supported).length
  const move = (index: number, direction: -1 | 1) => {
    const next = [...build.skillIds]
    ;[next[index], next[index + direction]] = [next[index + direction], next[index]]
    onChange(next)
  }
  return <div className="balance-subpanel balance-skill-picker"><h5>スキル</h5>
    <p className="balance-muted">全マスターから選べます。職業の潜在候補はクラス定義とスキルツリー上の関連を示し、習得済みや装備可能を保証しません。アクティブの選択順は使用優先順です。パッシブは装着して発動条件を満たすと働きます。計算条件は単体・スキルLv1・シジル未装着です。</p>
    <p className="balance-skill-counts">全 {entries.length}件 · 計算対応 {supportedCount}件 · 未対応 {entries.length - supportedCount}件</p>
    <div className="balance-skill-controls"><label>スキル検索<input type="search" value={query} onChange={event => setQuery(event.target.value)} placeholder="名前・IDで検索" /></label><label className="balance-filter-toggle"><input type="checkbox" checked={classOnly} onChange={event => setClassOnly(event.target.checked)} />職業の潜在候補のみ</label><label className="balance-filter-toggle"><input type="checkbox" checked={supportedOnly} onChange={event => setSupportedOnly(event.target.checked)} />計算対応のみ</label></div>
    {build.skillIds.length > 0 && <div className="balance-selected-skills"><strong>アクティブ・優先順</strong><ol>{build.skillIds.map((id, index) => <li key={`${id}:${index}`}><span className="balance-selected-name"><MinecraftText value={byId.get(id) ? masterName(byId.get(id)!) : id} /><small>{id}</small></span><div className="balance-skill-order"><button type="button" className="button compact" aria-label={`${id}の優先順位を上げる`} disabled={index === 0} onClick={() => move(index, -1)}>↑</button><button type="button" className="button compact" aria-label={`${id}の優先順位を下げる`} disabled={index === build.skillIds.length - 1} onClick={() => move(index, 1)}>↓</button></div></li>)}</ol></div>}
    <p className="balance-muted">パッシブ装着：{build.passiveSkillIds?.length ?? 0}件</p>
    <p className="balance-muted">表示 {visible.length} / 全 {entries.length} 件</p>
    <div className="balance-checks balance-skill-checks">{visible.map(entry => { const master = byPath.get(entry.path); const candidate = skillIsClassCandidate(entry, classMaster); const links = entry.classLinks.filter(link => link.classId === build.classId); return <div key={entry.path} className="balance-skill-row"><label className="balance-check-item"><input type="checkbox" checked={(entry.kind === 'passive' ? build.passiveSkillIds ?? [] : build.skillIds).includes(entry.id)} onChange={event => { const current = entry.kind === 'passive' ? build.passiveSkillIds ?? [] : build.skillIds; const next = event.target.checked ? [...current, entry.id] : current.filter(value => value !== entry.id); if (entry.kind === 'passive') onChange(build.skillIds, next); else onChange(next, build.passiveSkillIds) }} /><span className="balance-check-name"><MinecraftText value={master ? masterName(master) : entry.name} /><small>{entry.id}</small></span></label><div className="balance-skill-meta"><span className={entry.supported ? 'balance-skill-supported' : 'balance-skill-unsupported'}>{entry.supported ? (entry.requirements?.length ? '条件付き対応' : '計算対応') : '計算未対応'} · {entry.kind === 'passive' ? 'パッシブ' : 'アクティブ'}</span>{candidate && <span>職業の潜在候補{links.some(link => link.kind === 'node') ? ' · スキルツリー' : ''}</span>}</div>{Boolean(entry.requirements?.length) && <p className="balance-muted">追加条件：{entry.requirements?.map(value => ({ bow: '弓装備', velocity_replay: '速度変更後の座標軌跡', party: '味方の入力', static_only: '静止条件のみ' })[value] ?? value).join('、')}</p>}{entry.reasons.length > 0 && <details className="balance-skill-details"><summary>未対応理由 {entry.reasons.length}件</summary>{entry.reasons.map((reason, index) => <p key={index}>{reason}</p>)}</details>}{entry.assumptions.length > 0 && <details className="balance-skill-details"><summary>計算の前提</summary>{entry.assumptions.map((assumption, index) => <p key={index}>{assumption}</p>)}</details>}{entry.sources.length > 0 && <details className="balance-skill-details"><summary>参照ソース</summary>{entry.sources.map(source => <p key={source}><code>{source}</code></p>)}</details>}</div> })}</div>
    {!visible.length && <p className="balance-muted">条件に合うスキルがありません。検索語や候補の絞り込みを変更してください。</p>}
  </div>
}

function SellItemsPicker({ masters, selected, onChange }: { masters: BalanceMaster[]; selected: string[]; onChange: (ids: string[]) => void }) {
  const [query, setQuery] = useState('')
  const search = query.trim().toLocaleLowerCase()
  const visible = masters.filter(master => !search || `${masterId(master)} ${masterName(master)} ${master.path}`.toLocaleLowerCase().includes(search))
  return <details className="balance-subpanel balance-sell-picker"><summary>売却アイテム（{selected.length}件選択）</summary>
    <label className="balance-search">売却アイテム検索<input type="search" value={query} onChange={event => setQuery(event.target.value)} placeholder="名前・IDで検索" /></label>
    <p className="balance-muted">候補 {visible.length} / 全 {masters.length} 件</p>
    <div className="balance-checks">{visible.map(master => { const id = masterId(master); return <label key={master.path} className="balance-check-item"><input type="checkbox" checked={selected.includes(id)} onChange={event => onChange(event.target.checked ? [...selected, id] : selected.filter(value => value !== id))} /><span className="balance-check-name"><MinecraftText value={masterName(master)} /><small>{id}</small></span></label> })}</div>
    {!visible.length && <p className="balance-muted">条件に合うアイテムがありません。</p>}
  </details>
}

function ResourceChart({ trial }: { trial: BalanceTrial }) {
  const points = trial.resources ?? []
  if (!points.length) return <p className="balance-muted">この試行のリソース記録はありません。詳細グラフは各案の代表第1試行を表示します。</p>
  const width = 760, height = 250, pad = 32
  const maxTick = Math.max(1, ...points.map(point => point.tick))
  const maxValue = Math.max(1, ...points.flatMap(point => [point.hp, point.mp, point.eng, point.shield]))
  const series = [{ key: 'hp', label: 'HP', color: '#f58b8b' }, { key: 'mp', label: 'MP', color: '#69bdfa' }, { key: 'eng', label: 'ENG', color: '#f2c96c' }, { key: 'shield', label: 'シールド', color: '#a7d99b' }] as const
  return <div className="balance-resource"><svg role="img" aria-label="時間ごとのHP、MP、ENG、シールド" viewBox={`0 0 ${width} ${height}`}><line x1={pad} x2={width - pad} y1={height - pad} y2={height - pad} stroke="#566a7e" /><line x1={pad} x2={pad} y1={pad} y2={height - pad} stroke="#566a7e" />{series.map(item => <polyline key={item.key} fill="none" stroke={item.color} strokeWidth="2.3" points={points.map(point => `${pad + point.tick / maxTick * (width - pad * 2)},${height - pad - Math.max(0, point[item.key]) / maxValue * (height - pad * 2)}`).join(' ')} />)}<text x={pad} y={height - 5}>0秒</text><text x={width - pad} y={height - 5} textAnchor="end">{fmt(maxTick / 20)}秒</text><text x={pad + 4} y={pad + 12}>最大 {fmt(maxValue)}</text></svg><div className="balance-chart-legend">{series.map(item => <span key={item.key}><i style={{ background: item.color }} />{item.label}</span>)}</div></div>
}

function EconomyView({ economy }: { economy: BalanceEconomyResult }) {
  const collection = economy.collection, enhancement = economy.enhancement, growth = economy.growth, gold = economy.gold, time = economy.time
  const statusName = { completed: '計算完了', unsupported: '未対応', unreachable: '到達不能', invalid: '入力無効' }[economy.status] ?? economy.status
  return <details><summary>収集・強化・成長・金策・所要時間（{statusName}）</summary>
    {economy.diagnostics?.map((item, index) => <p key={index} className="balance-warning"><strong>{item.severity}</strong> {item.code}: {item.message}</p>)}
    <div className="balance-economy-grid">
      {collection && <section><h4>収集</h4><dl><dt>対象 / 必要数</dt><dd>{collection.itemId} / {fmt(collection.quantity)}個</dd><dt>1戦闘あたり</dt><dd>{fmt(collection.expectedItemsPerKill)}個</dd><dt>期待戦闘数</dt><dd>{fmt(collection.expectedKills)}戦</dd><dt>中央値 / 90%点</dt><dd>{fmt(collection.medianKills)} / {fmt(collection.p90Kills)}戦</dd>{collection.probabilityWithinBudget != null && <><dt>予算内到達確率</dt><dd>{fmt(collection.probabilityWithinBudget * 100)}%</dd></>}</dl><small>戦闘数には失敗・死亡も含みます。算出: {collection.method}</small></section>}
      {enhancement && <section><h4>強化</h4><dl><dt>装備 / 目標Lv</dt><dd>{enhancement.itemId} / {fmt(enhancement.fromLevel)}→{fmt(enhancement.targetLevel)}</dd><dt>期待試行</dt><dd>{fmt(enhancement.expectedAttempts)}回</dd><dt>中央値 / 90%点</dt><dd>{fmt(enhancement.medianAttempts)} / {fmt(enhancement.p90Attempts)}回</dd><dt>必要オーブ期待値</dt><dd>{fmt(enhancement.expectedOrbs)}個</dd><dt>強化費用期待値</dt><dd>{goldText(enhancement.expectedGold)}</dd>{enhancement.orbStockCompletionProbability != null && <><dt>収集分で強化完了</dt><dd>{fmt(enhancement.orbStockCompletionProbability * 100)}%</dd></>}</dl>{enhancement.estimatedKillsForExpectedOrbs != null && <p className="balance-muted">必要オーブ期待数は {fmt(enhancement.estimatedKillsForExpectedOrbs)}戦相当の目安です。{collection?.itemId === enhancement.orbItemId ? '収集目標の時間に含まれますが、強化成功までの全調達を保証しません。' : '別途調達する時間は合計に含みません。'}</p>}</section>}
      {growth && <section><h4>成長</h4><dl><dt>1戦闘あたり期待EXP</dt><dd>{fmt(growth.expectedExperiencePerKill)}</dd><dt>期待獲得EXP</dt><dd>{fmt(growth.expectedExperience)}</dd>{growth.expectedKillsToPlayerLevel != null && <><dt>目標プレイヤーLvまで</dt><dd>{fmt(growth.expectedKillsToPlayerLevel)}戦</dd></>}{growth.expectedKillsToClassLevel != null && <><dt>目標クラスLvまで</dt><dd>{fmt(growth.expectedKillsToClassLevel)}戦</dd></>}</dl>{growth.classPath && <details><summary>転職経路</summary><p>{growth.classPath.currentClassId} → {growth.classPath.targetClassId} · 解放Lv {fmt(growth.classPath.unlockPlayerLevel)} · 現在選択 {growth.classPath.canSelectNow ? '可' : '不可'}</p>{growth.classPath.prerequisites?.length ? <p>前提: {growth.classPath.prerequisites.map(item => `${item.classId} Lv.${item.requiredLevel}（現在${item.actualLevel}）`).join('、')}</p> : null}{growth.classPath.adminChangeOnly && <p>管理者変更のみ</p>}{growth.classPath.commandOnly && <p>コマンド変更のみ</p>}</details>}</section>}
      {gold && <section><h4>お金</h4><dl><dt>収入</dt><dd>{fmt(gold.income)}G</dd><dt>支出</dt><dd>{goldText(gold.expense)}</dd><dt>差引</dt><dd>{goldText(gold.net)}</dd></dl><p className="balance-muted">{gold.scope === 'directGoldOnly' ? '直接得られるゴールドのみ' : gold.scope ?? ''} · {gold.completeness === 'partial' ? '一部推定' : '推定'}</p>{gold.excludedCosts?.length ? <p className="balance-warning">未算入費用: {gold.excludedCosts.map(value => costName[value] ?? value).join('、')}</p> : null}{gold.lines?.length ? <details><summary>内訳</summary><pre>{JSON.stringify(gold.lines, null, 2)}</pre></details> : null}</section>}
      {time && <section><h4>所要時間</h4><dl><dt>戦闘</dt><dd>{fmt(time.combatSeconds)}秒</dd><dt>移動</dt><dd>{fmt(time.moveSeconds)}秒</dd><dt>待機</dt><dd>{fmt(time.waitSeconds)}秒</dd><dt>補給</dt><dd>{fmt(time.resupplySeconds)}秒</dd><dt>合計</dt><dd>{fmt(time.totalSeconds)}秒</dd></dl></section>}
    </div>
    {economy.assumptions?.length ? <details><summary>経済計算の前提</summary>{economy.assumptions.map((item, index) => <p key={index}>{item}</p>)}</details> : null}
    {economy.sources?.length ? <details><summary>経済計算の参照</summary>{economy.sources.map((item, index) => <code key={index}>{item}</code>)}</details> : null}
  </details>
}

function ResultCard({ row }: { row: BalanceResultRow }) {
  const [trialIndex, setTrialIndex] = useState(0)
  const trials = row.trials ?? []
  const trial = trials[Math.min(trialIndex, trials.length - 1)]
  const summary = row.summary
  const contributions = [...(trial?.contributions ?? [])].sort((a, b) => b.damage - a.damage)
  return <article className="balance-result-card"><header><h3><MinecraftText value={row.buildName} /> <small>{row.variant === 'proposal' ? '調整案' : '現行'}</small></h3><span>{statusName[row.status] ?? row.status}</span></header>
    {row.diagnostics?.length ? <ul className="balance-diagnostics">{row.diagnostics.map((item, index) => <li key={index}><strong>{item.severity}</strong> {item.code}: {item.message}</li>)}</ul> : null}
    {summary && <><div className="balance-metrics"><div><strong>{summary.successRate == null ? '計算不能' : `${fmt(summary.successRate * 100)}%`}</strong><span>討伐率</span></div><div><strong>{fmt(summary.meanDps)}</strong><span>平均DPS</span></div><div><strong>{fmt(summary.meanElapsedSeconds)}秒</strong><span>平均経過</span></div><div><strong>{fmt(summary.p90ElapsedSeconds)}秒</strong><span>90%経過</span></div></div><p className="balance-muted">全 {summary.totalTrials}回 · 有効 {summary.validTrials ?? summary.totalTrials - (summary.invalid ?? 0) - (summary.unsupported ?? 0)} · 討伐 {summary.victories} · 死亡 {summary.deaths} · 未討伐 {summary.timeouts} · 無効 {summary.invalid ?? 0} · 未対応 {summary.unsupported ?? 0}。平均には死亡と未討伐を含み、無効・未対応は除外します。DPS中央値 {fmt(summary.medianDps)}、標準偏差 {fmt(summary.stdDevDps)}。討伐時間中央値 {summary.medianKillSeconds == null ? '未到達' : `${fmt(summary.medianKillSeconds)}秒`}、90%点 {summary.p90KillSeconds == null ? '未到達' : `${fmt(summary.p90KillSeconds)}秒`}。</p></>}
    {trials.length > 0 && <><label className="balance-trial-choice">詳細試行<select value={trialIndex} onChange={event => setTrialIndex(number(event.target.value))}>{trials.map((entry, index) => <option key={index} value={index}>#{entry.index + 1} {outcomeName[entry.outcome] ?? entry.outcome} · seed {entry.seed}</option>)}</select></label>{trial && <><p className="balance-muted">{fmt(trial.elapsedTicks / 20)}秒 · 与ダメージ {fmt(trial.damageDealt)} · 被ダメージ {fmt(trial.damageTaken)} · DPS {fmt(trial.dps)} · MP枯渇 {trial.mpExhaustedTick == null ? 'なし' : `${fmt(trial.mpExhaustedTick / 20)}秒`} · ENG枯渇 {trial.engExhaustedTick == null ? 'なし' : `${fmt(trial.engExhaustedTick / 20)}秒`}</p><ResourceChart trial={trial} /><BalanceRuntimeTrace trial={trial} />{trial.skillTimeline?.length ? <details><summary>発動・効果の履歴（最大2000件）</summary><div className="balance-scroll"><table><thead><tr><th>tick</th><th>スキル</th><th>処理</th><th>値</th><th>詳細</th></tr></thead><tbody>{trial.skillTimeline.map((entry, index) => <tr key={index}><td>{entry.tick}</td><td>{entry.skillId}</td><td>{entry.kind}</td><td>{fmt(entry.value ?? entry.damage)}</td><td>{entry.detail}</td></tr>)}</tbody></table></div></details> : null}{contributions.length > 0 && <div className="balance-scroll"><table><caption>攻撃・スキルの寄与</caption><thead><tr><th>名前</th><th>使用回数</th><th>ダメージ</th><th>構成比</th></tr></thead><tbody>{contributions.map(item => <tr key={item.id}><th scope="row"><MinecraftText value={item.name || item.id} /><small>{item.id}</small></th><td>{item.uses}</td><td>{fmt(item.damage)}</td><td>{fmt(trial.damageDealt ? item.damage / trial.damageDealt * 100 : 0)}%</td></tr>)}</tbody></table></div>}</>}</>}
    {row.economy && <EconomyView economy={row.economy} />}
    {(row.assumptions?.length || row.sources?.length) ? <details><summary>このビルドの前提と参照</summary>{row.assumptions?.map((item, index) => <p key={index}>{item}</p>)}{row.sources?.map((item, index) => <code key={index}>{item}</code>)}</details> : null}
    {(row.playerStats || row.enemyStats) && <details><summary>適用されたステータス</summary><div className="balance-stat-pair"><section><h4>プレイヤー</h4><pre>{JSON.stringify(row.playerStats, null, 2)}</pre></section><section><h4>敵</h4><pre>{JSON.stringify(row.enemyStats, null, 2)}</pre></section></div></details>}
  </article>
}

export function BalanceLab() {
  const [catalog, setCatalog] = useState<BalanceCatalog | null>(null)
  const [revision, setRevision] = useState(0)
  const [runtimeRevision, setRuntimeRevision] = useState(0)
  const [scenario, setScenario] = useState<BalanceScenario>(() => loadScenario() ?? createScenario())
  const [error, setError] = useState('')
  const [notice, setNotice] = useState('')
  const [job, setJob] = useState<BalanceJob | null>(null)
  const [overridePath, setOverridePath] = useState('')
  const [overrideText, setOverrideText] = useState('')
  const [importing, setImporting] = useState(false)
  const [submitting, setSubmitting] = useState(false)
  const [overrideDirty, setOverrideDirty] = useState(false)
  const [restoring, setRestoring] = useState(true)
  const [jsonDrafts, setJsonDrafts] = useState<Record<string, boolean>>({})
  const hasJsonDrafts = Object.values(jsonDrafts).some(Boolean)
  const markDraft = (key: string, dirty: boolean) => setJsonDrafts(current => ({ ...current, [key]: dirty }))
  const submittingRef = useRef(false)
  const loadingFileRef = useRef(false)
  const importRef = useRef<HTMLInputElement>(null)
  const groups = useMemo(() => catalog ? classifyMasters(catalog) : { classes: [], mobs: [], equipment: [], skills: [], items: [] }, [catalog])
  const skillEntries = useMemo(() => catalog ? skillCatalogForMasters(catalog) : [], [catalog])
  const basicAvailable = groups.classes.some(master => masterId(master) === 'adventurer') && groups.mobs.some(master => masterId(master) === 'midgard_grassboar') && groups.equipment.some(master => masterId(master) === '20a00001')

  useEffect(() => {
    const controller = new AbortController()
    try {
      const id = sessionStorage.getItem(jobKey)
      if (id) void getBalanceJob(id, controller.signal).then(setJob).catch(reason => { if (!controller.signal.aborted) { if (reason instanceof BalanceApiError && reason.status === 404) sessionStorage.removeItem(jobKey); else { setJob({ id, status: 'running', completed: 0, total: 1 }); setError('進捗取得に失敗しました。保存したジョブの取得を再試行します。') } } }).finally(() => { if (!controller.signal.aborted) setRestoring(false) })
      else setRestoring(false)
    } catch { setRestoring(false) }
    return () => controller.abort()
  }, [])
  useEffect(() => {
    if (!job) return
    try { sessionStorage.setItem(jobKey, job.id) } catch { /* Export remains available. */ }
  }, [job])

  useEffect(() => {
    const controller = new AbortController()
    void getBalanceCatalog(controller.signal).then(value => {
      setCatalog(value)
      setScenario(previous => {
        if (previous.masterHash || previous.modelVersion) return { ...previous, sourceHash: previous.sourceHash ?? value.sourceHash }
        const defaults = createScenario(value)
        return { ...previous, masterHash: value.masterHash, sourceHash: value.sourceHash, modelVersion: value.modelVersion, builds: previous.builds.map(build => ({ ...build, classId: build.classId || defaults.builds[0].classId })), enemy: { ...previous.enemy, mobId: previous.enemy.mobId || defaults.enemy.mobId } }
      })
      setError('')
    }).catch(reason => { if (!controller.signal.aborted) setError(String(reason)) })
    return () => controller.abort()
  }, [revision])
  useEffect(() => {
    if (!job || job.status !== 'running') return
    const controller = new AbortController()
    let timer: number
    const poll = () => void getBalanceJob(job.id, controller.signal).then(value => {
      setJob(value); setError(previous => previous.startsWith('進捗取得に失敗') ? '' : previous)
    }).catch(reason => {
      if (!controller.signal.aborted) { if (reason instanceof BalanceApiError && reason.status === 404) { sessionStorage.removeItem(jobKey); setJob(null); setError('保存した実行結果は期限切れです。条件から再実行してください。'); return } setError(`進捗取得に失敗しました。再試行します: ${String(reason)}`); timer = window.setTimeout(poll, 1200) }
    })
    timer = window.setTimeout(poll, 600)
    return () => { window.clearTimeout(timer); controller.abort() }
  }, [job])

  const change = (update: (current: BalanceScenario) => BalanceScenario) => { setScenario(current => update(current)); setNotice('') }
  const changeBuild = (id: string, update: (build: BalanceBuild) => BalanceBuild) => change(current => ({ ...current, builds: current.builds.map(build => build.id === id ? update(build) : build) }))
  const changeEconomy = (update: (economy: BalanceEconomy) => BalanceEconomy) => change(current => ({ ...current, economy: update(current.economy ?? defaultEconomy()) }))
  const stale = catalog && (scenario.masterHash && scenario.masterHash !== catalog.masterHash || scenario.sourceHash && scenario.sourceHash !== catalog.sourceHash || scenario.modelVersion && scenario.modelVersion !== catalog.modelVersion)
  const busy = restoring || submitting || job?.status === 'running'
  const result = job?.result
  const differentConditions = result && conditionKey(result.scenario) !== conditionKey(scenario)
  const limits = catalog?.limits ?? { maxTrials: 500, maxSeconds: 600, maxBuilds: 4, maxTickWork: 2000000, wallSeconds: 30 }

  async function run() {
    if (restoring || loadingFileRef.current || submittingRef.current || job?.status === 'running') return
    if (hasJsonDrafts) { setError('詳細JSONを修正し、欄からフォーカスを外して確定してください。'); return }
    if (overrideDirty) { setError('調整案JSONの変更を「調整案に追加」で反映してから実行してください。'); return }
    submittingRef.current = true; setSubmitting(true)
    setError(''); setNotice('')
    try {
      const validated = validateScenario(scenario)
      if (validated.builds.length > limits.maxBuilds || validated.trials > limits.maxTrials || validated.maxSeconds > limits.maxSeconds) throw new Error(`最大 ${limits.maxBuilds}ビルド、${limits.maxTrials}試行、${limits.maxSeconds}秒です。`)
      const started = await startBalanceJob(validated)
      setJob(started)
      setScenario(current => ({ ...current, masterHash: catalog?.masterHash, sourceHash: catalog?.sourceHash, modelVersion: catalog?.modelVersion }))
    } catch (reason) { setError(String(reason)) }
    finally { submittingRef.current = false; setSubmitting(false) }
  }
  async function cancel() {
    if (!job) return
    try { setJob(await cancelBalanceJob(job.id)) } catch (reason) { setError(String(reason)) }
  }
  function addOverride() {
    try {
      if (!catalog?.masters.some(master => master.path === overridePath)) throw new Error('調整する保存済みマスターを選択してください。')
      const content: unknown = JSON.parse(overrideText)
      if (!content || typeof content !== 'object' || Array.isArray(content)) throw new Error('調整案はJSONオブジェクトにしてください。')
      change(current => ({ ...current, overrides: [...current.overrides.filter(item => item.path !== overridePath), { path: overridePath, content }] }))
      setOverrideDirty(false)
      setError(''); setNotice(`${overridePath} の調整案を追加しました。元マスターは変更していません。`)
    } catch (reason) { setError(String(reason)) }
  }
  async function importJson(file: File) {
    if (busy) return
    loadingFileRef.current = true
    setImporting(true)
    try {
      const parsed: unknown = JSON.parse(await file.text())
      const record = asRecord(parsed)
      const next = validateScenario(record.scenario ?? parsed)
      setScenario(next); setJsonDrafts({}); setRuntimeRevision(value => value + 1); setJob(null); setError(''); setNotice('シナリオを読み込みました。再計算して結果を確認してください。')
    } catch (reason) { setError(String(reason)) } finally { loadingFileRef.current = false; setImporting(false); if (importRef.current) importRef.current.value = '' }
  }
  return <div className="balance-lab">
    <div className="balance-heading"><div><h2>統合バランスラボ</h2><p>保存済みマスターを使って戦闘と育成・経済を試算します。調整案はこの画面のシナリオにだけ適用します。</p></div><button className="button" onClick={() => setRevision(value => value + 1)}>カタログ再読込</button></div>
    {error && <p role="alert" className="balance-error">{error}</p>}{notice && <p role="status" className="balance-notice">{notice}</p>}
    {!catalog ? <p role="status">マスターを読み込んでいます…</p> : <>
      <p className="balance-fingerprint">マスター指紋 <code>{catalog.masterHash}</code> · ソース指紋 <code>{catalog.sourceHash ?? '未提供'}</code> · 計算モデル <code>{catalog.modelVersion}</code></p>
      {stale && <div role="alert" className="balance-warning">保存したシナリオからマスター、ソース、または計算モデルが変わっています。候補と調整案を確認してください。既存の指紋のまま実行すると差異をサーバーに検証させます。<button className="button compact" onClick={() => change(current => ({ ...current, masterHash: catalog.masterHash, sourceHash: catalog.sourceHash, modelVersion: catalog.modelVersion }))}>現在の指紋を採用</button></div>}
      {catalog.diagnostics.length > 0 && <details className="balance-warning"><summary>読み込み診断 {catalog.diagnostics.length}件</summary>{catalog.diagnostics.map(item => <p key={item.path}><code>{item.path}</code> {item.message}</p>)}</details>}
      <section className="balance-panel"><h3>試算条件</h3><div className="balance-fields"><NumberInput label="乱数seed" value={scenario.seed} min={0} onChange={value => change(current => ({ ...current, seed: value }))} /><NumberInput label="試行回数" value={scenario.trials} min={1} max={limits.maxTrials} onChange={value => change(current => ({ ...current, trials: value }))} /><NumberInput label="最大戦闘秒数" value={scenario.maxSeconds} min={1} max={limits.maxSeconds} onChange={value => change(current => ({ ...current, maxSeconds: value }))} /><label>計算モード<select value={scenario.mode} onChange={event => change(current => ({ ...current, mode: event.target.value as BalanceScenario['mode'] }))}><option value="strict">厳密（未対応効果は停止）</option><option value="approximate">近似（未対応効果を診断に表示）</option></select></label></div><p className="balance-muted">seedと試行回数を固定すると再比較できます。近似モードでは診断・前提を確認してください。最大 {limits.maxBuilds}ビルド、{fmt(limits.maxTickWork)} tick、実行上限 {limits.wallSeconds}秒。</p></section>
      <div className="balance-actions"><button className="button" disabled={!basicAvailable || busy} onClick={() => { const next = createScenario(catalog); next.seed = 123; next.trials = 1; next.maxSeconds = 60; next.builds = [{ id: 'basic', name: '冒険者・初期装備', classId: 'adventurer', playerLevel: 1, classLevel: 1, equipment: [{ itemId: '20a00001', enhanceLevel: 0 }], skillIds: [], normalAttacks: true, initialShield: 0 }]; next.enemy = { mobId: 'midgard_grassboar', level: 1, distanceBlocks: 3, targetHalfDepth: 0.3 }; setScenario(next); setJsonDrafts({}); setRuntimeRevision(value => value + 1); setJob(null); setNotice('実在マスターの基本ケースを読み込みました。') }}>基本ケースを読込</button>{!basicAvailable && <span className="balance-muted">基本ケースのマスターが見つかりません。</span>}</div>
      <section className="balance-panel"><div className="balance-section-heading"><h3>ビルド（{scenario.builds.length}/{limits.maxBuilds}）</h3><button className="button" disabled={scenario.builds.length >= limits.maxBuilds || busy} onClick={() => change(current => ({ ...current, builds: [...current.builds, { id: `build-${Date.now()}`, name: `ビルド${current.builds.length + 1}`, classId: groups.classes[0] ? masterId(groups.classes[0]) : '', playerLevel: 1, classLevel: 1, equipment: [], skillIds: [], normalAttacks: true, initialShield: 0 }] }))}>ビルドを追加</button></div>
        {scenario.builds.map((build, index) => <article key={build.id} className="balance-build"><div className="balance-section-heading"><h4>ビルド {index + 1}</h4><button className="button compact" disabled={scenario.builds.length <= 1 || busy || hasJsonDrafts} onClick={() => change(current => ({ ...current, builds: current.builds.filter(item => item.id !== build.id) }))}>削除</button></div><fieldset className="balance-build-fields" disabled={hasJsonDrafts}><div className="balance-fields"><label>名前<input value={build.name} onChange={event => changeBuild(build.id, previous => ({ ...previous, name: event.target.value }))} /></label><MasterSelect label="職業" masters={groups.classes} value={build.classId} onChange={value => changeBuild(build.id, previous => ({ ...previous, classId: value }))} /><NumberInput label="プレイヤーLv" value={build.playerLevel} min={1} onChange={value => changeBuild(build.id, previous => ({ ...previous, playerLevel: value }))} /><NumberInput label="クラスLv" value={build.classLevel} min={1} onChange={value => changeBuild(build.id, previous => ({ ...previous, classLevel: value }))} /><NumberInput label="初期シールド" value={build.initialShield ?? 0} min={0} onChange={value => changeBuild(build.id, previous => ({ ...previous, initialShield: value }))} /></div><label className="balance-toggle"><input type="checkbox" checked={build.normalAttacks !== false} onChange={event => changeBuild(build.id, previous => ({ ...previous, normalAttacks: event.target.checked }))} />通常攻撃も使う</label>
          <div className="balance-subpanel"><h5>装備</h5>{build.equipment.map((entry, equipmentIndex) => <div key={equipmentIndex} className="balance-fields"><MasterSelect label={`装備 ${equipmentIndex + 1}`} masters={groups.equipment} value={entry.itemId} onChange={value => changeBuild(build.id, previous => ({ ...previous, equipment: previous.equipment.map((item, i) => i === equipmentIndex ? { ...item, itemId: value } : item) }))} /><NumberInput label="強化Lv" value={entry.enhanceLevel} min={0} onChange={value => changeBuild(build.id, previous => ({ ...previous, equipment: previous.equipment.map((item, i) => i === equipmentIndex ? { ...item, enhanceLevel: value } : item) }))} /><button className="button compact" onClick={() => changeBuild(build.id, previous => ({ ...previous, equipment: previous.equipment.filter((_, i) => i !== equipmentIndex) }))}>外す</button></div>)}<button className="button compact" onClick={() => changeBuild(build.id, previous => ({ ...previous, equipment: [...previous.equipment, { itemId: '', enhanceLevel: 0 }] }))}>装備を追加</button></div>
          <SkillPicker build={build} classMaster={groups.classes.find(master => masterId(master) === build.classId)} masters={groups.skills} entries={skillEntries} onChange={(skillIds, passiveSkillIds) => changeBuild(build.id, previous => ({ ...previous, skillIds, passiveSkillIds: passiveSkillIds ?? previous.passiveSkillIds }))} /></fieldset>
          <details><summary>基礎ステータスの上書き（JSON）</summary><textarea disabled={hasJsonDrafts && !jsonDrafts[`${build.id}:base`]} aria-label={`${build.name}の基礎ステータスJSON`} defaultValue={JSON.stringify(build.baseStats ?? {}, null, 2)} key={`${build.id}:${JSON.stringify(build.baseStats)}`} onChange={() => markDraft(`${build.id}:base`, true)} onBlur={event => { try { const parsed = JSON.parse(event.target.value); if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed) || Object.values(parsed).some(value => typeof value !== 'number')) throw new Error('数値のMapを入力してください。'); changeBuild(build.id, previous => ({ ...previous, baseStats: parsed })); markDraft(`${build.id}:base`, false); setError('') } catch (reason) { setError(String(reason)) } }} /></details>
          <details><summary>装備個体値・エンチャント・ルーン・超越などの詳細JSON</summary><p className="balance-muted">このビルドのコピーを編集します。サーバーが未対応と判定した効果は診断に表示されます。</p><textarea disabled={hasJsonDrafts && !jsonDrafts[`${build.id}:details`]} aria-label={`${build.name}の詳細JSON`} defaultValue={JSON.stringify(build, null, 2)} key={`${build.id}:${JSON.stringify(build)}`} onChange={() => markDraft(`${build.id}:details`, true)} onBlur={event => { try { const parsed = JSON.parse(event.target.value) as BalanceBuild; if (!parsed || typeof parsed !== 'object' || parsed.id !== build.id || !parsed.classId || !Array.isArray(parsed.equipment) || !Array.isArray(parsed.skillIds)) throw new Error('ビルドID・職業・装備・スキル配列を確認してください。'); changeBuild(build.id, () => parsed); markDraft(`${build.id}:details`, false); setError('') } catch (reason) { setError(String(reason)) } }} /></details>
        </article>)}
      </section>
      <BalanceRuntimeConditions key={runtimeRevision} scenario={scenario} disabled={busy || hasJsonDrafts} onChange={setScenario} onDraft={dirty => markDraft('runtime', dirty)} />
      <section className="balance-panel"><h3>敵</h3><div className="balance-fields"><MasterSelect label="モブ" masters={groups.mobs} value={scenario.enemy.mobId} onChange={value => change(current => ({ ...current, enemy: { ...current.enemy, mobId: value } }))} /><label>攻撃する味方<select value={scenario.enemy.targetAllyId ?? ''} onChange={event => change(current => ({ ...current, enemy: { ...current.enemy, targetAllyId: event.target.value || undefined } }))}><option value="">自分</option>{scenario.allies?.map(ally => <option key={ally.id} value={ally.id}>{ally.id}</option>)}</select></label><NumberInput label="敵Lv" value={scenario.enemy.level} min={1} onChange={value => change(current => ({ ...current, enemy: { ...current.enemy, level: value } }))} /><NumberInput label="攻撃間隔（tick、0で定義値）" value={scenario.enemy.attackIntervalTicks ?? 0} min={0} onChange={value => change(current => ({ ...current, enemy: { ...current.enemy, attackIntervalTicks: value || undefined } }))} /><NumberInput label="敵との距離（ブロック）" value={scenario.enemy.distanceBlocks ?? 3} min={0.1} step={0.1} onChange={value => change(current => ({ ...current, enemy: { ...current.enemy, distanceBlocks: value } }))} /><NumberInput label="敵の当たり判定半奥行き" value={scenario.enemy.targetHalfDepth ?? 0.3} min={0.01} step={0.01} onChange={value => change(current => ({ ...current, enemy: { ...current.enemy, targetHalfDepth: value } }))} /></div></section>
      <section className="balance-panel"><h3>育成・経済</h3><label className="balance-toggle"><input type="checkbox" checked={Boolean(scenario.economy)} onChange={event => change(current => ({ ...current, economy: event.target.checked ? defaultEconomy() : undefined }))} />収集・強化・成長・金策も計算する</label>{scenario.economy && <><div className="balance-fields"><MasterSelect label="目標アイテム" masters={groups.items} value={scenario.economy.targetItemId} onChange={value => changeEconomy(current => ({ ...current, targetItemId: value }))} /><NumberInput label="必要数" value={scenario.economy.quantity} min={1} onChange={value => changeEconomy(current => ({ ...current, quantity: value }))} /><MasterSelect label="強化する装備" masters={groups.equipment} value={scenario.economy.enhanceItemId} onChange={value => changeEconomy(current => ({ ...current, enhanceItemId: value }))} /><NumberInput label="開始強化Lv" value={scenario.economy.fromEnhanceLevel} min={0} onChange={value => changeEconomy(current => ({ ...current, fromEnhanceLevel: value }))} /><NumberInput label="目標強化Lv" value={scenario.economy.targetEnhanceLevel} min={0} onChange={value => changeEconomy(current => ({ ...current, targetEnhanceLevel: value }))} /><MasterSelect label="オーブ" masters={groups.items} value={scenario.economy.orbItemId ?? ''} onChange={value => changeEconomy(current => ({ ...current, orbItemId: value || undefined }))} empty="なし" /><label>ドロップ倍率上書き（%・空欄はビルド）<input type="number" min="0" value={scenario.economy.dropRatePercent ?? ''} onChange={event => changeEconomy(current => ({ ...current, dropRatePercent: event.target.value === '' ? undefined : number(event.target.value) }))} /></label><label>幸運上書き（空欄はビルド）<input type="number" min="0" value={scenario.economy.luck ?? ''} onChange={event => changeEconomy(current => ({ ...current, luck: event.target.value === '' ? undefined : number(event.target.value) }))} /></label><NumberInput label="移動秒数" value={scenario.economy.moveSeconds} min={0} onChange={value => changeEconomy(current => ({ ...current, moveSeconds: value }))} /><NumberInput label="待機秒数" value={scenario.economy.waitSeconds} min={0} onChange={value => changeEconomy(current => ({ ...current, waitSeconds: value }))} /><NumberInput label="補給秒数" value={scenario.economy.resupplySeconds} min={0} onChange={value => changeEconomy(current => ({ ...current, resupplySeconds: value }))} /><NumberInput label="討伐秒数（空欄なら戦闘結果）" value={scenario.economy.killSeconds ?? 0} min={0} onChange={value => changeEconomy(current => ({ ...current, killSeconds: value || undefined }))} /><NumberInput label="収集の時間予算秒（0は未指定）" value={scenario.economy.timeBudgetSeconds ?? 0} min={0} onChange={value => changeEconomy(current => ({ ...current, timeBudgetSeconds: value || undefined }))} /><NumberInput label="目標プレイヤーLv" value={scenario.economy.targetPlayerLevel ?? 0} min={0} onChange={value => changeEconomy(current => ({ ...current, targetPlayerLevel: value || undefined }))} /><NumberInput label="目標クラスLv" value={scenario.economy.targetClassLevel ?? 0} min={0} onChange={value => changeEconomy(current => ({ ...current, targetClassLevel: value || undefined }))} /><label>アカウントUUID<input value={scenario.economy.accountUuid ?? ''} onChange={event => changeEconomy(current => ({ ...current, accountUuid: event.target.value || undefined }))} /></label></div><SellItemsPicker masters={groups.items} selected={scenario.economy.sellItemIds} onChange={sellItemIds => changeEconomy(current => ({ ...current, sellItemIds }))} /></>}</section>
      <section className="balance-panel"><h3>調整案</h3><p className="balance-muted">選んだマスターのコピーをJSONで編集します。元マスターを保存するAPIは呼びません。pathはFilebase相対パスです。</p><div className="balance-fields"><label>調整するマスター<select value={overridePath} onChange={event => { const path = event.target.value; setOverridePath(path); setOverrideDirty(false); setOverrideText(JSON.stringify(scenario.overrides.find(item => item.path === path)?.content ?? catalog.masters.find(item => item.path === path)?.content ?? {}, null, 2)) }}><option value="">選択してください</option>{catalog.masters.map(master => <option key={master.path} value={master.path}>{masterName(master).replace(/(?:&|§)[0-9a-fk-or]/gi, '')} · {master.path}</option>)}</select></label></div><textarea aria-label="調整案JSON" value={overrideText} onChange={event => { setOverrideText(event.target.value); setOverrideDirty(true) }} spellCheck={false} /><div className="balance-actions"><button className="button" disabled={!overridePath} onClick={addOverride}>調整案に追加</button><span>{scenario.overrides.length}件のマスターを調整</span></div>{scenario.overrides.map(item => <div key={item.path} className="balance-override"><code>{item.path}</code><button className="button compact" onClick={() => { setOverridePath(item.path); setOverrideDirty(false); setOverrideText(JSON.stringify(item.content, null, 2)) }}>編集</button><button className="button compact" onClick={() => change(current => ({ ...current, overrides: current.overrides.filter(entry => entry.path !== item.path) }))}>削除</button></div>)}</section>
      <section className="balance-panel"><h3>保存と実行</h3><div className="balance-actions"><button className="button" disabled={hasJsonDrafts || overrideDirty} onClick={() => { saveScenario(scenario); setNotice('このブラウザに保存しました。') }}>ブラウザに保存</button><button className="button" disabled={busy} onClick={() => { const saved = loadScenario(); if (saved) { setScenario(saved); setJsonDrafts({}); setRuntimeRevision(value => value + 1); setNotice('保存したシナリオを復元しました。') } else setError('保存したシナリオはありません。') }}>保存分を復元</button><button className="button" onClick={() => { clearScenario(); setNotice('ブラウザ保存を削除しました。') }}>保存分を削除</button><button className="button" disabled={hasJsonDrafts || overrideDirty} onClick={() => downloadText('balance-scenario.json', JSON.stringify({ scenario }, null, 2), 'application/json')}>条件JSON出力</button><button className="button" disabled={importing || busy} onClick={() => importRef.current?.click()}>条件JSON読込</button><input ref={importRef} hidden type="file" accept=".json,application/json" aria-label="条件JSONファイル" onChange={event => { const file = event.target.files?.[0]; if (file) void importJson(file) }} /><button className="button primary" disabled={busy || importing || hasJsonDrafts} onClick={() => void run()}>{busy ? '実行中…' : 'この条件で実行'}</button>{job?.status === 'running' && <button className="button" onClick={() => void cancel()}>実行をキャンセル</button>}</div><p className="balance-muted">サーバーには1件ずつ実行できます。結果はサーバーの直近8件に限り保持されるため、必要な集計はCSVで保存してください。</p></section>
      {hasJsonDrafts && <p role="alert" className="balance-warning">詳細JSONが未確定です。内容を修正し、欄からフォーカスを外して反映してください。未確定の間は他のビルド操作・実行・条件保存・出力を停止します。</p>}{differentConditions && <p role="alert" className="balance-warning">入力条件が結果の実行時から変わっています。表示と出力は前回の条件の結果です。再実行してください。</p>}{overrideDirty && <p role="alert" className="balance-warning">調整案JSONに未反映の変更があります。条件保存・出力には追加済みの調整案だけが含まれます。</p>}{result && <div className="balance-actions"><button className="button" onClick={() => downloadText('balance-summary.csv', summaryCsv(result), 'text/csv;charset=utf-8')}>ビルド集計CSV出力</button></div>}
      {result && <p className="balance-fingerprint">結果のソース指紋 <code>{result.sourceHash ?? '未提供'}</code></p>}
      {result?.comparison?.length ? <section className="balance-panel"><h3>現行との比較</h3><p className="balance-muted">先頭ビルドの現行を基準に比較します。討伐率の差はパーセントポイントです。</p><div className="balance-scroll"><table><thead><tr><th>ビルド</th><th>指標</th><th>現行</th><th>比較先</th><th>差</th></tr></thead><tbody>{result.comparison.flatMap((entry, entryIndex) => entry.metrics.map((metric, metricIndex) => { const percent = metric.metric.toLowerCase().includes('rate'); const display = (value: number | null) => value == null ? '算出不能' : `${fmt(percent ? value * 100 : value)}${percent ? '%' : ''}`; return <tr key={`${entryIndex}:${metricIndex}`}><th scope="row">{result.results.find(row => row.buildId === entry.buildId)?.buildName ?? entry.buildId}（{entry.variant === 'proposal' ? '調整案' : '現行'}）</th><td>{metricName[metric.metric] ?? metric.metric}</td><td>{display(metric.before)}</td><td>{display(metric.after)}</td><td>{display(metric.delta)}</td></tr> }))}</tbody></table></div></section> : null}
      {job && <section className="balance-panel" aria-live="polite"><div className="balance-section-heading"><h3>実行結果</h3><span>{job.status === 'running' ? '計算中' : job.status === 'completed' ? '完了' : job.status === 'cancelled' ? 'キャンセル' : '失敗'} · {job.completed}/{job.total}</span></div><progress max={Math.max(1, job.total)} value={job.completed} />{job.error && <p role="alert" className="balance-error">{job.error}</p>}{result && <><p className="balance-fingerprint">seed {result.seed} · マスター <code>{result.masterHash}</code> · モデル <code>{result.modelVersion}</code>{result.proposalHash && <> · 調整案 <code>{result.proposalHash}</code></>}</p>{result.warnings?.map((item, index) => <p key={index} className="balance-warning">{item}</p>)}<div className="balance-actions"><button className="button" onClick={() => downloadText('balance-trials.csv', resultCsv(result), 'text/csv;charset=utf-8')}>試行集計CSV出力</button><button className="button" onClick={() => downloadText('balance-result.json', JSON.stringify(result, null, 2), 'application/json')}>結果JSON出力</button></div><div className="balance-results">{result.results.map((row, index) => <ResultCard key={`${row.buildId}:${row.variant}:${index}`} row={row} />)}</div>{result.changes?.length ? <details><summary>調整案の変更 {result.changes.length}件</summary><div className="balance-scroll"><table><thead><tr><th>マスター</th><th>場所</th><th>変更前</th><th>変更後</th></tr></thead><tbody>{result.changes.map((change, index) => <tr key={index}><td><code>{change.path}</code></td><td><code>{change.pointer}</code></td><td><code>{JSON.stringify(change.before)}</code></td><td><code>{JSON.stringify(change.after)}</code></td></tr>)}</tbody></table></div></details> : null}{result.comparison?.length ? <details><summary>現行との差分</summary><pre>{JSON.stringify(result.comparison, null, 2)}</pre></details> : null}<details><summary>根拠・前提・入力条件</summary>{result.assumptions?.map((item, index) => <p key={index}>{item}</p>)}{result.sources?.map(source => <p key={source.path}><code>{source.path}</code> {source.hash}</p>)}<pre>{JSON.stringify(result.scenario, null, 2)}</pre></details></>}</section>}
    </>}
  </div>
}

export type JsonObject = Record<string, unknown>

export interface BalanceMaster { path: string; kind: string; revision?: string; content: JsonObject }
export interface BalanceCatalog {
  masters: BalanceMaster[]
  masterHash: string
  sourceHash?: string
  supportedSkillIds?: string[]
  modelVersion: string
  sources: { path: string; hash: string }[]
  diagnostics: { path: string; message: string }[]
  limits?: { maxTrials: number; maxSeconds: number; maxBuilds: number; maxTickWork: number; wallSeconds: number }
}
export interface BalanceEquipment { itemId: string; enhanceLevel: number; [key: string]: unknown }
export interface BalanceBuild {
  id: string; name: string; classId: string; playerLevel: number; classLevel: number
  equipment: BalanceEquipment[]; skillIds: string[]; baseStats?: Record<string, number>
}
export interface BalanceEconomy {
  targetItemId: string; quantity: number; enhanceItemId: string; fromEnhanceLevel: number
  targetEnhanceLevel: number; orbItemId?: string; dropRatePercent?: number; luck?: number; sellItemIds: string[]
  moveSeconds: number; waitSeconds: number; resupplySeconds: number; killSeconds?: number
  accountUuid?: string; targetPlayerLevel?: number; targetClassLevel?: number; timeBudgetSeconds?: number
}
export interface BalanceScenario {
  schemaVersion: 1; masterHash?: string; sourceHash?: string; modelVersion?: string; seed: number; trials: number
  maxSeconds: number; mode: 'strict' | 'approximate'; builds: BalanceBuild[]
  enemy: { mobId: string; level: number; attackIntervalTicks?: number }
  overrides: { path: string; content: unknown }[]; economy?: BalanceEconomy
}
export interface BalanceTrial {
  index: number; seed: number; outcome: 'victory' | 'death' | 'timeout' | 'invalid' | 'unsupported'
  elapsedTicks: number; damageDealt: number; damageTaken: number; dps: number
  mpExhaustedTick?: number | null; engExhaustedTick?: number | null
  contributions?: { id: string; name: string; uses: number; damage: number }[]
  resources?: { tick: number; hp: number; mp: number; eng: number; shield: number }[]
}
export interface BalanceEconomyResult {
  status: 'completed' | 'unsupported' | 'unreachable' | 'invalid'
  diagnostics?: { code: string; message: string; severity: string }[]
  collection?: { itemId?: string; quantity?: number; expectedItemsPerKill?: number; expectedKills?: number; medianKills?: number; p90Kills?: number; method?: string; probabilityWithinBudget?: number }
  enhancement?: { itemId?: string; fromLevel?: number; targetLevel?: number; orbItemId?: string; expectedAttempts?: number; medianAttempts?: number; p90Attempts?: number; expectedOrbs?: number; orbStockCompletionProbability?: number; expectedOrbDropsPerKill?: number; estimatedKillsForExpectedOrbs?: number; goldPerAttempt?: number; expectedGold?: number }
  growth?: { experiencePerVictory?: number; expectedExperiencePerKill?: number; expectedExperience?: number; playerLevel?: number; classLevel?: number; playerExperienceNeeded?: number; expectedKillsToPlayerLevel?: number; classExperienceNeeded?: number; expectedKillsToClassLevel?: number; classPath?: { currentClassId?: string; targetClassId?: string; unlockPlayerLevel?: number; adminChangeOnly?: boolean; commandOnly?: boolean; prerequisites?: { classId: string; requiredLevel: number; actualLevel: number }[]; canSelectNow?: boolean } }
  gold?: { income?: number; expense?: number; net?: number; scope?: string; completeness?: string; excludedCosts?: string[]; lines?: unknown[] }
  time?: { combatSeconds?: number; moveSeconds?: number; waitSeconds?: number; resupplySeconds?: number; totalSeconds?: number }
  assumptions?: string[]; sources?: string[]
}
export interface BalanceResultRow {
  buildId: string; buildName: string; variant: 'current' | 'proposal'; status: string
  diagnostics?: { code: string; message: string; severity: string }[]; assumptions?: string[]; sources?: string[]; playerStats?: JsonObject; enemyStats?: JsonObject; trials?: BalanceTrial[]
  summary?: { totalTrials: number; validTrials?: number; victories: number; deaths: number; timeouts: number; invalid?: number; unsupported?: number; meanDps: number; medianDps: number; stdDevDps: number; meanDamageDealt?: number; meanDamageTaken?: number; meanElapsedSeconds: number; medianElapsedSeconds: number; p90ElapsedSeconds: number | null; medianKillSeconds?: number | null; p90KillSeconds?: number | null; successRate: number | null }
  economy?: BalanceEconomyResult
}
export interface BalanceResult {
  modelVersion: string; masterHash: string; sourceHash?: string; proposalHash?: string; seed: number; scenario: BalanceScenario
  sources?: { path: string; hash?: string }[]; assumptions?: string[]; warnings?: string[]
  results: BalanceResultRow[]; changes?: { path: string; pointer: string; before: unknown; after: unknown }[]
  comparison?: { baselineBuildId: string; buildId: string; variant: string; metrics: { metric: string; before: number | null; after: number | null; delta: number | null }[] }[]
}
export interface BalanceJob {
  id: string; status: 'running' | 'completed' | 'cancelled' | 'failed'; completed: number; total: number
  result?: BalanceResult; error?: string
}

const storageKey = 'astralrecord.balance-lab.scenario.v1'
export const asRecord = (value: unknown): JsonObject => value && typeof value === 'object' && !Array.isArray(value) ? value as JsonObject : {}
export const textValue = (value: unknown): string => typeof value === 'string' ? value : ''
export const numberValue = (value: unknown, fallback = 0): number => typeof value === 'number' && Number.isFinite(value) ? value : fallback
export const masterId = (master: BalanceMaster): string => textValue(master.content.id) || master.path
export const masterName = (master: BalanceMaster): string => textValue(master.content.displayName) || textValue(master.content.name) || masterId(master)

export function classifyMasters(catalog: BalanceCatalog) {
  const is = (master: BalanceMaster, pattern: RegExp) => pattern.test(`${master.kind} ${master.path}`)
  return {
    classes: catalog.masters.filter(master => master.kind === 'class' && typeof master.content.id === 'string' && Array.isArray(master.content.baseStats)),
    mobs: catalog.masters.filter(master => master.kind === 'mob' && typeof master.content.id === 'string'),
    equipment: catalog.masters.filter(master => is(master, /equipment/i) && Boolean(master.content.equipment && typeof master.content.equipment === 'object')),
    skills: catalog.masters.filter(master => master.kind === 'skill' && typeof master.content.id === 'string'),
    items: catalog.masters.filter(master => master.kind.toLowerCase() === 'item' && Boolean(master.content.id)),
  }
}

export function createScenario(catalog?: BalanceCatalog): BalanceScenario {
  const groups = catalog ? classifyMasters(catalog) : undefined
  return {
    schemaVersion: 1, masterHash: catalog?.masterHash, sourceHash: catalog?.sourceHash, modelVersion: catalog?.modelVersion,
    seed: 1, trials: 1, maxSeconds: 120, mode: 'strict',
    builds: [{ id: 'build-1', name: 'ビルド1', classId: groups?.classes[0] ? masterId(groups.classes[0]) : '', playerLevel: 1, classLevel: 1, equipment: [], skillIds: [] }],
    enemy: { mobId: groups?.mobs[0] ? masterId(groups.mobs[0]) : '', level: 1 }, overrides: [],
  }
}

export function validateScenario(value: unknown): BalanceScenario {
  const input = asRecord(value)
  if (input.schemaVersion !== 1 || !Array.isArray(input.builds) || !Array.isArray(input.overrides)) throw new Error('シナリオの形式が違います。schemaVersion 1 のJSONを選択してください。')
  const scenario = value as BalanceScenario
  if (!scenario.builds.length || scenario.builds.some(build => !build.id || !build.classId || !Number.isInteger(build.playerLevel) || !Number.isInteger(build.classLevel))) throw new Error('各ビルドの職業とレベルを指定してください。')
  if (!scenario.enemy?.mobId || !Number.isInteger(scenario.enemy.level)) throw new Error('敵と敵レベルを指定してください。')
  if (!Number.isInteger(scenario.seed) || scenario.seed < 0 || !Number.isInteger(scenario.trials) || scenario.trials < 1 || scenario.trials > 500 || !Number.isInteger(scenario.maxSeconds) || scenario.maxSeconds < 1 || scenario.maxSeconds > 600) throw new Error('seed、試行回数（1～500）、最大秒数（1～600）を確認してください。')
  if (scenario.mode !== 'strict' && scenario.mode !== 'approximate') throw new Error('計算モードを確認してください。')
  return scenario
}

export function loadScenario(): BalanceScenario | null {
  try { const saved = localStorage.getItem(storageKey); return saved ? validateScenario(JSON.parse(saved)) : null } catch { return null }
}
export function saveScenario(scenario: BalanceScenario) { localStorage.setItem(storageKey, JSON.stringify(scenario)) }
export function clearScenario() { localStorage.removeItem(storageKey) }

export function downloadText(filename: string, body: string, type: string) {
  const href = URL.createObjectURL(new Blob([body], { type }))
  const anchor = document.createElement('a'); anchor.href = href; anchor.download = filename; anchor.click()
  URL.revokeObjectURL(href)
}
const csvCell = (value: unknown) => {
  const text = typeof value === 'string' && /^\s*[=+@-]/.test(value) ? `'${value}` : String(value ?? '')
  return `"${text.replaceAll('"', '""')}"`
}
const contextHeaders = ['計算モデル', 'マスターSHA256', 'PluginソースSHA256', '調整案SHA256', '基準seed', '条件JSON（先頭行）']
const contextCells = (result: BalanceResult, first: boolean) => [result.modelVersion, result.masterHash, result.sourceHash, result.proposalHash, result.seed, first ? JSON.stringify(result.scenario) : '']
export const conditionKey = (scenario: BalanceScenario) => JSON.stringify({ seed: scenario.seed, trials: scenario.trials, maxSeconds: scenario.maxSeconds, mode: scenario.mode, builds: scenario.builds, enemy: scenario.enemy, overrides: scenario.overrides, economy: scenario.economy })
export function resultCsv(result: BalanceResult): string {
  const lines: unknown[][] = [['ビルドID', 'ビルド名', '案', '試行', 'seed', '結果', '秒', 'DPS', '与ダメージ', '被ダメージ', 'MP枯渇tick', 'ENG枯渇tick']]
  for (const row of result.results) for (const trial of row.trials ?? []) lines.push([row.buildId, row.buildName, row.variant, trial.index, trial.seed, trial.outcome, trial.elapsedTicks / 20, trial.dps, trial.damageDealt, trial.damageTaken, trial.mpExhaustedTick, trial.engExhaustedTick])
  const contextual = lines.map((line, index) => [...line, ...(index === 0 ? contextHeaders : contextCells(result, index === 1))])
  return '\uFEFF' + contextual.map(line => line.map(csvCell).join(',')).join('\r\n') + '\r\n'
}

export function summaryCsv(result: BalanceResult): string {
  const lines: unknown[][] = [['ビルドID', 'ビルド名', '案', '状態', '全試行', '有効試行', '討伐', '死亡', '未討伐', '無効', '未対応', '討伐率（%）', '平均DPS', '中央値DPS', 'DPS標準偏差', '平均経過秒', '中央値経過秒', '90%経過秒']]
  for (const row of result.results) {
    const summary = row.summary
    lines.push([row.buildId, row.buildName, row.variant, row.status, summary?.totalTrials, summary?.validTrials, summary?.victories, summary?.deaths, summary?.timeouts, summary?.invalid, summary?.unsupported, summary?.successRate == null ? null : summary.successRate * 100, summary?.meanDps, summary?.medianDps, summary?.stdDevDps, summary?.meanElapsedSeconds, summary?.medianElapsedSeconds, summary?.p90ElapsedSeconds])
  }
  const contextual = lines.map((line, index) => [...line, ...(index === 0 ? contextHeaders : contextCells(result, index === 1))])
  return '\uFEFF' + contextual.map(line => line.map(csvCell).join(',')).join('\r\n') + '\r\n'
}

import { stripMinecraftFormatting } from '../utils/minecraft'

export type MasterObject = Record<string, unknown>
export interface AnalyticsMaster { path: string; content: MasterObject; progression: number | null }
export interface AnalyticsCatalog { equipment: AnalyticsMaster[]; classes: AnalyticsMaster[]; diagnostics: { path: string; message: string }[] }
export interface GrowthSimulation {
  accountUuid: string; startPlayerLevel: number; startClassLevel: number
  classes: { id: string; name: string; maxLevel: number; expRate: number }[]
  points: { playerLevel: number; playerTotalExperience: number; classes: { id: string; level: number; totalExperience: number; nextLevelExperience: number }[] }[]
  sources: string[]; assumption: string
}
export interface StatRange { min: number; max: number; expected?: number }
export interface EquipmentRow { path: string; id: string; name: string; slot: string; tag: string; hand: string; rarity: string; level: number; progression: number | null; requiredClasses: string; stats: Record<string, StatRange>; rawStats: string[] }
export const asObject = (value: unknown): MasterObject => value !== null && typeof value === 'object' && !Array.isArray(value) ? value as MasterObject : {}
export const asList = (value: unknown): unknown[] => Array.isArray(value) ? value : []
export const numeric = (value: unknown, fallback = 0): number => (typeof value === 'number' || typeof value === 'string') && String(value).trim() !== '' && Number.isFinite(Number(value)) ? Number(value) : fallback
export const plainName = (value: unknown): string => stripMinecraftFormatting(String(value ?? ''))
export const normalizeStatus = (value: unknown): string => String(value ?? '').trim().replace(/[ -]/g, '_').toUpperCase()

export function rangeOf(value: unknown): StatRange | null {
  if (value === null || value === undefined || typeof value === 'boolean' || Array.isArray(value)) return null
  if (typeof value === 'number') return Number.isFinite(value) ? { min: value, max: value } : null
  if (typeof value === 'string') {
    const parts = value.trim().split(/[~～]/)
    if (parts.length > 2 || parts.some(part => part.trim() === '' || !Number.isFinite(Number(part)))) return null
    const numbers = parts.map(Number)
    return { min: Math.min(...numbers), max: Math.max(...numbers) }
  }
  const object = asObject(value)
  const lower = rangeOf(object.min)
  const upper = rangeOf(object.max)
  if (!lower && !upper) return null
  const first = lower?.min ?? upper!.min
  const second = upper?.max ?? lower!.max
  return { min: Math.min(first, second), max: Math.max(first, second) }
}

/** Bounds and mean of one equipment roll; a random roll is sampled once on creation. */
export function equipmentStatRange(definition: MasterObject): StatRange | null {
  const fixed = rangeOf(definition.value ?? { min: definition.min, max: definition.max })
  if (!fixed || !definition.random) return fixed
  const random = asObject(definition.random)
  if (fixed.min !== fixed.max || typeof definition.value === 'object'
      || 'min' in definition || 'max' in definition || /[~～]/.test(String(definition.value))) return null
  const offsetMin = numeric(random.offsetMin, NaN)
  const offsetMax = numeric(random.offsetMax, NaN)
  const step = numeric(random.step, NaN)
  const zeroChance = numeric(random.zeroChance)
  if (![offsetMin, offsetMax, step, zeroChance].every(Number.isFinite)
      || offsetMin > offsetMax || step <= 0 || zeroChance < 0 || zeroChance > 1) return null
  const steps = (offsetMax - offsetMin) / step
  if (steps > 999_999 || Math.abs(steps - Math.round(steps)) > 1e-8) return null
  const lower = fixed.min + offsetMin, upper = fixed.max + offsetMax
  if (!Number.isFinite(lower) || !Number.isFinite(upper)
      || offsetMin !== offsetMax && lower === upper
      || zeroChance > 0 && lower <= 0 && upper >= 0) return null
  if (zeroChance === 1) return { min: 0, max: 0, expected: 0 }
  return {
    min: zeroChance > 0 ? Math.min(0, lower) : lower,
    max: zeroChance > 0 ? Math.max(0, upper) : upper,
    expected: (1 - zeroChance) * (lower + upper) / 2,
  }
}

// Mirrors StatusService.addItemBonus / calculateEnhanceStats: FLAT adds; SCALAR replaces.
export function equipmentRows(masters: AnalyticsMaster[], enhanceLevel: number): EquipmentRow[] {
  return masters.map(master => {
    const item = master.content
    const equipment = asObject(item.equipment)
    const enhancement = asObject(equipment.enhance)
    const requestedLevel = Math.min(Math.max(0, enhanceLevel), numeric(enhancement.maxLevel))
    const increments = asList(enhancement.levels).map(asObject).filter(level => numeric(level.level) <= requestedLevel).flatMap(level => asList(level.statIncrease))
    const baseDefinitions = asList(equipment.stats).map(asObject)
    const definitions = [...baseDefinitions, ...(requestedLevel > 0 ? increments.map(asObject) : [])]
    const groups = new Map<string, { flat?: StatRange; scalar?: StatRange }>()
    const addItemBonus = (definition: MasterObject) => {
      const status = normalizeStatus(definition.status)
      const amount = equipmentStatRange(definition)
      if (!status || !amount) return
      const group = groups.get(status) ?? {}
      const kind = String(definition.type).toUpperCase() === 'SCALAR' ? 'scalar' : 'flat'
      const existing: StatRange = group[kind] ?? { min: 0, max: 0 }
      group[kind] = kind === 'scalar' ? amount : {
        min: existing.min + amount.min, max: existing.max + amount.max,
        ...(amount.expected === undefined && existing.expected === undefined ? {} : {
          expected: (existing.expected ?? (existing.min + existing.max) / 2) + (amount.expected ?? (amount.min + amount.max) / 2),
        }),
      }
      groups.set(status, group)
    }
    baseDefinitions.forEach(addItemBonus)
    // Plugin sums each enhancement status/type before adding that one result to the item.
    const cumulative = new Map<string, MasterObject>()
    for (const raw of requestedLevel > 0 ? increments : []) {
      const definition = asObject(raw), status = normalizeStatus(definition.status)
      const amount = rangeOf(definition.value ?? { min: definition.min, max: definition.max })
      if (!status || !amount) continue
      const type = String(definition.type).toUpperCase() === 'SCALAR' ? 'SCALAR' : 'FLAT'
      const key = `${status}#${type}`
      const old = rangeOf(cumulative.get(key)?.value) ?? { min: 0, max: 0 }
      cumulative.set(key, { status, type, value: { min: old.min + amount.min, max: old.max + amount.max } })
    }
    cumulative.forEach(addItemBonus)
    const stats: Record<string, StatRange> = {}
    for (const [status, group] of groups) {
      if (!group.flat) continue
      if (!group.scalar) stats[status] = group.flat
      else {
        const products = [group.flat.min * group.scalar.min, group.flat.min * group.scalar.max, group.flat.max * group.scalar.min, group.flat.max * group.scalar.max]
        stats[status] = {
          min: Math.min(...products), max: Math.max(...products),
          ...(group.flat.expected === undefined && group.scalar.expected === undefined ? {} : {
            expected: (group.flat.expected ?? (group.flat.min + group.flat.max) / 2) * (group.scalar.expected ?? (group.scalar.min + group.scalar.max) / 2),
          }),
        }
      }
    }
    return {
      path: master.path, id: String(item.id ?? ''), name: plainName(item.name), slot: String(equipment.slot ?? ''), tag: String(equipment.tag ?? ''), hand: String(equipment.handType ?? 'ONE'), rarity: String(item.rarity ?? ''),
      level: numeric(equipment.requiredLevel), progression: master.progression,
      requiredClasses: asList(equipment.requiredClasses).map(asObject).map(requirement => `${requirement.classId} Lv.${numeric(requirement.level, 1)}`).join(' / '), stats,
      rawStats: definitions.map(definition => `${definition.status} ${definition.type ?? 'FLAT'} ${typeof definition.value === 'object' ? JSON.stringify(definition.value) : definition.value ?? `${definition.min}~${definition.max}`}${definition.random ? ` random ${JSON.stringify(definition.random)}` : ''}`),
    }
  })
}

// PlayerClassService.classStatValue takes the first matching definition, rather than summing duplicates.
export function classBonus(master: AnalyticsMaster, status: string, classLevel: number): number {
  const first = (value: unknown) => numeric(asList(value).map(asObject).find(stat => normalizeStatus(stat.status) === status)?.value)
  const level = Math.max(1, Math.min(Math.max(1, numeric(master.content.maxLevel, 100)), classLevel))
  return first(master.content.baseStats) + first(master.content.growthPerLevel) * (level - 1)
}
export const formatRange = (range: StatRange | undefined): string => !range ? '—' : range.min === range.max ? formatNumber(range.min) : `${formatNumber(range.min)}〜${formatNumber(range.max)}`
export const formatNumber = (value: number): string => value.toLocaleString('ja-JP', { maximumFractionDigits: 3 })
export function formatEquipmentRange(range: StatRange | undefined): string {
  if (range?.expected === undefined) return formatRange(range)
  const precise = (value: number) => value.toLocaleString('ja-JP', { maximumFractionDigits: 4 })
  const bounds = range.min === range.max ? precise(range.min) : `${precise(range.min)}～${precise(range.max)}`
  return `${bounds} (期待値 ${range.expected.toLocaleString('ja-JP', { maximumFractionDigits: 6 })})`
}

export function downloadCsv(name: string, rows: unknown[][]) {
  const body = '\uFEFF' + rows.map(row => row.map(value => {
    const text = String(value ?? '')
    // Spreadsheet imports must treat user-authored master names as text.
    const safe = /^[=+\-@\t\r]/.test(text) && typeof value !== 'number' ? `'${text}` : text
    return `"${safe.replace(/"/g, '""')}"`
  }).join(',')).join('\r\n')
  const link = document.createElement('a')
  const url = URL.createObjectURL(new Blob([body], { type: 'text/csv;charset=utf-8' }))
  link.href = url; link.download = name; link.click(); URL.revokeObjectURL(url)
}

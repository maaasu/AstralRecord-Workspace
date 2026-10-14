import type { MasterFileSummary, MasterFilterEntry, MasterFilterIndex } from '../types/masterData'
import { stripMinecraftFormatting } from '../utils/minecraft'

const itemDirectory = '10.features.item'
const setEffectDirectory = `${itemDirectory}/20.equipment/set_effect`
export const isItemDefinitionDirectory = (directory: string) => (directory === itemDirectory || directory.startsWith(`${itemDirectory}/`)) && !directory.startsWith(setEffectDirectory)
export const isSkillDirectory = (directory: string) => directory === '30.features.skill' || directory.startsWith('30.features.skill/')
export const isDropSourceDirectory = (directory: string) => ['40.features.mob', '42.features.gathering'].some((root) => directory === root || directory.startsWith(`${root}/`))
const inDirectory = (path: string, directory: string) => directory === '.' ? !path.includes('/') : path.startsWith(`${directory}/`)
export const inCategory = (path: string, directory: string) => inDirectory(path, directory)
  && !(directory.startsWith(itemDirectory) && directory !== setEffectDirectory && inDirectory(path, setEffectDirectory))

export const facetLabels: Record<string, string> = {
  category: 'データ種別', type: '種別', tags: 'タグ', rarity: 'レアリティ', slot: '装備スロット',
  world: 'ワールド', entityType: 'エンティティ種別', passiveConfigured: 'パッシブ設定', enabled: '有効・無効',
  role: 'クラス役割', requiredToolTags: '必要な道具タグ', worldType: 'ワールド種別',
  unTradeable: 'トレード不可', unSellable: '売却不可', enchantEnabled: 'エンチャント可能', enhanceEnabled: '強化可能',
  requiredClasses: '装備可能クラス', classRestricted: '装備クラス制限', handType: '装備の手数', equipmentTag: '装備種別タグ', setId: '装備セット',
}

export interface NumericFilterRange { min: string; max: string }
export type NumericFilters = Record<string, NumericFilterRange>

export const numericFilterLabels: Record<string, string> = {
  mobLevel: 'モブの定義レベル', requiredLevel: '装備に必要なプレイヤーレベル', saleValue: '売却額', maxStack: '最大スタック数',
  enhanceMaxLevel: '強化上限（初期状態）', enchantMaxSlots: 'エンチャント枠上限（初期状態）', durabilityMax: '最大耐久値',
}

export function categoryNumericOptions(files: MasterFileSummary[], index: MasterFilterIndex | null): Record<string, number[]> {
  const paths = new Set(files.map((file) => file.path))
  const values: Record<string, Set<number>> = {}
  for (const entry of index?.entries ?? []) {
    if (!paths.has(entry.path)) continue
    for (const [key, items] of Object.entries(entry.numbers ?? {})) {
      if (!numericFilterLabels[key]) continue
      const set = values[key] ??= new Set()
      items.filter(Number.isFinite).forEach((item) => set.add(item))
    }
  }
  return Object.fromEntries(Object.entries(values).filter(([, items]) => items.size > 0)
    .map(([key, items]) => [key, [...items].sort((a, b) => a - b)]))
}

function numericBound(raw: string): number | null {
  if (!raw.trim()) return null
  // Reject coercions such as hexadecimal and Infinity; preserve incomplete input as an error.
  if (!/^[+-]?(?:\d+(?:\.\d*)?|\.\d+)(?:e[+-]?\d+)?$/i.test(raw.trim())) return NaN
  return Number(raw)
}

export function numericFilterError(filters: NumericFilters): string | null {
  for (const [key, range] of Object.entries(filters)) {
    const min = numericBound(range.min), max = numericBound(range.max)
    const label = numericFilterLabels[key] ?? key
    if ((min !== null && !Number.isFinite(min)) || (max !== null && !Number.isFinite(max)))
      return `${label}の範囲は有限の数値で入力してください。`
    if (min !== null && max !== null && min > max) return `${label}の下限は上限以下にしてください。`
  }
  return null
}

export function matchesNumericFilters(entry: MasterFilterEntry | null | undefined, filters: NumericFilters): boolean {
  if (numericFilterError(filters)) return false
  return Object.entries(filters).every(([key, range]) => {
    const min = numericBound(range.min), max = numericBound(range.max)
    if (min === null && max === null) return true
    return (entry?.numbers?.[key] ?? []).some((value) => Number.isFinite(value)
      && (min === null || value >= min) && (max === null || value <= max))
  })
}

export function categoryFacetOptions(files: MasterFileSummary[], index: MasterFilterIndex | null): Record<string, string[]> {
  const paths = new Set(files.map((file) => file.path))
  const values: Record<string, Set<string>> = {}
  for (const entry of index?.entries ?? []) {
    if (!paths.has(entry.path)) continue
    for (const [key, items] of Object.entries(entry.facets)) {
      if (!facetLabels[key]) continue
      const set = values[key] ??= new Set()
      items.forEach((item) => set.add(item))
    }
  }
  return Object.fromEntries(Object.entries(values).filter(([, items]) => items.size > 0)
    .map(([key, items]) => [key, [...items].sort((a, b) => a.localeCompare(b, 'ja'))]))
}

export function searchMasterName(query: string, ...values: (string | null | undefined)[]): boolean {
  const search = stripMinecraftFormatting(query).trim().toLocaleLowerCase()
  return !search || values.some((value) => value != null && stripMinecraftFormatting(value).toLocaleLowerCase().includes(search))
}

export function matchesFileStatus(file: MasterFileSummary, status: string): boolean {
  return !status || (status === 'error' ? Boolean(file.parseError) : status === 'readOnly' ? Boolean(file.readOnly)
    : status === 'editable' ? !file.readOnly : false)
}

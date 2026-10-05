import type { MasterFileSummary, MasterFilterIndex } from '../types/masterData'
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

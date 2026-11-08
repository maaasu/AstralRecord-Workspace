import type { JsonObject, JsonValue } from '../types/editor'
import type { MasterFileSummary, MasterReference } from '../types/masterData'
import { parentPointer } from './masterDataNavigation'

export interface CollectionField {
  key: string
  label: string
  input: 'integer' | 'decimal' | 'range'
  defaultValue?: JsonValue
}

export type PlacementKind = 'shop' | 'quest_board'

interface ReferenceCollectionDefinition {
  kind: 'item' | 'mob' | 'gathering' | 'quest' | 'loot_pool' | 'fishing_fish'
  key: 'itemId' | 'mobId' | 'gatheringId' | 'questId' | 'ref' | 'fishId'
  title: string
  noun: string
  fields: readonly CollectionField[]
  encoding: 'wrapped' | 'prefixed' | 'bare'
  initial?: JsonObject
  itemCategory?: boolean
  dropFlags?: boolean
  placement?: PlacementKind
}

const quantity: CollectionField = { key: 'amount', label: '数量', input: 'integer', defaultValue: 1 }
const rangedAmount: CollectionField = { key: 'amount', label: '数量', input: 'range', defaultValue: '1' }
const dropRate: CollectionField = { key: 'rate', label: '確率 (%)', input: 'decimal', defaultValue: 0 }
const weight: CollectionField = { key: 'weight', label: '抽選重み', input: 'integer', defaultValue: 1 }
const itemQuantity: ReferenceCollectionDefinition = {
  kind: 'item', key: 'itemId', title: 'アイテム', noun: 'アイテム', encoding: 'wrapped',
  fields: [quantity], initial: { amount: 1 },
}
const drops: ReferenceCollectionDefinition = {
  ...itemQuantity, title: 'ドロップアイテム', fields: [dropRate, rangedAmount], dropFlags: true,
  initial: { rate: 0, amount: '1', luckAffected: true, hidden: false },
}
const gatheringDrops: ReferenceCollectionDefinition = { ...drops, encoding: 'prefixed' }
const spawnMobs: ReferenceCollectionDefinition = {
  kind: 'mob', key: 'mobId', title: '出現するモブ', noun: 'モブ', encoding: 'prefixed',
  fields: [{ key: 'level', label: 'レベル', input: 'integer' }, weight], initial: { weight: 1 },
}
const spawnGatherings: ReferenceCollectionDefinition = {
  kind: 'gathering', key: 'gatheringId', title: '出現するギャザリング対象', noun: '採集対象', encoding: 'prefixed',
  fields: [weight], initial: { weight: 1 },
}
const recipeIngredients: ReferenceCollectionDefinition = { ...itemQuantity, title: '素材', noun: '素材' }
const shopItems: ReferenceCollectionDefinition = {
  ...itemQuantity, title: '商品', noun: '商品', itemCategory: true, placement: 'shop',
  fields: [quantity, { key: 'priceGold', label: '価格 (Gold)', input: 'integer', defaultValue: 0 }],
}
const requiredItems: ReferenceCollectionDefinition = {
  ...itemQuantity, title: '必要アイテム', itemCategory: true,
}
const questRewards: ReferenceCollectionDefinition = { ...itemQuantity, title: '報酬アイテム', itemCategory: true }
const mailRewards: ReferenceCollectionDefinition = { ...questRewards, encoding: 'bare' }
const lootContents: ReferenceCollectionDefinition = {
  ...itemQuantity, title: '抽選アイテム', fields: [dropRate, rangedAmount], initial: { rate: 0, amount: '1' },
}
const bundleRewards: ReferenceCollectionDefinition = { ...itemQuantity, title: '開封時のアイテム' }
const dungeonRewards: ReferenceCollectionDefinition = {
  ...lootContents, title: 'クリア報酬', encoding: 'prefixed',
  fields: [{ ...dropRate, defaultValue: 100 }, rangedAmount],
  initial: { rate: 100, amount: '1' },
}
const quests: ReferenceCollectionDefinition = {
  kind: 'quest', key: 'questId', title: '掲示するクエスト', noun: 'クエスト', encoding: 'wrapped', fields: [], placement: 'quest_board',
}
const lootPools: ReferenceCollectionDefinition = {
  kind: 'loot_pool', key: 'ref', title: '抽選プール', noun: 'プール', encoding: 'prefixed', fields: [],
}
const fish: ReferenceCollectionDefinition = {
  kind: 'fishing_fish', key: 'fishId', title: '釣れる魚', noun: '魚', encoding: 'wrapped',
  fields: [{ key: 'chancePercent', label: '確率 (%)', input: 'decimal', defaultValue: 0 }],
}

export function isVisualDropContainer(documentPath: string, path: string) {
  const mob = /^40\.features\.mob\/(enemy|boss)\//.test(documentPath)
  if (path === '/drops') return mob || documentPath.startsWith('42.features.gathering/')
  return mob && /^\/levels\/\d+\/drops$/.test(path)
}

export function visualReferenceCollection(documentPath: string, path: string) {
  if (path.endsWith('/items') && isVisualDropContainer(documentPath, path.slice(0, -'/items'.length))) {
    return documentPath.startsWith('42.features.gathering/') ? gatheringDrops : drops
  }
  const category = documentPath.split('/')[0]
  switch (category) {
    case '41.features.mob.spawner': return path === '/spawnMobs' ? spawnMobs : undefined
    case '43.features.gathering.spawner': return path === '/spawnGatherings' ? spawnGatherings : undefined
    case '45.features.shop':
      if (path === '/items') return shopItems
      return /^\/items\/\d+\/requiredItems$/.test(path) ? requiredItems : undefined
    case '85.shared.recipe': return path === '/ingredients' ? recipeIngredients : undefined
    case '51.features.quest':
      if (path === '/rewards/items') return questRewards
      return path === '/acceptRequirements/items' ? requiredItems : undefined
    case '05.features.mail': return path === '/rewards' ? mailRewards : undefined
    case '52.features.quest_board': return path === '/quests' ? quests : undefined
    case '80.shared.loot':
      if (documentPath.startsWith('80.shared.loot/pool/') && path === '/contents') return lootContents
      return documentPath.startsWith('80.shared.loot/table/') && path === '/pools' ? lootPools : undefined
    case '10.features.item':
      return documentPath.startsWith('10.features.item/50.bundle/') && path === '/bundle/items' ? bundleRewards : undefined
    case '65.features.dungeon': return path === '/clearRewards/items' ? dungeonRewards : undefined
    case '46.features.fishing.area': return /^\/levelRanges\/\d+\/fish$/.test(path) ? fish : undefined
  }
  return undefined
}

export function canShowReferenceCollection(definition: ReferenceCollectionDefinition, values: JsonValue[]) {
  return values.every(value => {
    const row = referenceObject(value)
    return row !== null && (definition.key !== 'ref' || typeof row.ref === 'string')
  })
}

export function categorizedItemRow(documentPath: string, path: string) {
  const fieldPath = path.endsWith('/ref') ? parentPointer(path) : path
  const rowPath = parentPointer(fieldPath)
  if (!/\/\d+$/.test(rowPath)) return undefined
  const definition = visualReferenceCollection(documentPath, parentPointer(rowPath))
  if (definition?.itemCategory && fieldPath === rowPath + '/' + definition.key) return { rowPath, definition }
}

export const referenceObject = (value: JsonValue | undefined): JsonObject | null =>
  value !== null && typeof value === 'object' && !Array.isArray(value) ? value : null

export function referenceText(value: JsonValue | undefined) {
  const ref = referenceObject(value)?.ref ?? value
  return typeof ref === 'string' ? ref.trim() : ''
}

export function referenceId(text: string, kind: string, prefixes?: Readonly<Record<string, string>> | null) {
  const colon = text.indexOf(':')
  if (colon === -1) return text
  const prefix = text.slice(0, colon)
  return prefix === kind || prefixes?.[prefix] === kind ? text.slice(colon + 1) : text
}

export function currentReferenceFile(entry: MasterReference, files: ReadonlyMap<string, MasterFileSummary>, prefixes?: Readonly<Record<string, string>> | null) {
  const file = files.get(entry.path)
  if (!file) return undefined
  // File summaries and candidates are refreshed by separate requests.
  const kind = entry.kind ?? ''
  if (['/id', '/nodeId', '/structureId'].includes(entry.pointer)
    && referenceId(file.id ?? '', kind, prefixes) !== referenceId(entry.value, kind, prefixes)) return undefined
  return file
}

const itemCategories = new Set(['material', 'equipment', 'consumable', 'orb', 'bundle', 'rune', 'sigil', 'pet', 'pet_egg', 'currency'])

export function referencedItemCategory(file: MasterFileSummary | undefined) {
  const category = file?.path.match(/^10\.features\.item\/\d+\.([a-z_]+)\//)?.[1]
  return category && itemCategories.has(category) ? category : undefined
}

export function setCollectionReference(entry: JsonObject, definition: ReferenceCollectionDefinition, id: string,
  file?: MasterFileSummary, prefixes?: Readonly<Record<string, string>> | null): JsonObject {
  const current = entry[definition.key]
  const wrapper = referenceObject(current)
  const previous = referenceText(current)
  const category = definition.itemCategory ? referencedItemCategory(file) : undefined
  const categoryFields = category ? { category } : {}
  if (previous && referenceId(previous, definition.kind, prefixes) === id) return category && category !== entry.category
    ? { ...entry, ...categoryFields } : entry
  const colon = previous.indexOf(':')
  const knownPrefix = colon > 0 && referenceId(previous, definition.kind, prefixes) !== previous
  const prefix = knownPrefix ? previous.slice(0, colon + 1)
    : definition.encoding === 'bare' && !wrapper ? '' : definition.kind + ':'
  const ref = id ? prefix + id : ''
  const wrapped = wrapper !== null || current === undefined && definition.encoding === 'wrapped'
  return { ...entry, [definition.key]: wrapped ? { ...wrapper, ref } : ref, ...categoryFields }
}

export function newCollectionEntry(definition: ReferenceCollectionDefinition, values: JsonValue[], id: string, file?: MasterFileSummary): JsonObject {
  const base = { ...definition.initial }
  if (definition.kind === 'fishing_fish') base.chancePercent = values.length === 0 ? 100 : 1
  if (definition.placement === 'shop') {
    const existing = new Set(values.map(value => referenceObject(value)?.id))
    const stem = id || 'offer'
    let unique = stem
    for (let suffix = 2; existing.has(unique); suffix++) unique = stem + '_' + suffix
    base.id = unique
  }
  return setCollectionReference(base, definition, id, file)
}

import type { JsonObject, JsonValue } from '../types/editor'
import type { MasterFileSummary, MasterReference } from '../types/masterData'

export interface CollectionField {
  key: string
  label: string
  defaultValue?: JsonValue
}

interface ReferenceCollectionDefinition {
  kind: 'item' | 'mob' | 'gathering'
  key: 'itemId' | 'mobId' | 'gatheringId'
  title: string
  noun: string
  fields: readonly CollectionField[]
}

const drops: ReferenceCollectionDefinition = {
  kind: 'item', key: 'itemId', title: 'ドロップアイテム', noun: 'アイテム',
  fields: [{ key: 'rate', label: '確率 (%)', defaultValue: 0 }, { key: 'amount', label: '数量', defaultValue: '1' }],
}
const spawnMobs: ReferenceCollectionDefinition = {
  kind: 'mob', key: 'mobId', title: '出現するモブ', noun: 'モブ',
  fields: [{ key: 'level', label: 'レベル' }, { key: 'weight', label: '抽選重み', defaultValue: 1 }],
}
const spawnGatherings: ReferenceCollectionDefinition = {
  kind: 'gathering', key: 'gatheringId', title: '出現するギャザリング対象', noun: '採集対象',
  fields: [{ key: 'weight', label: '抽選重み', defaultValue: 1 }],
}

export function isVisualDropContainer(documentPath: string, path: string) {
  const mob = /^40\.features\.mob\/(enemy|boss)\//.test(documentPath)
  if (path === '/drops') return mob || documentPath.startsWith('42.features.gathering/')
  return mob && /^\/levels\/\d+\/drops$/.test(path)
}

export function visualReferenceCollection(documentPath: string, path: string) {
  if (path.endsWith('/items') && isVisualDropContainer(documentPath, path.slice(0, -'/items'.length))) return drops
  if (path === '/spawnMobs' && documentPath.startsWith('41.features.mob.spawner/')) return spawnMobs
  if (path === '/spawnGatherings' && documentPath.startsWith('43.features.gathering.spawner/')) return spawnGatherings
  return undefined
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

export function setCollectionReference(entry: JsonObject, definition: ReferenceCollectionDefinition, id: string): JsonObject {
  const current = entry[definition.key]
  const ref = id ? definition.kind + ':' + id : ''
  const wrapper = referenceObject(current)
  return { ...entry, [definition.key]: wrapper ? { ...wrapper, ref } : ref }
}

export function newCollectionEntry(definition: ReferenceCollectionDefinition, documentPath: string, id: string): JsonObject {
  const base: JsonObject = definition.kind === 'item'
    ? { itemId: documentPath.startsWith('42.features.gathering/') ? '' : { ref: '' }, rate: 0, amount: '1', luckAffected: true, hidden: false }
    : { weight: 1 }
  return setCollectionReference(base, definition, id)
}

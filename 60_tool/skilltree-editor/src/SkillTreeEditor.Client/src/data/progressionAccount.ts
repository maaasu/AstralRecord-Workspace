import type { JsonValue } from '../types/editor'
import type { DesignCheckpoint, DesignPreset, ProgressionDesignCatalog } from '../types/progressionDesign'
import { valueAtPointer } from './masterDataNavigation'
import { objectValue, setDesignValue } from './progressionDesign'

export interface AccountCheckpointLocation { pointer: string; level: number }
export type AccountSkillList = 'skills' | 'passives'

export function accountPermittedSkillIds(catalog: ProgressionDesignCatalog, preset: DesignPreset, checkpoint: DesignCheckpoint) {
  const selectedClass = catalog.classes.find(entry => entry.id === preset.classId)
  if (!selectedClass?.usableSkillIds) return undefined
  const learned = new Set(checkpoint.nodes ?? [])
  return new Set([...selectedClass.usableSkillIds, ...(catalog.nodes ?? []).filter(node => learned.has(node.id)).flatMap(node => node.skillIds ?? [])])
}

/** Invalid large slot numbers must remain removable without allocating their preceding slots. */
export function accountSigilSlots(unlocked: number, assigned: readonly number[]) {
  const limit = 24
  return { limited: unlocked > limit, slots: [...new Set([
    ...Array.from({ length: Math.max(0, Math.min(limit, unlocked)) }, (_, index) => index + 1), ...assigned,
  ])].sort((a, b) => a - b) }
}

function checkpointPath(content: JsonValue, checkpoint: AccountCheckpointLocation, field: string) {
  if (objectValue(valueAtPointer(content, checkpoint.pointer)).level !== checkpoint.level) throw new Error('レベル段階が変わっています。図の更新を待って選び直してください。')
  return `${checkpoint.pointer}/${field}`
}

function list(content: JsonValue, path: string) {
  const value = valueAtPointer(content, path)
  if (value !== undefined && !Array.isArray(value)) throw new Error('一覧の構造が変わっています。詳細編集で確認してください。')
  return (value ?? []) as JsonValue[]
}

export function setAccountClassLevel(content: JsonValue, checkpoint: AccountCheckpointLocation, level: number) {
  if (!Number.isSafeInteger(level) || level < 1 || level > 2147483647) throw new Error('職業レベルは1以上の整数を指定してください。')
  return setDesignValue(content, checkpointPath(content, checkpoint, 'classLevel'), level)
}

export function setAccountNode(content: JsonValue, checkpoint: AccountCheckpointLocation, nodeId: number, present: boolean) {
  const path = checkpointPath(content, checkpoint, 'nodes')
  const current = list(content, path)
  if (!Number.isSafeInteger(nodeId) || (present && (nodeId < 1 || nodeId > 2147483647))) throw new Error('有効なノードを選択してください。')
  return setDesignValue(content, path, present ? current.includes(nodeId) ? current : [...current, nodeId] : current.filter(id => id !== nodeId))
}

export function setAccountSkill(content: JsonValue, checkpoint: AccountCheckpointLocation, listName: AccountSkillList, slot: number, skillId: string | null) {
  const max = listName === 'skills' ? 8 : 12
  if (!Number.isInteger(slot) || (skillId !== null && (slot < 1 || slot > max))) throw new Error('有効なスキル枠を選択してください。')
  if (!skillId && listName === 'skills' && slot === 1) throw new Error('アクションリングの1番枠は必須です。')
  const path = checkpointPath(content, checkpoint, listName)
  const current = list(content, path)
  const index = current.findIndex(entry => objectValue(entry).slot === slot)
  if (!skillId) return setDesignValue(content, path, current.filter((_, i) => i !== index))
  if (index >= 0 && objectValue(current[index]).skill === `skill:${skillId}`) return content
  const replacement = { slot, skill: `skill:${skillId}`, skillLevel: 1 }
  return setDesignValue(content, path, (index < 0 ? [...current, replacement] : current.map((entry, i) => i === index ? replacement : entry))
    .sort((a, b) => Number(objectValue(a).slot) - Number(objectValue(b).slot)))
}

export function setAccountSigil(content: JsonValue, checkpoint: AccountCheckpointLocation, listName: AccountSkillList, skillSlot: number, sigilSlot: number, itemId: string | null) {
  if (!Number.isInteger(sigilSlot) || (itemId !== null && (sigilSlot < 1 || sigilSlot > 2147483647))) throw new Error('有効なシジル枠を選択してください。')
  const path = checkpointPath(content, checkpoint, listName)
  const current = list(content, path)
  const index = current.findIndex(entry => objectValue(entry).slot === skillSlot)
  if (index < 0) throw new Error('スキル枠が変わっています。図の更新を待って選び直してください。')
  const assigned = objectValue(current[index])
  const sigils = list(content, `${path}/${index}/sigils`)
  const sigilIndex = sigils.findIndex(entry => objectValue(entry).slot === sigilSlot)
  const next = itemId ? sigilIndex < 0 ? [...sigils, { slot: sigilSlot, item: `item:${itemId}` }] : sigils.map((entry, i) => i === sigilIndex ? { slot: sigilSlot, item: `item:${itemId}` } : entry)
    : sigils.filter((_, i) => i !== sigilIndex)
  return setDesignValue(content, path, current.map((entry, i) => i === index ? { ...assigned, sigils: next.sort((a, b) => Number(objectValue(a).slot) - Number(objectValue(b).slot)) } : entry))
}

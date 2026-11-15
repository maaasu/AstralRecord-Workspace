import type { JsonObject, JsonValue } from '../types/editor'
import { valueAtPointer } from './masterDataNavigation'
import { setDesignValue } from './progressionDesign'

export const presetDirectory = '90.shared.progression_preset'
export const validPresetId = (id: string) => /^[a-z0-9]+(?:_[a-z0-9]+)*$/.test(id)
export const presetPath = (id: string) => `${presetDirectory}/v1.${id}.yml`

function checkedLevels(content: JsonValue) {
  const levels = valueAtPointer(content, '/levels')
  if (!Array.isArray(levels) || levels.length < 1 || levels.length > 50 || levels.some(row => !row || typeof row !== 'object' || Array.isArray(row) || !Number.isInteger(row.level)))
    throw new Error('レベル構成が変わっています。再読込してください。')
  return levels as JsonObject[]
}

function checkedStage(content: JsonValue, pointer: string, expectedLevel: number) {
  const levels = checkedLevels(content)
  const index = Number(pointer.match(/^\/levels\/(\d+)$/)?.[1])
  if (!Number.isSafeInteger(index) || levels[index]?.level !== expectedLevel) throw new Error('レベル段階が変わっています。図の更新を待って選び直してください。')
  return { levels, index }
}

function validLevel(level: number) {
  if (!Number.isInteger(level) || level < 1 || level > 50) throw new Error('適用開始レベルは1〜50の整数です。')
}

export function addLevelStage(content: JsonValue, pointer: string, expectedLevel: number, nextLevel: number): JsonValue {
  validLevel(nextLevel)
  const { levels, index } = checkedStage(content, pointer, expectedLevel)
  if (levels.length >= 50) throw new Error('レベル段階は最大50件です。')
  if (levels.some(row => row.level === nextLevel)) throw new Error(`Lv.${nextLevel}は登録済みです。`)
  return setDesignValue(content, '/levels', [...levels, { ...levels[index], level: nextLevel }].sort((a, b) => Number(a.level) - Number(b.level)))
}

export function changeLevelStage(content: JsonValue, pointer: string, expectedLevel: number, nextLevel: number): JsonValue {
  validLevel(nextLevel)
  const { levels, index } = checkedStage(content, pointer, expectedLevel)
  if (expectedLevel === 1) throw new Error('Lv.1の段階は変更できません。')
  if (levels.some((row, position) => position !== index && row.level === nextLevel)) throw new Error(`Lv.${nextLevel}は登録済みです。`)
  return setDesignValue(content, '/levels', levels.map((row, position) => position === index ? { ...row, level: nextLevel } : row).sort((a, b) => Number(a.level) - Number(b.level)))
}

export function removeLevelStage(content: JsonValue, pointer: string, expectedLevel: number): JsonValue {
  const { levels, index } = checkedStage(content, pointer, expectedLevel)
  if (expectedLevel === 1 || levels.length <= 1) throw new Error('Lv.1と最後のレベル段階は削除できません。')
  return setDesignValue(content, '/levels', levels.filter((_, position) => position !== index))
}

export function changeBuildIdentity(content: JsonValue, name: string, buildId: string): JsonValue {
  if (!name.trim()) throw new Error('表示名を入力してください。')
  if (!validPresetId(buildId)) throw new Error('ビルドIDは小文字英数字とアンダースコアで指定してください。')
  return setDesignValue(setDesignValue(content, '/name', name.trim()), '/buildId', buildId)
}

export function createBlankPreset(id: string, name: string, classId: string, buildId: string, itemId: string, skillId: string, skillbookId = '', nodeIds: number[] = []): JsonObject {
  if (!validPresetId(id) || !validPresetId(classId) || !validPresetId(buildId)) throw new Error('IDは小文字英数字とアンダースコアで指定してください。')
  if (!name.trim() || !itemId || !skillId) throw new Error('表示名・武器・1番枠のスキルを指定してください。')
  if (nodeIds.some(nodeId => !Number.isInteger(nodeId) || nodeId < 1 || nodeId > 2147483647) || new Set(nodeIds).size !== nodeIds.length) throw new Error('習得ノードに無効または重複するIDがあります。')
  return { schemaVersion: 1, type: 'PROGRESSION_PRESET', id, name: name.trim(), classId, buildId,
    levels: [{ level: 1, classLevel: 1, nodes: nodeIds, equipment: [{ slot: 'MAIN_HAND', item: `item:${itemId}` },
      ...(skillbookId ? [{ slot: 'SKILLBOOK', item: `item:${skillbookId}` }] : [])], skills: [{ slot: 1, skill: `skill:${skillId}` }], passives: [] }] }
}

export function clonePreset(content: JsonValue, id: string, name: string, buildId: string): JsonValue {
  if (!validPresetId(id) || !validPresetId(buildId)) throw new Error('IDは小文字英数字とアンダースコアで指定してください。')
  if (!name.trim()) throw new Error('表示名を入力してください。')
  if (!content || typeof content !== 'object' || Array.isArray(content)) throw new Error('複製元を読み込めません。')
  return { ...content, id, name: name.trim(), buildId }
}

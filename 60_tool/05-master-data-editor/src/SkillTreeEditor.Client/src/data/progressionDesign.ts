import { replaceAtPointer, valueAtPointer } from './masterDataNavigation'
import type { JsonObject, JsonValue } from '../types/editor'
import type { DesignCheckpoint, DesignEnchantChoice, DesignEquipment, DesignEquipmentEnchant, DesignItem, DesignPreset, ProgressionDesignCatalog } from '../types/progressionDesign'

export const equipmentSlots = [
  ['MAIN_HAND', '武器'], ['OFF_HAND', 'オフハンド'], ['HEAD', '頭'], ['CHEST', '胴'], ['LEGS', '脚'], ['FEET', '足'],
  ['SKILLBOOK', 'スキルブック'], ['AMULET', 'アミュレット'],
  ['TALISMAN_1', 'タリスマン 1'], ['TALISMAN_2', 'タリスマン 2'],
  ['CHARM_1', 'チャーム 1'], ['CHARM_2', 'チャーム 2'], ['CHARM_3', 'チャーム 3'],
  ['CORE', 'コア'], ['RELIC_1', 'レリック 1'], ['RELIC_2', 'レリック 2'],
] as const
export const sourceLabels: Record<string, string> = { shop: 'ショップ・交換', recipe: 'レシピ', mob: 'モブ', gathering: '採集', dungeon: 'ダンジョン', lootTable: '報酬テーブル', lootPool: '報酬プール', fishing: '釣り', world: 'ワールド', preset: 'ビルド装備' }
export const objectValue = (value: JsonValue | undefined): JsonObject => value && typeof value === 'object' && !Array.isArray(value) ? value : {}

/** Only adds absent object fields; array indices must already exist. */
export function setDesignValue(content: JsonValue, pointer: string, value: JsonValue): JsonValue {
  if (!pointer) return value
  if (valueAtPointer(content, pointer) !== undefined) return replaceAtPointer(content, pointer, value)
  const slash = pointer.lastIndexOf('/')
  const parent = pointer.slice(0, slash)
  const key = pointer.slice(slash + 1).replaceAll('~1', '/').replaceAll('~0', '~')
  if (['__proto__', 'constructor', 'prototype'].includes(key)) throw new Error('この項目は変更できません。')
  const target = valueAtPointer(content, parent)
  if (target !== undefined && (target === null || typeof target !== 'object' || Array.isArray(target))) throw new Error('項目の構造が変わっています。再読込してください。')
  return setDesignValue(content, parent, { ...objectValue(target), [key]: value })
}

export function appendDesignEntry(content: JsonValue, pointer: string, entry: JsonValue): JsonValue {
  const current = valueAtPointer(content, pointer)
  if (current !== undefined && !Array.isArray(current)) throw new Error('一覧の構造が変わっています。詳細編集で確認してください。')
  return setDesignValue(content, pointer, [...(current ?? []), entry])
}

export function changeEquipment(content: JsonValue, checkpoint: DesignCheckpoint, slot: string, itemId: string | null,
  maxEnhanceLevel = 0, compatibleEnchants: DesignEquipmentEnchant[] = []): JsonValue {
  const path = `${checkpoint.pointer}/equipment`
  const value = valueAtPointer(content, path)
  if (!Array.isArray(value)) throw new Error('装備一覧が見つかりません。再読込してください。')
  const index = value.findIndex(entry => objectValue(entry).slot === slot)
  if (!itemId) {
    if (slot === 'MAIN_HAND') throw new Error('武器は必須です。別の武器を選択してください。')
    return setDesignValue(content, path, value.filter((_, i) => i !== index))
  }
  const previous = index < 0 ? {} : objectValue(value[index])
  const replacement = { ...previous, slot, item: `item:${itemId}`, enhanceLevel: Math.min(Number(previous.enhanceLevel ?? 0), maxEnhanceLevel) }
  if (previous.item !== replacement.item) {
    if (compatibleEnchants.length) Object.assign(replacement, { enchants: compatibleEnchants.map(enchant => ({ ...enchant })) })
    else delete (replacement as JsonObject).enchants
  }
  return setDesignValue(content, path, index < 0 ? [...value, replacement] : value.map((entry, i) => i === index ? replacement : entry))
}

/** Apply the customization as one undoable edit, checking the row that was opened. */
export function setEquipmentOptions(content: JsonValue, checkpoint: DesignCheckpoint, equipment: DesignEquipment,
  enhanceLevel: number, enchants: DesignEquipmentEnchant[]): JsonValue {
  const row = objectValue(valueAtPointer(content, equipment.pointer))
  if (row.slot !== equipment.slot || row.item !== `item:${equipment.itemId}` || valueAtPointer(content, `${checkpoint.pointer}/level`) !== checkpoint.level)
    throw new Error('装備またはレベル段階が変更されています。図の更新を待って選び直してください。')
  if (row.enchants !== undefined && (!Array.isArray(row.enchants) || row.enchants.length !== (equipment.enchants?.length ?? 0)))
    throw new Error('読み取れない付与設定があります。ビルドの詳細で原稿を確認してください。')
  const next = { ...row, enhanceLevel }
  if (enchants.length) Object.assign(next, { enchants: enchants.map(enchant => ({ ...enchant })) })
  else delete (next as JsonObject).enchants
  return setDesignValue(content, equipment.pointer, next)
}

export const enchantEffectKey = (effectId: string) => effectId.trim().toLowerCase()
export const enchantChoiceKey = (entry: Pick<DesignEquipmentEnchant, 'enchantMasterId' | 'effectId'>) =>
  `${entry.enchantMasterId.toLowerCase()}/${enchantEffectKey(entry.effectId)}`

export function compatibleEnchantChoices(item: DesignItem, catalog: ProgressionDesignCatalog) {
  return (item.maxEnchantSlots ?? 0) > 0 ? (catalog.enchantChoices ?? []).filter(choice => choice.equipmentType === item.enchantEquipmentType) : []
}

export function enchantAmount(text: string, choice: DesignEnchantChoice): string | undefined {
  if (!/^-?(?:\d+(?:\.\d{0,4})?|\.\d{1,4})$/.test(text.trim())) return undefined
  const value = Number(text)
  return Number.isFinite(value) && value >= choice.min && value <= choice.max && Math.abs(value) <= 100_000_000_000
    ? String(value) : undefined
}

export function retainEquipmentEnchants(enchants: DesignEquipmentEnchant[], item: DesignItem, catalog: ProgressionDesignCatalog) {
  const choices = new Map(compatibleEnchantChoices(item, catalog).map(choice => [enchantChoiceKey(choice), choice]))
  return enchants.filter(entry => {
    const choice = choices.get(enchantChoiceKey(entry))
    return choice && enchantAmount(entry.value, choice) !== undefined
  }).slice(0, item.maxEnchantSlots ?? 0)
}

export function equipmentRestriction(item: DesignItem, slot: string, preset: DesignPreset, checkpoint: DesignCheckpoint, catalog: ProgressionDesignCatalog): string | undefined {
  if (!item.compatibleSlots.includes(slot)) return 'この装備枠には設定できません'
  if (item.requiredLevel > checkpoint.level) return `必要プレイヤーLv.${item.requiredLevel}に達していません`
  const classId = preset.classId.toLowerCase()
  if (item.requiredClasses.length && !item.requiredClasses.some(id => id.toLowerCase() === classId)) return '選択中の職業では装備できません'
  const requirements = (item.requiredClassLevels ?? []).filter(entry => entry.classId.toLowerCase() === classId)
  const requiredClassLevel = requirements.length ? Math.min(...requirements.map(entry => Math.max(1, entry.level))) : 1
  if (Math.max(1, checkpoint.classLevel) < requiredClassLevel) return `必要職業Lv.${requiredClassLevel}に達していません`
  void catalog
  return undefined
}

export function numericDesignValue(text: string, field: string, location?: { path: string; pointer: string }): number | string | undefined {
  if (!text.trim()) return undefined
  const lootAmount = field === 'amount' && location?.path.startsWith('80.shared.loot/') === true
  const rangeAmount = field === 'amount' && (!location || !/^(45\.features\.shop|85\.shared\.recipe)\//.test(location.path))
  if ((rangeAmount || field === 'rolls' || field === 'pick') && /^\d+\s*~\s*\d+$/.test(text.trim())) {
    const [min, max] = text.split('~').map(Number)
    const valid = Number.isSafeInteger(min) && Number.isSafeInteger(max) && min <= 2147483647 && max <= 2147483647 && (field === 'amount' && !lootAmount ? min >= 1 && max >= min : min >= 0 && max >= 0)
    return valid ? `${min}~${max}` : undefined
  }
  if (!/^\d+(?:\.\d*)?$/.test(text.trim())) return undefined
  const number = Number(text)
  if (!Number.isFinite(number) || number < 0) return undefined
  if (/amount|min|max|level|rolls|pick/i.test(field) && number > 2147483647) return undefined
  if (/rate|chance/i.test(field)) return number <= 100 ? number : undefined
  if (/amount|min|max|level|rolls|pick|priceGold|requiredCurrency/i.test(field) && (!Number.isSafeInteger(number) || number < (lootAmount || /enhanceLevel|rolls|pick|priceGold|requiredCurrency/i.test(field) ? 0 : 1))) return undefined
  return number
}

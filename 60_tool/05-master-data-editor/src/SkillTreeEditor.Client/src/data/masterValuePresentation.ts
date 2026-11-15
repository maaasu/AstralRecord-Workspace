import { STATUS_TYPES, STATUS_TYPE_BY_ID, isStatusTypeId } from './statusTypes.generated'
import { MASTER_TAGS } from './masterTags.generated'
import { masterTagLabel } from './masterTagPresentation'
import type { FieldSuggestion } from '../types/editor'

// Accept both a field key and a JSON Pointer, including scalar array entries.
export function masterValueFieldKey(fieldKey: string): string {
  return fieldKey.split('/').filter((part) => part && !/^\d+$/.test(part)).at(-1)?.replaceAll('~1', '/').replaceAll('~0', '~') ?? fieldKey
}

export function masterValueReferenceKind(fieldKey: string): 'status' | 'tag' | undefined {
  const key = masterValueFieldKey(fieldKey).toLowerCase()
  if (['status', 'statusid', 'statustype'].includes(key)) return 'status'
  if (['tag', 'tags', 'requiredtooltags', 'equipmenttag'].includes(key)) return 'tag'
  return undefined
}

// Labels describe the existing Filebase contracts; IDs and numeric semantics are unchanged.
// See node.v1.schema.json, docs.buff, docs.orb, docs.class and docs.dungeon.
// Item category/rarity/slot/hand names match ItemCategory.kt, ItemRarity.java and ItemEquipment.kt.
const enumNames: Record<string, Readonly<Record<string, string>>> = {
  category: {
    BUNDLE: 'バンドル', CURRENCY: '通貨', EQUIPMENT: '装備', PET: 'ペット', PET_EGG: 'ペットの卵',
    MATERIAL: '素材', ORB: 'オーブ', CONSUMABLE: '消耗品', RUNE: 'ルーン', SIGIL: 'シジル', UNKNOWN: '不明',
  },
  rarity: { COMMON: 'コモン', UNCOMMON: 'アンコモン', RARE: 'レア', EPIC: 'エピック', LEGENDARY: 'レジェンダリー', MYTHIC: 'ミシック' },
  slot: { WEAPON: '武器', SUBWEAPON: '補助装備', HEAD: '頭', CHEST: '胴', LEGS: '脚', FEET: '足', ACCESSORY: 'アクセサリ', SKILLBOOK: 'スキルブック', TOOL: '道具', UNKNOWN: '不明' },
  handType: { ONE: '片手', TWO: '両手' },
  pointType: { CP: 'クラスポイント', PP: 'パッシブポイント' },
  modifierType: { FLAT: '定数加算', SCALAR: '割合補正', FINAL_SCALAR: '最終倍率補正' },
  type: {
    skill: 'スキル効果', status: 'ステータス効果',
    FLAT: '定数加算', SCALAR: '倍率補正', FINAL_SCALAR: '最終倍率補正',
    CLASS: 'クラス', ITEM: 'アイテム', SKILL: 'スキル', BUFF: 'バフ',
  },
  role: { TANK: 'タンク', DEALER: '攻撃役', HEALER: '回復役', SUPPORT: '支援役' },
  rankBasis: { CURRENT: '現在のランク', TARGET: '到達先のランク' },
  landscape: { INDOOR: '屋内', PLAINS: '平原' },
  enchantOperation: {
    OVERWRITE_RANDOM: 'ランダム上書き', FILL_ONE_EMPTY: '空き枠を1つ埋める', FILL_ALL_EMPTY: '空き枠をすべて埋める',
    REROLL_VALUE_RANDOM: 'ランダムな数値再抽選', REMOVE_RANDOM: 'ランダム削除', LOCK_FIRST: '先頭を固定',
  },
  entityType: { WOLF: 'オオカミ', CAT: 'ネコ', CHICKEN: 'ニワトリ', COD: 'タラ', SALMON: 'サケ', TROPICAL_FISH: '熱帯魚', PUFFERFISH: 'フグ' },
}

export function masterStatusLabel(id: string): string {
  const name = isStatusTypeId(id) ? STATUS_TYPE_BY_ID.get(id)?.displayName : undefined
  return name ? `${name}（${id}）` : id
}

export function masterValueLabel(fieldKey: string, value: string): string {
  const kind = masterValueReferenceKind(fieldKey)
  if (kind === 'status') return masterStatusLabel(value)
  if (kind === 'tag') return masterTagLabel(value, true)
  const key = masterValueFieldKey(fieldKey)
  const names = Object.hasOwn(enumNames, key) ? enumNames[key] : undefined
  // These Plugin enums accept case-insensitive API values; retain the original spelling in the label.
  const lookup = ['category', 'rarity', 'slot', 'handType'].includes(key) ? value.toUpperCase() : value
  const name = names && Object.hasOwn(names, lookup) ? names[lookup] : undefined
  return name ? `${name}（${value}）` : value
}

export const masterStatusSuggestions: readonly FieldSuggestion[] = STATUS_TYPES.map((status) => ({
  value: status.id, label: masterStatusLabel(status.id), description: status.description,
}))
export const masterTagSuggestions: readonly FieldSuggestion[] = MASTER_TAGS.map((tag) => ({
  value: tag.id, label: masterTagLabel(tag.id, true), description: tag.description,
}))

import type { JsonValue } from '../types/editor'
import { masterStatusLabel } from './masterValuePresentation'

const fieldLabels: Record<string, string> = {
  displayName: '表示名', name: '表示名', title: '題名',
  entry: '入場地点', party: 'パーティー', challenge: '挑戦ルール', clearRewards: 'クリア報酬',
  generation: '生成設定', theme: '外観テーマ', encounter: 'モブの出現設定',
  $schema: 'スキーマ参照', schemaVersion: '定義バージョン', nodeId: 'ノードID',
  icon: 'アイコン', tags: 'タグ', pointType: '消費ポイントの種類', pointCost: '消費ポイント',
  unlockCondition: '解放条件', classId: 'クラスID', playerLevel: 'プレイヤーレベル',
  effects: '効果', type: '種類', skillId: 'スキルID', status: 'ステータス', statusID: 'ステータスID', statusId: 'ステータスID', modifierType: '補正の種類', value: '値',
  stats: 'ステータス補正', baseStats: '初期ステータス補正', growthPerLevel: 'レベルごとの成長量',
  tag: 'タグID', role: '職業ロール', description: '説明', lore: '説明文',
  category: 'カテゴリ', rarity: 'レアリティ', slot: '装備スロット', handType: '持ち方', equipmentTag: '装備タグ',
  equipment: '装備設定', maxStack: '最大スタック数', saleValue: '売却額',
  requiredLevel: '必要なプレイヤーレベル', requiredClasses: '装備に必要なクラス',
  enhance: '強化設定', enchant: 'エンチャント設定', durability: '耐久設定', transcendence: '超越設定',
  unTradeable: 'トレード禁止', unSellable: '売却禁止', setId: '装備セットID',
  structureId: '構造ID', rootNodeId: '起点ノードID', nodes: 'ノード配置', edges: '接続',
  x: 'X座標', y: 'Y座標', z: 'Z座標', from: '接続元', to: '接続先',
}
export const masterFieldLabel = (key: string): string => Object.hasOwn(fieldLabels, key) ? fieldLabels[key] : masterStatusLabel(key)

/** Metadata may retain the raw key when no Japanese field label was declared. */
export function masterFieldTitle(key: string, title: unknown): string {
  return typeof title === 'string' && title !== key ? title : masterFieldLabel(key)
}

export function masterDisplayName(value: JsonValue | undefined): string {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return ''
  for (const key of ['displayName', 'name', 'title']) {
    const name = value[key]
    if (typeof name === 'string' && name.trim()) return name
  }
  return ''
}

export function fileNameIncludesId(path: string, id: string | null | undefined): boolean {
  if (!id) return false
  const fileName = path.split('/').at(-1) ?? path
  const stem = fileName.replace(/\.(ya?ml|json)$/i, '').replace(/^v\d+\./, '')
  return stem === id
}

export function namedMasterOption(name: string | null | undefined, id: string): string {
  return name && name !== id ? `${name} (${id})` : id
}

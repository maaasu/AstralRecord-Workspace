import type { JsonValue } from '../types/editor'

const fieldLabels: Record<string, string> = {
  displayName: '表示名', name: '表示名', title: '題名',
  entry: '入場地点', party: 'パーティー', challenge: '挑戦ルール', clearRewards: 'クリア報酬',
  generation: '生成設定', theme: '外観テーマ', encounter: 'モブの出現設定',
  $schema: 'スキーマ参照', schemaVersion: '定義バージョン', nodeId: 'ノードID',
  icon: 'アイコン', tags: 'タグ', pointType: '消費ポイントの種類', pointCost: '消費ポイント',
  unlockCondition: '解放条件', classId: 'クラスID', playerLevel: 'プレイヤーレベル',
  effects: '効果', type: '種類', skillId: 'スキルID', status: 'ステータス', modifierType: '補正の種類', value: '値',
  structureId: '構造ID', rootNodeId: '起点ノードID', nodes: 'ノード配置', edges: '接続',
  x: 'X座標', y: 'Y座標', z: 'Z座標', from: '接続元', to: '接続先',
}
export const masterFieldLabel = (key: string): string => fieldLabels[key] ?? key

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

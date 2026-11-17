import type { JsonObject, JsonValue } from '../types/editor'

/** Filebase のゲーム進行フロー（95.design.game_flow）。 */
export const gameFlowCategory = '95.design.game_flow'
export const gameFlowPath = (id: string) => `${gameFlowCategory}/v1.${id}.yml`
export const isGameFlowPath = (path: string) => path.startsWith(gameFlowCategory + '/') && !path.endsWith('.schema.json')
export const flowIdPattern = /^[a-z0-9]+(?:_[a-z0-9]+)*$/

export interface GameFlowNode {
  id: string
  ref?: string
  label?: string
  note?: string
  classes: string[]
  x: number
  y: number
  /** スキーマ外のキー。保存時に消さない。 */
  rest: JsonObject
}

export interface GameFlowEdge {
  from: string
  to: string
  label?: string
  note?: string
  classes: string[]
  rest: JsonObject
}

export interface GameFlow {
  id: string
  name: string
  description?: string
  classes: string[]
  nodes: GameFlowNode[]
  edges: GameFlowEdge[]
  rest: JsonObject
}

export interface GameFlowContext {
  shops: { id: string; path: string; items: string[] }[]
  itemClasses: Record<string, string[]>
}

export const edgeLabelSuggestions = ['ドロップ', '素材', '購入', '交換', '挑戦', 'クリア報酬', '解放', '転職', '移動']

export const kindLabels: Record<string, string> = {
  dungeon: 'ダンジョン', shop: 'ショップ', item: 'アイテム', mob: 'モブ', class: '職業', world: 'ワールド',
  skill: 'スキル', recipe: 'レシピ', loot_table: 'ルートテーブル', loot_pool: 'ルートプール', gathering: '採集',
  mob_spawner: 'モブスポナー', gathering_spawner: '採集スポナー', enchant: 'エンチャント', buff: 'バフ',
  set_effect: 'セット効果', mail: 'メール', guide: 'ガイド', pet: 'ペット', fishing_fish: '釣り魚',
  fishing_area: '釣りエリア', fishing_rules: '釣りルール', note: 'メモ',
}

/** ノード追加メニューに並べる参照の種類。 */
export const addableKinds = ['dungeon', 'shop', 'item', 'mob', 'class', 'world', 'gathering', 'recipe', 'skill'] as const

const isObject = (value: JsonValue | undefined): value is JsonObject => typeof value === 'object' && value !== null && !Array.isArray(value)
const text = (value: JsonValue | undefined) => typeof value === 'string' ? value : undefined
const integer = (value: JsonValue | undefined) => typeof value === 'number' && Number.isFinite(value) ? Math.round(value) : 0
const strings = (value: JsonValue | undefined) => Array.isArray(value) ? value.filter((entry): entry is string => typeof entry === 'string') : []

function without(source: JsonObject, keys: readonly string[]): JsonObject {
  return Object.fromEntries(Object.entries(source).filter(([key]) => !keys.includes(key)))
}

const nodeKeys = ['id', 'ref', 'label', 'note', 'classes', 'x', 'y'] as const
const edgeKeys = ['from', 'to', 'label', 'note', 'classes'] as const
const flowKeys = ['schemaVersion', 'type', 'id', 'name', 'description', 'classes', 'nodes', 'edges'] as const

export function parseGameFlow(content: JsonValue): GameFlow {
  const root = isObject(content) ? content : {}
  const nodes = (Array.isArray(root.nodes) ? root.nodes : []).filter(isObject).map(node => ({
    id: text(node.id) ?? '', ref: text(node.ref), label: text(node.label), note: text(node.note),
    classes: strings(node.classes), x: integer(node.x), y: integer(node.y), rest: without(node, nodeKeys),
  }))
  const edges = (Array.isArray(root.edges) ? root.edges : []).filter(isObject).map(edge => ({
    from: text(edge.from) ?? '', to: text(edge.to) ?? '', label: text(edge.label), note: text(edge.note),
    classes: strings(edge.classes), rest: without(edge, edgeKeys),
  }))
  return {
    id: text(root.id) ?? '', name: text(root.name) ?? '', description: text(root.description),
    classes: strings(root.classes), nodes, edges, rest: without(root, flowKeys),
  }
}

const optional = (key: string, value: string | undefined): JsonObject => value?.trim() ? { [key]: value } : {}
const optionalList = (key: string, values: string[]): JsonObject => values.length ? { [key]: [...values] } : {}

/** スキーマの記載順で JSON に戻す。空の任意項目は省略する。 */
export function serializeGameFlow(flow: GameFlow): JsonValue {
  return {
    schemaVersion: 1, type: 'GAME_FLOW', id: flow.id, name: flow.name,
    ...optional('description', flow.description), ...optionalList('classes', flow.classes),
    nodes: flow.nodes.map(node => ({
      id: node.id, ...optional('ref', node.ref), ...optional('label', node.label), ...optional('note', node.note),
      ...optionalList('classes', node.classes), x: Math.round(node.x), y: Math.round(node.y), ...node.rest,
    })),
    edges: flow.edges.map(edge => ({
      from: edge.from, to: edge.to, ...optional('label', edge.label), ...optional('note', edge.note),
      ...optionalList('classes', edge.classes), ...edge.rest,
    })),
    ...flow.rest,
  }
}

export function emptyGameFlow(id: string, name: string): GameFlow {
  return { id, name, classes: [], nodes: [], edges: [], rest: {} }
}

/** 参照の接頭辞（別名を含む）から種類を得る。参照がなければメモ。 */
export function refKind(ref: string | undefined, prefixes?: Readonly<Record<string, string>> | null) {
  if (!ref) return 'note'
  const prefix = ref.slice(0, Math.max(0, ref.indexOf(':')))
  return prefixes?.[prefix] ?? prefix
}

export const refId = (ref: string | undefined) => ref ? ref.slice(ref.indexOf(':') + 1) : ''

/** 職業フィルタで表示されるノード・矢印。フィルタなしなら全件。 */
export function visibleFlow(flow: GameFlow, classRef: string | null) {
  const applies = (classes: string[]) => !classRef || classes.length === 0 || classes.includes(classRef)
  const nodes = new Set(flow.nodes.filter(node => applies(node.classes)).map(node => node.id))
  const edges = new Set(flow.edges.map((edge, index) => ({ edge, index }))
    .filter(({ edge }) => nodes.has(edge.from) && nodes.has(edge.to) && applies(edge.classes)).map(({ index }) => index))
  return { nodes, edges }
}

/** 参照IDなどから、フロー内で一意のノードIDを作る。 */
export function uniqueNodeId(flow: GameFlow, seed: string) {
  const base = seed.toLowerCase().replace(/[^a-z0-9]+/g, '_').replace(/^_+|_+$/g, '') || 'node'
  const prefixed = /^[a-z0-9]/.test(base) ? base : 'node_' + base
  const used = new Set(flow.nodes.map(node => node.id))
  if (!used.has(prefixed)) return prefixed
  for (let index = 2; ; index++) if (!used.has(`${prefixed}_${index}`)) return `${prefixed}_${index}`
}

export function addNode(flow: GameFlow, node: Omit<GameFlowNode, 'id' | 'rest' | 'classes'> & { classes?: string[] }): { flow: GameFlow; id: string } {
  const id = uniqueNodeId(flow, node.ref ? refId(node.ref) : node.label ?? 'note')
  return { id, flow: { ...flow, nodes: [...flow.nodes, { classes: [], ...node, id, rest: {} }] } }
}

export function canConnect(flow: GameFlow, from: string, to: string) {
  return from !== to && flow.nodes.some(node => node.id === from) && flow.nodes.some(node => node.id === to)
    && !flow.edges.some(edge => edge.from === from && edge.to === to)
}

export function connect(flow: GameFlow, from: string, to: string, label?: string, classes: string[] = []): GameFlow {
  if (!canConnect(flow, from, to)) return flow
  return { ...flow, edges: [...flow.edges, { from, to, label, classes, rest: {} }] }
}

export function removeNodes(flow: GameFlow, ids: ReadonlySet<string>): GameFlow {
  return {
    ...flow,
    nodes: flow.nodes.filter(node => !ids.has(node.id)),
    edges: flow.edges.filter(edge => !ids.has(edge.from) && !ids.has(edge.to)),
  }
}

export const edgeKey = (edge: Pick<GameFlowEdge, 'from' | 'to'>) => `${edge.from}->${edge.to}`

/** 装備マスターの requiredClasses から、職業の初期割り当てを得る。 */
export function suggestedClasses(context: GameFlowContext | null, itemId: string) {
  return (context?.itemClasses[itemId] ?? []).map(id => 'class:' + id)
}

/**
 * ショップのノードから購入アイテムのノードと「購入」の矢印を追加する。
 * 同じアイテムのノードが既にあれば矢印だけをつなぐ。
 */
export function addShopPurchases(flow: GameFlow, shopNodeId: string, itemIds: readonly string[], context: GameFlowContext | null): GameFlow {
  const shop = flow.nodes.find(node => node.id === shopNodeId)
  if (!shop) return flow
  let next = flow
  const existingTargets = flow.edges.filter(edge => edge.from === shopNodeId).length
  let row = 0
  for (const itemId of itemIds) {
    const ref = 'item:' + itemId
    let target = next.nodes.find(node => node.ref === ref)?.id
    if (!target) {
      const added = addNode(next, {
        ref, classes: suggestedClasses(context, itemId),
        x: shop.x + 300, y: shop.y + (existingTargets + row) * 110,
      })
      next = added.flow
      target = added.id
      row++
    }
    next = connect(next, shopNodeId, target, '購入')
  }
  return next
}

export interface FlowNameResolver {
  (ref: string): string | undefined
}

/**
 * 参照先の名前を解決した、人とAIが読むための一覧。
 * 職業を指定した場合は、その職業に該当するノードと矢印だけを書き出す。
 */
export function gameFlowOutline(flow: GameFlow, resolve: FlowNameResolver, options: {
  prefixes?: Readonly<Record<string, string>> | null
  classRef?: string | null
  className?: (classRef: string) => string
} = {}) {
  const { prefixes, classRef = null } = options
  const className = options.className ?? ((value: string) => resolve(value) ?? value)
  const visible = visibleFlow(flow, classRef)
  const nodeName = (node: GameFlowNode) => node.label?.trim() || (node.ref && resolve(node.ref)) || node.ref || node.id
  const byId = new Map(flow.nodes.map(node => [node.id, node]))
  const classes = (values: string[]) => values.length ? ` {${values.map(className).join(', ')}}` : ''
  const lines = [`# ${flow.name || flow.id}（${gameFlowPath(flow.id)}）`]
  if (flow.description?.trim()) lines.push(flow.description.trim())
  if (classRef) lines.push(`職業フィルタ: ${className(classRef)}`)
  else if (flow.classes.length) lines.push(`対象職業: ${flow.classes.map(className).join(', ')}`)
  lines.push('', '## ノード')
  for (const node of flow.nodes) {
    if (!visible.nodes.has(node.id)) continue
    const kind = kindLabels[refKind(node.ref, prefixes)] ?? refKind(node.ref, prefixes)
    const reference = node.ref ? ` (${node.ref})` : ''
    const note = node.note?.trim() ? ` — ${node.note.trim()}` : ''
    lines.push(`- ${node.id}: [${kind}] ${nodeName(node)}${reference}${classes(node.classes)}${note}`)
  }
  lines.push('', '## 流れ')
  flow.edges.forEach((edge, index) => {
    if (!visible.edges.has(index)) return
    const from = byId.get(edge.from)
    const to = byId.get(edge.to)
    const label = edge.label?.trim() ? `（${edge.label.trim()}）` : ''
    const note = edge.note?.trim() ? ` — ${edge.note.trim()}` : ''
    lines.push(`- ${from ? nodeName(from) : edge.from} → ${to ? nodeName(to) : edge.to}${label}${classes(edge.classes)}${note}`)
  })
  return lines.join('\n') + '\n'
}

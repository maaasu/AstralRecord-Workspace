import type { JsonObject, JsonValue } from '../types/editor'

/** Filebase のゲーム進行フロー（95.design.game_flow）。 */
export const gameFlowCategory = '95.design.game_flow'
export const gameFlowPath = (id: string) => `${gameFlowCategory}/v1.${id}.yml`
export const isGameFlowPath = (path: string) => path.startsWith(gameFlowCategory + '/') && !path.endsWith('.schema.json')
export const flowIdPattern = /^[a-z0-9]+(?:_[a-z0-9]+)*$/

export const gameFlowPorts = ['top', 'right', 'bottom', 'left'] as const
export type GameFlowPort = typeof gameFlowPorts[number]

export const gameFlowPort = (value: unknown): GameFlowPort | undefined => gameFlowPorts.find(port => port === value)

export interface GameFlowNode {
  id: string
  ref?: string
  role?: 'action'
  label?: string
  icon?: string
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
  fromPort?: GameFlowPort
  toPort?: GameFlowPort
  label?: string
  note?: string
  classes: string[]
  rest: JsonObject
}

export interface GameFlow {
  id: string
  name: string
  icon?: string
  description?: string
  classes: string[]
  nodes: GameFlowNode[]
  edges: GameFlowEdge[]
  rest: JsonObject
}

export interface GameFlowLink {
  /** `kind:id`（config.yml の正規の接頭辞）。矢印と同じ向き。 */
  from: string
  to: string
  label: string
}

export interface GameFlowContext {
  shops: { id: string; path: string; items: string[] }[]
  itemClasses: Record<string, string[]>
  links?: GameFlowLink[]
}

export const edgeLabelSuggestions = ['ドロップ', '素材', '購入', '交換', '挑戦', 'クリア報酬', '解放', '転職', '移動']

export const kindLabels: Record<string, string> = {
  dungeon: 'ダンジョン', shop: 'ショップ', item: 'アイテム', mob: 'モブ', class: '職業', world: 'ワールド',
  skill: 'スキル', recipe: 'レシピ', loot_table: 'ルートテーブル', loot_pool: 'ルートプール', gathering: '採集',
  mob_spawner: 'モブスポナー', gathering_spawner: '採集スポナー', enchant: 'エンチャント', buff: 'バフ',
  set_effect: 'セット効果', mail: 'メール', guide: 'ガイド', pet: 'ペット', fishing_fish: '釣り魚',
  fishing_area: '釣りエリア', fishing_rules: '釣りルール', npc: 'NPC', action: '操作', note: 'メモ',
  game_flow: 'ゲーム進行フロー', progression_preset: 'ビルド',
}

/** ノード追加メニューに並べる参照の種類。 */
export const addableKinds = ['dungeon', 'shop', 'item', 'mob', 'class', 'world', 'gathering', 'recipe', 'skill', 'game_flow', 'progression_preset'] as const

const isObject = (value: JsonValue | undefined): value is JsonObject => typeof value === 'object' && value !== null && !Array.isArray(value)
/** 手書きの YAML で数値・真偽値になった文字列項目も、消さずに文字列として扱う。 */
const text = (value: JsonValue | undefined) => typeof value === 'string' ? value
  : typeof value === 'number' || typeof value === 'boolean' ? String(value) : undefined
const coordinate = (value: JsonValue | undefined) => typeof value === 'number' && Number.isFinite(value) ? Math.round(value) : undefined
const strings = (value: JsonValue | undefined) => Array.isArray(value) ? value.filter((entry): entry is string => typeof entry === 'string') : []

function without(source: JsonObject, keys: readonly string[]): JsonObject {
  return Object.fromEntries(Object.entries(source).filter(([key]) => !keys.includes(key)))
}

const nodeKeys = ['id', 'ref', 'role', 'label', 'icon', 'note', 'classes', 'x', 'y'] as const
const edgeKeys = ['from', 'to', 'fromPort', 'toPort', 'label', 'note', 'classes'] as const
const flowKeys = ['schemaVersion', 'type', 'id', 'name', 'icon', 'description', 'classes', 'nodes', 'edges'] as const

export function parseGameFlow(content: JsonValue): GameFlow {
  const root = isObject(content) ? content : {}
  // 座標のないノード（AI や手書きで追加したもの）は、重ならないよう格子状に並べる。
  let unplaced = 0
  const nodes = (Array.isArray(root.nodes) ? root.nodes : []).filter(isObject).map((node): GameFlowNode => {
    let x = coordinate(node.x)
    let y = coordinate(node.y)
    if (x === undefined || y === undefined) {
      x ??= (unplaced % 5) * 300
      y ??= Math.floor(unplaced / 5) * 140
      unplaced++
    }
    const role = node.role === 'action' ? node.role : undefined
    return {
      id: text(node.id) ?? '', ref: text(node.ref), ...(role ? { role } : {}), label: text(node.label), icon: text(node.icon), note: text(node.note),
      classes: strings(node.classes), x, y, rest: without(node, nodeKeys),
    }
  })
  const edges = (Array.isArray(root.edges) ? root.edges : []).filter(isObject).map((edge): GameFlowEdge => {
    const fromPort = gameFlowPort(edge.fromPort)
    const toPort = gameFlowPort(edge.toPort)
    return {
      from: text(edge.from) ?? '', to: text(edge.to) ?? '', ...(fromPort ? { fromPort } : {}), ...(toPort ? { toPort } : {}),
      label: text(edge.label), note: text(edge.note), classes: strings(edge.classes), rest: without(edge, edgeKeys),
    }
  })
  return {
    id: text(root.id) ?? '', name: text(root.name) ?? '', icon: text(root.icon), description: text(root.description),
    classes: strings(root.classes), nodes, edges, rest: without(root, flowKeys),
  }
}

const optional = (key: string, value: string | undefined): JsonObject => value?.trim() ? { [key]: value } : {}
const optionalList = (key: string, values: string[]): JsonObject => values.length ? { [key]: [...values] } : {}

/** スキーマの記載順で JSON に戻す。空の任意項目は省略する。 */
export function serializeGameFlow(flow: GameFlow): JsonValue {
  return {
    schemaVersion: 1, type: 'GAME_FLOW', id: flow.id, name: flow.name,
    ...optional('icon', flow.icon), ...optional('description', flow.description), ...optionalList('classes', flow.classes),
    nodes: flow.nodes.map(node => ({
      id: node.id, ...optional('ref', node.ref), ...optional('role', node.role), ...optional('label', node.label),
      ...optional('icon', node.icon), ...optional('note', node.note),
      ...optionalList('classes', node.classes), x: Math.round(node.x), y: Math.round(node.y), ...node.rest,
    })),
    edges: flow.edges.map(edge => ({
      from: edge.from, to: edge.to, ...optional('fromPort', edge.fromPort), ...optional('toPort', edge.toPort),
      ...optional('label', edge.label), ...optional('note', edge.note),
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

export function nodeKind(node: Pick<GameFlowNode, 'ref' | 'role'>,
  prefixes?: Readonly<Record<string, string>> | null, masterPath?: string) {
  if (!node.ref) return node.role ?? 'note'
  const kind = refKind(node.ref, prefixes)
  return kind === 'mob' && masterPath?.toLowerCase().startsWith('40.features.mob/npc/') ? 'npc' : kind
}

/** 付箋に接続する線は注釈として扱う。既存の矢印と同じ形式で端点を保持する。 */
export function isAnnotationEdge(from?: GameFlowNode, to?: GameFlowNode) {
  return Boolean((from && nodeKind(from) === 'note') || (to && nodeKind(to) === 'note'))
}

type EdgePorts = Pick<GameFlowEdge, 'fromPort' | 'toPort'>
type FlowPoint = Pick<GameFlowNode, 'x' | 'y'>

/** 接続点を指定していない既存の矢印は、ノードの並びに合う向かい合う側を使う。 */
export function edgePorts(edge: EdgePorts, from?: FlowPoint, to?: FlowPoint): Required<EdgePorts> {
  const dx = to && from ? to.x - from.x : 0
  const dy = to && from ? to.y - from.y : 0
  const horizontal = Math.abs(dx) >= Math.abs(dy)
  return {
    fromPort: edge.fromPort ?? (horizontal ? (dx < 0 ? 'left' : 'right') : (dy < 0 ? 'top' : 'bottom')),
    toPort: edge.toPort ?? (horizontal ? (dx < 0 ? 'right' : 'left') : (dy < 0 ? 'bottom' : 'top')),
  }
}

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
  const id = uniqueNodeId(flow, node.ref ? refId(node.ref) : node.role ?? node.label ?? 'note')
  return { id, flow: { ...flow, nodes: [...flow.nodes, { classes: [], ...node, id, rest: {} }] } }
}

export function canConnect(flow: GameFlow, from: string, to: string) {
  if (from === to) return false
  const source = flow.nodes.find(node => node.id === from)
  const target = flow.nodes.find(node => node.id === to)
  if (!source || !target) return false
  const annotation = isAnnotationEdge(source, target)
  return !flow.edges.some(edge => (edge.from === from && edge.to === to)
    || (annotation && edge.from === to && edge.to === from))
}

export function connect(flow: GameFlow, from: string, to: string, label?: string, classes: string[] = [], ports: EdgePorts = {}): GameFlow {
  if (!canConnect(flow, from, to)) return flow
  return { ...flow, edges: [...flow.edges, { from, to, ...ports, label, classes, rest: {} }] }
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
export function addShopPurchases(flow: GameFlow, shopNodeId: string, itemIds: readonly string[], context: GameFlowContext | null,
  prefixes?: Readonly<Record<string, string>> | null): GameFlow {
  const shop = flow.nodes.find(node => node.id === shopNodeId)
  if (!shop) return flow
  let next = flow
  const existingTargets = flow.edges.filter(edge => edge.from === shopNodeId).length
  let row = 0
  for (const itemId of itemIds) {
    let target = next.nodes.find(node => refKind(node.ref, prefixes) === 'item' && refId(node.ref) === itemId)?.id
    if (!target) {
      const added = addNode(next, {
        ref: 'item:' + itemId, classes: suggestedClasses(context, itemId),
        x: shop.x + 300, y: shop.y + (existingTargets + row) * rowSpacing,
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
  masterPath?: (ref: string) => string | undefined
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
    const kind = nodeKind(node, prefixes, node.ref && options.masterPath?.(node.ref))
    const reference = node.ref ? ` (${node.ref})` : ''
    const note = node.note?.trim() ? ` — ${node.note.trim()}` : ''
    lines.push(`- ${node.id}: [${kindLabels[kind] ?? kind}] ${nodeName(node)}${reference}${classes(node.classes)}${note}`)
  }
  const progression: string[] = []
  const annotations: string[] = []
  flow.edges.forEach((edge, index) => {
    if (!visible.edges.has(index)) return
    const from = byId.get(edge.from)
    const to = byId.get(edge.to)
    const label = edge.label?.trim() ? `（${edge.label.trim()}）` : ''
    const note = edge.note?.trim() ? ` — ${edge.note.trim()}` : ''
    const annotation = isAnnotationEdge(from, to)
    const separator = annotation ? '—' : '→'
    const target = annotation ? annotations : progression
    target.push(`- ${from ? nodeName(from) : edge.from} ${separator} ${to ? nodeName(to) : edge.to}${label}${classes(edge.classes)}${note}`)
  })
  lines.push('', '## 流れ', ...progression)
  if (annotations.length) lines.push('', '## 注釈', ...annotations)
  return lines.join('\n') + '\n'
}

// ---- 次に置くノードの候補（予測） ----

export type SuggestionDirection = 'next' | 'previous'

export interface FlowSuggestion {
  /** 置く・つなぐ相手の参照（正規の接頭辞）。 */
  ref: string
  label: string
  direction: SuggestionDirection
  /** 同じマスターのノードが既にあれば、そのノードへ矢印だけをつなぐ。 */
  existingNodeId?: string
  /** プレビューで表示した位置。指定があればその位置に置く（1件ずつ確定しても表示どおりに置くため）。 */
  position?: { x: number; y: number }
}

export interface SuggestionPlacement {
  suggestion: FlowSuggestion
  /** 置いた、またはつないだノード。 */
  nodeId: string
  added: boolean
}

/** 自動配置の行の間隔。ノードのアイコンと名前を収める。 */
export const rowSpacing = 140

/** 1回の Tab で配置する候補の上限。 */
export const suggestionBatchSize = 8

const lowPriorityKinds = new Set(['mob_spawner', 'gathering_spawner', 'loot_table', 'loot_pool'])

export const canonicalRef = (ref: string | undefined, prefixes?: Readonly<Record<string, string>> | null) =>
  ref ? `${refKind(ref, prefixes)}:${refId(ref)}` : ''

/**
 * 選択中のノードから、マスター同士のつながりをもとに次（右）と前（左）に置く候補を作る。
 * 既に同じ向きの矢印でつながっている相手は除く。
 */
export function suggestFor(flow: GameFlow, nodeId: string, context: GameFlowContext | null,
  prefixes?: Readonly<Record<string, string>> | null): { next: FlowSuggestion[]; previous: FlowSuggestion[] } {
  const node = flow.nodes.find(entry => entry.id === nodeId)
  const links = context?.links ?? []
  if (!node?.ref || !links.length) return { next: [], previous: [] }
  const self = canonicalRef(node.ref, prefixes)
  const byRef = new Map<string, string>()
  for (const entry of flow.nodes) if (entry.ref && !byRef.has(canonicalRef(entry.ref, prefixes))) byRef.set(canonicalRef(entry.ref, prefixes), entry.id)
  const connected = (from: string, to: string) => flow.edges.some(edge => edge.from === from && edge.to === to)
  const next: FlowSuggestion[] = []
  const previous: FlowSuggestion[] = []
  const seen = new Set<string>()
  for (const link of links) {
    const direction: SuggestionDirection | null = link.from === self ? 'next' : link.to === self ? 'previous' : null
    if (!direction) continue
    const ref = direction === 'next' ? link.to : link.from
    if (ref === self || seen.has(direction + ref)) continue
    seen.add(direction + ref)
    const existingNodeId = byRef.get(ref)
    if (existingNodeId && (direction === 'next' ? connected(node.id, existingNodeId) : connected(existingNodeId, node.id))) continue
    ;(direction === 'next' ? next : previous).push({ ref, label: link.label, direction, existingNodeId })
  }
  // 進行の節目になるマスター（ダンジョン・ワールド・ショップなど）を、スポナーやルート定義より先に並べる。
  const rank = (suggestion: FlowSuggestion) => lowPriorityKinds.has(suggestion.ref.slice(0, suggestion.ref.indexOf(':'))) ? 1 : 0
  const ordered = (list: FlowSuggestion[]) => list.map((entry, index) => ({ entry, index }))
    .sort((left, right) => rank(left.entry) - rank(right.entry) || left.index - right.index).map(({ entry }) => entry)
  return { next: ordered(next), previous: ordered(previous) }
}

const occupied = (flow: GameFlow, x: number, y: number) =>
  flow.nodes.some(node => Math.abs(node.x - x) < 240 && Math.abs(node.y - y) < rowSpacing - 10)

/** 基準の行から上下交互に、他のノードと重ならない位置を探す。 */
export function freeSlot(flow: GameFlow, x: number, y: number) {
  for (let step = 0; step < 200; step++) {
    const offset = step === 0 ? 0 : Math.ceil(step / 2) * rowSpacing * (step % 2 ? 1 : -1)
    if (!occupied(flow, x, y + offset)) return { x, y: y + offset }
  }
  return { x, y }
}

/**
 * 候補を配置する。新しいノードは選択中ノードの右（次）または左（前）の空き位置に置き、
 * 候補のラベルで矢印をつなぐ。アイテムには装備条件の職業を初期値として付ける。
 */
export function applySuggestions(flow: GameFlow, nodeId: string, suggestions: readonly FlowSuggestion[],
  context: GameFlowContext | null, classes: string[] = []): GameFlow {
  return planSuggestions(flow, nodeId, suggestions, context, classes).flow
}

/** {@link applySuggestions} と同じ配置を行い、候補ごとに置いた・つないだノードも返す（ゴースト表示用）。 */
export function planSuggestions(flow: GameFlow, nodeId: string, suggestions: readonly FlowSuggestion[],
  context: GameFlowContext | null, classes: string[] = []): { flow: GameFlow; placements: SuggestionPlacement[] } {
  const anchor = flow.nodes.find(node => node.id === nodeId)
  if (!anchor) return { flow, placements: [] }
  let next = flow
  const placements: SuggestionPlacement[] = []
  // 同じ一括配置の中で同じマスターが次・前の両方に出た場合は、1つのノードにまとめる。
  const addedByRef = new Map<string, string>()
  for (const suggestion of suggestions) {
    let target = suggestion.existingNodeId && next.nodes.some(node => node.id === suggestion.existingNodeId)
      ? suggestion.existingNodeId : addedByRef.get(suggestion.ref)
    const added = !target
    if (!target) {
      const position = suggestion.position
        ?? freeSlot(next, anchor.x + (suggestion.direction === 'next' ? 300 : -300), anchor.y)
      const kind = suggestion.ref.slice(0, suggestion.ref.indexOf(':'))
      const result = addNode(next, {
        ref: suggestion.ref, ...position,
        classes: kind === 'item' ? suggestedClasses(context, refId(suggestion.ref)) : [...classes],
      })
      next = result.flow
      target = result.id
      addedByRef.set(suggestion.ref, target)
    }
    next = suggestion.direction === 'next'
      ? connect(next, nodeId, target, suggestion.label)
      : connect(next, target, nodeId, suggestion.label)
    placements.push({ suggestion, nodeId: target, added })
  }
  return { flow: next, placements }
}

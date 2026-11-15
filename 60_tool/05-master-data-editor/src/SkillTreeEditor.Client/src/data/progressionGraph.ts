import type { DesignField, DesignItem, DesignSource, ProgressionDesignCatalog } from '../types/progressionDesign'
import { stripMinecraftFormatting } from '../utils/minecraft'

export const acquisitionColumns = ['入手先', '素材', '製作・交換', '装備'] as const
export const sourceKindNames: Record<string, string> = {
  recipe: 'レシピ', shop: 'ショップ', mob: 'モブ', gathering: '採集', dungeon: 'ダンジョン',
  lootTable: 'ルートテーブル', lootPool: '抽選プール', fishing: '釣り',
}

export const graphName = (name: string | undefined, fallback: string) => stripMinecraftFormatting(name ?? '').trim() || fallback

export function formatDesignFields(fields: DesignField[]): string {
  const names: Record<string, string> = {
    quantity: '数量', amount: '数量', count: '数量', min: '最小', max: '最大', minAmount: '最小', maxAmount: '最大',
    rate: 'rate', pick: 'pick', rolls: 'rolls', priceGold: '金額', requiredCurrency: '通貨',
  }
  return fields.map(({ pointer, value }) => {
    const key = pointer.split('/').at(-1) ?? ''
    return `${names[key] ?? key} ${value}`
  }).join(' / ')
}

export interface AcquisitionNode {
  key: string
  label: string
  itemId?: string
  item?: DesignItem
  source?: DesignSource
  sourceIds?: string[]
  notes: string[]
}
export interface AcquisitionEdge { from: string; to: string; label?: string }
export interface AcquisitionDiagram {
  columns: AcquisitionNode[][]
  edges: AcquisitionEdge[]
  routes: number
  hidden: number
}

type SourceGroup = { source: DesignSource; ids: string[] }
const cell = (column: number, id: string) => `${column}:${id}`
const location = (path: string, pointer: string) => JSON.stringify([path, pointer])

export function indexAcquisition(catalog: ProgressionDesignCatalog) {
  const items = new Map(catalog.items.map(item => [item.id, item]))
  const targets = new Map<string, DesignSource[]>()
  for (const source of catalog.sources) {
    const group = targets.get(source.itemId)
    if (group) group.push(source)
    else targets.set(source.itemId, [source])
  }
  return { items, targets }
}

function groupSources(sources: DesignSource[], selectedSourceId?: string | null): SourceGroup[] {
  const recipeContexts = new Set(sources.flatMap(source => source.kind === 'shop' && source.availability === 'configured'
    ? source.context.filter(context => context.kind === 'recipe').map(context => location(context.path, context.pointer)) : []))
  const groups = new Map<string, SourceGroup>()
  for (const source of sources) {
    if (source.id !== selectedSourceId && source.kind === 'recipe' && source.availability === 'definitionOnly'
        && recipeContexts.has(location(source.path, source.pointer))) continue
    const signature = JSON.stringify([source.kind, source.availability, source.path, source.pointer, source.itemPath,
      source.itemPointer, source.level, source.mode, source.context.map(value => [value.kind, value.path, value.pointer]),
      source.quantity, source.rates, source.costs, source.ingredients])
    const group = groups.get(signature)
    if (group) {
      if (!group.ids.includes(source.id)) group.ids.push(source.id)
      if (source.id === selectedSourceId) group.source = source
    } else groups.set(signature, { source, ids: [source.id] })
  }
  return Array.from(groups.values()).sort((a, b) =>
    Number(a.source.availability === 'definitionOnly') - Number(b.source.availability === 'definitionOnly'))
}

function takeSources(groups: SourceGroup[], limit: number, selectedSourceId?: string | null): SourceGroup[] {
  const shown = groups.slice(0, limit)
  const selected = groups.find(group => group.ids.includes(selectedSourceId ?? ''))
  if (selected && !shown.includes(selected)) {
    if (limit > 0) shown[shown.length - 1] = selected
    else shown.push(selected)
  }
  return shown
}

function itemNode(column: number, itemId: string, items: Map<string, DesignItem>): AcquisitionNode {
  const item = items.get(itemId)
  return { key: cell(column, itemId), label: graphName(item?.name, itemId), itemId, item, notes: item ? [] : ['アイテム定義なし'] }
}

function sourceNode(column: number, { source, ids }: SourceGroup): AcquisitionNode {
  return { key: cell(column, source.id), label: graphName(source.name, source.itemId), source, sourceIds: ids,
    notes: source.availability === 'definitionOnly' ? ['定義のみ'] : [] }
}

const maxRoutes = 12
const maxMaterials = 18
const maxUpstream = 24
const maxSourcesPerMaterial = 3

export function buildAcquisitionDiagram(index: ReturnType<typeof indexAcquisition>, itemId: string, selectedSourceId?: string | null): AcquisitionDiagram {
  const { items, targets } = index
  const columns: AcquisitionNode[][] = [[], [], [], []]
  const edges: AcquisitionEdge[] = []
  const equipment = itemNode(3, itemId, items)
  columns[3].push(equipment)
  const routes = groupSources(targets.get(itemId) ?? [], selectedSourceId)
  const materials = new Map<string, AcquisitionNode>()
  const hiddenMaterials = new Set<string>()
  let hidden = Math.max(0, routes.length - maxRoutes)
  for (const group of takeSources(routes, maxRoutes, selectedSourceId)) {
    const { source } = group
    const route = sourceNode(source.kind === 'recipe' || source.kind === 'shop' ? 2 : 0, group)
    columns[source.kind === 'recipe' || source.kind === 'shop' ? 2 : 0].push(route)
    edges.push({ from: route.key, to: equipment.key })
    const required = new Map<string, string[]>()
    for (const ingredient of source.ingredients) {
      const labels = required.get(ingredient.itemId) ?? []
      labels.push(formatDesignFields(ingredient.quantity))
      required.set(ingredient.itemId, labels)
    }
    let hiddenForRoute = 0
    for (const [ingredientId, labels] of required) {
      let material = materials.get(ingredientId)
      if (!material) {
        if (materials.size >= maxMaterials) {
          hiddenMaterials.add(ingredientId)
          hiddenForRoute++
          continue
        }
        material = itemNode(1, ingredientId, items)
        if (ingredientId === itemId) material.notes.push('循環参照')
        materials.set(ingredientId, material)
        columns[1].push(material)
      }
      const label = labels.filter(Boolean).join(' + ')
      edges.push({ from: material.key, to: route.key, label })
    }
    if (hiddenForRoute) route.notes.push(`ほか素材 ${hiddenForRoute} 件`)
  }
  hidden += hiddenMaterials.size
  let shownUpstream = 0
  for (const [ingredientId, material] of materials) {
    if (ingredientId === itemId) continue
    const sources = groupSources(targets.get(ingredientId) ?? [], selectedSourceId)
    if (!sources.some(({ source }) => source.availability === 'configured')) material.notes.push('入手経路未確認')
    const limit = Math.max(0, Math.min(maxSourcesPerMaterial, maxUpstream - shownUpstream))
    const displayed = takeSources(sources, limit, selectedSourceId)
    for (const group of displayed) {
      const upstream = sourceNode(0, group)
      const nested = group.source.ingredients
      if (nested.some(ingredient => ingredient.itemId === itemId || ingredient.itemId === ingredientId)) upstream.notes.push('循環参照')
      if (nested.some(ingredient => ingredient.itemId !== itemId && ingredient.itemId !== ingredientId)) upstream.notes.push('素材の先は省略')
      columns[0].push(upstream)
      edges.push({ from: upstream.key, to: material.key })
      shownUpstream++
    }
    const remaining = sources.length - displayed.length
    if (remaining > 0) material.notes.push(`ほか入手先 ${remaining} 件`)
    hidden += remaining
  }
  return { columns, edges, routes: routes.length, hidden }
}

export function selectedAcquisitionPath(diagram: AcquisitionDiagram, sourceId: string | null | undefined): Set<string> {
  const selected = diagram.columns.flat().find(node => node.sourceIds?.includes(sourceId ?? ''))
  if (!selected) return new Set()
  const path = new Set([selected.key])
  for (const [start, end] of [['from', 'to'], ['to', 'from']] as const) {
    const visited = new Set([selected.key])
    const queue = [selected.key]
    for (const key of queue) {
      for (const edge of diagram.edges) {
        if (edge[start] !== key || visited.has(edge[end])) continue
        visited.add(edge[end])
        path.add(edge[end])
        queue.push(edge[end])
      }
    }
  }
  return path
}

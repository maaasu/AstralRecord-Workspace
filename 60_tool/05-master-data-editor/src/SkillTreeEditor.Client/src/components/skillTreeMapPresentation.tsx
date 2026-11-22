import type { CSSProperties } from 'react'
import { describeNodeEffects } from '../data/nodeEffectPresentation'
import { STATUS_TYPE_BY_ID, isStatusTypeId } from '../data/statusTypes.generated'
import type { ClassMasterSummary, NodeMaster, SkillMasterSummary, StructureDocument } from '../types/editor'
import { parseMinecraftText, stripMinecraftFormatting } from '../utils/minecraft'
import { MinecraftIcon } from './MinecraftIcon'
import { MinecraftText } from './MinecraftText'
import './skillTreeMap.css'

export const NODE_KIND_LABEL = {
  small: '小ノード',
  notable: '主要ノード',
  keystone: 'キーストーン',
  gateway: '道標',
  root: '起点',
  skill: 'スキル',
}
export type NodeKind = keyof typeof NODE_KIND_LABEL

export function nodeKind(node?: NodeMaster | null, root = false): NodeKind {
  if (root) return 'root'
  if (node?.tags?.includes('keystone')) return 'keystone'
  if (node?.tags?.includes('notable')) return 'notable'
  if (node?.tags?.includes('gateway')) return 'gateway'
  if (node?.effects?.length && node.effects.every(effect =>
    effect && typeof effect === 'object' && !Array.isArray(effect) && effect.type === 'skill',
  )) return 'skill'
  return 'small'
}

export const pointType = (node?: NodeMaster | null) => node?.pointType.toUpperCase() === 'CP' ? 'CP' : 'PP'
export const mapNodeCost = (node?: NodeMaster | null) => node ? `${node.pointCost} ${node.pointType.toUpperCase()}` : '—'
export const nodeClassId = (node?: NodeMaster | null) => {
  const id = node?.unlockCondition?.classId
  return typeof id === 'string' ? id.trim() : ''
}
export function nodeClassName(node: NodeMaster | undefined | null, classes: readonly ClassMasterSummary[]) {
  const id = nodeClassId(node)
  const entry = classes.find(candidate => candidate.id.toLowerCase() === id.toLowerCase())
  return entry ? stripMinecraftFormatting(entry.name) : id || '共通'
}
export function nodeLevel(node?: NodeMaster | null) {
  const level = node?.unlockCondition?.playerLevel
  return typeof level === 'number' ? level : undefined
}

export function connectedNodeIds(edges: StructureDocument['edges'], id: string) {
  return [...new Set(edges.flatMap(edge => edge.sourceNodeId === id ? [edge.targetNodeId]
    : edge.targetNodeId === id ? [edge.sourceNodeId] : []))]
}

export function mapNodeEffects(node: NodeMaster, skills: readonly SkillMasterSummary[]) {
  return describeNodeEffects(node, skills).map((effect, index) => {
    const id = effect.kind === 'status' ? node.effects[index] : undefined
    const statusId = id && typeof id === 'object' && !Array.isArray(id) ? id.status : undefined
    const status = typeof statusId === 'string' && isStatusTypeId(statusId) ? STATUS_TYPE_BY_ID.get(statusId) : undefined
    return { ...effect, category: status?.category.toLowerCase(), statusName: status?.displayName }
  })
}
export type MapEffect = ReturnType<typeof mapNodeEffects>[number]

export function MapEffectText({ effect }: { effect: MapEffect }) {
  return effect.statusName
    ? <><span data-stat-category={effect.category}>{effect.statusName}</span>{effect.title.slice(effect.statusName.length)}</>
    : <span data-stat-category={effect.kind === 'skill' ? 'condition' : undefined}>{effect.title}</span>
}

export function MapEffectList({ effects }: { effects: readonly MapEffect[] }) {
  return effects.length
    ? <ul className="tree-map-effects">{effects.map((effect, index) =>
      <li key={index} title={effect.detail}><MapEffectText effect={effect} /></li>,
    )}</ul>
    : <p className="tree-map-muted">効果なし</p>
}

export function MapNodeIcon({ node, kind, size, revision }: {
  node?: NodeMaster | null; kind: NodeKind; size: number; revision: number
}) {
  return <span className="tree-map-icon-frame" data-point-type={pointType(node)} data-node-kind={kind}
    style={{ '--tree-icon-size': `${size}px` } as CSSProperties}>
    <MinecraftIcon icon={node?.icon ?? ''} revision={revision} />
  </span>
}

export function shortNodeName(name: string): string {
  const segments = parseMinecraftText(name)
  if (segments.reduce((length, segment) => length + Array.from(segment.text).length, 0) <= 12) return name
  let remaining = 11
  let result = ''
  for (const segment of segments) {
    const letters = Array.from(segment.text).slice(0, remaining)
    result += '§r' + (segment.color ? `§${segment.color}` : '')
      + (segment.bold ? '§l' : '') + (segment.italic ? '§o' : '')
      + (segment.underline ? '§n' : '') + (segment.strikethrough ? '§m' : '')
      + (segment.obfuscated ? '§k' : '') + letters.join('')
    remaining -= letters.length
    if (remaining === 0) break
  }
  return result + '…'
}

export function SkillTreeLegend() {
  const marks = [
    { label: '丸：PP（共通）', kind: 'small', circle: true },
    { label: '角：CP（クラス）', kind: 'small' },
    ...(['notable', 'keystone', 'gateway', 'root'] as const).map(kind => ({ label: NODE_KIND_LABEL[kind], kind, circle: true })),
  ]
  return <div className="tree-map-legend" aria-label="ノードの凡例">{marks.map(mark =>
    <span key={mark.label} data-node-kind={mark.kind}>
      <svg aria-hidden="true" width="16" height="16" viewBox="0 0 16 16">
        {mark.circle ? <circle cx="8" cy="8" r="4.5" /> : <rect x="3.5" y="3.5" width="9" height="9" rx="1.6" />}
        {mark.kind === 'keystone' && <circle className="legend-keystone-ring" cx="8" cy="8" r="7" />}
      </svg>
      {mark.label}
    </span>,
  )}</div>
}

export function MapNodeName({ node, id }: { node?: NodeMaster | null; id: string }) {
  return <MinecraftText value={node?.name ?? `不明なノード #${id}`} />
}

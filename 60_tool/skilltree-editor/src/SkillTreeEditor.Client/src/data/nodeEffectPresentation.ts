import { STATUS_TYPE_BY_ID, formatStatusModifier, isStatusTypeId } from './statusTypes.generated'
import { stripMinecraftFormatting } from '../utils/minecraft'
import type { ClassMasterSummary, JsonObject, JsonValue, NodeMaster, SkillMasterSummary } from '../types/editor'

export interface NodeEffectPresentation {
  kind: 'status' | 'skill' | 'unknown'
  title: string
  detail: string
  searchText: string
}

export interface NodeCostPresentation {
  kind: 'cp' | 'pp' | 'unknown'
  title: string
  detail: string
  searchText: string
}

export function describeNodeCost(
  node: NodeMaster,
  classes: readonly ClassMasterSummary[] = [],
): NodeCostPresentation {
  const pointType = node.pointType.toUpperCase()
  const kind = pointType === 'CP' ? 'cp' : pointType === 'PP' ? 'pp' : 'unknown'
  const classId = stringValue(asObject(node.unlockCondition)?.classId).trim().toLowerCase()
  const classMaster = classId
    ? classes.find((entry) => entry.id.toLowerCase() === classId)
    : undefined
  const className = classMaster ? stripMinecraftFormatting(classMaster.name) : classId
  const label = classId
    ? `${pointType || '未設定'}[${className || classId}]`
    : pointType || '未設定'
  const title = `${label} ${node.pointCost}`
  const classSearchText = classId ? ` ${classId} ${className} クラス条件 unlockCondition` : ''
  return {
    kind,
    title,
    detail: `${label}を${node.pointCost}消費`,
    searchText: `${title} ${label}消費 ポイント消費${classSearchText}`,
  }
}

export function describeNodeEffects(
  node: NodeMaster,
  skills: readonly SkillMasterSummary[],
): NodeEffectPresentation[] {
  const skillMap = new Map(skills.map((skill) => [skill.id, skill]))
  return (node.effects ?? []).map((rawEffect) => describeEffect(rawEffect, skillMap))
}

export function nodeTooltip(
  node: NodeMaster,
  effects: readonly NodeEffectPresentation[],
  classes: readonly ClassMasterSummary[] = [],
): string {
  const cost = describeNodeCost(node, classes)
  const lines = [`${stripMinecraftFormatting(node.name)} (#${node.nodeId})`, `消費: ${cost.title}`]
  for (const effect of effects) {
    lines.push(`・${effect.title}`)
    if (effect.detail) lines.push(`  ${effect.detail}`)
  }
  return lines.join('\n')
}

function describeEffect(
  rawEffect: JsonValue,
  skills: ReadonlyMap<string, SkillMasterSummary>,
): NodeEffectPresentation {
  const effect = asObject(rawEffect)
  const type = stringValue(effect?.type)
  if (type === 'status') {
    const statusId = stringValue(effect?.status)
    const modifierType = stringValue(effect?.modifierType)
    const value = numberValue(effect?.value)
    const status = isStatusTypeId(statusId) ? STATUS_TYPE_BY_ID.get(statusId) : undefined
    const displayName = status?.displayName ?? `未定義ステータス ${statusId || '(空)'}`
    const formatted = status && value !== null
      ? formatStatusModifier(status, modifierType, value)
      : value === null ? '値未設定' : `${value}`
    const title = `${displayName} ${formatted}`
    const detail = [statusId, modifierType].filter(Boolean).join(' · ')
    return { kind: 'status', title, detail, searchText: `${title} ${detail}` }
  }

  if (type === 'skill') {
    const skillId = stringValue(effect?.skillId)
    const skill = skills.get(skillId)
    const name = skill ? stripMinecraftFormatting(skill.name) : `未定義スキル ${skillId || '(空)'}`
    const description = skill ? stripMinecraftFormatting(skill.description) : ''
    const detail = [skillId, skill?.type, description].filter(Boolean).join(' · ')
    return { kind: 'skill', title: name, detail, searchText: `${name} ${detail}` }
  }

  const unknownType = type || '未指定'
  return {
    kind: 'unknown',
    title: `未対応効果 ${unknownType}`,
    detail: '',
    searchText: unknownType,
  }
}

const asObject = (value: JsonValue | undefined): JsonObject | null => (
  value !== null && typeof value === 'object' && !Array.isArray(value) ? value : null
)
const stringValue = (value: JsonValue | undefined): string => typeof value === 'string' ? value : ''
const numberValue = (value: JsonValue | undefined): number | null => typeof value === 'number' ? value : null

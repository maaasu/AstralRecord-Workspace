import { STATUS_TYPE_BY_ID, formatStatusModifier, isStatusTypeId } from './statusTypes.generated'
import { stripMinecraftFormatting } from '../utils/minecraft'
import type { JsonObject, JsonValue, NodeMaster, SkillMasterSummary } from '../types/editor'

export interface NodeEffectPresentation {
  kind: 'status' | 'skill' | 'unknown'
  title: string
  detail: string
  searchText: string
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
): string {
  const lines = [`${stripMinecraftFormatting(node.name)} (#${node.nodeId})`]
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

import minecraftMaterials from './minecraft-materials.1.21.11.json'
import { STATUS_TYPES } from './statusTypes.generated'
import { stripMinecraftFormatting } from '../utils/minecraft'
import type { FieldSuggestion, FieldSuggestionValue, SkillMasterSummary } from '../types/editor'

export const MINECRAFT_MATERIAL_VERSION = '1.21.11'
export const minecraftMaterialSuggestions: readonly string[] = minecraftMaterials

export const statusTypeSuggestions: readonly FieldSuggestion[] = STATUS_TYPES.map((status) => ({
  value: status.id,
  label: `${status.displayName}（${status.id}）`,
}))

export function buildNodeFieldSuggestions(
  tags: readonly string[],
  skills: readonly SkillMasterSummary[] = [],
) {
  const skillSuggestions: readonly FieldSuggestion[] = skills.map((skill) => ({
    value: skill.id,
    label: `${stripMinecraftFormatting(skill.name)}（${skill.id}）`,
    description: stripMinecraftFormatting(skill.description),
  }))
  return {
    '/icon': minecraftMaterialSuggestions,
    '/tags/*': tags,
    '/effects/*/status': statusTypeSuggestions,
    '/effects/*/skillId': skillSuggestions,
  } satisfies Readonly<Record<string, readonly FieldSuggestionValue[]>>
}

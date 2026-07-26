import minecraftMaterials from './minecraft-materials.1.21.11.json'
import { STATUS_TYPES } from './statusTypes.generated'
import { stripMinecraftFormatting } from '../utils/minecraft'
import type { FieldSuggestion, FieldSuggestionValue, SkillMasterSummary } from '../types/editor'
import { describeMasterTag, skillTreeNodeTagDefinitions } from './masterTagPresentation'

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
  const knownTagIds = new Set(skillTreeNodeTagDefinitions.map((tag) => tag.id))
  const tagSuggestions: readonly FieldSuggestion[] = [
    ...skillTreeNodeTagDefinitions.map((tag) => ({
      value: tag.id,
      label: `${tag.displayName}（${tag.id}）`,
      description: tag.description,
    })),
    ...tags.filter((tag) => !knownTagIds.has(tag)).map((tag) => {
      const presentation = describeMasterTag(tag)
      return {
        value: tag,
        label: `未定義: ${presentation.displayName}（${tag}）`,
        description: presentation.description,
      }
    }),
  ]
  const skillSuggestions: readonly FieldSuggestion[] = skills.map((skill) => ({
    value: skill.id,
    label: `${stripMinecraftFormatting(skill.name)}（${skill.id}）`,
    description: stripMinecraftFormatting(skill.description),
  }))
  return {
    '/icon': minecraftMaterialSuggestions,
    '/tags/*': tagSuggestions,
    '/effects/*/status': statusTypeSuggestions,
    '/effects/*/skillId': skillSuggestions,
  } satisfies Readonly<Record<string, readonly FieldSuggestionValue[]>>
}

import {
  MASTER_TAG_BY_ID,
  SKILLTREE_NODE_TAGS,
  type MasterTagDefinition,
} from './masterTags.generated'

export interface MasterTagPresentation {
  id: string
  displayName: string
  description: string
  known: boolean
}

export const skillTreeNodeTagDefinitions: readonly MasterTagDefinition[] = SKILLTREE_NODE_TAGS

export function describeMasterTag(id: string): MasterTagPresentation {
  const definition = MASTER_TAG_BY_ID.get(id)
  return definition
    ? {
        id: definition.id,
        displayName: definition.displayName,
        description: definition.description,
        known: true,
      }
    : {
        id,
        displayName: id,
        description: `共有タグカタログに未登録のIDです: ${id}`,
        known: false,
      }
}

export function masterTagLabel(id: string, includeId = false): string {
  const tag = describeMasterTag(id)
  return includeId && tag.displayName !== tag.id
    ? `${tag.displayName}（${tag.id}）`
    : tag.displayName
}

export function masterTagTooltip(id: string): string {
  const tag = describeMasterTag(id)
  return tag.known
    ? `${tag.displayName}（${tag.id}）\n${tag.description}`
    : tag.description
}

export function masterTagSearchText(id: string): string {
  const tag = describeMasterTag(id)
  return `${tag.id} ${tag.displayName} ${tag.description}`
}

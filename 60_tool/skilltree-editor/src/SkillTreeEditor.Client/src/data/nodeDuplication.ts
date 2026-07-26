import type { JsonObject } from '../types/editor'

export function duplicateNodeDraft(node: JsonObject): JsonObject {
  const duplicate = structuredClone(node)
  duplicate.nodeId = ''
  return duplicate
}

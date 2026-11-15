import type { JsonObject, JsonValue } from '../types/editor'

const objectOf = (value: JsonValue | undefined): JsonObject | undefined =>
  value !== null && typeof value === 'object' && !Array.isArray(value) ? value : undefined

/** Resolve reference chains, retaining the referring schema's display metadata. */
export function resolveMasterSchema(schema: JsonObject, root: JsonObject): JsonObject {
  let resolved = schema
  const seen = new Set<string>()
  while (typeof resolved.$ref === 'string' && resolved.$ref.startsWith('#/')) {
    const reference = resolved.$ref
    if (seen.has(reference)) break
    seen.add(reference)
    let target: JsonValue | undefined = root
    for (const part of reference.slice(2).split('/')) {
      const object = objectOf(target)
      const key = part.replaceAll('~1', '/').replaceAll('~0', '~')
      target = object && Object.hasOwn(object, key) ? object[key] : undefined
    }
    const definition = objectOf(target)
    if (!definition) break
    const siblings = { ...resolved }
    delete siblings.$ref
    resolved = { ...definition, ...siblings }
  }
  return resolved
}

export function masterUnionMatches(schema: JsonObject, value: JsonValue, root: JsonObject): boolean {
  const resolved = resolveMasterSchema(schema, root)
  const properties = objectOf(resolved.properties)
  const object = objectOf(value)
  if (!properties || !object) return false
  const constants = Object.entries(properties).flatMap(([key, property]) => {
    const definition = resolveMasterSchema(objectOf(property) ?? {}, root)
    return definition.const === undefined ? [] : [[key, definition.const] as const]
  })
  return constants.length > 0 && constants.every(([key, constant]) => object[key] === constant)
}

/** Keep draft fields; remove previous branch fields when the next branch forbids them. */
export function mergeMasterUnion(value: JsonValue, defaults: JsonValue, previous: JsonObject, next: JsonObject, root: JsonObject): JsonValue {
  const current = objectOf(value)
  const initial = objectOf(defaults)
  if (!current || !initial) return defaults
  const oldProperties = objectOf(resolveMasterSchema(previous, root).properties) ?? {}
  const resolvedNext = resolveMasterSchema(next, root)
  const properties = objectOf(resolvedNext.properties) ?? {}
  const merged: JsonObject = { ...initial, ...Object.fromEntries(Object.entries(current).filter(([key]) => resolvedNext.additionalProperties !== false || !Object.hasOwn(oldProperties, key) || Object.hasOwn(properties, key))) }
  for (const [key, property] of Object.entries(properties)) {
    const definition = resolveMasterSchema(objectOf(property) ?? {}, root)
    if (definition.const !== undefined) merged[key] = structuredClone(definition.const)
  }
  return merged
}

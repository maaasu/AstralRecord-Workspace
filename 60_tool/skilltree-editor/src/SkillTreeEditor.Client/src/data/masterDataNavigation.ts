import type { JsonObject, JsonValue } from '../types/editor'

function pointerParts(path: string): string[] {
  if (path === '') return []
  if (!path.startsWith('/')) throw new Error(`Invalid JSON Pointer: ${path}`)
  return path.slice(1).split('/').map((part) => {
    if (/~(?![01])/.test(part)) throw new Error(`Invalid JSON Pointer escape: ${path}`)
    return part.replaceAll('~1', '/').replaceAll('~0', '~')
  })
}

function pointerKey(key: string): string {
  return key.replaceAll('~', '~0').replaceAll('/', '~1')
}

function objectOf(value: JsonValue | undefined): JsonObject | undefined {
  return value !== null && typeof value === 'object' && !Array.isArray(value) ? value : undefined
}

function arrayIndex(part: string, length: number): number | undefined {
  if (!/^(0|[1-9]\d*)$/.test(part)) return undefined
  const index = Number(part)
  return Number.isSafeInteger(index) && index < length ? index : undefined
}

function childAt(value: JsonValue | undefined, part: string): JsonValue | undefined {
  if (Array.isArray(value)) {
    const index = arrayIndex(part, value.length)
    return index === undefined ? undefined : value[index]
  }
  const object = objectOf(value)
  return object && Object.hasOwn(object, part) ? object[part] : undefined
}

export function valueAtPointer(root: JsonValue, path: string): JsonValue | undefined {
  return pointerParts(path).reduce<JsonValue | undefined>(childAt, root)
}

export function replaceAtPointer(root: JsonValue, path: string, next: JsonValue): JsonValue {
  const parts = pointerParts(path)
  function replace(value: JsonValue, depth: number): JsonValue {
    if (depth === parts.length) return next
    const part = parts[depth]
    if (Array.isArray(value)) {
      const index = arrayIndex(part, value.length)
      if (index === undefined || !Object.hasOwn(value, index)) throw new Error(`JSON Pointer does not exist: ${path}`)
      const copy = [...value]
      copy[index] = replace(value[index], depth + 1)
      return copy
    }
    const object = objectOf(value)
    if (!object || !Object.hasOwn(object, part)) throw new Error(`JSON Pointer does not exist: ${path}`)
    const copy = { ...object }
    // defineProperty also treats "__proto__" as an ordinary JSON key.
    Object.defineProperty(copy, part, { value: replace(object[part] as JsonValue, depth + 1), writable: true, enumerable: true, configurable: true })
    return copy
  }
  return replace(root, 0)
}

export function parentPointer(path: string): string {
  const parts = pointerParts(path)
  return parts.length < 2 ? '' : `/${parts.slice(0, -1).map(pointerKey).join('/')}`
}

export function closestContainerPointer(root: JsonValue, path: string): string {
  const parts = pointerParts(path)
  let current: JsonValue | undefined = root
  let currentPath = ''
  let closest = ''
  for (const part of parts) {
    current = childAt(current, part)
    if (current === undefined) break
    currentPath += `/${pointerKey(part)}`
    if (current !== null && typeof current === 'object') closest = currentPath
  }
  return closest
}

function resolveSchema(schema: JsonObject, root: JsonObject): JsonObject {
  let resolved = schema
  const seen = new Set<string>()
  while (typeof resolved.$ref === 'string' && resolved.$ref.startsWith('#/')) {
    const reference = resolved.$ref
    if (seen.has(reference)) break
    seen.add(reference)
    const target = valueAtPointer(root, reference.slice(1))
    const definition = objectOf(target)
    if (!definition) break
    const siblings = { ...resolved }
    delete siblings.$ref
    resolved = { ...definition, ...siblings }
  }
  return resolved
}

function schemaForValue(schema: JsonObject, rootSchema: JsonObject, value: JsonValue | undefined): JsonObject {
  let resolved = resolveSchema(schema, rootSchema)
  for (let depth = 0; depth < 20; depth++) {
    const candidate = resolved.oneOf ?? resolved.anyOf
    const union = Array.isArray(candidate) ? candidate : undefined
    if (!union?.length) break
    const object = objectOf(value)
    const choices = union.map((choice) => resolveSchema(objectOf(choice) ?? {}, rootSchema))
    const selected = choices.find((choice) => {
      const properties = objectOf(choice.properties)
      return properties && Object.entries(properties).some(([key, definition]) => {
        const property = resolveSchema(objectOf(definition) ?? {}, rootSchema)
        return property.const !== undefined && object && Object.hasOwn(object, key) && property.const === object[key]
      })
    }) ?? choices[0]
    resolved = selected
  }
  return resolved
}

export function schemaAtPointer(rootSchema: JsonObject, path: string, rootValue: JsonValue): JsonObject {
  let schema = schemaForValue(rootSchema, rootSchema, rootValue)
  let value: JsonValue | undefined = rootValue
  for (const part of pointerParts(path)) {
    let childSchema: JsonObject = {}
    if (Array.isArray(value) || value === undefined && schema.type === 'array') {
      childSchema = objectOf(schema.items) ?? {}
    } else {
      const properties = objectOf(schema.properties)
      childSchema = objectOf(properties && Object.hasOwn(properties, part) ? properties[part] : undefined) ?? objectOf(schema.additionalProperties) ?? {}
    }
    value = childAt(value, part)
    schema = schemaForValue(childSchema, rootSchema, value)
  }
  return schema
}

function shortScalar(value: JsonValue | undefined): string | undefined {
  if (value === undefined) return undefined
  if (value !== null && typeof value === 'object') return undefined
  const text = typeof value === 'string' ? value.replace(/\s+/g, ' ').trim() : String(value)
  return text.length > 80 ? `${text.slice(0, 79)}…` : text
}

export function summarizeMasterValue(value: JsonValue): string {
  if (Array.isArray(value)) return `${value.length} 件`
  const object = objectOf(value)
  if (!object) return shortScalar(value) ?? ''
  const entries = Object.entries(object)
  const preferred = ['名前', 'name', 'id', 'type']
  const selected = [...preferred.filter((key) => Object.hasOwn(object, key)), ...entries.map(([key]) => key)]
  const seen = new Set<string>()
  const parts: string[] = []
  for (const key of selected) {
    if (seen.has(key)) continue
    seen.add(key)
    const scalar = shortScalar(object[key])
    if (scalar !== undefined) parts.push(`${key}: ${scalar}`)
    if (parts.length === 3) break
  }
  return parts.length ? `${parts.join(' · ')}（${entries.length} 項目）` : `${entries.length} 項目`
}

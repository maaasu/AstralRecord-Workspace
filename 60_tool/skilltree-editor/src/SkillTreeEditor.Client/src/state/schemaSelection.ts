import type { JsonObject, JsonValue, LoadedSchema } from '../types/editor'

export function referencedSchemaFileName(reference: JsonValue | undefined): string | null {
  if (typeof reference !== 'string' || !reference.trim()) return null
  const path = reference.split(/[?#]/, 1)[0].replaceAll('\\', '/')
  const fileName = path.slice(path.lastIndexOf('/') + 1)
  return fileName.endsWith('.schema.json') ? fileName : null
}

export function schemaForDocument(document: JsonObject, schemas: LoadedSchema[]): LoadedSchema | null {
  const fileName = referencedSchemaFileName(document.$schema)
  return schemas.find((schema) => schema.summary.fileName.toLocaleLowerCase() === fileName?.toLocaleLowerCase()) ?? null
}

export function defaultSchema(schemas: LoadedSchema[]): LoadedSchema | null {
  return schemas.find((schema) => schema.summary.isDefault)
    ?? [...schemas].sort((left, right) =>
      (right.summary.version ?? -1) - (left.summary.version ?? -1)
      || right.summary.fileName.localeCompare(left.summary.fileName))[0]
    ?? null
}

import { describe, expect, it } from 'vitest'
import { defaultSchema, schemaForDocument } from './schemaSelection'
import type { LoadedSchema } from '../types/editor'

const schemas: LoadedSchema[] = [
  {
    summary: { fileName: 'node.v1.schema.json', entityKind: 'node', version: 1, isDefault: false },
    content: { title: 'v1' },
  },
  {
    summary: { fileName: 'node.v2.schema.json', entityKind: 'node', version: 2, isDefault: true },
    content: { title: 'v2' },
  },
]

describe('schema selection', () => {
  it('uses the schema referenced by an existing document instead of the default', () => {
    expect(schemaForDocument({ $schema: '../schemas/node.v1.schema.json' }, schemas)?.content.title).toBe('v1')
  })

  it('uses the catalog default only for new documents', () => {
    expect(defaultSchema(schemas)?.content.title).toBe('v2')
  })
})

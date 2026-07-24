import { describe, expect, it } from 'vitest'
import { defaultFromSchema } from './SchemaForm'
import type { JsonObject } from '../types/editor'

describe('defaultFromSchema', () => {
  it('uses the first enum value even when type is omitted', () => {
    expect(defaultFromSchema({ enum: ['CP', 'PP'] })).toBe('CP')
    expect(defaultFromSchema({ enum: ['FLAT', 'SCALAR'] })).toBe('FLAT')
  })

  it('resolves local refs while creating nested defaults', () => {
    const schema: JsonObject = {
      type: 'object',
      properties: {
        effect: { $ref: '#/$defs/effect' },
      },
      $defs: {
        effect: {
          type: 'object',
          properties: {
            type: { const: 'skill' },
            skillId: { type: 'string' },
          },
        },
      },
    }
    expect(defaultFromSchema(schema)).toEqual({ effect: { type: 'skill', skillId: '' } })
  })
})

import { describe, expect, it } from 'vitest'
import { masterUnionMatches, mergeMasterUnion, resolveMasterSchema } from './masterFormSchema'
import type { JsonObject } from '../types/editor'

describe('master schema references and unions', () => {
  it('resolves reference chains and referring titles without following cycles forever', () => {
    const root: JsonObject = { $defs: { first: { $ref: '#/$defs/second' }, second: { type: 'integer', title: '元の名前' }, cycle: { $ref: '#/$defs/cycle' } } }
    expect(resolveMasterSchema({ $ref: '#/$defs/first', title: '項目の名前' }, root)).toEqual({ type: 'integer', title: '項目の名前' })
    expect(resolveMasterSchema({ $ref: '#/$defs/cycle' }, root).$ref).toBe('#/$defs/cycle')
  })

  it('matches all referenced discriminators and preserves shared and unknown fields on a switch', () => {
    const root: JsonObject = { $defs: { kind: { const: 'second' } } }
    const previous: JsonObject = { properties: { type: { const: 'first' }, common: { type: 'number' }, old: { type: 'string' } } }
    const next: JsonObject = { additionalProperties: false, properties: { type: { $ref: '#/$defs/kind' }, common: { type: 'number' }, added: { type: 'string' }, mode: { const: 'specific' } } }
    expect(masterUnionMatches(next, { type: 'second', mode: 'other' }, root)).toBe(false)
    expect(masterUnionMatches(next, { type: 'second', mode: 'specific' }, root)).toBe(true)
    expect(mergeMasterUnion({ type: 'first', common: 42, old: '旧項目', unknown: true }, { type: 'second', common: 0, added: '', mode: 'specific' }, previous, next, root))
      .toEqual({ type: 'second', common: 42, added: '', mode: 'specific', unknown: true })
  })
})

import { describe, expect, it } from 'vitest'
import type { JsonObject, JsonValue } from '../types/editor'
import { closestContainerPointer, parentPointer, replaceAtPointer, schemaAtPointer, summarizeMasterValue, valueAtPointer } from './masterDataNavigation'

describe('master data navigation', () => {
  it('reads root, empty keys, escaped keys and array indices as JSON Pointers', () => {
    const value = { '': { 'a/b~c': [{ id: 'first' }] }, constructor: 'own' }
    expect(valueAtPointer(value, '')).toBe(value)
    expect(valueAtPointer(value, '//a~1b~0c/0/id')).toBe('first')
    expect(valueAtPointer(value, '/constructor')).toBe('own')
    expect(valueAtPointer(value, '/toString')).toBeUndefined()
    expect(valueAtPointer(value, '//a~1b~0c/01')).toBeUndefined()
    expect(() => valueAtPointer(value, '/a~2b')).toThrow()
    expect(() => valueAtPointer(value, 'a/b')).toThrow()
  })

  it('replaces only an existing path without mutating or dropping siblings or prototype-like keys', () => {
    const value = JSON.parse('{"__proto__":{"a/b~c":[{"name":"old","extra":7}]},"constructor":"kept","unknown":{"flag":true}}') as JsonValue
    const changed = replaceAtPointer(value, '/__proto__/a~1b~0c/0/name', 'new')
    expect(valueAtPointer(value, '/__proto__/a~1b~0c/0/name')).toBe('old')
    expect(valueAtPointer(changed, '/__proto__/a~1b~0c/0/name')).toBe('new')
    expect(valueAtPointer(changed, '/__proto__/a~1b~0c/0/extra')).toBe(7)
    expect(valueAtPointer(changed, '/constructor')).toBe('kept')
    expect(valueAtPointer(changed, '/unknown/flag')).toBe(true)
    expect(Object.getPrototypeOf(changed)).toBe(Object.prototype)
    expect(Object.hasOwn(changed as JsonObject, '__proto__')).toBe(true)
    expect((changed as JsonObject).unknown).toBe((value as JsonObject).unknown)
    expect(replaceAtPointer(value, '', null)).toBeNull()
  })

  it('rejects missing keys and invalid array positions on replacement', () => {
    const value = { items: [1], empty: {} }
    for (const path of ['/missing', '/items/1', '/items/01', '/items/-', '/items/foo', '/empty/toString']) {
      expect(() => replaceAtPointer(value, path, 2)).toThrow()
    }
    expect(value).toEqual({ items: [1], empty: {} })
  })

  it('finds parent and closest existing container, including an empty object key', () => {
    const value = { '': { list: [{ leaf: 4 }] } }
    expect(parentPointer('')).toBe('')
    expect(parentPointer('/')).toBe('')
    expect(parentPointer('//list/0/leaf')).toBe('//list/0')
    expect(closestContainerPointer(value, '//list/0')).toBe('//list/0')
    expect(closestContainerPointer(value, '//list/0/leaf')).toBe('//list/0')
    expect(closestContainerPointer(value, '//list/4/missing')).toBe('//list')
    expect(closestContainerPointer(value, '/absent/key')).toBe('')
  })

  it('walks local references, selected const unions, properties, map values and array items', () => {
    const schema: JsonObject = {
      type: 'object',
      properties: {
        items: { type: 'array', items: { $ref: '#/$defs/entry' } },
        map: { type: 'object', additionalProperties: { $ref: '#/$defs/entry' } },
      },
      $defs: {
        entry: { oneOf: [
          { title: '攻撃', properties: { type: { const: 'attack' }, damage: { type: 'integer' } } },
          { title: '回復', properties: { type: { const: 'heal' }, amount: { type: 'number' } } },
        ] },
      },
    }
    const value = { items: [{ type: 'heal', amount: 2 }], map: { 'a/b': { type: 'attack', damage: 3 } } }
    expect(schemaAtPointer(schema, '/items/0', value).title).toBe('回復')
    expect(schemaAtPointer(schema, '/items/0/amount', value).type).toBe('number')
    expect(schemaAtPointer(schema, '/map/a~1b/damage', value).type).toBe('integer')
    expect(schemaAtPointer(schema, '/items/0/other', value)).toEqual({})
  })

  it('selects anyOf by an own discriminator and uses the first variant otherwise', () => {
    const schema: JsonObject = { anyOf: [
      { title: '既定', properties: { kind: { const: 'first' }, value: { type: 'string' } } },
      { title: '別型', properties: { kind: { const: 'second' }, value: { type: 'number' } } },
    ] }
    expect(schemaAtPointer(schema, '/value', { kind: 'second', value: 1 }).type).toBe('number')
    expect(schemaAtPointer(schema, '', { value: 'fallback' }).title).toBe('既定')
    expect(schemaAtPointer({ properties: {} }, '/__proto__', { __proto__: 1 })).toEqual({})
  })

  it('resolves chained local references with escaped schema keys', () => {
    const schema: JsonObject = {
      properties: { nested: { $ref: '#/$defs/a~1b' } },
      $defs: { 'a/b': { $ref: '#/$defs/final' }, final: { type: 'object', properties: { count: { type: 'integer' } } } },
    }
    expect(schemaAtPointer(schema, '/nested/count', { nested: { count: 2 } }).type).toBe('integer')
  })

  it('summarizes collection sizes, useful scalar fields and long text', () => {
    expect(summarizeMasterValue([1, 2, 3])).toBe('3 件')
    expect(summarizeMasterValue({ nested: { x: 1 } })).toBe('1 項目')
    expect(summarizeMasterValue({ other: 1, name: 'Sword', id: 'sword', type: 'weapon' })).toBe('name: Sword · id: sword · type: weapon（4 項目）')
    expect(summarizeMasterValue('a'.repeat(100))).toHaveLength(80)
    expect(summarizeMasterValue({ description: 'a'.repeat(100) })).toContain('…')
  })
})

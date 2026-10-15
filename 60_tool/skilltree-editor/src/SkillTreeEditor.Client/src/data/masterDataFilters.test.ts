import { describe, expect, it } from 'vitest'
import type { MasterFileSummary, MasterFilterEntry, MasterFilterIndex } from '../types/masterData'
import { categoryFacetOptions, categoryNumericOptions, matchesNumericFilters, numericFilterError } from './masterDataFilters'

const file = (path: string): MasterFileSummary => ({ path, category: 'sample', format: 'yaml', revision: '1', size: 1, modifiedUtc: '' })
const entry: MasterFilterEntry = { path: 'mob.yml', facets: {}, numbers: { mobLevel: [1, 20], requiredLevel: [0] } }

describe('numeric master filters', () => {
  it('matches actual profile levels, includes boundaries and does not fill gaps', () => {
    expect(matchesNumericFilters(entry, { mobLevel: { min: '1', max: '1' } })).toBe(true)
    expect(matchesNumericFilters(entry, { mobLevel: { min: '20', max: '20' } })).toBe(true)
    expect(matchesNumericFilters(entry, { mobLevel: { min: '5', max: '15' } })).toBe(false)
    expect(matchesNumericFilters(entry, { mobLevel: { min: '', max: '0' } })).toBe(false)
    expect(matchesNumericFilters(entry, { mobLevel: { min: '21', max: '' } })).toBe(false)
    expect(matchesNumericFilters(entry, { mobLevel: { min: '2e1', max: '' } })).toBe(true)
  })

  it('uses AND between numeric fields and keeps no restriction separate from undefined', () => {
    expect(matchesNumericFilters(entry, { mobLevel: { min: '20', max: '' }, requiredLevel: { min: '', max: '0' } })).toBe(true)
    expect(matchesNumericFilters(entry, { mobLevel: { min: '20', max: '' }, requiredLevel: { min: '1', max: '' } })).toBe(false)
    expect(matchesNumericFilters({ path: 'material.yml', facets: {} }, { requiredLevel: { min: '', max: '0' } })).toBe(false)
    expect(matchesNumericFilters(undefined, {})).toBe(true)
    expect(matchesNumericFilters(undefined, { requiredLevel: { min: ' ', max: '' } })).toBe(true)
    expect(matchesNumericFilters(undefined, { requiredLevel: { min: '0', max: '' } })).toBe(false)
  })

  it.each([
    { min: '20', max: '1' }, { min: 'NaN', max: '' }, { min: '', max: 'Infinity' },
    { min: '1e309', max: '' }, { min: '-', max: '' }, { min: '0x20', max: '' },
  ])('rejects invalid or reversed bounds %j without silently matching masters', (range) => {
    expect(numericFilterError({ mobLevel: range })).toBeTruthy()
    expect(matchesNumericFilters(entry, { mobLevel: range })).toBe(false)
  })

  it('collects only supported numeric fields and category files with sorted distinct finite values', () => {
    const index: MasterFilterIndex = { sources: [], skillTags: [], tags: [], entries: [entry,
      { path: 'mob2.yml', facets: {}, numbers: { mobLevel: [20, 3, NaN], secret: [1] } },
      { path: 'item.yml', facets: { unTradeable: ['true'] }, numbers: { requiredLevel: [99] } },
    ] }
    expect(categoryNumericOptions([file('mob.yml'), file('mob2.yml')], index)).toEqual({ mobLevel: [1, 3, 20], requiredLevel: [0] })
    expect(categoryNumericOptions([file('mob.yml')], null)).toEqual({})
    expect(categoryNumericOptions([file('old.yml')], { ...index, entries: [{ path: 'old.yml', facets: {} }] })).toEqual({})
    expect(categoryFacetOptions([file('item.yml')], index)).toEqual({ unTradeable: ['true'] })
  })
})

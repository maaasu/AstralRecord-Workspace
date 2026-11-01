import { afterEach, describe, expect, it, vi } from 'vitest'
import { createMasterNameMatcher, createMasterNameSearchCache } from './masterDataFilters'
import { stripMinecraftFormatting } from '../utils/minecraft'

vi.mock('../utils/minecraft', async importOriginal => {
  const actual = await importOriginal<typeof import('../utils/minecraft')>()
  return { ...actual, stripMinecraftFormatting: vi.fn(actual.stripMinecraftFormatting) }
})
afterEach(() => vi.clearAllMocks())

describe('catalog-owned master name search cache', () => {
  it('retains AND across fields, formatting, whitespace, case and missing-value semantics', () => {
    const cache = createMasterNameSearchCache()
    const candidates = [
      ['§a銀の長剣', 'SILVER-07', 'item/ERIVA/07.yml'],
      ['&#88FF88銀の盾', 'SILVER-08', 'item/ERIVA/08.yml'],
      ['銀の長剣', 'SILVER-09', 'item/midgard/09.yml'],
      [null, undefined, ''], ['ab', 'cd'], ['§r', '&a'],
    ]
    for (const query of ['', '　\t ', '§BERIVA　銀\t07', '07 銀 ERIVA 銀', '盾 silver', '銀 盾 07', 'bc', 'ab cd', 'missing']) {
      const expected = createMasterNameMatcher(query)
      const actual = cache.matcher(query)
      expect(candidates.map(values => actual(...values)), query).toEqual(candidates.map(values => expected(...values)))
    }
    expect(cache.matcher('bc')('ab', 'cd')).toBe(false)
    expect(cache.matcher('ab cd')('ab', 'cd')).toBe(true)
  })

  it('normalizes lazily, keeps empty normalized values, and owns no cross-catalog state', () => {
    const cache = createMasterNameSearchCache()
    const empty = cache.matcher('　')
    vi.mocked(stripMinecraftFormatting).mockClear()
    expect(empty('§a名前', 'ID', 'path')).toBe(true)
    expect(stripMinecraftFormatting).not.toHaveBeenCalled()
    const byName = cache.matcher('名前')
    vi.mocked(stripMinecraftFormatting).mockClear()
    expect(byName('§a名前', 'ID', 'path')).toBe(true)
    expect(vi.mocked(stripMinecraftFormatting).mock.calls).toEqual([['§a名前']])
    const missing = cache.matcher('absent')
    vi.mocked(stripMinecraftFormatting).mockClear()
    expect(missing('§a名前', 'ID', 'path', '&a', undefined)).toBe(false)
    expect(vi.mocked(stripMinecraftFormatting).mock.calls).toEqual([['ID'], ['path'], ['&a']])
    const anotherQuery = cache.matcher('other')
    vi.mocked(stripMinecraftFormatting).mockClear()
    expect(anotherQuery('§a名前', 'ID', 'path', '&a')).toBe(false)
    expect(stripMinecraftFormatting).not.toHaveBeenCalled()
    // A new catalog starts with fresh storage, even for strings seen before.
    const fresh = createMasterNameSearchCache().matcher('名前')
    vi.mocked(stripMinecraftFormatting).mockClear()
    expect(fresh('§a名前')).toBe(true)
    expect(stripMinecraftFormatting).toHaveBeenCalledExactlyOnceWith('§a名前')
  })

  it('measures field normalization for 1000 candidates across four searches, including the cold search', () => {
    const candidates = Array.from({ length: 1000 }, (_, index) => {
      const id = String(index).padStart(4, '0')
      return [`&a銀の装備${id}`, `GEAR-${id}`, `items/ERIVA/gear-${id}.yml`]
    })
    const queries = ['not-present', '装備 0099', 'ERIVA 0999', 'GeAr　銀']
    const run = (matcher: (query: string) => (...values: string[]) => boolean) => queries.map(query => {
      const matches = matcher(query)
      return candidates.filter(values => matches(...values)).map(values => values[1])
    })
    vi.mocked(stripMinecraftFormatting).mockClear()
    const expected = run(createMasterNameMatcher)
    const before = vi.mocked(stripMinecraftFormatting).mock.calls.length - queries.length
    const cache = createMasterNameSearchCache()
    vi.mocked(stripMinecraftFormatting).mockClear()
    expect(run(cache.matcher)).toEqual(expected)
    const after = vi.mocked(stripMinecraftFormatting).mock.calls.length - queries.length
    expect(after).toBe(3000)
    expect(before).toBeGreaterThan(after)
    vi.mocked(stripMinecraftFormatting).mockClear()
    expect(run(cache.matcher)).toEqual(expected)
    const repeated = vi.mocked(stripMinecraftFormatting).mock.calls.length - queries.length
    expect(repeated).toBe(0)
    console.info('1000 candidates × 4 queries: candidate field normalizations', { before, afterIncludingColdSearch: after, repeated })
  })
})

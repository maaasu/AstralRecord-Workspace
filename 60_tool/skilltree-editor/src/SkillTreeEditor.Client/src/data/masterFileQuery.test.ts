import { describe, expect, it } from 'vitest'
import type { MasterFileSummary, MasterFilterIndex } from '../types/masterData'
import { emptyMasterListFilters, filterMasterFiles } from './masterFileQuery'

const files: MasterFileSummary[] = [
  { path: '41.features.mob.spawner/eriva_grassboar_lv18_spawner.yml', id: 'eriva_grassboar_lv18_spawner', name: '§aグラスボア', category: 'spawner', format: 'yaml', revision: '1', size: 1, modifiedUtc: '' },
  { path: '41.features.mob.spawner/eriva_westroad_grass_lv18_spawner.yml', id: 'eriva_westroad_grass_lv18_spawner', name: '§bグラスボア', category: 'spawner', format: 'yaml', revision: '1', size: 1, modifiedUtc: '' },
  { path: '41.features.mob.spawner/eriva_westroad_grass_lv20_spawner.yml', id: 'eriva_westroad_grass_lv20_spawner', name: '§bグラスボア', category: 'spawner', format: 'yaml', revision: '1', size: 1, modifiedUtc: '' },
]

const index: MasterFilterIndex = { sources: [], skillTags: [], tags: [], entries: files.map((file, i) => ({ path: file.path, facets: { enabled: [i === 1 ? 'false' : 'true'] } })) }

describe('master file query', () => {
  it('matches multiple fields in any order and excludes a missing word', () => {
    const expected = files.slice(0, 2)
    expect(filterMasterFiles(files, index, emptyMasterListFilters(), 'eriva グラスボア 18')).toEqual(expected)
    expect(filterMasterFiles(files, index, emptyMasterListFilters(), '18　　グラスボア\teriva')).toEqual(expected)
    expect(filterMasterFiles(files, index, emptyMasterListFilters(), 'eriva グラスボア 19')).toEqual([])
  })

  it('combines name words with an existing facet filter', () => {
    const filters = { ...emptyMasterListFilters(), facetFilters: { enabled: 'true' } }
    expect(filterMasterFiles(files, index, filters, 'eriva グラスボア 18')).toEqual([files[0]])
    expect(filterMasterFiles(files, index, filters, '　\t')).toEqual([files[0], files[2]])
  })
})

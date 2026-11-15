import { expect, it, vi } from 'vitest'
import type { MasterFileSummary } from '../types/masterData'
import { emptyMasterListFilters, filterMasterFiles } from './masterFileQuery'
import { createMasterNameMatcher } from './masterDataFilters'

const { queryPreparation, valuePreparation } = vi.hoisted(() => ({
  queryPreparation: { calls: 0 },
  valuePreparation: { path: 0, id: 0, name: 0 },
}))
vi.mock('../utils/minecraft', async (importOriginal) => {
  const original = await importOriginal<typeof import('../utils/minecraft')>()
  return {
    ...original,
    stripMinecraftFormatting: (value: string) => {
      if (value === 'eriva グラスボア 18') queryPreparation.calls++
      if (value === 'path-mark') valuePreparation.path++
      if (value === 'id-mark') valuePreparation.id++
      if (value === 'name-mark') valuePreparation.name++
      return original.stripMinecraftFormatting(value)
    },
  }
})

it('prepares the query once when filtering 1000 files', () => {
  const files: MasterFileSummary[] = Array.from({ length: 1000 }, (_, index) => ({
    path: `41.features.mob.spawner/eriva_lv${index}.yml`, id: `spawner_${index}`, name: 'グラスボア',
    category: 'spawner', format: 'yaml', revision: '1', size: 1, modifiedUtc: '',
  }))
  queryPreparation.calls = 0
  expect(filterMasterFiles(files, null, emptyMasterListFilters(), 'eriva グラスボア 18').length).toBeGreaterThan(0)
  expect(queryPreparation.calls).toBe(1)
})

it('normalizes candidate values lazily and reuses each value across words', () => {
  valuePreparation.path = valuePreparation.id = valuePreparation.name = 0
  expect(createMasterNameMatcher('path')('path-mark', 'id-mark', 'name-mark')).toBe(true)
  expect(valuePreparation).toEqual({ path: 1, id: 0, name: 0 })

  valuePreparation.path = valuePreparation.id = valuePreparation.name = 0
  expect(createMasterNameMatcher('path id')('path-mark', 'id-mark', 'name-mark')).toBe(true)
  expect(valuePreparation).toEqual({ path: 1, id: 1, name: 0 })
})

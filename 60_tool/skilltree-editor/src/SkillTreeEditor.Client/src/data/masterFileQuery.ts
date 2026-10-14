import type { MasterFileSummary, MasterFilterIndex } from '../types/masterData'
import { inCategory, matchesFileStatus, matchesNumericFilters, numericFilterError, searchMasterName, type NumericFilters } from './masterDataFilters'

export interface MasterListFilters {
  format: string
  sourceQuery: string
  dropSourcePath: string
  skillTag: string
  dropItemQuery: string
  dropItemId: string
  dropPresence: string
  facetFilters: Record<string, string>
  fileStatus: string
  numericFilters: NumericFilters
}

export const emptyMasterListFilters = (): MasterListFilters => ({ format: '', sourceQuery: '', dropSourcePath: '', skillTag: '', dropItemQuery: '', dropItemId: '', dropPresence: '', facetFilters: {}, fileStatus: '', numericFilters: {} })

/** The dialog preview and the visible result list use exactly the same predicates. */
export function filterMasterFiles(files: MasterFileSummary[], index: MasterFilterIndex | null, filters: MasterListFilters, query: string): MasterFileSummary[] {
  if (numericFilterError(filters.numericFilters)) return []
  const entries = new Map(index?.entries?.map((entry) => [entry.path, entry]) ?? [])
  const source = index?.sources.find((entry) => entry.path === filters.dropSourcePath)
  const sourceItems = new Set(source?.itemIds ?? [])
  const tagged = new Set(index?.skillTags.filter((entry) => entry.tags.includes(filters.skillTag)).map((entry) => entry.path) ?? [])
  const sources = new Map(index?.sources.map((entry) => [entry.path, entry]) ?? [])
  return files.filter((file) => {
    const entry = entries.get(file.path)
    const drops = sources.get(file.path)
    return (!filters.format || file.format === filters.format)
      && (!filters.dropSourcePath || inCategory(file.path, '10.features.item') && Boolean(file.id && sourceItems.has(file.id)))
      && (!filters.skillTag || tagged.has(file.path))
      && (!filters.dropItemId || Boolean(drops?.itemIds.includes(filters.dropItemId)))
      && (!filters.dropPresence || Boolean(drops && (filters.dropPresence === 'has' ? drops.itemIds.length > 0 : drops.itemIds.length === 0)))
      && Object.entries(filters.facetFilters).every(([key, value]) => !value || entry?.facets[key]?.includes(value))
      && matchesNumericFilters(entry, filters.numericFilters)
      && matchesFileStatus(file, filters.fileStatus)
      && searchMasterName(query, file.path, file.id, file.name)
  })
}

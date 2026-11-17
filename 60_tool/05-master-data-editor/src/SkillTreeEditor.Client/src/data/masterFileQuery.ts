import type { MasterFileSummary, MasterFilterIndex } from '../types/masterData'
import { createMasterNameMatcher, inCategory, matchesFileStatus, matchesNumericFilters, numericFilterError, type NumericFilters } from './masterDataFilters'

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

export function indexMasterFilesByDirectory(files: MasterFileSummary[]): Map<string, MasterFileSummary[]> {
  const index = new Map<string, MasterFileSummary[]>()
  const add = (directory: string, file: MasterFileSummary) => {
    const entries = index.get(directory)
    if (entries) entries.push(file)
    else index.set(directory, [file])
  }
  for (const file of files) {
    let slash = file.path.indexOf('/')
    if (slash < 0) add('.', file)
    while (slash >= 0) {
      const directory = file.path.slice(0, slash)
      if (inCategory(file.path, directory)) add(directory, file)
      slash = file.path.indexOf('/', slash + 1)
    }
  }
  return index
}

/** The dialog preview and the visible result list use exactly the same predicates. */
export function filterMasterFiles(files: MasterFileSummary[], index: MasterFilterIndex | null, filters: MasterListFilters, query: string): MasterFileSummary[] {
  const activeFacets = Object.entries(filters.facetFilters).filter(([, value]) => value)
  const hasNumericFilters = Object.values(filters.numericFilters).some((range) => range.min.trim() || range.max.trim())
  const hasDropFilters = filters.dropItemId || filters.dropPresence
  if (!query.trim() && !filters.format && !filters.dropSourcePath && !filters.skillTag && !hasDropFilters
    && !filters.fileStatus && !activeFacets.length && !hasNumericFilters) return files
  if (hasNumericFilters && numericFilterError(filters.numericFilters)) return []

  const entries = index && (activeFacets.length || hasNumericFilters)
    ? new Map(index.entries?.map((entry) => [entry.path, entry])) : null
  const sourceItems = filters.dropSourcePath
    ? new Set(index?.sources.find((entry) => entry.path === filters.dropSourcePath)?.itemIds) : null
  const tagged = filters.skillTag
    ? new Set(index?.skillTags.filter((entry) => entry.tags.includes(filters.skillTag)).map((entry) => entry.path)) : null
  const sources = index && hasDropFilters ? new Map(index.sources.map((entry) => [entry.path, entry])) : null
  const matchesName = query.trim() ? createMasterNameMatcher(query) : null
  return files.filter((file) => {
    const entry = entries?.get(file.path)
    const drops = sources?.get(file.path)
    return (!filters.format || file.format === filters.format)
      && (!sourceItems || inCategory(file.path, '10.features.item') && Boolean(file.id && sourceItems.has(file.id)))
      && (!tagged || tagged.has(file.path))
      && (!filters.dropItemId || Boolean(drops?.itemIds.includes(filters.dropItemId)))
      && (!filters.dropPresence || Boolean(drops && (filters.dropPresence === 'has' ? drops.itemIds.length > 0 : drops.itemIds.length === 0)))
      && activeFacets.every(([key, value]) => entry?.facets[key]?.includes(value))
      && (!hasNumericFilters || matchesNumericFilters(entry, filters.numericFilters))
      && matchesFileStatus(file, filters.fileStatus)
      && (!matchesName || matchesName(file.path, file.id, file.name))
  })
}

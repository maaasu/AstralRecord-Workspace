import type { MasterReference } from '../types/masterData'
import { stripMinecraftFormatting } from '../utils/minecraft'

interface SearchText {
  value: string
  label?: { display: string; search: string }
}

// One index per immutable, already deduplicated candidate list. Normalize only
// entries that are searched or displayed, so a closed field need not process all names.
export function createMasterCandidateSearch(candidates: MasterReference[]) {
  const byValue = new Map(candidates.map(entry => [entry.value, entry]))
  const normalized = new Map<MasterReference, SearchText>()
  function text(entry: MasterReference) {
    let result = normalized.get(entry)
    if (!result) {
      result = { value: entry.value.toLocaleLowerCase() }
      normalized.set(entry, result)
    }
    return result
  }
  function label(entry: MasterReference) {
    const result = text(entry)
    if (!result.label) {
      const display = stripMinecraftFormatting(entry.label ?? entry.value)
      result.label = { display, search: entry.label == null ? '' : display.toLocaleLowerCase() }
    }
    return result.label
  }
  return {
    get: (value: string) => byValue.get(value),
    label: (entry: MasterReference) => label(entry).display,
    // Callers retain their existing query rules (draft formatting vs. browser trim).
    find(query: string, limit = Infinity) {
      if (limit <= 0) return []
      if (!query) return limit >= candidates.length ? candidates : candidates.slice(0, limit)
      const matches: MasterReference[] = []
      for (const entry of candidates) {
        if (text(entry).value.includes(query) || (entry.label != null && label(entry).search.includes(query))) {
          matches.push(entry)
          if (matches.length >= limit) break
        }
      }
      return matches
    },
  }
}

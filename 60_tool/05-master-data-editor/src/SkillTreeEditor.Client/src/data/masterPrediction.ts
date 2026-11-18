import { stripMinecraftFormatting } from '../utils/minecraft'

export type MasterPredictionEntry = { path: string; fieldPointer: string; text: string }

type IndexedEntry = MasterPredictionEntry & { category: string; normalizedPointer: string; formatted: boolean }
type MasterPredictionIndex = ReadonlyMap<string, readonly IndexedEntry[]>
type RankedPrediction = { text: string; rank: number }

const historyLimit = 200
const historyTextLimit = 8000
const predictionLimit = 3

function fieldLocation(pointer: string) {
  const parts = pointer.split('/').slice(1)
  const arrayIndex = /^(?:0|[1-9]\d*)$/
  return {
    key: parts.filter(part => !arrayIndex.test(part)).at(-1)?.toLowerCase() ?? '',
    normalized: '/' + parts.map(part => arrayIndex.test(part) ? '*' : part).join('/'),
  }
}

function categoryOf(path: string) {
  return path.slice(0, Math.max(0, path.lastIndexOf('/')))
}

export function buildMasterPredictionIndex(entries: readonly MasterPredictionEntry[]): MasterPredictionIndex {
  const index = new Map<string, IndexedEntry[]>()
  for (const entry of entries) {
    const field = fieldLocation(entry.fieldPointer)
    if (!field.key || !entry.text.trim()) continue
    const bucket = index.get(field.key) ?? []
    const visibleText = stripMinecraftFormatting(entry.text)
    const indexed = { ...entry, category: categoryOf(entry.path), normalizedPointer: field.normalized, formatted: visibleText !== entry.text }
    bucket.push(indexed)
    if (indexed.formatted && visibleText.trim()) bucket.push({ ...indexed, text: visibleText, formatted: false })
    index.set(field.key, bucket)
  }
  for (const bucket of index.values()) {
    bucket.sort((left, right) => left.text < right.text ? -1 : Number(left.text > right.text))
  }
  return index
}

function lowerBound(entries: readonly IndexedEntry[], prefix: string) {
  let first = 0
  let last = entries.length
  while (first < last) {
    const middle = first + Math.floor((last - first) / 2)
    if (entries[middle].text < prefix) first = middle + 1
    else last = middle
  }
  return first
}

export function findLocalPredictions(
  index: MasterPredictionIndex,
  history: readonly MasterPredictionEntry[],
  path: string,
  pointer: string,
  prefix: string,
  maxLength: number,
  multiline = false,
): string[] {
  if (prefix === '') return findMatches(index, history, path, pointer, prefix, maxLength, multiline, true)
  const exact = findMatches(index, history, path, pointer, prefix, maxLength, multiline)
  if (exact.length) return exact

  const visiblePrefix = stripMinecraftFormatting(prefix)
  if (visiblePrefix === prefix || !visiblePrefix.trim()) return []
  const visibleMaxLength = maxLength - (prefix.length - visiblePrefix.length)
  return findMatches(index, history, path, pointer, visiblePrefix, visibleMaxLength, multiline, true)
    .map(candidate => prefix + candidate.slice(visiblePrefix.length))
}

function findMatches(
  index: MasterPredictionIndex,
  history: readonly MasterPredictionEntry[],
  path: string,
  pointer: string,
  prefix: string,
  maxLength: number,
  multiline: boolean,
  visibleOnly = false,
): string[] {
  const field = fieldLocation(pointer)
  if (!field.key || (prefix !== '' && !prefix.trim()) || prefix.length >= maxLength) return []
  const category = categoryOf(path)
  const best: RankedPrediction[] = []

  const consider = (entry: MasterPredictionEntry, entryCategory: string, normalizedPointer: string, fromHistory: boolean) => {
    const { text } = entry
    if (!text.startsWith(prefix) || text.length > maxLength || !text.slice(prefix.length).trim()
      || (!multiline && /[\r\n]/.test(text))) return
    // Each higher-priority preference outweighs the lower-priority preferences combined.
    const rank = Number(fromHistory) * 8 + Number(entryCategory === category) * 4
      + Number(normalizedPointer === field.normalized) + Number(entry.fieldPointer === pointer)
    const duplicate = best.findIndex(candidate => candidate.text === text)
    if (duplicate >= 0) {
      if (best[duplicate].rank >= rank) return
      best.splice(duplicate, 1)
    }
    const position = best.findIndex(candidate => candidate.rank < rank)
    if (position < 0) best.push({ text, rank })
    else best.splice(position, 0, { text, rank })
    if (best.length > predictionLimit) best.pop()
  }

  for (const entry of history) {
    const entryField = fieldLocation(entry.fieldPointer)
    if (entryField.key !== field.key) continue
    const candidate = visibleOnly ? { ...entry, text: stripMinecraftFormatting(entry.text) } : entry
    consider(candidate, categoryOf(entry.path), entryField.normalized, true)
  }
  if (best.length === predictionLimit) return best.map(candidate => candidate.text)

  const bucket = index.get(field.key) ?? []
  for (let position = lowerBound(bucket, prefix); position < bucket.length; position++) {
    const entry = bucket[position]
    if (!entry.text.startsWith(prefix)) break
    if (visibleOnly && entry.formatted) continue
    consider(entry, entry.category, entry.normalizedPointer, false)
  }
  return best.map(candidate => candidate.text)
}

export function updatePredictionHistory(
  history: MasterPredictionEntry[],
  path: string,
  pointer: string,
  text: string,
): MasterPredictionEntry[] {
  if (!path || !fieldLocation(pointer).key || !text.trim() || text.length > historyTextLimit) return history
  const previous = history[0]
  if (previous?.path === path && previous.fieldPointer === pointer && previous.text === text) return history
  return [{ path, fieldPointer: pointer, text }, ...history.filter(entry =>
    entry.path !== path || entry.fieldPointer !== pointer || entry.text !== text,
  )].slice(0, historyLimit)
}

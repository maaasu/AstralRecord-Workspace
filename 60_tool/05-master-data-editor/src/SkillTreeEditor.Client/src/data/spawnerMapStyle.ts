// Keep master numbers independent of the current map filters and placement order.
export function spawnerMasterNumbers(ids: (string | null)[]) {
  return new Map([...new Set(ids.filter((id): id is string => !!id))].sort().map((id, index) => [id, index + 1]))
}

const masterColors = ['#56c9eb', '#f6bc64', '#c5a4ef', '#8ed5ad', '#ee9bc2', '#b8c96b', '#9cbcf5', '#eea48b']
export function spawnerMasterColor(id: string | null) {
  if (!id) return '#b0bbc8'
  let hash = 0
  for (const character of id) hash = (Math.imul(hash, 31) + character.charCodeAt(0)) | 0
  return masterColors[(hash >>> 0) % masterColors.length]
}

export const spawnerLevelBands = [
  { max: 19, label: 'Lv.1–19', color: '#81d5ed' },
  { max: 29, label: 'Lv.20–29', color: '#a4dfbc' },
  { max: 39, label: 'Lv.30–39', color: '#f4d680' },
  { max: 49, label: 'Lv.40–49', color: '#eea6d6' },
  { max: Infinity, label: 'Lv.50+', color: '#b4b4fa' },
]
export function spawnerLevels(levels: number[]) {
  const values = [...new Set(levels.filter(level => Number.isFinite(level) && level > 0))].sort((a, b) => a - b)
  return {
    label: values.length ? `Lv.${values[0]}${values.length > 1 ? `–${values.at(-1)}` : ''}` : 'Lv.不明',
    // A mixed master is not reduced to a single level band.
    color: values.length === 1 ? spawnerLevelBands.find(band => values[0] <= band.max)!.color : '#b0bbc8',
  }
}

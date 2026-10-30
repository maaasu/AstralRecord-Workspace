import type { MasterCategory, MasterFileSummary, MasterReference } from '../types/masterData'
import { stripMinecraftFormatting } from '../utils/minecraft'

const normalize = (text: string) => stripMinecraftFormatting(text).toLocaleLowerCase('ja-JP')

export function indexMasterReferences(references: MasterReference[], files: MasterFileSummary[], categories: MasterCategory[]) {
  const byPath = new Map(files.map(file => [file.path, file]))
  const byDepth = [...categories].sort((a, b) => b.directory.length - a.directory.length)
  const options = new Map<string, { key: string; label: string; count: number }>()
  const rows = references.map(entry => {
    const file = byPath.get(entry.path)
    const category = file ? byDepth.find(item => entry.path.startsWith(`${item.directory}/`)) : undefined
    // API kind describes the referenced target, not the file containing the link.
    const directory = category?.directory ?? file?.category ?? '.'
    const key = file ? `category:${directory}` : 'outside'
    const label = file ? category?.label ?? directory : 'ファイル一覧外'
    const option = options.get(key)
    if (option) option.count++
    else options.set(key, { key, label, count: 1 })
    return { entry, categoryKey: key, categoryLabel: label, canOpen: !!file,
      search: normalize(`${entry.path} ${entry.pointer} ${entry.value} ${entry.label ?? ''} ${label}`) }
  })
  return { rows, categories: [...options.values()].sort((a, b) => a.label.localeCompare(b.label, 'ja-JP') || a.key.localeCompare(b.key)) }
}

export function filterMasterReferences(index: ReturnType<typeof indexMasterReferences>, query: string, category: string) {
  const words = normalize(query).trim().split(/\s+/u).filter(Boolean)
  return index.rows.filter(row => (!category || row.categoryKey === category) && words.every(word => row.search.includes(word)))
}

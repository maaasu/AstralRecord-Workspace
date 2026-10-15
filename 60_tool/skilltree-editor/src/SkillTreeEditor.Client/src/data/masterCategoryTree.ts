import type { MasterCategory, MasterFileSummary } from '../types/masterData'
import { inCategory } from './masterDataFilters'

export interface MasterCategoryNode {
  directory: string
  label: string
  number?: string
  category?: MasterCategory
  count: number
  parent?: string
  children: MasterCategoryNode[]
}

export function buildMasterCategoryTree(categories: MasterCategory[], files: MasterFileSummary[]): MasterCategoryNode[] {
  const nodes = new Map<string, MasterCategoryNode>()
  const roots: MasterCategoryNode[] = []
  for (const category of categories) {
    const parts = category.directory.split('/')
    const labels = category.label.split(' / ')
    parts.forEach((part, index) => {
      const directory = parts.slice(0, index + 1).join('/')
      let node = nodes.get(directory)
      if (!node) {
        node = { directory, label: part, number: part.match(/^(\d+)\./)?.[1], count: 0, children: [], parent: index ? parts.slice(0, index).join('/') : undefined }
        nodes.set(directory, node)
        if (node.parent) nodes.get(node.parent)!.children.push(node)
        else roots.push(node)
      }
      // Server labels contain one segment per directory, including missing parents.
      const label = labels.length === parts.length ? labels[index] : index === parts.length - 1 ? labels.at(-1) : undefined
      if (label && (node.label === part || /[\u3040-\u30ff\u3400-\u9fff]/u.test(label))) node.label = label
      if (index === parts.length - 1) node.category = category
    })
  }
  const sort = (siblings: MasterCategoryNode[]) => {
    siblings.sort((a, b) => a.directory.localeCompare(b.directory, 'ja', { numeric: true }))
    for (const node of siblings) {
      node.count = files.filter((file) => inCategory(file.path, node.directory)).length
      sort(node.children)
    }
  }
  sort(roots)
  return roots
}

export function visibleMasterCategoryNodes(roots: MasterCategoryNode[], expanded: Set<string>): MasterCategoryNode[] {
  return roots.flatMap((node) => [node, ...(expanded.has(node.directory) ? visibleMasterCategoryNodes(node.children, expanded) : [])])
}

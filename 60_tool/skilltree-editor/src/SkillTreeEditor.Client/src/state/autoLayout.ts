import type { StructureDocument } from '../types/editor'

const compareNodeId = (left: string, right: string) => left.localeCompare(right, undefined, { numeric: true })

export function applyAuxiliaryLayout(structure: StructureDocument, spacing = 6): StructureDocument {
  const nodeIds = structure.nodes.map((node) => node.nodeId).sort(compareNodeId)
  if (nodeIds.length === 0) return structuredClone(structure)

  const placed = new Set(nodeIds)
  const adjacency = new Map(nodeIds.map((nodeId) => [nodeId, new Set<string>()]))
  for (const edge of structure.edges) {
    if (!placed.has(edge.sourceNodeId) || !placed.has(edge.targetNodeId)) continue
    adjacency.get(edge.sourceNodeId)?.add(edge.targetNodeId)
    adjacency.get(edge.targetNodeId)?.add(edge.sourceNodeId)
  }

  const depths = new Map<string, number>()
  const layoutComponent = (start: string, baseDepth: number) => {
    depths.set(start, baseDepth)
    const queue = [start]
    let maximumDepth = baseDepth
    for (let index = 0; index < queue.length; index += 1) {
      const current = queue[index]
      const nextDepth = (depths.get(current) ?? baseDepth) + 1
      const neighbors = [...(adjacency.get(current) ?? [])].sort(compareNodeId)
      for (const neighbor of neighbors) {
        if (depths.has(neighbor)) continue
        depths.set(neighbor, nextDepth)
        maximumDepth = Math.max(maximumDepth, nextDepth)
        queue.push(neighbor)
      }
    }
    return maximumDepth
  }

  const root = placed.has(structure.rootNodeId) ? structure.rootNodeId : nodeIds[0]
  let maximumDepth = layoutComponent(root, 0)
  for (const nodeId of nodeIds) {
    if (depths.has(nodeId)) continue
    maximumDepth = layoutComponent(nodeId, maximumDepth + 1)
  }

  const positions = new Map<string, { x: number; z: number }>()
  const levels = new Map<number, string[]>()
  for (const nodeId of nodeIds) {
    const depth = depths.get(nodeId) ?? 0
    levels.set(depth, [...(levels.get(depth) ?? []), nodeId])
  }
  for (const [depth, levelNodeIds] of levels) {
    levelNodeIds.sort(compareNodeId)
    levelNodeIds.forEach((nodeId, index) => {
      const x = Math.round((index - (levelNodeIds.length - 1) / 2) * spacing)
      positions.set(nodeId, { x, z: depth * spacing })
    })
  }

  return {
    ...structure,
    nodes: structure.nodes.map((node) => ({
      ...node,
      ...(positions.get(node.nodeId) ?? { x: node.x, z: node.z }),
    })),
  }
}

import type { StructureDocument, ValidationIssue } from '../types/editor'

const edgeKey = (source: string, target: string) => source < target
  ? `${source}\0${target}`
  : `${target}\0${source}`

export function validateGraph(structure: StructureDocument, masterIds: Set<string>): ValidationIssue[] {
  const issues: ValidationIssue[] = []
  const placements = new Set<string>()
  const coordinates = new Set<string>()
  for (const [index, node] of structure.nodes.entries()) {
    if (placements.has(node.nodeId)) {
      issues.push(error('DUPLICATE_PLACEMENT', `nodeId '${node.nodeId}' is placed more than once.`, `/nodes/${index}`))
    }
    placements.add(node.nodeId)
    if (!masterIds.has(node.nodeId)) {
      issues.push(error('UNKNOWN_NODE_ID', `Unknown nodeId '${node.nodeId}'.`, `/nodes/${index}/nodeId`))
    }
    const coordinate = `${node.x}|${node.y}|${node.z}`
    if (coordinates.has(coordinate)) {
      issues.push(error('DUPLICATE_COORDINATE', `Coordinate (${node.x}, ${node.y}, ${node.z}) is duplicated.`, `/nodes/${index}`))
    }
    coordinates.add(coordinate)
  }

  if (!placements.has(structure.rootNodeId)) {
    issues.push(error('ROOT_NODE_NOT_PLACED', `Root '${structure.rootNodeId}' is not placed.`, '/rootNodeId'))
  }

  const edgeKeys = new Set<string>()
  const links = new Map([...placements].map((id) => [id, new Set<string>()]))
  for (const [index, edge] of structure.edges.entries()) {
    if (edge.sourceNodeId === edge.targetNodeId) {
      issues.push(error('SELF_EDGE', `nodeId '${edge.sourceNodeId}' cannot connect to itself.`, `/edges/${index}`))
    }
    const key = edgeKey(edge.sourceNodeId, edge.targetNodeId)
    if (edgeKeys.has(key)) {
      issues.push(error('DUPLICATE_EDGE', 'The undirected edge is duplicated.', `/edges/${index}`))
    }
    edgeKeys.add(key)
    if (!placements.has(edge.sourceNodeId) || !placements.has(edge.targetNodeId)) {
      issues.push(error('EDGE_NODE_NOT_PLACED', 'Both edge endpoints must be placed.', `/edges/${index}`))
      continue
    }
    links.get(edge.sourceNodeId)?.add(edge.targetNodeId)
    links.get(edge.targetNodeId)?.add(edge.sourceNodeId)
  }

  if (placements.has(structure.rootNodeId)) {
    const visited = new Set([structure.rootNodeId])
    const queue = [structure.rootNodeId]
    while (queue.length) {
      const current = queue.shift()!
      for (const neighbor of links.get(current) ?? []) {
        if (!visited.has(neighbor)) {
          visited.add(neighbor)
          queue.push(neighbor)
        }
      }
    }
    for (const nodeId of placements) {
      if (!visited.has(nodeId)) {
        issues.push(error('UNREACHABLE_NODE', `nodeId '${nodeId}' is unreachable from root.`, '/nodes'))
      }
    }
  }

  return issues
}

const error = (code: string, message: string, path: string): ValidationIssue => ({
  code,
  message,
  path,
  severity: 'error',
})

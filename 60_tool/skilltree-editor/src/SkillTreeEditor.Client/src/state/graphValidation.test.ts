import { describe, expect, it } from 'vitest'
import type { StructureDocument } from '../types/editor'
import { validateGraph } from './graphValidation'

const structure = (overrides: Partial<StructureDocument> = {}): StructureDocument => ({
  $schema: '../schemas/structure.v1.schema.json',
  schemaVersion: 1,
  structureId: 'main',
  name: 'Main',
  rootNodeId: '1000',
  nodes: [
    { nodeId: '1000', x: 0, y: 0, z: 0 },
    { nodeId: '1001', x: 20, y: 0, z: 0 },
  ],
  edges: [{ sourceNodeId: '1000', targetNodeId: '1001' }],
  ...overrides,
})

describe('validateGraph', () => {
  it('accepts a connected graph', () => {
    expect(validateGraph(structure(), new Set(['1000', '1001']))).toEqual([])
  })

  it('detects duplicate undirected edges and unreachable nodes', () => {
    const value = structure({
      edges: [
        { sourceNodeId: '1000', targetNodeId: '1001' },
        { sourceNodeId: '1001', targetNodeId: '1000' },
      ],
      nodes: [
        { nodeId: '1000', x: 0, y: 0, z: 0 },
        { nodeId: '1001', x: 20, y: 0, z: 0 },
        { nodeId: '1002', x: 40, y: 0, z: 0 },
      ],
    })
    const codes = validateGraph(value, new Set(['1000', '1001', '1002'])).map((issue) => issue.code)
    expect(codes).toContain('DUPLICATE_EDGE')
    expect(codes).toContain('UNREACHABLE_NODE')
  })
})

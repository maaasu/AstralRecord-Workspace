import { describe, expect, it } from 'vitest'
import { applyAuxiliaryLayout } from './autoLayout'
import type { StructureDocument } from '../types/editor'

describe('applyAuxiliaryLayout', () => {
  it('writes deterministic coordinates into the structure while preserving y', () => {
    const structure: StructureDocument = {
      $schema: '../schemas/structure.v1.schema.json', schemaVersion: 1, structureId: 'main', name: 'Main',
      rootNodeId: '1000',
      nodes: [
        { nodeId: '1002', x: 99, y: 7, z: 99 },
        { nodeId: '1000', x: 99, y: 1, z: 99 },
        { nodeId: '1001', x: 99, y: 3, z: 99 },
      ],
      edges: [
        { sourceNodeId: '1000', targetNodeId: '1002' },
        { sourceNodeId: '1000', targetNodeId: '1001' },
      ],
    }

    const laidOut = applyAuxiliaryLayout(structure)

    expect(laidOut.nodes).toEqual([
      { nodeId: '1002', x: 3, y: 7, z: 6 },
      { nodeId: '1000', x: 0, y: 1, z: 0 },
      { nodeId: '1001', x: -3, y: 3, z: 6 },
    ])
    expect(structure.nodes[0].x).toBe(99)
  })
})

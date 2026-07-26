import { describe, expect, it } from 'vitest'
import type { ClassMasterSummary, NodeMaster } from '../types/editor'
import { isClassOrAncestor, isNodeVisibleInSimulation } from './nodeVisibility'

const classes: ClassMasterSummary[] = [
  { id: 'adventurer', name: '冒険者', parentClassIds: [] },
  { id: 'hunter', name: 'ハンター', parentClassIds: ['adventurer'] },
  { id: 'ranger', name: 'レンジャー', parentClassIds: ['hunter'] },
  { id: 'cycle_a', name: 'A', parentClassIds: ['cycle_b'] },
  { id: 'cycle_b', name: 'B', parentClassIds: ['cycle_a'] },
]

const node = (unlockCondition?: Record<string, string | number>): NodeMaster => ({
  $schema: '../schemas/node.v1.schema.json',
  schemaVersion: 1,
  nodeId: '1000',
  name: 'node',
  icon: 'STONE',
  lore: [],
  tags: [],
  pointType: 'CP',
  pointCost: 1,
  effects: [],
  ...(unlockCondition ? { unlockCondition } : {}),
})

describe('skill tree game visibility simulation', () => {
  it('accepts the current class and every transitive ancestor', () => {
    expect(isClassOrAncestor(classes, 'ranger', 'ranger')).toBe(true)
    expect(isClassOrAncestor(classes, 'ranger', 'hunter')).toBe(true)
    expect(isClassOrAncestor(classes, 'ranger', 'adventurer')).toBe(true)
    expect(isClassOrAncestor(classes, 'hunter', 'ranger')).toBe(false)
  })

  it('handles cyclic class definitions without looping', () => {
    expect(isClassOrAncestor(classes, 'cycle_a', 'cycle_b')).toBe(true)
    expect(isClassOrAncestor(classes, 'cycle_a', 'adventurer')).toBe(false)
  })

  it('combines current-class ancestry and player-level conditions', () => {
    const conditioned = node({ classId: 'adventurer', playerLevel: 20 })
    expect(isNodeVisibleInSimulation(conditioned, classes, { currentClassId: 'ranger', playerLevel: 20 })).toBe(true)
    expect(isNodeVisibleInSimulation(conditioned, classes, { currentClassId: 'ranger', playerLevel: 19 })).toBe(false)
    expect(isNodeVisibleInSimulation(conditioned, classes, { currentClassId: 'cycle_a', playerLevel: 20 })).toBe(false)
    expect(isNodeVisibleInSimulation(node(), classes, { currentClassId: 'cycle_a', playerLevel: 1 })).toBe(true)
  })
})

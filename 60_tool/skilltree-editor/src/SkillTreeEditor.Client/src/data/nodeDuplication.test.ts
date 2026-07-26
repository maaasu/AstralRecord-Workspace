import { describe, expect, it } from 'vitest'
import type { JsonObject } from '../types/editor'
import { duplicateNodeDraft } from './nodeDuplication'

describe('duplicateNodeDraft', () => {
  it('copies every node field while clearing only nodeId for automatic allocation', () => {
    const source: JsonObject = {
      $schema: '../schemas/node.v1.schema.json',
      schemaVersion: 1,
      nodeId: '1042',
      name: '基礎体力',
      icon: 'RED_DYE',
      lore: ['最大HPを増加する。'],
      tags: ['status'],
      pointType: 'PP',
      pointCost: 2,
      effects: [{ type: 'status', status: 'MAX_HEALTH', modifierType: 'FLAT', value: 10 }],
    }

    const duplicate = duplicateNodeDraft(source)

    expect(duplicate).toEqual({ ...source, nodeId: '' })
    expect(duplicate).not.toBe(source)
    expect(duplicate.effects).not.toBe(source.effects)
  })
})

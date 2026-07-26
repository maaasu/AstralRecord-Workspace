import { describe, expect, it } from 'vitest'
import { describeNodeCost, describeNodeEffects, nodeTooltip } from './nodeEffectPresentation'
import type { NodeMaster, SkillMasterSummary } from '../types/editor'

const node: NodeMaster = {
  $schema: '../schemas/node.v1.schema.json',
  schemaVersion: 1,
  nodeId: '1000',
  name: '&d旅立ちの記録',
  icon: 'NETHER_STAR',
  lore: [],
  tags: [],
  pointType: 'PP',
  pointCost: 1,
  effects: [
    { type: 'status', status: 'CRITICAL_RATE', modifierType: 'FLAT', value: 5 },
    { type: 'status', status: 'MAX_HEALTH', modifierType: 'SCALAR', value: 0.1 },
    { type: 'skill', skillId: 'iron_will' },
  ],
}

const skills: SkillMasterSummary[] = [{
  id: 'iron_will',
  name: '&7アイアンウィル',
  description: '&7被ダメージを軽減する。',
  type: 'SKILL',
}]

describe('nodeEffectPresentation', () => {
  it('formats Japanese status values and skill information without changing ids', () => {
    const effects = describeNodeEffects(node, skills)

    expect(effects[0].title).toBe('会心率 +5.0%')
    expect(effects[0].detail).toContain('CRITICAL_RATE')
    expect(effects[1].title).toBe('最大HP +10%')
    expect(effects[2].title).toBe('アイアンウィル')
    expect(effects[2].detail).toContain('被ダメージを軽減する。')
    expect(describeNodeCost(node)).toEqual({
      kind: 'pp',
      title: 'PP 1',
      detail: 'PPを1消費',
      searchText: 'PP 1 PP消費 ポイント消費',
    })
    expect(nodeTooltip(node, effects)).toContain('消費: PP 1')
    expect(nodeTooltip(node, effects)).toContain('会心率 +5.0%')
  })
})

import { describe, expect, it } from 'vitest'
import { describeNodeCost, describeNodeEffects, nodeTooltip } from './nodeEffectPresentation'
import type { ClassMasterSummary, NodeMaster, SkillMasterSummary } from '../types/editor'

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
    { type: 'skill', skillId: 'adventurer_astral_edge' },
  ],
}

const skills: SkillMasterSummary[] = [{
  id: 'adventurer_astral_edge',
  name: '&bアストラルエッジ',
  description: '&7流れるような二段攻撃を繰り出す近接技。',
  type: 'SKILL',
}]

const classes: ClassMasterSummary[] = [{
  id: 'adventurer',
  name: '&6冒険者',
  parentClassIds: [],
}]

describe('nodeEffectPresentation', () => {
  it('formats Japanese status values and skill information without changing ids', () => {
    const effects = describeNodeEffects(node, skills)

    expect(effects[0].title).toBe('会心率 +5.0%')
    expect(effects[0].detail).toContain('CRITICAL_RATE')
    expect(effects[1].title).toBe('最大HP +10%')
    expect(effects[2].title).toBe('アストラルエッジ')
    expect(effects[2].detail).toContain('流れるような二段攻撃を繰り出す近接技。')
    expect(describeNodeCost(node)).toEqual({
      kind: 'pp',
      title: 'PP 1',
      detail: 'PPを1消費',
      searchText: 'PP 1 PP消費 ポイント消費',
    })
    expect(nodeTooltip(node, effects)).toContain('消費: PP 1')
    expect(nodeTooltip(node, effects)).toContain('会心率 +5.0%')
  })

  it('includes the unlock class in class point cost presentations', () => {
    const classNode: NodeMaster = {
      ...node,
      nodeId: '1001',
      pointType: 'CP',
      pointCost: 1,
      unlockCondition: { classId: 'adventurer' },
    }

    expect(describeNodeCost(classNode, classes)).toEqual({
      kind: 'cp',
      title: 'CP[冒険者] 1',
      detail: 'CP[冒険者]を1消費',
      searchText: 'CP[冒険者] 1 CP[冒険者]消費 ポイント消費 adventurer 冒険者 クラス条件 unlockCondition',
    })
    expect(nodeTooltip(classNode, [], classes)).toContain('消費: CP[冒険者] 1')
  })
})

import { describe, expect, it } from 'vitest'
import { setAccountClassLevel, setAccountNode, setAccountSigil, setAccountSkill } from './progressionAccount'

const original = { levels: [
  { level: 1, classLevel: 1, nodes: [10], equipment: [{ slot: 'MAIN_HAND', item: 'item:sword' }], skills: [{ slot: 1, skill: 'skill:slash', skillLevel: 3, sigils: [{ slot: 1, item: 'item:red' }] }], passives: [] },
  { level: 10, classLevel: 20, nodes: [10, 11], equipment: [{ slot: 'MAIN_HAND', item: 'item:blade' }], skills: [{ slot: 1, skill: 'skill:slash' }], passives: [] },
] }
const checkpoint = { pointer: '/levels/1', level: 10 }

describe('account progression edits', () => {
  it('removes invalid existing values without permitting them for new assignments', () => {
    const broken = { levels: [{ ...original.levels[1], nodes: [0, 10], skills: [
      { slot: 1, skill: 'skill:slash', sigils: [{ slot: 0, item: 'item:red' }] }, { slot: 9, skill: 'skill:burst' },
    ] }] }
    const at = { pointer: '/levels/0', level: 10 }
    const repaired = setAccountSkill(setAccountSigil(setAccountNode(broken, at, 0, false), at, 'skills', 1, 0, null), at, 'skills', 9, null) as typeof broken
    expect(repaired.levels[0].nodes).toEqual([10])
    expect(repaired.levels[0].skills).toEqual([{ slot: 1, skill: 'skill:slash', sigils: [] }])
    expect(repaired.levels[0].equipment).toEqual(broken.levels[0].equipment)
    expect(() => setAccountNode(broken, at, 0, true)).toThrow('有効なノード')
    expect(() => setAccountSkill(broken, at, 'skills', 9, 'slash')).toThrow('有効なスキル枠')
    expect(() => setAccountSigil(broken, at, 'skills', 1, 0, 'red')).toThrow('有効なシジル枠')
  })
  it('targets only the selected checkpoint and keeps equipment and other level rows', () => {
    const next = setAccountNode(setAccountClassLevel(original, checkpoint, 25), checkpoint, 12, true) as typeof original
    expect(next.levels[0]).toEqual(original.levels[0])
    expect(next.levels[1]).toEqual({ ...original.levels[1], classLevel: 25, nodes: [10, 11, 12] })
    expect(setAccountNode(next, checkpoint, 11, false)).toEqual({ levels: [original.levels[0], { ...original.levels[1], classLevel: 25, nodes: [10, 12] }] })
    expect(() => setAccountNode(original, { pointer: '/levels/0', level: 10 }, 12, true)).toThrow('レベル段階が変わっています')
  })

  it('preserves an unchanged skill and resets attached state only when replacing it', () => {
    expect(setAccountSkill(original, { pointer: '/levels/0', level: 1 }, 'skills', 1, 'slash')).toBe(original)
    const replaced = setAccountSkill(original, { pointer: '/levels/0', level: 1 }, 'skills', 1, 'burst') as typeof original
    expect(replaced.levels[0].skills[0]).toEqual({ slot: 1, skill: 'skill:burst', skillLevel: 1 })
    expect(replaced.levels[0].equipment).toEqual(original.levels[0].equipment)
    expect(() => setAccountSkill(original, { pointer: '/levels/0', level: 1 }, 'skills', 1, null)).toThrow('1番枠は必須')
  })

  it('adds and removes sigils within the addressed skill slot', () => {
    const first = setAccountSigil(original, { pointer: '/levels/0', level: 1 }, 'skills', 1, 2, 'item_blue') as typeof original
    expect((first.levels[0].skills[0] as { sigils: { slot: number; item: string }[] }).sigils).toEqual([{ slot: 1, item: 'item:red' }, { slot: 2, item: 'item:item_blue' }])
    const second = setAccountSigil(first, { pointer: '/levels/0', level: 1 }, 'skills', 1, 1, null) as typeof original
    expect((second.levels[0].skills[0] as { sigils: { slot: number; item: string }[] }).sigils).toEqual([{ slot: 2, item: 'item:item_blue' }])
    expect(second.levels[1]).toEqual(original.levels[1])
  })
})

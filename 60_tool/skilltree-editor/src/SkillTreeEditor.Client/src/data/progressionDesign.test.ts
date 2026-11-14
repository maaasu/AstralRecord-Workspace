import { describe, expect, it } from 'vitest'
import type { JsonValue } from '../types/editor'
import type { DesignCheckpoint, DesignItem, DesignPreset, ProgressionDesignCatalog } from '../types/progressionDesign'
import { appendDesignEntry, changeEquipment, equipmentRestriction, numericDesignValue, setDesignValue } from './progressionDesign'

const checkpoint: DesignCheckpoint = { level: 10, classLevel: 2, pointer: '/checkpoints/0', equipment: [] }
const weapon = { slot: 'MAIN_HAND', item: 'item:old_sword', enhanceLevel: 7, note: '維持' }
const helmet = { slot: 'HEAD', item: 'item:helmet', enhanceLevel: 1 }
const content: JsonValue = {
  checkpoints: [
    { level: 10, equipment: [weapon, helmet] },
    { level: 20, equipment: [weapon] },
  ],
}
const item: DesignItem = {
  id: 'sword', name: '剣', path: 'sword.yml', category: null, rarity: null, icon: null,
  customModelData: null, iconTexture: null, slot: 'MAIN_HAND', handType: null, requiredLevel: 50,
  requiredClasses: ['FIGHTER'], compatibleSlots: ['MAIN_HAND'], maxEnhanceLevel: 4,
}
const preset: DesignPreset = { id: 'warrior', name: '戦士', path: 'warrior.yml', classId: 'WARRIOR', buildId: 'warrior', checkpoints: [] }
const catalog: ProgressionDesignCatalog = {
  classes: [
    { id: 'WARRIOR', name: '戦士', path: 'warrior.yml', prerequisiteClassIds: ['FIGHTER'] },
    { id: 'FIGHTER', name: '闘士', path: 'fighter.yml', prerequisiteClassIds: [] },
  ],
  items: [], presets: [], sources: [], usages: [], addTargets: [],
}

describe('progression design master updates', () => {
  it('replaces equipment in only the targeted checkpoint and trims the previous enhancement to the new cap', () => {
    const changed = changeEquipment(content, checkpoint, 'MAIN_HAND', 'new_sword', 4)
    expect(changed).toEqual({
      checkpoints: [
        { level: 10, equipment: [{ ...weapon, item: 'item:new_sword', enhanceLevel: 4 }, helmet] },
        { level: 20, equipment: [weapon] },
      ],
    })
    expect(content).toEqual({
      checkpoints: [
        { level: 10, equipment: [weapon, helmet] },
        { level: 20, equipment: [weapon] },
      ],
    })
  })

  it('adds a missing slot at enhancement zero and removes optional slots without allowing an empty weapon', () => {
    const added = changeEquipment(content, checkpoint, 'OFF_HAND', 'talisman', 4)
    expect(added).toEqual({
      checkpoints: [
        { level: 10, equipment: [weapon, helmet, { slot: 'OFF_HAND', item: 'item:talisman', enhanceLevel: 0 }] },
        { level: 20, equipment: [weapon] },
      ],
    })
    expect(changeEquipment(added, checkpoint, 'OFF_HAND', null)).toEqual(content)
    expect(() => changeEquipment(content, checkpoint, 'MAIN_HAND', null)).toThrow('武器は必須')
  })

  it('checks slots while leaving class and level requirements advisory at a preset checkpoint', () => {
    expect(equipmentRestriction(item, 'MAIN_HAND', preset, checkpoint, catalog)).toBeUndefined()
    expect(equipmentRestriction(item, 'OFF_HAND', preset, checkpoint, catalog)).toContain('装備枠')
    expect(equipmentRestriction({ ...item, requiredClasses: ['MAGE'] }, 'MAIN_HAND', preset, checkpoint, catalog)).toBeUndefined()
  })

  it('adds escaped keys and missing lists through an existing array item without changing its siblings', () => {
    const source = { entries: [{ keep: true }, { item: 'item:stone' }] }
    const updated = setDesignValue(source, '/entries/0/note~1for~0editor', '保存')
    expect(updated).toEqual({ entries: [{ keep: true, 'note/for~editor': '保存' }, { item: 'item:stone' }] })
    expect(appendDesignEntry(updated, '/entries/0/rewards', { item: 'item:wood', amount: 3 }))
      .toEqual({ entries: [{ keep: true, 'note/for~editor': '保存', rewards: [{ item: 'item:wood', amount: 3 }] }, { item: 'item:stone' }] })
    expect(source).toEqual({ entries: [{ keep: true }, { item: 'item:stone' }] })
  })

  it('refuses missing array indices, mismatched types, and unsafe object keys', () => {
    const source = { entries: [{ item: 'item:stone' }] }
    expect(() => setDesignValue(source, '/entries/5/item', 'item:wood')).toThrow('構造')
    expect(() => setDesignValue(source, '/entries/0/item/amount', 3)).toThrow('構造')
    expect(() => appendDesignEntry(source, '/entries/0/item', 'item:wood')).toThrow('構造')
    expect(() => setDesignValue(source, '/entries/0/__proto__/amount', 3)).toThrow('変更できません')
  })

  it('accepts bounded decimal percentages, valid ranges, and level zero only for enhancement', () => {
    expect(numericDesignValue('0', 'chance')).toBe(0)
    expect(numericDesignValue('98.5', 'chance')).toBe(98.5)
    expect(numericDesignValue('100', 'dropRate')).toBe(100)
    expect(numericDesignValue('100.1', 'dropRate')).toBeUndefined()
    expect(numericDesignValue('-1', 'priceGold')).toBeUndefined()
    expect(numericDesignValue('0', 'amount')).toBeUndefined()
    expect(numericDesignValue('1.5', 'amount')).toBeUndefined()
    expect(numericDesignValue('0', 'requiredLevel')).toBeUndefined()
    expect(numericDesignValue('0', 'enhanceLevel')).toBe(0)
    expect(numericDesignValue(' 2 ~ 4 ', 'amount')).toBe('2~4')
    expect(numericDesignValue('4~2', 'amount')).toBeUndefined()
    expect(numericDesignValue('0~2', 'rolls')).toBe('0~2')
    expect(numericDesignValue('0x10', 'amount')).toBeUndefined()
    expect(numericDesignValue('1e2', 'priceGold')).toBeUndefined()
  })

  it('rejects amounts that cannot be represented as safe integers', () => {
    expect(numericDesignValue('2147483647', 'rolls')).toBe(2147483647)
    expect(numericDesignValue('2147483648', 'rolls')).toBeUndefined()
    expect(numericDesignValue('1~2147483648', 'pick')).toBeUndefined()
    expect(numericDesignValue('9007199254740992', 'amount')).toBeUndefined()
    expect(numericDesignValue('1~9007199254740992', 'amount')).toBeUndefined()
    expect(numericDesignValue(`1~${'9'.repeat(310)}`, 'amount')).toBeUndefined()
  })
})

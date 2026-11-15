import { describe, expect, it } from 'vitest'
import { addLevelStage, changeBuildIdentity, changeLevelStage, clonePreset, createBlankPreset, removeLevelStage } from './progressionBuildManagement'

const source = { schemaVersion: 1, type: 'PROGRESSION_PRESET', id: 'warrior_base', name: '標準', classId: 'warrior', buildId: 'base', levels: [
  { level: 1, classLevel: 1, nodes: [3], equipment: [{ slot: 'MAIN_HAND', item: 'item:sword' }], skills: [{ slot: 1, skill: 'skill:slash' }], passives: [] },
  { level: 10, classLevel: 8, nodes: [3, 5], equipment: [{ slot: 'MAIN_HAND', item: 'item:sword' }], skills: [{ slot: 1, skill: 'skill:slash' }], passives: [] },
] }

describe('progression build and level editing', () => {
  it('copies the selected stage including account state and sorts it without mutating the source', () => {
    const added = addLevelStage(source, '/levels/1', 10, 5) as typeof source
    expect(added.levels.map(row => row.level)).toEqual([1, 5, 10])
    expect(added.levels[1].nodes).toEqual([3, 5])
    expect(added.levels[1].classLevel).toBe(8)
    expect(source.levels.map(row => row.level)).toEqual([1, 10])
  })

  it('checks the checkpoint pointer before changing or deleting a stage', () => {
    expect(() => changeLevelStage(source, '/levels/0', 10, 12)).toThrow('レベル段階が変わっています')
    expect(() => removeLevelStage(source, '/levels/0', 10)).toThrow('レベル段階が変わっています')
    expect((changeLevelStage(source, '/levels/1', 10, 12) as typeof source).levels.map(row => row.level)).toEqual([1, 12])
    expect((removeLevelStage(source, '/levels/1', 10) as typeof source).levels.map(row => row.level)).toEqual([1])
  })

  it('keeps level one and rejects duplicates and out of range levels', () => {
    expect(() => removeLevelStage(source, '/levels/0', 1)).toThrow('Lv.1')
    expect(() => changeLevelStage(source, '/levels/0', 1, 2)).toThrow('Lv.1')
    expect(() => addLevelStage(source, '/levels/1', 10, 1)).toThrow('登録済み')
    expect(() => addLevelStage(source, '/levels/1', 10, 51)).toThrow('1〜50')
  })

  it('separates display name and build ID while preserving internal ID', () => {
    const changed = changeBuildIdentity(source, '新しい表示名', 'new_build') as typeof source
    expect([changed.id, changed.name, changed.buildId]).toEqual(['warrior_base', '新しい表示名', 'new_build'])
    const copy = clonePreset(source, 'warrior_copy', '複製', 'copy') as typeof source
    expect([copy.id, copy.name, copy.buildId, copy.levels]).toEqual(['warrior_copy', '複製', 'copy', source.levels])
    const blank = createBlankPreset('ranger_base', '弓使い', 'ranger', 'base', 'bow', 'shot')
    expect(blank.levels).toEqual([{ level: 1, classLevel: 1, nodes: [], equipment: [{ slot: 'MAIN_HAND', item: 'item:bow' }], skills: [{ slot: 1, skill: 'skill:shot' }], passives: [] }])
    const withSources = createBlankPreset('ranger_book', '本とノード', 'ranger', 'book', 'bow', 'shot', { classLevel: 3, skillbookId: 'archer_book', nodeIds: [10, 20] })
    expect(withSources.levels).toEqual([{ level: 1, classLevel: 3, nodes: [10, 20], equipment: [
      { slot: 'MAIN_HAND', item: 'item:bow' }, { slot: 'SKILLBOOK', item: 'item:archer_book' },
    ], skills: [{ slot: 1, skill: 'skill:shot' }], passives: [] }])
    expect(() => createBlankPreset('ranger_book', '重複', 'ranger', 'book', 'bow', 'shot', { nodeIds: [10, 10] })).toThrow('重複')
    expect(() => createBlankPreset('ranger_book', '不正', 'ranger', 'book', 'bow', 'shot', { classLevel: 0 })).toThrow('初期職業Lv.')
  })
})

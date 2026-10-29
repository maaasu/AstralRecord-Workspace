import { describe, expect, it } from 'vitest'
import type { WorldPlacement } from '../api/worldPlacementApi'
import { placementChangeEntries, revertPlacementChange, samePlacement } from './worldPlacementEditing'

const point: WorldPlacement = { placementId: 'one', kind: 'waystone', worldId: 'eriva', worldName: 'worlds/eriva', templateId: null, x: 3, y: 70, z: 3, yaw: 0, pitch: 0, enabled: true,
  waystone: { id: 'home', name: '帰還地点', lockEnabled: false, unlockGold: 0, icon: 'LODESTONE', createdAt: null, createdBy: 'system' } }
describe('配置の変更比較と復元', () => {
  it('契約の全フィールドを比較し、キーの順序や同値コピーを変更としない', () => {
    expect(samePlacement(point, { ...point, waystone: { ...point.waystone! } })).toBe(true)
    for (const key of Object.keys(point) as (keyof WorldPlacement)[]) {
      const value = point[key]
      if (key === 'waystone') continue
      const changed = { ...point, [key]: typeof value === 'number' ? value + 1 : typeof value === 'boolean' ? !value : value === null ? 'changed' : `${value}-changed` }
      expect(samePlacement(point, changed), key).toBe(false)
    }
    for (const key of Object.keys(point.waystone!) as (keyof NonNullable<WorldPlacement['waystone']>)[]) {
      const value = point.waystone![key]
      const changed = { ...point, waystone: { ...point.waystone!, [key]: typeof value === 'number' ? value + 1 : typeof value === 'boolean' ? !value : `${value}-changed` } }
      expect(samePlacement(point, changed), key).toBe(false)
    }
    expect(samePlacement(point, { ...point, waystone: null })).toBe(false)
  })
  it('共有参照の未変更配置では詳細フィールドを読まない', () => {
    const stable = { ...point, get waystone(): WorldPlacement['waystone'] { throw new Error('unexpected deep read') } }
    expect(samePlacement(stable, stable)).toBe(true)
    expect(placementChangeEntries([stable], [stable])).toEqual([])
  })
  it('削除前を保持し、追加取消・変更復元・削除復元が他の配置を保持する', () => {
    const deleted = { ...point, placementId: 'deleted' }, added = { ...point, placementId: 'added' }, unchanged = { ...point, placementId: 'stable' }
    const changed = { ...point, x: 100 }
    const working = [changed, added, unchanged]
    const entries = placementChangeEntries([point, deleted, unchanged], working)
    expect(entries.map(item => item.kind)).toEqual(['changed', 'added', 'deleted'])
    expect(entries[2].before).toBe(deleted)
    expect(revertPlacementChange(working, entries[0])).toEqual([point, added, unchanged])
    expect(revertPlacementChange(working, entries[1])).toEqual([changed, unchanged])
    expect(revertPlacementChange(working, entries[2])).toEqual([...working, deleted])
    expect(working).toEqual([changed, added, unchanged])
  })
})

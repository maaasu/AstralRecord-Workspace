import { describe, expect, it } from 'vitest'
import { assertPlanningIdentity, cellAtWorld, reasonColor, terrainColor, validatePlanningPlan, type PlanningCell } from './spawnerPlanning'
import type { PlanningManifest, PlanningPlan, PlanningSnapshot } from './spawnerPlanning'

const manifest = { schemaVersion: 2, worldId: 'test', exportRevision: 'revision-a', terrainFingerprint: 'terrain', radiusOptions: [32, 48, 64] } as PlanningManifest
it('地形の未解析は控えめな独立色で表示し、未配置理由の警告色は保持する', () => {
  const failed = { status: 'failed' } as PlanningCell
  expect(terrainColor(failed)).toBe('#344253')
  expect(terrainColor({ ...failed, status: 'unknown' })).toBe(terrainColor(failed))
  expect(terrainColor({ ...failed, status: 'absent' })).not.toBe(terrainColor(failed))
  expect(terrainColor({ ...failed, status: 'ok', surface: 'water' })).not.toBe(terrainColor(failed))
  expect(reasonColor('failed')).toBe('#df6278')
})
describe('配置プレビューの入力境界', () => {
  it('同じ地形でも異なる計算版を混ぜない', () => {
    expect(() => assertPlanningIdentity(manifest, { ...manifest, exportRevision: 'revision-b' })).toThrow('版が一致しません')
    expect(() => assertPlanningIdentity(manifest, { ...manifest, worldId: 'other' })).toThrow('版が一致しません')
  })
  it('全区画の結果が揃わない案と実適用データを拒否する', () => {
    const snapshot = { manifest, cells: [{}, {}], regions: [] } as unknown as PlanningSnapshot
    const plan = { ...manifest, previewOnly: true, radiusMeters: 64, proposals: [], reasonCodes: ['water', 'failed'], counts: { water: 1, failed: 1 } } as unknown as PlanningPlan
    expect(() => validatePlanningPlan(snapshot, plan)).not.toThrow()
    expect(() => validatePlanningPlan(snapshot, { ...plan, reasonCodes: ['water'] })).toThrow('区画数')
    expect(() => validatePlanningPlan(snapshot, { ...plan, previewOnly: false })).toThrow('プレビュー')
  })
  it('負座標を正しい区画へ丸め、右端と下端を範囲外にする', () => {
    const bounds = { minX: -32, minZ: -16, width: 32, height: 32, cellSize: 16 }
    const grid = new Map([['-32,-16', 0], ['-16,-16', 1], ['-32,0', 2], ['-16,0', 3]])
    expect(cellAtWorld(bounds, grid, -16.1, -.1)).toBe(0)
    expect(cellAtWorld(bounds, grid, -16, 0)).toBe(3)
    expect(cellAtWorld(bounds, grid, 0, 0)).toBeUndefined()
    expect(cellAtWorld(bounds, grid, -1, 16)).toBeUndefined()
  })
})

import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { WorldPlacementEditor } from './WorldPlacementEditor'
import { worldPlacementApi, type WorldPlacement } from '../api/worldPlacementApi'
import type { PlanningSnapshot } from '../data/spawnerPlanning'

vi.mock('../api/worldPlacementApi', () => ({ worldPlacementApi: { read: vi.fn(), save: vi.fn() } }))

const snapshot: PlanningSnapshot = {
  manifest: { schemaVersion: 2, worldId: 'eriva', worldName: 'エリヴァ', terrainFingerprint: 'terrain', exportRevision: 'revision', previewOnly: true, bounds: { minX: -16, minZ: 0, width: 32, height: 16, cellSize: 16 }, spawn: { x: -4, y: 70, z: 3 }, capturedAt: '', radiusOptions: [64], reasons: {}, limitations: [], progressionPolicy: {} },
  cells: [-16, 0].map((x, i) => ({ x, z: 0, y: 70, status: 'ok', surface: 'grass', ground: 'dirt', biome: 'plains', safeCount: 256, waterCount: 0, treeCount: 0, candidateX: x + 8, candidateY: 70, candidateZ: 8, minY: 70, maxY: 70, habitat: 'grass', regionId: i ? 'east' : 'west', targetLevel: 15, reasonCode: 'eligible', reason: '' })),
  regions: ['west', 'east'].map((id, i) => ({ id, name: i ? '東の森' : '西の草原', habitat: 'grass', levelMin: 15, levelMax: 15, cellCount: 1, eligibleCells: 1, coveredCellsByRadius: { '64': 1 }, spawnerIds: [], reason: '' })),
}
const placement = (id: string): WorldPlacement => ({ placementId: id, kind: 'spawner', worldId: 'eriva', worldName: 'worlds/eriva', templateId: 'wolf', x: -4, y: 70, z: 3, yaw: 0, pitch: 0, enabled: true, waystone: null })
const catalog = { spawners: [{ id: 'wolf', name: '森&b林狼 Lv.15', radiusMeters: 64, levels: [15] }, { id: 'fox', name: 'キツネ Lv.30', radiusMeters: 64, levels: [30] }], npcs: [{ id: 'guide', name: '案内人' }], worlds: [{ id: 'eriva', name: 'エリヴァ', baseWorldPath: 'worlds/eriva' }] }
const props = () => ({ snapshot, point: null, selectedId: null as string | null, onSelected: vi.fn(), onPlacements: vi.fn(), placing: false, onPlacing: vi.fn() })
const search = (query: string) => fireEvent.change(screen.getByLabelText('保存済み配置を検索'), { target: { value: query } })
beforeEach(() => {
  vi.resetAllMocks()
  vi.stubGlobal('fetch', vi.fn(async () => ({ ok: true, json: async () => catalog })))
})
afterEach(() => { vi.unstubAllGlobals(); vi.restoreAllMocks() })

describe('placement display reuse', () => {
  it('recomputes only an edited placement and reuses history without stale coordinates or regions', async () => {
    const items = Array.from({ length: 2000 }, (_, i) => Object.freeze(placement(`__display_perf_${i}`)))
    vi.mocked(worldPlacementApi.read).mockResolvedValue({ schemaVersion: 1, revision: 'r1', placements: items })
    const lower = String.prototype.toLocaleLowerCase
    let normalized = 0
    vi.spyOn(String.prototype, 'toLocaleLowerCase').mockImplementation(function (this: string, ...args) {
      if (String(this).includes('__display_perf_')) normalized++
      return lower.apply(this, args)
    })
    const inputProps = { ...props(), selectedId: items[0].placementId }
    const { container } = render(<WorldPlacementEditor {...inputProps} />)
    await screen.findByLabelText('X')
    const initial = normalized
    expect(container.querySelectorAll('.wp-row')).toHaveLength(50)
    normalized = 0
    fireEvent.change(screen.getByLabelText('X'), { target: { value: '-' } })
    expect(screen.getByLabelText('X')).toHaveAttribute('aria-invalid', 'true')
    expect(normalized).toBe(0)
    fireEvent.change(screen.getByLabelText('X'), { target: { value: '4' } })
    fireEvent.keyDown(screen.getByLabelText('X'), { key: 'Enter' })
    const edit = normalized
    search('森林狼 東の森 4')
    expect(screen.getByText('1 件を表示 · 世界: エリヴァ')).toBeInTheDocument()
    expect(inputProps.onPlacements.mock.lastCall?.[0][0]).toMatchObject({ placementId: items[0].placementId, x: 4 })
    normalized = 0
    fireEvent.click(screen.getByRole('button', { name: '元に戻す' }))
    const undo = normalized
    expect(screen.getByLabelText('X')).toHaveValue('-4')
    expect(screen.getByText('0 件を表示 · 世界: エリヴァ')).toBeInTheDocument()
    normalized = 0
    fireEvent.click(screen.getByRole('button', { name: 'やり直す' }))
    const redo = normalized
    expect(screen.getByLabelText('X')).toHaveValue('4')
    expect(screen.getByText('1 件を表示 · 世界: エリヴァ')).toBeInTheDocument()
    normalized = 0
    for (const query of ['森', '森林', '森林狼', '森林狼 東']) search(query)
    const typing = normalized
    console.info('2000 saved placements: display normalizations', { initial, edit, undo, redo, typing })
    expect.soft({ initial, edit, undo, redo, typing }).toEqual({ initial: 2000, edit: 1, undo: 0, redo: 0, typing: 0 })
    expect(items[0].x).toBe(-4)
    expect(worldPlacementApi.read).toHaveBeenCalledTimes(1)
    expect(worldPlacementApi.save).not.toHaveBeenCalled()
  })

  it('updates template levels and colored waystone names without changing unrelated rows', async () => {
    const stone: WorldPlacement = { ...placement('stone'), kind: 'waystone', templateId: null, waystone: { id: 'home', name: '古い道標', icon: 'LODESTONE', lockEnabled: false, unlockGold: 0, createdAt: null, createdBy: 'fixture' } }
    vi.mocked(worldPlacementApi.read).mockResolvedValue({ schemaVersion: 1, revision: 'r1', placements: [placement('wolf-position'), stone] })
    const inputProps = props()
    const { rerender } = render(<WorldPlacementEditor {...inputProps} selectedId="wolf-position" />)
    await screen.findByLabelText('マスター')
    fireEvent.change(screen.getByLabelText('マスター'), { target: { value: 'fox' } })
    search('キツネ')
    expect(screen.getByText('1 件を表示 · 世界: エリヴァ')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: /^詳細条件/ }))
    fireEvent.change(screen.getByLabelText('出現レベル下限'), { target: { value: '25' } })
    expect(screen.getByText('1 件を表示 · 世界: エリヴァ')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '検索条件をすべて解除' }))
    rerender(<WorldPlacementEditor {...inputProps} selectedId="stone" />)
    fireEvent.change(screen.getByLabelText('表示名'), { target: { value: '旅&a人の道標' } })
    search('旅人 HOME')
    expect(screen.getByText('1 件を表示 · 世界: エリヴァ')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '元に戻す' }))
    expect(screen.getByText('0 件を表示 · 世界: エリヴァ')).toBeInTheDocument()
    expect(screen.getByLabelText('表示名')).toHaveValue('古い道標')
    expect(stone.waystone?.name).toBe('古い道標')
    expect(worldPlacementApi.save).not.toHaveBeenCalled()
  })

  it('invalidates for terrain and catalog changes even when placement objects are reused', async () => {
    const items = [placement('wolf-position'), { ...placement('guide-position'), kind: 'npc' as const, templateId: 'guide' }]
    vi.mocked(worldPlacementApi.read).mockResolvedValue({ schemaVersion: 1, revision: 'r1', placements: items })
    const inputProps = props()
    const { rerender } = render(<WorldPlacementEditor {...inputProps} />)
    await screen.findByText('2 件を表示 · 世界: エリヴァ')
    search('森林狼 草原')
    expect(screen.getByText('1 件を表示 · 世界: エリヴァ')).toBeInTheDocument()
    const changedTerrain = { ...snapshot, cells: snapshot.cells.map(cell => ({ ...cell, regionId: 'east' })), regions: snapshot.regions.map(region => ({ ...region, name: region.id === 'east' ? '新しい丘' : region.name })) }
    rerender(<WorldPlacementEditor {...inputProps} snapshot={changedTerrain} />)
    expect(screen.getByText('0 件を表示 · 世界: エリヴァ')).toBeInTheDocument()
    search('森林狼 丘')
    expect(screen.getByText('1 件を表示 · 世界: エリヴァ')).toBeInTheDocument()
    const changedCatalog = { ...catalog, spawners: [{ ...catalog.spawners[0], name: '大&a狼 Lv.45', levels: [45] }], npcs: [{ id: 'guide', name: '旅の商人' }] }
    vi.mocked(fetch).mockResolvedValue({ ok: true, json: async () => changedCatalog } as Response)
    fireEvent.click(screen.getByRole('button', { name: 'Dev を再読込' }))
    await screen.findByText('Dev 配置を再読込しました。')
    await waitFor(() => expect(screen.getByRole('button', { name: 'Dev を再読込' })).toBeEnabled())
    expect(screen.getByText('0 件を表示 · 世界: エリヴァ')).toBeInTheDocument()
    search('大狼 丘')
    fireEvent.click(screen.getByRole('button', { name: /^詳細条件/ }))
    fireEvent.change(screen.getByLabelText('出現レベル下限'), { target: { value: '40' } })
    expect(screen.getByText('1 件を表示 · 世界: エリヴァ')).toBeInTheDocument()
    fireEvent.change(screen.getByLabelText('出現レベル下限'), { target: { value: '' } })
    search('旅の商人')
    expect(screen.getByText('1 件を表示 · 世界: エリヴァ')).toBeInTheDocument()
    expect(inputProps.onPlacements.mock.lastCall?.[0][0].placementId).toBe('guide-position')
    expect(worldPlacementApi.read).toHaveBeenCalledTimes(2)
    expect(worldPlacementApi.save).not.toHaveBeenCalled()
  })
})

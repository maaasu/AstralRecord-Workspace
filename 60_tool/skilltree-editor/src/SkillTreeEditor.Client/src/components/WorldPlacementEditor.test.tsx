import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { WorldPlacementEditor } from './WorldPlacementEditor'
import { worldPlacementApi } from '../api/worldPlacementApi'
import type { WorldPlacement } from '../api/worldPlacementApi'
import type { PlanningSnapshot } from '../data/spawnerPlanning'

vi.mock('../api/worldPlacementApi', () => ({ worldPlacementApi: { read: vi.fn(), save: vi.fn() } }))
const snapshot: PlanningSnapshot = {
  manifest: { schemaVersion: 2, worldId: 'eriva', worldName: 'エリヴァ', terrainFingerprint: 'a', exportRevision: 'b', previewOnly: true, bounds: { minX: 0, minZ: 0, width: 16, height: 16, cellSize: 16 }, spawn: { x: 2, y: 70, z: 2 }, capturedAt: '', radiusOptions: [32], reasons: {}, limitations: [], progressionPolicy: {} },
  cells: [{ x: 0, z: 0, y: 70, status: 'ok', surface: 'grass', ground: 'dirt', biome: 'plains', safeCount: 256, waterCount: 0, treeCount: 0, candidateX: 8, candidateY: 70, candidateZ: 8, minY: 70, maxY: 70, habitat: 'grass', regionId: 'field', targetLevel: 10, reasonCode: 'eligible', reason: '' }],
  regions: [{ id: 'field', name: '草原', habitat: 'grass', levelMin: 10, levelMax: 10, cellCount: 1, eligibleCells: 1, coveredCellsByRadius: {}, spawnerIds: [], reason: '' }],
}
beforeEach(() => {
  vi.resetAllMocks()
  vi.mocked(worldPlacementApi.read).mockResolvedValue({ schemaVersion: 1, revision: 'rev-1', placements: [] })
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, json: async () => ({ spawners: [{ id: 'wolf', name: '森林狼 Lv.10', radiusMeters: 32, levels: [10] }], npcs: [], worlds: [{ id: 'eriva', name: 'エリヴァ', baseWorldPath: 'plugins/AstralRecord/worlds/eriva' }] }) }))
})
afterEach(() => { vi.unstubAllGlobals(); vi.restoreAllMocks() })
describe('Dev 配置の編集', () => {
  it('保存済みの物理ワールド名を新規配置へ引き継ぎ、候補から選択できる', async () => {
    vi.mocked(worldPlacementApi.read).mockResolvedValue({ schemaVersion: 1, revision: 'rev-1', placements: [
      { placementId: 'old', kind: 'spawner', worldId: 'eriva', worldName: 'worlds/legacy', templateId: 'wolf', x: 1, y: 70, z: 1, yaw: 0, pitch: 0, enabled: true, waystone: null },
      { placementId: 'new', kind: 'spawner', worldId: 'eriva', worldName: 'worlds/renamed', templateId: 'wolf', x: 2, y: 70, z: 2, yaw: 0, pitch: 0, enabled: true, waystone: null },
    ] })
    const selected = vi.fn(), visible = vi.fn()
    render(<WorldPlacementEditor snapshot={snapshot} point={null} selectedId={null} onSelected={selected} onPlacements={visible} placing={false} onPlacing={vi.fn()} />)
    await waitFor(() => expect(screen.getByLabelText('追加先のワールド')).toHaveValue('worlds/legacy'))
    fireEvent.change(screen.getByLabelText('追加先のワールド'), { target: { value: 'worlds/renamed' } })
    fireEvent.click(screen.getByRole('button', { name: '座標を入力して追加' }))
    await waitFor(() => expect(visible.mock.lastCall?.[0]).toHaveLength(3))
    expect(visible.mock.lastCall?.[0].find((item: { placementId: string }) => item.placementId === selected.mock.lastCall?.[0]).worldName).toBe('worlds/renamed')
  })
  it('レベル条件を一覧と地図の表示対象に同時適用する', async () => {
    vi.mocked(worldPlacementApi.read).mockResolvedValue({ schemaVersion: 1, revision: 'rev-1', placements: [
      { placementId: 'one', kind: 'spawner', worldId: 'eriva', worldName: 'worlds/eriva', templateId: 'wolf', x: 3, y: 70, z: 3, yaw: 0, pitch: 0, enabled: true, waystone: null },
    ] })
    const visible = vi.fn()
    render(<WorldPlacementEditor snapshot={snapshot} point={null} selectedId={null} onSelected={vi.fn()} onPlacements={visible} placing={false} onPlacing={vi.fn()} />)
    await screen.findByText(/1 件を表示/)
    fireEvent.change(screen.getByLabelText('出現レベル下限'), { target: { value: '11' } })
    expect(screen.getByText(/0 件を表示/)).toBeInTheDocument()
    expect(visible.mock.lastCall?.[0]).toEqual([])
    fireEvent.change(screen.getByLabelText('出現レベル上限'), { target: { value: '10' } })
    fireEvent.change(screen.getByLabelText('出現レベル下限'), { target: { value: '10' } })
    expect(screen.getByText(/1 件を表示/)).toBeInTheDocument()
  })
  it('保存待機中は座標と地図追加を変更できず、応答で編集を上書きしない', async () => {
    let finish!: (value: { schemaVersion: 1; revision: string; placements: WorldPlacement[] }) => void
    vi.mocked(worldPlacementApi.save).mockReturnValue(new Promise(resolve => { finish = resolve }))
    const selected = vi.fn(), visible = vi.fn(), busy = vi.fn()
    const props = { snapshot, selectedId: null as string | null, onSelected: selected, onPlacements: visible, placing: true, onPlacing: vi.fn(), onBusyChange: busy }
    const view = render(<WorldPlacementEditor {...props} point={null} />)
    await waitFor(() => expect(screen.getByRole('button', { name: '座標を入力して追加' })).toBeEnabled())
    fireEvent.click(screen.getByRole('button', { name: '座標を入力して追加' }))
    const id = selected.mock.lastCall?.[0]
    view.rerender(<WorldPlacementEditor {...props} selectedId={id} point={null} />)
    fireEvent.change(screen.getByLabelText('マスター'), { target: { value: 'wolf' } })
    fireEvent.click(screen.getByRole('button', { name: 'Dev に保存' }))
    expect(screen.getByLabelText('X')).toBeDisabled()
    expect(screen.getByRole('button', { name: '座標を入力して追加' })).toBeDisabled()
    view.rerender(<WorldPlacementEditor {...props} selectedId={id} point={{ key: 1, x: 8, y: 70, z: 8 }} />)
    expect(screen.getByText(/1 件を表示/)).toBeInTheDocument()
    await act(async () => finish({ schemaVersion: 1, revision: 'rev-2', placements: visible.mock.lastCall?.[0] }))
    expect(screen.getByText(/1 件を表示/)).toBeInTheDocument()
    expect(busy).toHaveBeenCalledWith(true)
  })
  it('日本語名で絞り込み、選択した配置だけを確認後に削除する', async () => {
    vi.mocked(worldPlacementApi.read).mockResolvedValue({ schemaVersion: 1, revision: 'rev-1', placements: [
      { placementId: 'one', kind: 'spawner', worldId: 'eriva', worldName: 'エリヴァ', templateId: 'wolf', x: 3, y: 70, z: 3, yaw: 0, pitch: 0, enabled: true, waystone: null },
      { placementId: 'other-world', kind: 'spawner', worldId: 'elsewhere', worldName: '別世界', templateId: 'wolf', x: 4, y: 70, z: 4, yaw: 0, pitch: 0, enabled: true, waystone: null },
    ] })
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    const change = vi.fn()
    render(<WorldPlacementEditor snapshot={snapshot} point={null} selectedId={null} onSelected={vi.fn()} onPlacements={change} placing={false} onPlacing={vi.fn()} />)
    expect(await screen.findByText(/1 件を表示/)).toBeInTheDocument()
    fireEvent.change(screen.getByLabelText('保存済み配置を検索'), { target: { value: '森林狼' } })
    fireEvent.click(screen.getByLabelText(/森林狼 Lv.10 を削除対象に選択/))
    fireEvent.click(screen.getByRole('button', { name: '選択した 1 件を削除' }))
    expect(window.confirm).toHaveBeenCalled()
    expect(change.mock.lastCall?.[0]).toEqual([])
  })
  it('保存済み道標のIDは変更不可、複製のIDは新規入力できる', async () => {
    vi.mocked(worldPlacementApi.read).mockResolvedValue({ schemaVersion: 1, revision: 'rev-1', placements: [
      { placementId: 'stone', kind: 'waystone', worldId: 'eriva', worldName: 'worlds/eriva', templateId: null, x: 3, y: 70, z: 3, yaw: 0, pitch: 0, enabled: true,
        waystone: { id: 'home', name: '帰還地点', lockEnabled: false, unlockGold: 0, icon: 'LODESTONE', createdAt: null, createdBy: 'system' } },
    ] })
    const selected = vi.fn()
    const view = render(<WorldPlacementEditor snapshot={snapshot} point={null} selectedId="stone" onSelected={selected} onPlacements={vi.fn()} placing={false} onPlacing={vi.fn()} />)
    expect(await screen.findByLabelText('道標 ID')).toBeDisabled()
    expect(screen.getByLabelText('道標 ID')).toHaveValue('home')
    fireEvent.click(screen.getByRole('button', { name: '複製' }))
    view.rerender(<WorldPlacementEditor snapshot={snapshot} point={null} selectedId={selected.mock.lastCall?.[0]} onSelected={selected} onPlacements={vi.fn()} placing={false} onPlacing={vi.fn()} />)
    expect(screen.getByLabelText('道標 ID')).toBeEnabled()
    expect(screen.getByLabelText('道標 ID')).toHaveValue('')
  })
  it('地図の地表Yでスポナーを追加し、版を指定して保存する', async () => {
    vi.mocked(worldPlacementApi.save).mockImplementation(async (_, placements) => ({ schemaVersion: 1, revision: 'rev-2', placements }))
    const change = vi.fn()
    const view = render(<WorldPlacementEditor snapshot={snapshot} point={null} selectedId={null} onSelected={vi.fn()} onPlacements={change} placing onPlacing={vi.fn()} />)
    await waitFor(() => expect(screen.getByRole('button', { name: '座標を入力して追加' })).toBeEnabled())
    view.rerender(<WorldPlacementEditor snapshot={snapshot} point={{ key: 1, x: 8, y: 70, z: 8 }} selectedId={null} onSelected={vi.fn()} onPlacements={change} placing onPlacing={vi.fn()} />)
    expect(await screen.findByText(/1 件を表示/)).toBeInTheDocument()
    const added = change.mock.lastCall?.[0]
    expect(added[0]).toEqual(expect.objectContaining({ worldName: 'plugins/AstralRecord/worlds/eriva', x: 8, y: 70, z: 8 }))
    view.rerender(<WorldPlacementEditor snapshot={snapshot} point={{ key: 1, x: 8, y: 70, z: 8 }} selectedId={added[0].placementId} onSelected={vi.fn()} onPlacements={change} placing={false} onPlacing={vi.fn()} />)
    fireEvent.change(screen.getByLabelText('マスター'), { target: { value: 'wolf' } })
    fireEvent.click(screen.getByRole('button', { name: 'Dev に保存' }))
    await waitFor(() => expect(worldPlacementApi.save).toHaveBeenCalledWith('rev-1', expect.arrayContaining([expect.objectContaining({ templateId: 'wolf', y: 70 })])))
    expect(await screen.findByText(/Dev 配置を保存しました/)).toBeInTheDocument()
  })
  it('競合時は未保存の編集を保持する', async () => {
    vi.mocked(worldPlacementApi.save).mockRejectedValue(new Error('409 競合'))
    const selected = vi.fn()
    const view = render(<WorldPlacementEditor snapshot={snapshot} point={null} selectedId={null} onSelected={selected} onPlacements={vi.fn()} placing={false} onPlacing={vi.fn()} />)
    await waitFor(() => expect(screen.getByRole('button', { name: '座標を入力して追加' })).toBeEnabled())
    fireEvent.click(screen.getByRole('button', { name: '座標を入力して追加' }))
    const id = selected.mock.lastCall?.[0]
    view.rerender(<WorldPlacementEditor snapshot={snapshot} point={null} selectedId={id} onSelected={selected} onPlacements={vi.fn()} placing={false} onPlacing={vi.fn()} />)
    fireEvent.change(screen.getByLabelText('マスター'), { target: { value: 'wolf' } })
    fireEvent.click(screen.getByRole('button', { name: 'Dev に保存' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('409 競合')
    expect(screen.getByRole('button', { name: 'Dev に保存' })).toBeEnabled()
    expect(screen.getByLabelText('マスター')).toHaveValue('wolf')
  })
})

import { act, fireEvent, render, screen, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { WorldPlacementEditor } from './WorldPlacementEditor'
import { worldPlacementApi, type WorldPlacement } from '../api/worldPlacementApi'
import type { PlanningSnapshot } from '../data/spawnerPlanning'

vi.mock('../api/worldPlacementApi', () => ({ worldPlacementApi: { read: vi.fn(), save: vi.fn() } }))
const snapshot: PlanningSnapshot = {
  manifest: { schemaVersion: 2, worldId: 'eriva', worldName: 'エリヴァ', terrainFingerprint: 'a', exportRevision: 'b', previewOnly: true,
    bounds: { minX: 0, minZ: 0, width: 16, height: 16, cellSize: 16 }, spawn: { x: 0, y: 70, z: 0 }, capturedAt: '', radiusOptions: [64], reasons: {}, limitations: [], progressionPolicy: {} },
  cells: [], regions: [{ id: 'field', name: '草原', habitat: 'grass', levelMin: 10, levelMax: 10, cellCount: 0, eligibleCells: 0, coveredCellsByRadius: {}, spawnerIds: [], reason: '' }],
}
const entries: WorldPlacement[] = Array.from({ length: 101 }, (_, index) => ({ placementId: `point-${index}`, kind: 'spawner', worldId: 'eriva', worldName: 'worlds/eriva', templateId: 'wolf', x: 101 - index, y: 70, z: 0, yaw: 0, pitch: 0, enabled: true, waystone: null }))
const otherWorld = { ...entries[0], placementId: 'other-world', worldId: 'other', worldName: 'worlds/other' }
const onSelected = vi.fn(), onPlacements = vi.fn(), onDirtyChange = vi.fn()
const props = { snapshot, point: null, selectedId: 'point-0', onSelected, onPlacements, onDirtyChange, placing: false, onPlacing: vi.fn() }
beforeEach(() => {
  vi.resetAllMocks()
  vi.mocked(worldPlacementApi.read).mockResolvedValue({ schemaVersion: 1, revision: 'rev', placements: [...entries, otherWorld] })
  vi.stubGlobal('fetch', vi.fn(async () => ({ ok: true, json: async () => ({ spawners: [{ id: 'wolf', name: '森林狼 Lv.10', radiusMeters: 64, levels: [10] }], npcs: [], worlds: [] }) })))
})
afterEach(() => { vi.unstubAllGlobals(); vi.restoreAllMocks() })
const search = () => screen.getByLabelText('保存済み配置を検索')
const reveal = () => screen.getByRole('button', { name: '表示条件を解除して選択中の配置を表示' })
const bulkOnly = () => screen.getByLabelText('一括選択だけを一覧で確認（表示条件外を含む）')
const list = () => screen.getByLabelText('表示条件に合う配置の一覧')
const selectedRow = () => list().querySelector('button[data-placement-id="point-0"]')
const open = async () => { const view = render(<WorldPlacementEditor {...props} />); await screen.findByText(/101 件を表示/); return view }

describe('reveal a filtered-out selected placement', () => {
  it('clears blocking conditions and jumps to the sorted third page while keeping drafts, history and bulk selection', async () => {
    await open()
    fireEvent.click(screen.getByLabelText(/\(point-1\) を一括選択$/))
    fireEvent.change(screen.getByLabelText('一覧の並び順'), { target: { value: 'distance' } })
    fireEvent.change(screen.getByLabelText('Y'), { target: { value: '80' } })
    fireEvent.keyDown(screen.getByLabelText('Y'), { key: 'Enter' })
    fireEvent.change(screen.getByLabelText('X'), { target: { value: '-' } })
    fireEvent.change(search(), { target: { value: 'point-100' } })
    fireEvent.click(screen.getByRole('button', { name: /^詳細条件/ }))
    for (const [label, value] of [['種類', 'npc'], ['有効状態', 'false'], ['地域', '草原'], ['スポナーマスター', 'wolf'], ['出現レベル下限', '15'], ['出現レベル上限', '45']]) {
      fireEvent.change(screen.getByLabelText(label), { target: { value } })
    }
    fireEvent.click(screen.getByRole('button', { name: /^一括操作/ }))
    fireEvent.click(bulkOnly())
    expect(screen.getByText(/選択中の配置は現在の一覧に含まれていません/)).toBeInTheDocument()
    fireEvent.click(reveal())
    expect(search()).toHaveValue('')
    for (const label of ['種類', '有効状態', '地域', 'スポナーマスター']) expect(screen.getByLabelText(label)).toHaveValue('')
    for (const label of ['出現レベル下限', '出現レベル上限']) expect(screen.getByLabelText(label)).toHaveValue(null)
    expect(bulkOnly()).not.toBeChecked()
    expect(screen.getByLabelText('一覧の並び順')).toHaveValue('distance')
    expect(screen.getByText('一覧 101–101 / 101 件 · 3 / 3 ページ')).toBeInTheDocument()
    expect(selectedRow()).toHaveFocus()
    expect(selectedRow()).toHaveAttribute('aria-pressed', 'true')
    expect(within(list()).getAllByRole('button')).toHaveLength(1)
    expect(screen.getByLabelText('X')).toHaveValue('-')
    expect(screen.getByLabelText('Y')).toHaveValue('80')
    expect(screen.getByRole('button', { name: /^一括操作/ })).toHaveTextContent('合計 1 件 · 条件内 1 件 · 条件外 0 件')
    expect(onDirtyChange).toHaveBeenLastCalledWith(true)
    expect(onSelected).not.toHaveBeenCalled()
    expect(onPlacements.mock.lastCall![0]).toHaveLength(101)
    expect(onPlacements.mock.lastCall![0].find((item: WorldPlacement) => item.placementId === 'point-0')).toMatchObject({ x: 101, y: 80 })
    expect(worldPlacementApi.read).toHaveBeenCalledOnce()
    expect(fetch).toHaveBeenCalledOnce()
    expect(worldPlacementApi.save).not.toHaveBeenCalled()
    fireEvent.keyDown(screen.getByLabelText('X'), { key: 'Escape' })
    fireEvent.click(screen.getByRole('button', { name: '元に戻す' }))
    expect(screen.getByLabelText('Y')).toHaveValue('70')
    expect(entries[0].y).toBe(70)
  })

  it('only leaves the bulk-only view if the selected placement already matches the search, without updating the map', async () => {
    await open()
    fireEvent.click(screen.getByLabelText(/\(point-1\) を一括選択$/))
    fireEvent.change(search(), { target: { value: '森林狼' } })
    fireEvent.click(screen.getByRole('button', { name: /^詳細条件/ }))
    fireEvent.change(screen.getByLabelText('種類'), { target: { value: 'spawner' } })
    fireEvent.change(screen.getByLabelText('出現レベル下限'), { target: { value: '5' } })
    fireEvent.click(screen.getByRole('button', { name: /^一括操作/ }))
    fireEvent.click(bulkOnly())
    onPlacements.mockClear()
    fireEvent.click(reveal())
    expect(bulkOnly()).not.toBeChecked()
    expect(search()).toHaveValue('森林狼')
    expect(screen.getByLabelText('種類')).toHaveValue('spawner')
    expect(screen.getByLabelText('出現レベル下限')).toHaveValue(5)
    expect(selectedRow()).toHaveFocus()
    expect(screen.getByLabelText(/\(point-1\) を一括選択$/)).toBeChecked()
    expect(onPlacements).not.toHaveBeenCalled()
    expect(onSelected).not.toHaveBeenCalled()
    expect(onDirtyChange).toHaveBeenLastCalledWith(false)
    expect(worldPlacementApi.save).not.toHaveBeenCalled()
  })

  it('does not reveal a missing placement or a selection from another world', async () => {
    const view = await open()
    for (const selectedId of ['other-world', 'deleted', null]) {
      view.rerender(<WorldPlacementEditor {...props} selectedId={selectedId} />)
      expect(screen.getByRole('button', { name: '選択中の配置のページへ' })).toBeDisabled()
      expect(screen.queryByRole('button', { name: '表示条件を解除して選択中の配置を表示' })).not.toBeInTheDocument()
    }
    expect(onSelected).not.toHaveBeenCalled()
    expect(worldPlacementApi.save).not.toHaveBeenCalled()
  })

  it('keeps the search while saving and allows reveal after a failed save without retrying it', async () => {
    await open()
    fireEvent.click(screen.getByLabelText('有効'))
    fireEvent.change(search(), { target: { value: 'point-100' } })
    let reject!: (reason: Error) => void
    vi.mocked(worldPlacementApi.save).mockReturnValue(new Promise((_resolve, fail) => { reject = fail }))
    fireEvent.click(screen.getByRole('button', { name: 'Dev に保存' }))
    expect(reveal()).toBeDisabled()
    fireEvent.click(reveal())
    expect(search()).toHaveValue('point-100')
    await act(async () => reject(new Error('fixture save failure')))
    expect(reveal()).toBeEnabled()
    fireEvent.click(reveal())
    expect(selectedRow()).toHaveFocus()
    expect(search()).toHaveValue('')
    expect(screen.getByLabelText('有効')).not.toBeChecked()
    expect(worldPlacementApi.save).toHaveBeenCalledOnce()
    expect(worldPlacementApi.read).toHaveBeenCalledOnce()
  })
})

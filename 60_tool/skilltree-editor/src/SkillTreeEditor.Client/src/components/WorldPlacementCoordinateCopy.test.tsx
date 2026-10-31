import { fireEvent, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { WorldPlacementEditor } from './WorldPlacementEditor'
import { worldPlacementApi, type PlacementKind, type WorldPlacement } from '../api/worldPlacementApi'
import type { PlanningSnapshot } from '../data/spawnerPlanning'

vi.mock('../api/worldPlacementApi', () => ({ worldPlacementApi: { read: vi.fn(), save: vi.fn() } }))
const snapshot: PlanningSnapshot = {
  manifest: { schemaVersion: 2, worldId: 'eriva', worldName: 'エリヴァ', terrainFingerprint: 'a', exportRevision: 'b', previewOnly: true,
    bounds: { minX: 0, minZ: 0, width: 16, height: 16, cellSize: 16 }, spawn: { x: 2, y: 70, z: 2 }, capturedAt: '', radiusOptions: [64], reasons: {}, limitations: [], progressionPolicy: {} }, cells: [], regions: [],
}
const placement: WorldPlacement = { placementId: 'one', kind: 'spawner', worldId: 'eriva', worldName: 'actual/eriva', templateId: 'wolf', x: -1150.5, y: 107, z: 1480.25, yaw: 0, pitch: 0, enabled: true, waystone: null }
const props = { snapshot, point: null, selectedId: 'one', onSelected: vi.fn(), onPlacements: vi.fn(), placing: false, onPlacing: vi.fn(), onDirtyChange: vi.fn() }
let write: ReturnType<typeof vi.fn>
beforeEach(() => {
  vi.resetAllMocks()
  write = vi.fn().mockResolvedValue(undefined)
  vi.stubGlobal('navigator', { clipboard: { writeText: write } })
  vi.mocked(worldPlacementApi.read).mockResolvedValue({ schemaVersion: 1, revision: 'one', placements: [placement] })
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, json: async () => ({ spawners: [{ id: 'wolf', name: '森林狼', radiusMeters: 64, levels: [15] }], npcs: [{ id: 'wolf', name: '案内人' }], worlds: [{ id: 'eriva', name: 'エリヴァ', baseWorldPath: 'catalog/fallback' }] }) }))
})
afterEach(() => { vi.unstubAllGlobals(); vi.restoreAllMocks() })
const copy = () => screen.getByRole('button', { name: '座標をコピー' })

it.each<PlacementKind>(['spawner', 'npc', 'waystone'])('copies selected %s working coordinates without saving, reloading, or changing history/selection', async kind => {
  vi.mocked(worldPlacementApi.read).mockResolvedValue({ schemaVersion: 1, revision: 'one', placements: [{ ...placement, kind }] })
  render(<WorldPlacementEditor {...props} />)
  await screen.findByRole('button', { name: '座標をコピー' })
  expect(screen.getByText('ワールド: エリヴァ · actual/eriva')).toBeInTheDocument()
  props.onPlacements.mockClear(); props.onDirtyChange.mockClear()
  fireEvent.click(copy())
  await screen.findByText(/^座標をコピーしました:/)
  expect(write).toHaveBeenCalledExactlyOnceWith('-1150.5 107 1480.25')
  expect(worldPlacementApi.save).not.toHaveBeenCalled()
  expect(worldPlacementApi.read).toHaveBeenCalledTimes(1)
  expect(props.onSelected).not.toHaveBeenCalled()
  expect(props.onPlacements).not.toHaveBeenCalled()
  expect(props.onDirtyChange).not.toHaveBeenCalled()
  expect(screen.getByRole('button', { name: 'Dev に保存' })).toBeDisabled()
})

it('blocks valid and incomplete XYZ drafts until committed and copies unsaved working values while preserving unrelated drafts', async () => {
  render(<WorldPlacementEditor {...props} />)
  await screen.findByRole('button', { name: '座標をコピー' })
  fireEvent.change(screen.getByLabelText('X'), { target: { value: '-' } })
  expect(copy()).toBeDisabled(); fireEvent.click(copy()); expect(write).not.toHaveBeenCalled()
  expect(screen.getByLabelText('X')).toHaveValue('-')
  fireEvent.change(screen.getByLabelText('X'), { target: { value: '-1200.75' } })
  expect(copy()).toBeDisabled()
  fireEvent.keyDown(screen.getByLabelText('X'), { key: 'Enter' })
  fireEvent.change(screen.getByLabelText('YAW'), { target: { value: '-' } })
  expect(copy()).toBeEnabled()
  props.onPlacements.mockClear()
  fireEvent.click(copy()); await screen.findByText(/^座標をコピーしました:/)
  expect(write).toHaveBeenLastCalledWith('-1200.75 107 1480.25')
  expect(screen.getByLabelText('YAW')).toHaveValue('-')
  expect(props.onPlacements).not.toHaveBeenCalled()
  expect(worldPlacementApi.save).not.toHaveBeenCalled()
})

it('does not expose copy for no selection or a selected placement in another world', async () => {
  const { rerender } = render(<WorldPlacementEditor {...props} selectedId={null} />)
  await screen.findByText(/1 件を表示/)
  expect(screen.queryByRole('button', { name: '座標をコピー' })).not.toBeInTheDocument()
  rerender(<WorldPlacementEditor {...props} snapshot={{ ...snapshot, manifest: { ...snapshot.manifest, worldId: 'other' } }} />)
  expect(screen.queryByRole('button', { name: '座標をコピー' })).not.toBeInTheDocument()
  expect(write).not.toHaveBeenCalled()
})

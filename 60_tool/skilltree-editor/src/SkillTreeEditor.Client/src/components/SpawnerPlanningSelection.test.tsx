import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { SpawnerPlanningWorkspace } from './SpawnerPlanningWorkspace'
import { spawnerPlanningApi } from '../api/spawnerPlanningApi'
import { worldPlacementApi } from '../api/worldPlacementApi'
import type { PlanningSnapshot } from '../data/spawnerPlanning'
import type { WorldPlacement } from '../api/worldPlacementApi'

vi.mock('../api/spawnerPlanningApi', () => ({ spawnerPlanningApi: { snapshot: vi.fn(), plan: vi.fn() } }))
vi.mock('../api/worldPlacementApi', () => ({ worldPlacementApi: { read: vi.fn(), save: vi.fn() } }))
// Keep the real workspace/editor selection path and replace only the canvas hit-test.
vi.mock('./SpawnerPlanningMap', () => ({ SpawnerPlanningMap: ({ onPlacement, focus, placements, onCell, selectedCell }: {
  onPlacement: (id: string) => void; focus: { key: number; x: number; z: number } | null; placements: WorldPlacement[]
  onCell: (index: number) => void; selectedCell: number | null
}) => <><button disabled={!placements.some(item => item.placementId === 'one')} onClick={() => onPlacement('one')}>地図で配置を選択</button><output data-testid="selection-focus">{focus?.key ?? 0}</output><button onClick={() => onCell(0)}>地図で区画を選択</button><output data-testid="cell-selection">{selectedCell ?? 'none'}</output></> }))

const snapshot: PlanningSnapshot = {
  manifest: { schemaVersion: 2, worldId: 'eriva', worldName: 'エリヴァ', terrainFingerprint: 'a', exportRevision: 'b', previewOnly: true,
    bounds: { minX: 0, minZ: 0, width: 16, height: 16, cellSize: 16 }, spawn: { x: 2, y: 70, z: 2 }, capturedAt: '', radiusOptions: [32], reasons: {}, limitations: [], progressionPolicy: {} },
  cells: [{ x: 0, z: 0, y: 70, status: 'ok', surface: 'grass', ground: 'dirt', biome: 'plains', safeCount: 256,
    waterCount: 0, treeCount: 0, candidateX: 0, candidateY: 70, candidateZ: 0, minY: 70, maxY: 70,
    habitat: 'grass', regionId: '', targetLevel: 15, reasonCode: 'eligible', reason: '' }], regions: [],
}
beforeEach(() => {
  vi.resetAllMocks()
  vi.mocked(spawnerPlanningApi.snapshot).mockResolvedValue(snapshot)
  vi.mocked(worldPlacementApi.read).mockResolvedValue({ schemaVersion: 1, revision: 'rev-1', placements: [
    { placementId: 'one', kind: 'spawner', worldId: 'eriva', worldName: 'worlds/eriva', templateId: 'wolf', x: 3, y: 70, z: 3, yaw: 0, pitch: 0, enabled: true, waystone: null },
  ] })
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, json: async () => ({ spawners: [{ id: 'wolf', name: '森林狼 Lv.10', radiusMeters: 32, levels: [10] }], npcs: [], worlds: [] }) }))
})
afterEach(() => { vi.unstubAllGlobals(); vi.restoreAllMocks() })

it('Devでも空地点の区画選択を地図に返し、未保存の配置編集を保持する', async () => {
  render(<SpawnerPlanningWorkspace active />)
  await screen.findByText(/1 件を表示/)
  await waitFor(() => expect(screen.getByRole('button', { name: '地図で配置を選択' })).toBeEnabled())
  fireEvent.click(screen.getByRole('button', { name: '地図で配置を選択' }))
  fireEvent.change(screen.getByLabelText('X'), { target: { value: '-' } })
  fireEvent.click(screen.getByRole('button', { name: '地図で区画を選択' }))
  expect(screen.getByTestId('cell-selection')).toHaveTextContent('0')
  expect(screen.getByLabelText('X')).toHaveValue('-')
  expect(worldPlacementApi.save).not.toHaveBeenCalled()
})

it.each(['一覧', '地図'])('%s から同じ配置を再選択すると編集フォームへ戻り、フィルターと未保存編集を保持する', async source => {
  const dirty = vi.fn()
  const { container } = render(<SpawnerPlanningWorkspace active onDirtyChange={dirty} />)
  await screen.findByText(/1 件を表示/)
  await waitFor(() => expect(screen.getByRole('button', { name: '地図で配置を選択' })).toBeEnabled())
  fireEvent.click(screen.getByRole('button', { name: '地図で配置を選択' }))
  expect(screen.getByTestId('selection-focus')).toHaveTextContent('1')
  fireEvent.change(screen.getByLabelText('保存済み配置を検索'), { target: { value: 'one' } })
  fireEvent.change(screen.getByLabelText('X'), { target: { value: '12' } })
  await waitFor(() => expect(dirty).toHaveBeenLastCalledWith(true))
  const inspector = container.querySelector<HTMLDivElement>('.wp-inspector')!
  inspector.scrollTop = 500
  const target = source === '地図' ? screen.getByRole('button', { name: '地図で配置を選択' })
    : within(container.querySelector<HTMLElement>('.wp-list')!).getByRole('button', { pressed: true })
  fireEvent.click(target)
  expect(inspector.scrollTop).toBe(0)
  expect(screen.getByRole('heading', { name: 'スポナーの配置を編集' })).toBeInTheDocument()
  expect(screen.getByTestId('selection-focus')).toHaveTextContent('2')
  expect(screen.getByLabelText('X')).toHaveValue('12')
  expect(screen.getByLabelText('保存済み配置を検索')).toHaveValue('one')
  expect(screen.getByRole('button', { name: 'Dev に保存' })).toBeEnabled()
  expect(dirty).toHaveBeenLastCalledWith(true)
  expect(worldPlacementApi.save).not.toHaveBeenCalled()
})

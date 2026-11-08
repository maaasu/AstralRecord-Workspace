import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import type { ReactNode } from 'react'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import type { ChallengeEntry } from '../api/challengeEntryApi'
import { masterDataApi } from '../api/masterDataApi'
import { spawnerPlanningApi } from '../api/spawnerPlanningApi'
import type { PlanningSnapshot } from '../data/spawnerPlanning'
import { SpawnerPlanningWorkspace } from './SpawnerPlanningWorkspace'

vi.mock('../api/spawnerPlanningApi', () => ({
  spawnerPlanningApi: { snapshot: vi.fn(), plan: vi.fn(), worlds: vi.fn() },
  spawnerPlanningUrl: vi.fn(),
}))
vi.mock('../api/masterDataApi', () => ({
  masterDataApi: { files: vi.fn(), file: vi.fn(), render: vi.fn(), validate: vi.fn(), save: vi.fn() },
}))
const placement = vi.hoisted(() => ({ busy: false }))
vi.mock('./WorldPlacementEditor', async () => {
  const { useEffect } = await import('react')
  return {
    WorldPlacementEditor: ({ map, onBusyChange }: { map: ReactNode; onBusyChange: (busy: boolean) => void }) => {
      useEffect(() => { onBusyChange(placement.busy) }, [onBusyChange])
      return map
    },
  }
})

const worldId = 'eriva_supercontinent'
const snapshot: PlanningSnapshot = {
  manifest: { schemaVersion: 2, worldId, worldName: 'エリヴァ', terrainFingerprint: 'terrain', exportRevision: 'a',
    previewOnly: true, bounds: { minX: -100, minZ: -100, width: 200, height: 200, cellSize: 16 },
    spawn: { x: 0, y: 70, z: 0 }, capturedAt: '', radiusOptions: [64], reasons: {}, limitations: [], progressionPolicy: {} },
  cells: [], regions: [],
}
const boss = { path: '40.features.mob/boss/v1.boss.yml', kind: 'boss', name: '&c古代竜', worldReference: worldId, x: 8.5, y: 70, z: 9 } satisfies ChallengeEntry
const dungeon = { path: '65.features.dungeon/v1.dungeon.yml', kind: 'dungeon', name: '遺跡', worldReference: `wd:${worldId}`, x: 9.5, y: 69, z: 9 } satisfies ChallengeEntry
const bossMarker = () => screen.getByRole('button', { name: 'ボス受付 古代竜' })
const dungeonMarker = () => screen.getByRole('button', { name: 'ダンジョン受付 遺跡' })
const placementMode = () => screen.getByRole('button', { name: '配置編集' })
const challengeMode = () => screen.getByRole('button', { name: 'ボス・ダンジョン受付' })
const markerUrl = (id: string) => `/api/world-placements/challenge-entries?worldId=${id}`
const response = (entries: ChallengeEntry[]) => new Response(JSON.stringify(entries))
const bossDocument = (entry: ChallengeEntry) => ({
  path: entry.path, format: 'yaml', raw: '# boss', revision: 'a',
  content: { challenge: { entryLocation: { worldId, x: entry.x, y: entry.y, z: entry.z } } }, issues: [],
})

beforeEach(() => {
  vi.resetAllMocks()
  placement.busy = false
  vi.spyOn(HTMLCanvasElement.prototype, 'getContext').mockReturnValue(null)
  vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockReturnValue({ left: 0, top: 0, width: 406, height: 500 } as DOMRect)
  vi.mocked(spawnerPlanningApi.worlds).mockResolvedValue([{ id: worldId, name: 'エリヴァ' }, { id: 'verdia', name: 'ヴェルディア' }])
  vi.mocked(spawnerPlanningApi.snapshot).mockImplementation(async (_signal, id) => id === worldId ? snapshot
    : { ...snapshot, manifest: { ...snapshot.manifest, worldId: 'verdia', worldName: 'ヴェルディア', radiusOptions: [] } })
  vi.mocked(spawnerPlanningApi.plan).mockImplementation(() => new Promise(() => {}))
  vi.mocked(masterDataApi.files).mockImplementation(async (category = '') => {
    const entry = category === '40.features.mob/boss' ? boss : category === '65.features.dungeon' ? dungeon
      : { path: '60.features.world/v1.eriva_supercontinent.yml', name: 'エリヴァ' }
    const file = { ...entry, id: entry === boss ? 'boss' : entry === dungeon ? 'dungeon' : worldId, category, format: 'yaml' as const, size: 0, modifiedUtc: '', revision: 'a' }
    return category === '60.features.world' ? [file, { ...file, path: '60.features.world/v1.verdia.yml', name: 'ヴェルディア', id: 'verdia' }] : [file]
  })
  vi.mocked(masterDataApi.file).mockImplementation(async path => path === boss.path ? bossDocument(boss) : ({
    path, format: 'yaml', raw: '# dungeon', revision: 'a',
    content: { entry: { worldRef: dungeon.worldReference, x: dungeon.x, y: dungeon.y, z: dungeon.z } }, issues: [],
  }))
  vi.mocked(masterDataApi.render).mockResolvedValue({ raw: 'saved', commentsPreserved: true, warnings: [] })
  vi.mocked(masterDataApi.validate).mockResolvedValue({ isValid: true, issues: [] })
})
afterEach(() => { vi.restoreAllMocks(); vi.unstubAllGlobals() })

it('shows both nearby types in all map modes and preserves a draft when a marker selection is declined', async () => {
  vi.stubGlobal('fetch', vi.fn(async (url: string) => url === markerUrl(worldId) ? response([boss, dungeon]) : new Response(null, { status: 404 })))
  const { container } = render(<SpawnerPlanningWorkspace active />)
  await screen.findByRole('button', { name: 'ボス受付 古代竜' })
  expect(placementMode()).toHaveAttribute('aria-pressed', 'true')
  expect(bossMarker().style.top).not.toBe(dungeonMarker().style.top)
  expect(container.querySelector('.sp-challenge-leaders line')).toBeInTheDocument()

  fireEvent.click(screen.getByRole('button', { name: 'その他の機能' }))
  fireEvent.click(screen.getByRole('button', { name: 'AI 配置案' }))
  expect(bossMarker()).toBeEnabled()
  expect(dungeonMarker()).toBeEnabled()
  fireEvent.click(bossMarker())
  const master = await screen.findByRole('combobox', { name: '受付地点のマスター' })
  await waitFor(() => expect(bossMarker()).toHaveAttribute('aria-pressed', 'true'))
  expect(challengeMode()).toHaveAttribute('aria-pressed', 'true')
  expect(master).toHaveValue(boss.path)
  expect(masterDataApi.file).toHaveBeenCalledWith(boss.path)
  expect(screen.queryByRole('img', { name: /チャレンジ受付地点/ })).not.toBeInTheDocument()
  fireEvent.click(screen.getByRole('button', { name: '地図で受付地点を選択' }))
  expect(dungeonMarker()).toBeDisabled()
  fireEvent.click(screen.getByRole('button', { name: '地点の選択をキャンセル' }))

  fireEvent.change(screen.getByRole('textbox', { name: 'X' }), { target: { value: '32' } })
  expect(screen.getByRole('img', { name: '未保存のチャレンジ受付地点 X 32 / Y 70 / Z 9' })).toBeVisible()
  const confirm = vi.spyOn(window, 'confirm').mockReturnValue(false)
  fireEvent.click(placementMode())
  fireEvent.click(dungeonMarker())
  expect(confirm).toHaveBeenCalledOnce()
  expect(placementMode()).toHaveAttribute('aria-pressed', 'true')
  expect(masterDataApi.file).toHaveBeenCalledTimes(1)
  fireEvent.click(bossMarker())
  expect(await screen.findByRole('textbox', { name: 'X' })).toHaveValue('32')
  expect(master).toHaveValue(boss.path)
  expect(confirm).toHaveBeenCalledOnce()
  confirm.mockReturnValue(true)
  fireEvent.click(dungeonMarker())
  await waitFor(() => expect(dungeonMarker()).toHaveAttribute('aria-pressed', 'true'))
  expect(master).toHaveValue(dungeon.path)
  expect(masterDataApi.file).toHaveBeenLastCalledWith(dungeon.path)
})

it('opens a saved alias while Dev placements are loading and stops picking after changing its world', async () => {
  placement.busy = true
  vi.stubGlobal('fetch', vi.fn(async () => response([dungeon])))
  render(<SpawnerPlanningWorkspace active />)
  const marker = await screen.findByRole('button', { name: 'ダンジョン受付 遺跡' })
  expect(screen.getByRole('combobox', { name: '表示ワールド' })).toBeDisabled()
  expect(marker).toBeEnabled()
  fireEvent.click(marker)
  const pick = await screen.findByRole('button', { name: '地図で受付地点を選択' })
  expect(pick).toBeEnabled()
  fireEvent.change(screen.getByRole('combobox', { name: '受付地点のワールド' }), { target: { value: 'world:verdia' } })
  expect(pick).toBeDisabled()
})

it('refreshes a saved marker and ignores a late response for a previous world', async () => {
  const movedBoss = { ...boss, x: 48 }
  let finishOldRequest!: (value: Response) => void
  let oldSignal: AbortSignal | undefined
  let reads = 0
  const fetchMarkers = vi.fn(async (url: string, options?: RequestInit) => {
    if (url === markerUrl('verdia')) return response([{ ...dungeon, name: '新世界の遺跡' }])
    if (url !== markerUrl(worldId)) return new Response(null, { status: 404 })
    reads++
    if (reads === 1) return response([boss])
    if (reads === 2) return response([movedBoss])
    oldSignal = options?.signal ?? undefined
    return new Promise<Response>(resolve => { finishOldRequest = resolve })
  })
  vi.stubGlobal('fetch', fetchMarkers)
  vi.mocked(masterDataApi.save).mockResolvedValue(bossDocument(movedBoss))
  const { rerender } = render(<SpawnerPlanningWorkspace active />)
  fireEvent.click(await screen.findByRole('button', { name: 'ボス受付 古代竜' }))
  fireEvent.change(await screen.findByRole('textbox', { name: 'X' }), { target: { value: '48' } })
  fireEvent.click(screen.getByRole('button', { name: '受付地点を保存' }))
  await waitFor(() => expect(bossMarker()).toHaveAttribute('title', expect.stringContaining('X 48')))
  expect(bossMarker()).toHaveAttribute('aria-pressed', 'true')

  rerender(<SpawnerPlanningWorkspace active={false} />)
  rerender(<SpawnerPlanningWorkspace active />)
  await waitFor(() => expect(reads).toBe(3))
  fireEvent.change(screen.getByRole('combobox', { name: '表示ワールド' }), { target: { value: 'verdia' } })
  expect(await screen.findByRole('button', { name: 'ダンジョン受付 新世界の遺跡' })).toBeInTheDocument()
  expect(oldSignal?.aborted).toBe(true)
  await act(async () => finishOldRequest(response([boss])))
  expect(screen.queryByRole('button', { name: 'ボス受付 古代竜' })).not.toBeInTheDocument()
})

import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { StrictMode, useState, type ReactNode } from 'react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { SpawnerPlanningWorkspace } from './SpawnerPlanningWorkspace'
import { spawnerPlanningApi } from '../api/spawnerPlanningApi'
import type { PlacementCatalog } from './WorldPlacementEditor'
import type { WorldPlacement } from '../api/worldPlacementApi'
import type { PlanningPlan, PlanningSnapshot } from '../data/spawnerPlanning'

async function openProposals() {
  fireEvent.click(await screen.findByRole('button', { name: 'その他の機能' }))
  fireEvent.click(screen.getByRole('button', { name: 'AI 配置案' }))
}

vi.mock('../api/spawnerPlanningApi', () => ({ spawnerPlanningApi: { snapshot: vi.fn(), plan: vi.fn(), worlds: vi.fn() } }))
vi.mock('./SpawnerPlanningMap', () => ({ SpawnerPlanningMap: ({ onCell, layer, focus, radii, onRadiiChange }: { onCell: (index: number) => void; layer: string; focus: unknown; radii: boolean; onRadiiChange?: (value: boolean) => void }) => <>{onRadiiChange && <label><input type="checkbox" checked={radii} onChange={event => onRadiiChange(event.target.checked)} />すべての出現範囲を表示</label>}<output data-testid="map-layer">{layer}</output><output data-testid="map-focus">{JSON.stringify(focus)}</output><button onClick={() => onCell(1)}>解析失敗区画を選択</button></> }))
vi.mock('./WorldPlacementEditor', () => ({ WorldPlacementEditor: ({ map, onSelected, onPlacements, onDirtyChange, onBusyChange }: { map: ReactNode; onSelected: (id: string, location: { x: number; z: number }) => void; onPlacements: (items: WorldPlacement[], catalog: PlacementCatalog, ready: boolean) => void; onDirtyChange: (dirty: boolean) => void; onBusyChange: (busy: boolean) => void }) => <>{map}<button onClick={() => onSelected('hidden', { x: 120, z: 240 })}>条件外の配置を選択</button><button onClick={() => onPlacements([], { spawners: [], npcs: [], worlds: [] }, true)}>表示中配置を更新</button><button onClick={() => onDirtyChange(true)}>編集する</button><button onClick={() => onDirtyChange(false)}>編集を破棄する</button><button onClick={() => onBusyChange(true)}>保存を開始する</button><button onClick={() => onBusyChange(false)}>保存を完了する</button></> }))
vi.mock('./ChallengeLocationEditor', () => ({
  ChallengeLocationEditor: ({ onDirtyChange, canPick, active }: { onDirtyChange: (dirty: boolean) => void; canPick: boolean; active: boolean }) => {
    const [hasDraft, setHasDraft] = useState(false)
    return active ? <><button onClick={() => { setHasDraft(true); onDirtyChange(true) }}>チャレンジを編集する</button>
      <button disabled={!canPick}>地図で受付地点を選択</button>{hasDraft && <span>受付の原稿</span>}</> : null
  },
}))
const identity = { schemaVersion: 2, worldId: 'test', terrainFingerprint: 'terrain', exportRevision: 'revision-a' }
const snapshot: PlanningSnapshot = {
  manifest: { ...identity, worldName: 'テスト大陸', previewOnly: true, bounds: { minX: 0, minZ: 0, width: 48, height: 16, cellSize: 16 }, spawn: { x: 0, y: 70, z: 0 }, capturedAt: '2026-10-25T00:00:00Z', radiusOptions: [32, 48, 64], reasons: { covered: '新しい配置案でカバー', failed: '地形の読込・計測に失敗', absent: '未保存の区画' }, limitations: ['実配置は行いません'], progressionPolicy: {} },
  cells: [0, 1, 2].map((index) => ({ x: index * 16, z: 0, y: 70, status: index === 1 ? 'failed' : index === 2 ? 'absent' : 'ok', surface: 'minecraft:sand', ground: 'minecraft:sand', biome: 'minecraft:desert', safeCount: index === 0 ? 256 : 0, waterCount: 0, treeCount: 0, candidateX: index * 16 + 8, candidateY: 70, candidateZ: 8, minY: 70, maxY: 70, habitat: 'dry', regionId: 'east-dry', targetLevel: 38, reasonCode: index === 1 ? 'failed' : index === 2 ? 'absent' : 'covered', reason: index === 1 ? 'チャンク読込が失敗しました' : '' })),
  regions: [{ id: 'east-dry', name: '東部乾燥地', habitat: 'dry', levelMin: 38, levelMax: 38, cellCount: 3, eligibleCells: 1, coveredCellsByRadius: { '32': 1, '48': 1, '64': 1 }, spawnerIds: ['draft-sand'], reason: '乾燥地の野外モブを選定' }],
}
const plan: PlanningPlan = { ...identity, previewOnly: true, radiusMeters: 64, proposals: [{ proposalId: 'proposal', spawnerId: 'draft-sand', x: 8, y: 70, z: 8, radiusMeters: 64, originalRadiusMeters: null, masterUpdateRequired: true, definitionStatus: 'draft', regionId: 'east-dry', regionName: '東部乾燥地', habitat: 'dry', level: 38, spawnMobs: [{ mobId: 'sand-gecko', name: '砂ヤモリ', level: 38, weight: 100 }], reason: '乾燥地の実在Lv38を選定', newCoveredCells: 1, coveredCellIndices: [0] }], reasonCodes: ['covered', 'failed', 'absent'], counts: { covered: 1, failed: 1, absent: 1 }, targetCells: 1, coveredCells: 1, uncoveredCells: 0, coveragePercent: 100 }
const terrainOnly: PlanningSnapshot = { ...snapshot, manifest: { ...snapshot.manifest, worldId: 'other', worldName: '第二ワールド', radiusOptions: [] }, regions: [] }
beforeEach(() => { vi.resetAllMocks(); vi.mocked(spawnerPlanningApi.snapshot).mockResolvedValue(snapshot); vi.mocked(spawnerPlanningApi.plan).mockResolvedValue(plan); vi.mocked(spawnerPlanningApi.worlds).mockResolvedValue([]) })

describe('マスタエディタ内の配置計画', () => {
  it('編集中・保存中の切り替えを防ぎ、別ワールドでは地図の位置と配置案をリセットする', async () => {
    vi.mocked(spawnerPlanningApi.worlds).mockResolvedValue([{ id: 'test', name: 'テスト大陸' }, { id: 'other', name: '第二ワールド' }])
    vi.mocked(spawnerPlanningApi.snapshot).mockImplementation(async (_signal, id) => id === 'other' ? terrainOnly : snapshot)
    render(<SpawnerPlanningWorkspace active />)
    const selector = await screen.findByRole('combobox', { name: '表示ワールド' })
    await waitFor(() => expect(selector).toHaveValue('test'))
    fireEvent.click(await screen.findByRole('button', { name: '条件外の配置を選択' }))
    expect(screen.getByTestId('map-focus')).toHaveTextContent('120')
    fireEvent.click(screen.getByRole('button', { name: '編集する' }))
    expect(selector).toBeDisabled()
    fireEvent.change(selector, { target: { value: 'other' } })
    expect(spawnerPlanningApi.snapshot).toHaveBeenCalledTimes(1)
    fireEvent.click(screen.getByRole('button', { name: '編集を破棄する' }))
    fireEvent.click(screen.getByRole('button', { name: '保存を開始する' }))
    expect(selector).toBeDisabled()
    fireEvent.click(screen.getByRole('button', { name: '保存を完了する' }))
    fireEvent.change(selector, { target: { value: 'other' } })
    await waitFor(() => expect(screen.getByTestId('map-focus')).toHaveTextContent('null'))
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent('第二ワールド')
    expect(screen.queryByRole('button', { name: 'AI 配置案' })).not.toBeInTheDocument()
    expect(spawnerPlanningApi.plan).not.toHaveBeenCalled()
    expect(spawnerPlanningApi.snapshot).toHaveBeenLastCalledWith(expect.any(AbortSignal), 'other')
    fireEvent.click(screen.getByRole('button', { name: '地図を再読込' }))
    await waitFor(() => expect(spawnerPlanningApi.snapshot).toHaveBeenCalledTimes(3))
    expect(spawnerPlanningApi.snapshot).toHaveBeenLastCalledWith(expect.any(AbortSignal), 'other')
  })

  it('地図一覧にエリヴァがあれば最初に表示し、チャレンジ編集中はワールドを固定する', async () => {
    const eriva = { ...snapshot, manifest: { ...snapshot.manifest, worldId: 'eriva_supercontinent' } }
    vi.mocked(spawnerPlanningApi.worlds).mockResolvedValue([{ id: 'other', name: '第二ワールド' }, { id: 'eriva_supercontinent', name: 'エリヴァ超大陸' }])
    vi.mocked(spawnerPlanningApi.snapshot).mockResolvedValue(eriva)
    render(<SpawnerPlanningWorkspace active />)
    const entryMode = screen.getByRole('button', { name: 'ボス・ダンジョン受付' })
    await waitFor(() => expect(entryMode).toBeEnabled())
    fireEvent.click(entryMode)
    const selector = screen.getByRole('combobox', { name: '表示ワールド' })
    expect(selector).toHaveValue('eriva_supercontinent')
    expect(spawnerPlanningApi.snapshot).toHaveBeenLastCalledWith(expect.any(AbortSignal), 'eriva_supercontinent')
    fireEvent.click(screen.getByRole('button', { name: 'チャレンジを編集する' }))
    fireEvent.click(screen.getByRole('button', { name: '配置編集' }))
    expect(selector).toBeDisabled()
    expect(window.dispatchEvent(new Event('beforeunload', { cancelable: true }))).toBe(false)
    vi.mocked(spawnerPlanningApi.worlds).mockResolvedValue([{ id: 'other', name: '第二ワールド' }])
    vi.mocked(spawnerPlanningApi.snapshot).mockRejectedValueOnce(new Error('選択中の地図が一時的に取得できません'))
    fireEvent.click(screen.getByRole('button', { name: '地図を再読込' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('選択中の地図が一時的に取得できません')
    expect(selector).toHaveValue('eriva_supercontinent')
    expect(selector).toBeDisabled()
    expect(screen.getByTestId('map-layer')).toHaveTextContent('terrain')
    expect(window.dispatchEvent(new Event('beforeunload', { cancelable: true }))).toBe(false)
  })

  it('地図がない状態で起動しても再読込で最初に公開された地図を開く', async () => {
    vi.mocked(spawnerPlanningApi.worlds).mockResolvedValueOnce([]).mockResolvedValue([{ id: 'other', name: '第二ワールド' }])
    vi.mocked(spawnerPlanningApi.snapshot).mockRejectedValueOnce(new Error('解析結果がありません')).mockResolvedValue(terrainOnly)
    render(<SpawnerPlanningWorkspace active />)
    expect(await screen.findByRole('alert')).toHaveTextContent('解析結果がありません')
    fireEvent.click(screen.getByRole('button', { name: '地図を再読込' }))
    await waitFor(() => expect(screen.getByTestId('map-layer')).toHaveTextContent('terrain'))
    expect(screen.getByRole('combobox', { name: '表示ワールド' })).toHaveValue('other')
    expect(spawnerPlanningApi.snapshot).toHaveBeenCalledTimes(2)
    expect(spawnerPlanningApi.snapshot).toHaveBeenLastCalledWith(expect.any(AbortSignal), 'other')
  })

  it('地図が未取り込みでも受付地点を手入力でき、切り替えても原稿を保持する', async () => {
    vi.mocked(spawnerPlanningApi.snapshot).mockRejectedValue(new Error('地図がありません'))
    render(<SpawnerPlanningWorkspace active />)
    expect(await screen.findByRole('alert')).toHaveTextContent('地図がありません')
    const entryMode = screen.getByRole('button', { name: 'ボス・ダンジョン受付' })
    fireEvent.click(entryMode)
    expect(screen.getByRole('button', { name: '地図で受付地点を選択' })).toBeDisabled()
    const editor = screen.getByRole('button', { name: 'チャレンジを編集する' })
    fireEvent.click(editor)
    fireEvent.click(screen.getByRole('button', { name: '配置編集' }))
    expect(screen.getByRole('button', { name: '地図を再読込' })).toBeDisabled()
    fireEvent.click(entryMode)
    expect(screen.getByText('受付の原稿')).toBeInTheDocument()
    expect(window.dispatchEvent(new Event('beforeunload', { cancelable: true }))).toBe(false)
  })

  it('公開済みの地図を取得できなかった場合も受付の原稿を再読込で失わない', async () => {
    vi.mocked(spawnerPlanningApi.worlds).mockResolvedValue([{ id: 'test', name: 'テスト大陸' }])
    vi.mocked(spawnerPlanningApi.snapshot).mockRejectedValueOnce(new Error('地図の取得に失敗しました'))
    render(<SpawnerPlanningWorkspace active />)
    await screen.findByRole('alert')
    fireEvent.click(screen.getByRole('button', { name: 'ボス・ダンジョン受付' }))
    expect(screen.getByRole('alert')).toHaveTextContent('地図の取得に失敗しました')
    fireEvent.click(screen.getByRole('button', { name: 'チャレンジを編集する' }))
    expect(screen.getByRole('button', { name: '地図を再読込' })).toBeDisabled()
    expect(screen.getByText('受付の原稿')).toBeInTheDocument()
    expect(spawnerPlanningApi.snapshot).toHaveBeenCalledTimes(1)
  })

  it('同snapshotの3半径を保持し、モード・半径を往復しても再取得せず明示再読込で破棄する', async () => {
    render(<StrictMode><SpawnerPlanningWorkspace active /></StrictMode>)
    await openProposals()
    await waitFor(() => expect(screen.getByRole('button', { name: '案をJSON保存' })).toBeEnabled())
    for (const radius of ['32', '48', '64', '32', '48', '64']) {
      fireEvent.change(screen.getByRole('combobox', { name: '出現半径' }), { target: { value: radius } })
      await waitFor(() => expect(screen.getByRole('button', { name: '案をJSON保存' })).toBeEnabled())
    }
    fireEvent.click(screen.getByRole('button', { name: '配置編集' }))
    await openProposals()
    await waitFor(() => expect(screen.getByRole('button', { name: '案をJSON保存' })).toBeEnabled())
    expect(spawnerPlanningApi.plan).toHaveBeenCalledTimes(3)
    expect(vi.mocked(spawnerPlanningApi.plan).mock.calls.map(call => call[1])).toEqual([64, 32, 48])
    fireEvent.click(screen.getByRole('button', { name: '地図を再読込' }))
    await waitFor(() => expect(spawnerPlanningApi.plan).toHaveBeenCalledTimes(4))
    expect(spawnerPlanningApi.plan).toHaveBeenLastCalledWith(snapshot, 64, expect.any(AbortSignal))
  })

  it('失敗はキャッシュせず再試行し、中断後の遅い応答もキャッシュへ残さない', async () => {
    vi.mocked(spawnerPlanningApi.plan).mockRejectedValueOnce(new Error('一時失敗'))
    render(<SpawnerPlanningWorkspace active />)
    await openProposals()
    expect(await screen.findByRole('alert')).toHaveTextContent('一時失敗')
    fireEvent.click(screen.getByRole('button', { name: '配置編集' }))
    await openProposals()
    await waitFor(() => expect(screen.getByRole('button', { name: '案をJSON保存' })).toBeEnabled())
    let resolveLate!: (value: PlanningPlan) => void
    vi.mocked(spawnerPlanningApi.plan).mockReturnValueOnce(new Promise(resolve => { resolveLate = resolve }))
    fireEvent.change(screen.getByRole('combobox', { name: '出現半径' }), { target: { value: '32' } })
    const signal = vi.mocked(spawnerPlanningApi.plan).mock.calls[2][2]
    fireEvent.click(screen.getByRole('button', { name: '配置編集' }))
    expect(signal.aborted).toBe(true)
    await act(async () => resolveLate({ ...plan, radiusMeters: 32 }))
    await openProposals()
    await waitFor(() => expect(spawnerPlanningApi.plan).toHaveBeenCalledTimes(4))
    await waitFor(() => expect(screen.getByRole('button', { name: '案をJSON保存' })).toBeEnabled())
    expect(spawnerPlanningApi.plan).toHaveBeenLastCalledWith(snapshot, 32, expect.any(AbortSignal))
  })

  it('保存済み配置の編集を既定にし、AI 案は明示切替で読み込む', async () => {
    render(<SpawnerPlanningWorkspace active />)
    expect(screen.getByRole('button', { name: '配置編集' })).toHaveAttribute('aria-pressed', 'true')
    await screen.findByRole('heading', { name: 'テスト大陸' })
    expect(spawnerPlanningApi.plan).not.toHaveBeenCalled()
    expect(screen.queryByRole('button', { name: 'AI 配置案' })).not.toBeInTheDocument()
    await openProposals()
    expect(screen.getByRole('button', { name: 'AI 配置案' })).toHaveFocus()
    await waitFor(() => expect(spawnerPlanningApi.plan).toHaveBeenCalled())
  })
  it('Devの全範囲は初期OFFで、AIの既存一括切替と状態を共有しモード往復でも保持する', async () => {
    render(<SpawnerPlanningWorkspace active />)
    const toggle = await screen.findByRole('checkbox', { name: 'すべての出現範囲を表示' })
    expect(toggle).not.toBeChecked()
    fireEvent.click(toggle)
    expect(toggle).toBeChecked()
    await openProposals()
    const aiToggle = screen.getByRole('checkbox', { name: '出現範囲を重ねる' })
    expect(aiToggle).toBeChecked()
    fireEvent.click(aiToggle)
    fireEvent.click(screen.getByRole('button', { name: '配置編集' }))
    expect(screen.getByRole('checkbox', { name: 'すべての出現範囲を表示' })).not.toBeChecked()
  })
  it('AI 案のレイヤー選択を保持しつつ配置編集の地図は地形に固定する', async () => {
    render(<SpawnerPlanningWorkspace active />)
    expect(await screen.findByTestId('map-layer')).toHaveTextContent('terrain')
    await openProposals()
    await screen.findByRole('combobox', { name: '表示レイヤー' })
    fireEvent.change(screen.getByRole('combobox', { name: '表示レイヤー' }), { target: { value: 'levels' } })
    expect(screen.getByTestId('map-layer')).toHaveTextContent('levels')
    fireEvent.click(screen.getByRole('button', { name: '配置編集' }))
    expect(screen.getByTestId('map-layer')).toHaveTextContent('terrain')
    await openProposals()
    expect(screen.getByTestId('map-layer')).toHaveTextContent('levels')
  })
  it('未登録案と解析失敗を表示し、公開操作を用意しない', async () => {
    render(<SpawnerPlanningWorkspace active />)
    await openProposals()
    fireEvent.click(await screen.findByRole('button', { name: /Lv.38 · 東部乾燥地/ }))
    expect(screen.getByText('未登録のスポナーマスタ案')).toBeInTheDocument()
    expect(screen.getByText('draft-sand')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '解析失敗区画を選択' }))
    expect(screen.getByText('チャンク読込が失敗しました')).toBeInTheDocument()
    expect(screen.getByText('未完成・解析不可区画').parentElement).toHaveTextContent('1')
    expect(screen.queryByRole('button', { name: /公開|適用|実配置/ })).not.toBeInTheDocument()
  })
  it('解析が未準備のとき、空の成功結果として表示しない', async () => {
    vi.mocked(spawnerPlanningApi.snapshot).mockRejectedValue(new Error('配置案はまだありません'))
    render(<SpawnerPlanningWorkspace active />)
    expect(await screen.findByRole('alert')).toHaveTextContent('配置案はまだありません')
    expect(screen.queryByText('100.0%')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: '地図を再読込' })).toBeEnabled()
  })
  it('道路モードでは後続地域の保留と道路距離を表示する', async () => {
    const roadSnapshot = structuredClone(snapshot)
    roadSnapshot.manifest.progressionPolicy = { mode: 'road', maxLevel: 45, levelStepMeters: 300, road: { nodes: [], edges: [], maxDistanceMeters: 1400, corridorMeters: 384, candidateCenterMeters: 320 } }
    roadSnapshot.cells[1].roadDistanceMeters = 648
    roadSnapshot.cells[1].distanceToRoadMeters = 32
    vi.mocked(spawnerPlanningApi.snapshot).mockResolvedValue(roadSnapshot)
    render(<SpawnerPlanningWorkspace active />)
    await openProposals()
    expect(await screen.findByText(/北東のLv.100超予定地/)).toBeInTheDocument()
    expect(screen.getByText(/道路距離300mごと/)).toHaveTextContent('未到達の距離帯は配置しません')
    await waitFor(() => expect(screen.getByRole('button', { name: '案をJSON保存' })).toBeEnabled())
    fireEvent.click(screen.getByRole('button', { name: '解析失敗区画を選択' }))
    expect(screen.getByText(/道に沿った距離:/)).toHaveTextContent('648m')
    expect(screen.getByText(/道からの距離:/)).toHaveTextContent('32m')
  })
  it('半径切替の読込中は前の案の詳細と保存を消す', async () => {
    render(<SpawnerPlanningWorkspace active />)
    await openProposals()
    fireEvent.click(await screen.findByRole('button', { name: /Lv.38 · 東部乾燥地/ }))
    vi.mocked(spawnerPlanningApi.plan).mockReturnValue(new Promise(() => {}))
    fireEvent.change(screen.getByRole('combobox', { name: '出現半径' }), { target: { value: '32' } })
    await waitFor(() => expect(screen.queryByText('draft-sand')).not.toBeInTheDocument())
    expect(screen.getByRole('button', { name: '案をJSON保存' })).toBeDisabled()
  })
  it('地形延長を検出した道路と区別して説明する', async () => {
    const extended = structuredClone(snapshot)
    extended.manifest.progressionPolicy = { mode: 'road', levelStepMeters: 300, road: { nodes: [], edges: [], terrainExtension: { maxDistanceMeters: 3808 } } }
    extended.cells[1].roadDistanceMeters = 3424
    vi.mocked(spawnerPlanningApi.snapshot).mockResolvedValue(extended)
    render(<SpawnerPlanningWorkspace active />)
    await openProposals()
    expect(await screen.findByText(/地形経路距離300mごと/)).toHaveTextContent('水色の点線')
    expect(screen.getByText(/実際の歩行可能性は未検証/)).toBeInTheDocument()
    await waitFor(() => expect(screen.getByRole('button', { name: '案をJSON保存' })).toBeEnabled())
    fireEvent.click(screen.getByRole('button', { name: '解析失敗区画を選択' }))
    expect(screen.getByText(/地形経路距離:/)).toHaveTextContent('3,424m')
  })
  it('地図に未公開の配置でも指定座標へ移動し、後続の配置更新で再移動しない', async () => {
    render(<SpawnerPlanningWorkspace active />)
    fireEvent.click(await screen.findByRole('button', { name: '条件外の配置を選択' }))
    expect(screen.getByTestId('map-focus')).toHaveTextContent('{"key":1,"x":120,"z":240}')
    fireEvent.click(screen.getByRole('button', { name: '表示中配置を更新' }))
    expect(screen.getByTestId('map-focus')).toHaveTextContent('{"key":1,"x":120,"z":240}')
    fireEvent.click(screen.getByRole('button', { name: '条件外の配置を選択' }))
    expect(screen.getByTestId('map-focus')).toHaveTextContent('{"key":2,"x":120,"z":240}')
  })
})

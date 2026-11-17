import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, expect, it, vi } from 'vitest'
import { masterDataApi } from '../api/masterDataApi'
import type { JsonObject } from '../types/editor'
import type { MasterFileSummary } from '../types/masterData'
import { MasterDataEditor } from './MasterDataEditor'
import { DungeonMaterialField } from './DungeonMaterialField'

const category = '65.features.dungeon'
const path = category + '/v1.ruins.yml'
const content: JsonObject = {
  id: 'ruins', displayName: 'アルダ始まりの遺跡', schemaVersion: 1, recommendedLevel: 5,
  entry: { worldRef: 'world:greenfields', x: -0.5, y: 64, z: 2, future: { custom: true } },
  challenge: { timeLimitSeconds: null },
  encounter: { bossMobId: 'mob:guardian', normalMobPool: [{ mobId: 'mb:boar', note: '維持' }] },
  generation: { roomSize: { min: 15, max: 27 } },
  clearRewards: { lootTable: 'loot_table:common', items: [{ itemId: 'item:coin', amount: 2, note: '維持' }] },
}
const files = [
  [path, 'ruins', 'アルダ始まりの遺跡', 'dungeon', null],
  ['60.features.world/v1.greenfields.yml', 'greenfields', 'ミズガルズの緑野', 'world', null],
  ['40.features.mob/enemy/v1.boar.yml', 'boar', '野イノシシ', 'mob', 'PIG_SPAWN_EGG'],
  ['40.features.mob/enemy/v1.wolf.yml', 'wolf', '風のオオカミ', 'mob', 'WOLF_SPAWN_EGG', 'mob_head_wolf'],
  ['40.features.mob/boss/v1.guardian.yml', 'guardian', '遺跡の番人', 'mob', 'HUSK_SPAWN_EGG', 'mob_head_husk'],
  ['80.shared.loot/table/v1.common.yml', 'common', '冒険 / 共通報酬', 'loot_table', null],
  ['10.features.item/10.material/v1.coin.yml', 'coin', '古銭', 'item', 'GOLD_NUGGET'],
  ['10.features.item/10.material/v1.leaf.yml', 'leaf', '風の葉', 'item', 'FERN'],
] as const

beforeEach(() => {
  sessionStorage.clear()
  vi.restoreAllMocks()
  vi.spyOn(masterDataApi, 'catalog').mockResolvedValue({
    root: '/filebase', backups: '/backups', referencePrefixes: { mb: 'mob' },
    categories: [{ id: category, directory: category, label: 'ダンジョン', fields: [], fileCount: 1, documents: [], jsonSchemas: [], templates: [] }],
  })
  vi.spyOn(masterDataApi, 'files').mockResolvedValue(files.map(([filePath, id, name, , icon, iconModel]): MasterFileSummary => ({
    path: filePath, category: filePath.slice(0, filePath.lastIndexOf('/')), id, name, icon, iconModel, format: 'yaml', revision: 'rev', size: 1, modifiedUtc: '',
  })))
  vi.spyOn(masterDataApi, 'candidates').mockResolvedValue(files.map(([filePath, value, label, kind]) => ({ path: filePath, value, label, kind, pointer: '/id' })))
  vi.spyOn(masterDataApi, 'filterIndex').mockResolvedValue({ sources: [], skillTags: [], tags: [] })
  vi.spyOn(masterDataApi, 'file').mockResolvedValue({ path, content, raw: JSON.stringify(content), revision: 'rev', format: 'yaml', issues: [] })
  vi.spyOn(masterDataApi, 'render').mockImplementation(async (_path, value) => ({ raw: JSON.stringify(value), commentsPreserved: true, warnings: [] }))
})

async function openDungeon() {
  render(<MasterDataEditor />)
  fireEvent.doubleClick(await screen.findByRole('button', { name: /アルダ始まりの遺跡.*ruins/ }))
  return screen.findByRole('region', { name: 'マスター項目の編集' })
}

it('refreshes the boss catalog when the dungeon file is reloaded after an external rename', async () => {
  await openDungeon()
  const boss = '40.features.mob/boss/v1.morgras.yml'
  const originalFiles = await masterDataApi.files()
  const originalCandidates = await masterDataApi.candidates()
  vi.mocked(masterDataApi.files).mockResolvedValue([
    ...originalFiles.filter(file => file.id !== 'guardian'),
    { ...originalFiles.find(file => file.id === 'guardian')!, path: boss, id: 'morgras', name: 'モルグラス' },
  ])
  vi.mocked(masterDataApi.candidates).mockResolvedValue([
    ...originalCandidates.filter(entry => entry.value !== 'guardian'),
    { path: boss, value: 'morgras', label: 'モルグラス', kind: 'mob', pointer: '/id' },
  ])
  const updated = { ...content, encounter: { bossMobId: 'mob:morgras' } }
  vi.mocked(masterDataApi.file).mockResolvedValue({
    path, content: updated, raw: JSON.stringify(updated), revision: 'new', format: 'yaml', issues: [],
  })
  const reload = screen.getByRole('button', { name: 'ファイルを再読込' })
  await waitFor(() => expect(reload).toBeEnabled())
  vi.mocked(masterDataApi.file).mockClear()
  await act(async () => { fireEvent.click(reload) })
  expect(masterDataApi.file).toHaveBeenCalledWith(path, expect.any(AbortSignal))
  await waitFor(() => expect(screen.getByRole('button', { name: '/encounter/bossMobId の定義を開く' })).toHaveTextContent('モルグラス'))
  expect(screen.queryByText('参照先が見つかりません')).not.toBeInTheDocument()
  expect(masterDataApi.files).toHaveBeenLastCalledWith('', '', true)
})

it('shows the boss and reward definitions on hover and removes the preview without opening a tab', async () => {
  const editor = within(await openDungeon())
  const dungeon = await masterDataApi.file(path)
  vi.mocked(masterDataApi.file).mockImplementation(async filePath => {
    if (filePath === path) return dungeon
    const details = filePath.includes('/boss/')
      ? { lore: ['&7遺跡を守る。'], entityType: 'HUSK', level: 5, baseStats: [{ status: 'MAX_HEALTH', value: 3200 }] }
      : { lore: ['古い交換通貨。'], rarity: 'RARE' }
    return { ...dungeon, path: filePath, content: details }
  })
  const boss = editor.getByRole('button', { name: '/encounter/bossMobId の定義を開く' })
  const user = userEvent.setup()
  await user.hover(boss)
  expect(await screen.findByText('遺跡を守る。')).toBeVisible()
  expect(screen.getByRole('tooltip')).toHaveTextContent('mob:guardian')
  expect(screen.getByRole('tooltip')).toHaveTextContent('3200')
  const tooltip = screen.getByRole('tooltip')
  await user.hover(tooltip)
  await waitFor(() => expect(tooltip).toBeVisible())
  await user.unhover(tooltip)
  await waitFor(() => expect(screen.queryByRole('tooltip')).not.toBeInTheDocument())

  const reward = editor.getByRole('button', { name: '古銭 の定義を開く' })
  await user.hover(reward)
  expect(await screen.findByText('古い交換通貨。')).toBeVisible()
  expect(screen.getByRole('tooltip')).toHaveTextContent('レアリティ レア')
  expect(screen.getAllByRole('tab')).toHaveLength(1)
})

it('presents one editable dungeon overview and scrolls to a section without dropping the others', async () => {
  const editor = within(await openDungeon())
  for (const name of ['入場地点', 'パーティー', '挑戦ルール', 'モブの出現設定', 'クリア報酬', '生成設定', '外観テーマ']) {
    expect(editor.getByRole('heading', { name })).toBeVisible()
  }
  expect(editor.getByRole('checkbox', { name: '時間制限なし' })).toBeChecked()
  expect(editor.getByRole('textbox', { name: '/generation/bossRoomSize/min' })).toHaveValue('15')
  expect(editor.getByRole('textbox', { name: '/party/max' })).toHaveValue('4')
  expect(editor.getByRole('button', { name: '/entry/worldRef の定義を開く' })).toHaveTextContent('ミズガルズの緑野')
  expect(editor.getByRole('button', { name: '/clearRewards/lootTable の定義を開く' })).toHaveTextContent('冒険 / 共通報酬')
  const boss = editor.getByRole('button', { name: '/encounter/bossMobId の定義を開く' })
  const head = boss.querySelector('img')!
  expect(head).toHaveAttribute('src', '/api/minecraft-mob-heads/mob_head_husk?revision=0')
  fireEvent.error(head)
  expect(boss.querySelector('img')).toHaveAttribute('src', '/api/minecraft-icons/HUSK_SPAWN_EGG?revision=0')
  expect(editor.getByRole('combobox', { name: '/generation/roomShapes/0/type' })).toHaveDisplayValue('長方形')
  expect(editor.getAllByRole('button', { name: 'Stone Bricks' })[0].querySelector('img')).toHaveAttribute('src', '/api/minecraft-icons/STONE_BRICKS?revision=0')
  expect(editor.getByText('生成範囲に項目を追加')).toBeVisible()
  expect(editor.getByText('部屋ごとの出現数に項目を追加')).toBeVisible()

  fireEvent.click(editor.getByRole('button', { name: '生成設定' }))
  await waitFor(() => expect(editor.getByRole('heading', { name: '生成設定' })).toHaveFocus())
  expect(editor.getByRole('heading', { name: '入場地点' })).toBeVisible()

  const search = editor.getByRole('textbox', { name: 'フォーム内検索' })
  fireEvent.change(search, { target: { value: '受付半径' } })
  fireEvent.click(editor.getByRole('button', { name: '/entry/radius に移動' }))
  const radius = editor.getByRole('textbox', { name: '/entry/radius' })
  await waitFor(() => expect(radius).toHaveFocus())
  expect(radius).toHaveValue('2')

  for (const [fieldPath, label, defaultValue] of [
    ['/encounter/normalMobPool/0/weight', '抽選重み', '1'],
    ['/clearRewards/items/0/rate', '確率 (%)', '100'],
  ]) {
    fireEvent.change(search, { target: { value: fieldPath } })
    const match = editor.getByRole('button', { name: fieldPath + ' に移動' })
    expect(within(match).getByText(label)).toBeVisible()
    fireEvent.click(match)
    const input = editor.getByRole('textbox', { name: fieldPath })
    await waitFor(() => expect(input).toHaveFocus())
    expect(input).toHaveValue(defaultValue)
  }

  fireEvent.change(search, { target: { value: '/challenge/timeLimitSeconds' } })
  fireEvent.click(editor.getByRole('button', { name: '/challenge/timeLimitSeconds に移動' }))
  await waitFor(() => expect(editor.getByRole('checkbox', { name: '時間制限なし' })).toHaveFocus())
  expect(editor.getByRole('heading', { name: '入場地点' })).toBeVisible()
  expect(screen.getByRole('tab', { name: 'アルダ始まりの遺跡' })).toBeInTheDocument()

  fireEvent.change(search, { target: { value: 'bossMobId' } })
  fireEvent.click(editor.getByRole('button', { name: '/encounter/bossMobId に移動' }))
  await waitFor(() => expect(editor.getByRole('button', { name: 'ボスモブ（/encounter/bossMobId）を選択' })).toHaveFocus())
}, 15_000)

it('offers solid theme blocks and preserves torch and stair candidates for their respective fields', async () => {
  const onChange = vi.fn()
  const { rerender } = render(<DungeonMaterialField path="/theme/floor" add value="" onChange={onChange} />)
  fireEvent.click(screen.getByRole('button', { name: '＋ 素材を追加' }))
  let search = await screen.findByRole('searchbox', { name: 'Materialを検索' }, { timeout: 10_000 })
  fireEvent.change(search, { target: { value: 'TORCH' } })
  expect(screen.queryByRole('button', { name: 'TORCH' })).not.toBeInTheDocument()
  fireEvent.change(search, { target: { value: 'STONE_BRICKS' } })
  fireEvent.click(screen.getByRole('button', { name: 'STONE_BRICKS' }))
  expect(onChange).toHaveBeenLastCalledWith('STONE_BRICKS')

  rerender(<DungeonMaterialField path="/theme/lightMaterial" value="TORCH" onChange={onChange} />)
  fireEvent.click(screen.getByRole('button', { name: 'Torch' }))
  search = await screen.findByRole('searchbox', { name: 'Materialを検索' }, { timeout: 10_000 })
  fireEvent.change(search, { target: { value: 'SOUL_TORCH' } })
  fireEvent.click(screen.getByRole('button', { name: 'SOUL_TORCH' }))
  expect(onChange).toHaveBeenLastCalledWith('SOUL_TORCH')

  rerender(<DungeonMaterialField path="/theme/pillar/stairMaterial" value="STONE_BRICK_STAIRS" onChange={onChange} />)
  fireEvent.click(screen.getByRole('button', { name: 'Stone Brick Stairs' }))
  search = await screen.findByRole('searchbox', { name: 'Materialを検索' }, { timeout: 10_000 })
  fireEvent.change(search, { target: { value: 'STONE_BRICK' } })
  expect(screen.queryByRole('button', { name: 'STONE_BRICKS' })).not.toBeInTheDocument()
  fireEvent.click(screen.getByRole('button', { name: 'STONE_BRICK_STAIRS' }))
  expect(onChange).toHaveBeenLastCalledWith('STONE_BRICK_STAIRS')
}, 15_000)

it('adds a mob and a reward via searchable dialogs while preserving original values and omissions', async () => {
  const editor = within(await openDungeon())
  const user = userEvent.setup()
  const addMob = editor.getByRole('button', { name: '＋ モブを追加' })
  await user.click(addMob)
  let dialog = within(screen.getByRole('dialog', { name: 'モブを追加' }))
  expect(dialog.queryByRole('button', { name: /遺跡の番人/ })).not.toBeInTheDocument()
  await user.type(dialog.getByRole('searchbox'), '風 wolf')
  const wolf = dialog.getByRole('button', { name: /風のオオカミ.*wolf/ })
  expect(wolf.querySelector('img')).toHaveAttribute('src', '/api/minecraft-mob-heads/mob_head_wolf?revision=0')
  await user.click(wolf)
  expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  expect(editor.getByRole('button', { name: '風のオオカミ の定義を開く' }).querySelector('img')).toHaveAttribute('src', '/api/minecraft-mob-heads/mob_head_wolf?revision=0')
  expect(editor.getByRole('textbox', { name: '/encounter/normalMobPool/1/level' })).toHaveValue('')
  fireEvent.change(editor.getByRole('textbox', { name: '/encounter/normalMobPool/1/level' }), { target: { value: '5' } })

  const addReward = editor.getByRole('button', { name: '＋ アイテムを追加' })
  await user.click(addReward)
  dialog = within(screen.getByRole('dialog', { name: 'アイテムを追加' }))
  await user.type(dialog.getByRole('searchbox'), '風の葉')
  await user.click(dialog.getByRole('button', { name: /風の葉.*leaf/ }))
  expect(editor.getByRole('textbox', { name: '/clearRewards/items/1/rate' })).toHaveValue('100')
  fireEvent.change(editor.getByRole('textbox', { name: '/clearRewards/items/1/amount' }), { target: { value: '1~3' } })
  fireEvent.change(editor.getByRole('textbox', { name: '/generation/bossRoomSize/max' }), { target: { value: '33' } })
  fireEvent.click(within(screen.getByRole('tabpanel')).getByRole('button', { name: '原稿 YAML' }))
  await waitFor(() => expect(masterDataApi.render).toHaveBeenCalledWith(path, {
    ...content,
    encounter: { bossMobId: 'mob:guardian', normalMobPool: [{ mobId: 'mb:boar', note: '維持' }, { mobId: 'mob:wolf', level: 5, weight: 1 }] },
    clearRewards: { lootTable: 'loot_table:common', items: [{ itemId: 'item:coin', amount: 2, note: '維持' }, { itemId: 'item:leaf', rate: 100, amount: '1~3' }] },
    generation: { roomSize: { min: 15, max: 27 }, bossRoomSize: { max: 33 } },
  }, expect.any(String)))
}, 15_000)

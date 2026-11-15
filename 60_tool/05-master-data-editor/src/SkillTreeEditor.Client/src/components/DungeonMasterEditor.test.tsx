import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, expect, it, vi } from 'vitest'
import { masterDataApi } from '../api/masterDataApi'
import type { JsonObject } from '../types/editor'
import type { MasterFileSummary } from '../types/masterData'
import { MasterDataEditor } from './MasterDataEditor'

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
  ['40.features.mob/enemy/v1.wolf.yml', 'wolf', '風のオオカミ', 'mob', 'WOLF_SPAWN_EGG'],
  ['40.features.mob/boss/v1.guardian.yml', 'guardian', '遺跡の番人', 'mob', 'HUSK_SPAWN_EGG'],
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
  vi.spyOn(masterDataApi, 'files').mockResolvedValue(files.map(([filePath, id, name, , icon]): MasterFileSummary => ({
    path: filePath, category: filePath.slice(0, filePath.lastIndexOf('/')), id, name, icon, format: 'yaml', revision: 'rev', size: 1, modifiedUtc: '',
  })))
  vi.spyOn(masterDataApi, 'candidates').mockResolvedValue(files.map(([filePath, value, label, kind]) => ({ path: filePath, value, label, kind, pointer: '/id' })))
  vi.spyOn(masterDataApi, 'filterIndex').mockResolvedValue({ sources: [], skillTags: [], tags: [] })
  vi.spyOn(masterDataApi, 'file').mockResolvedValue({ path, content, raw: JSON.stringify(content), revision: 'rev', format: 'yaml', issues: [] })
  vi.spyOn(masterDataApi, 'render').mockImplementation(async (_path, value) => ({ raw: JSON.stringify(value), commentsPreserved: true, warnings: [] }))
})

async function openDungeon() {
  render(<MasterDataEditor />)
  fireEvent.click(await screen.findByRole('button', { name: /アルダ始まりの遺跡.*ruins/ }))
  return screen.findByRole('region', { name: 'マスター項目の編集' })
}

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
  expect(editor.getByRole('button', { name: '/encounter/bossMobId の定義を開く' }).querySelector('img')).toHaveAttribute('src', '/api/minecraft-icons/HUSK_SPAWN_EGG?revision=0')
  expect(editor.getByRole('combobox', { name: '/generation/roomShapes/0/type' })).toHaveDisplayValue('長方形')
  expect(editor.getAllByRole('button', { name: 'Stone Bricks' })[0].querySelector('img')).toHaveAttribute('src', '/api/minecraft-icons/STONE_BRICKS?revision=0')
  expect(editor.getByText('生成範囲に項目を追加')).toBeVisible()
  expect(editor.getByText('部屋ごとの出現数に項目を追加')).toBeVisible()

  fireEvent.click(editor.getByRole('button', { name: '生成設定' }))
  await waitFor(() => expect(editor.getByRole('heading', { name: '生成設定' })).toHaveFocus())
  expect(editor.getByRole('heading', { name: '入場地点' })).toBeVisible()

  const search = editor.getByRole('textbox', { name: 'フォーム内検索' })
  fireEvent.change(search, { target: { value: 'bossMobId' } })
  fireEvent.click(editor.getByRole('button', { name: '/encounter/bossMobId に移動' }))
  await waitFor(() => expect(editor.getByRole('button', { name: 'ボスモブ（/encounter/bossMobId）を選択' })).toHaveFocus())
})

it('adds a mob and a reward via searchable dialogs while preserving original values and omissions', async () => {
  const editor = within(await openDungeon())
  const user = userEvent.setup()
  const addMob = editor.getByRole('button', { name: '＋ モブを追加' })
  await user.click(addMob)
  let dialog = within(screen.getByRole('dialog', { name: 'モブを追加' }))
  expect(dialog.queryByRole('button', { name: /遺跡の番人/ })).not.toBeInTheDocument()
  await user.type(dialog.getByRole('searchbox'), '風 wolf')
  await user.click(dialog.getByRole('button', { name: /風のオオカミ.*wolf/ }))
  expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  expect(editor.getByRole('button', { name: '風のオオカミ の定義を開く' })).toBeVisible()
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
})

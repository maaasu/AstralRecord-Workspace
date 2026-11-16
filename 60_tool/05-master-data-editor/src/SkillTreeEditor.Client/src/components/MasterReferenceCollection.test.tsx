import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { beforeEach, expect, it, vi } from 'vitest'
import { masterDataApi } from '../api/masterDataApi'
import type { JsonObject } from '../types/editor'
import type { MasterDocument, MasterFileSummary } from '../types/masterData'
import { MasterDataEditor } from './MasterDataEditor'

const mobPath = '40.features.mob/enemy/v1.boar.yml'
const mobCategory = '40.features.mob/enemy'
const peltPath = '10.features.item/10.material/v1.10a00002.pelt.yml'
const sprigPath = '10.features.item/10.material/v1.10a00003.sprig.yml'
const mob: JsonObject = {
  id: 'boar', name: '野イノシシ',
  drops: { exp: 8, lootTable: { ref: 'loot_table:forest' }, items: [
    { itemId: { ref: 'im:10a00002' }, rate: 35, amount: 1, hidden: true, luckAffected: false },
    { itemId: 'item:missing', rate: 1 },
  ] },
}
const pelt = { id: '10a00002', name: '&6毛皮', icon: 'LEATHER', customModelData: 230002 }
const sprig = { id: '10a00003', name: '&a風葉', icon: 'FERN', customModelData: 230003 }
const originalDocuments = new Map<string, JsonObject>([[mobPath, mob], [peltPath, pelt], [sprigPath, sprig]])

function summary(path: string, content: JsonObject): MasterFileSummary {
  return {
    path, category: path.slice(0, path.lastIndexOf('/')), format: 'yaml', id: String(content.id), name: String(content.name),
    revision: 'rev', size: 1, modifiedUtc: '', icon: typeof content.icon === 'string' ? content.icon : undefined,
    customModelData: typeof content.customModelData === 'number' ? content.customModelData : undefined,
  }
}

function openRowActions(path: string) {
  fireEvent.click(screen.getByLabelText(path + ' の操作'))
}

beforeEach(() => {
  sessionStorage.clear()
  vi.restoreAllMocks()
  vi.spyOn(masterDataApi, 'catalog').mockResolvedValue({ root: '/filebase', backups: '/backups', referencePrefixes: { im: 'item' }, categories: [
    { id: mobCategory, directory: mobCategory, label: 'モブ / 敵', fields: [], documents: [], jsonSchemas: [], templates: [], fileCount: 1 },
  ] })
  vi.spyOn(masterDataApi, 'files').mockResolvedValue([...originalDocuments].map(([path, content]) => summary(path, content)))
  vi.spyOn(masterDataApi, 'file').mockImplementation(async (path): Promise<MasterDocument> => {
    const content = originalDocuments.get(path)!
    return { path, content: structuredClone(content), raw: JSON.stringify(content), revision: 'rev', format: 'yaml', issues: [] }
  })
  vi.spyOn(masterDataApi, 'candidates').mockResolvedValue([peltPath, sprigPath].map(path => {
    const { id, name } = summary(path, originalDocuments.get(path)!)
    return { path, label: name!, value: id!, kind: 'item', pointer: '/id' }
  }))
  vi.spyOn(masterDataApi, 'filterIndex').mockResolvedValue({ sources: [], skillTags: [], tags: [] })
  vi.spyOn(masterDataApi, 'render').mockImplementation(async (_path, value) => ({ raw: JSON.stringify(value), commentsPreserved: true, warnings: [] }))
  vi.spyOn(masterDataApi, 'save')
})

it('opens a textured drop definition and edits the drop list without losing the mob draft or other reward data', async () => {
  render(<MasterDataEditor />)
  fireEvent.click(await screen.findByRole('button', { name: /野イノシシ.*boar/ }))
  fireEvent.click(await screen.findByRole('button', { name: '/drops を開く' }))
  const peltLink = await screen.findByRole('button', { name: '毛皮 の定義を開く' })
  expect(peltLink.querySelector('img')).toHaveAttribute('src', '/api/minecraft-item-textures/leather/230002?revision=0')
  expect(within(peltLink).getByText('幸運補正なし')).toBeVisible()
  expect(screen.getByText('参照先が見つかりません')).toBeInTheDocument()

  const rate = screen.getByRole('textbox', { name: '/drops/items/0/rate' })
  fireEvent.change(rate, { target: { value: '' } })
  expect(rate).toBeEnabled()
  fireEvent.click(screen.getByRole('button', { name: '/drops/items/0/rate' }))
  await waitFor(() => expect(rate).toHaveFocus())
  fireEvent.change(rate, { target: { value: '45.5' } })
  fireEvent.change(screen.getByRole('textbox', { name: '/drops/items/0/amount' }), { target: { value: '1~3' } })
  fireEvent.click(peltLink)
  expect(await screen.findByRole('heading', { name: '毛皮' })).toBeInTheDocument()
  fireEvent.click(screen.getByRole('tab', { name: /野イノシシ · 未保存/ }))
  expect(screen.getByRole('textbox', { name: '/drops/items/0/rate' })).toHaveValue('45.5')

  fireEvent.click(screen.getByRole('button', { name: '＋ アイテムを追加' }))
  fireEvent.change(screen.getByRole('searchbox', { name: 'アイテムを検索' }), { target: { value: '風葉' } })
  fireEvent.click(screen.getByRole('button', { name: /風葉.*10a00003/ }))
  expect(screen.getByRole('button', { name: '風葉 の定義を開く' })).toBeInTheDocument()
  openRowActions('/drops/items/1')
  fireEvent.click(screen.getByRole('button', { name: '/drops/items/1 を削除' }))
  expect(screen.queryByText('参照先が見つかりません')).not.toBeInTheDocument()
  fireEvent.click(screen.getByRole('button', { name: '元に戻す Ctrl+Z' }))
  expect(screen.getByText('参照先が見つかりません')).toBeInTheDocument()
  openRowActions('/drops/items/1')
  fireEvent.click(screen.getByRole('button', { name: '/drops/items/1 を削除' }))

  fireEvent.click(within(screen.getByRole('tabpanel')).getByRole('button', { name: '原稿 YAML' }))
  await waitFor(() => expect(masterDataApi.render).toHaveBeenCalledWith(mobPath, {
    ...mob, drops: {
      exp: 8, lootTable: { ref: 'loot_table:forest' }, items: [
        { itemId: { ref: 'im:10a00002' }, rate: 45.5, amount: '1~3', hidden: true, luckAffected: false },
        { itemId: { ref: 'item:10a00003' }, rate: 0, amount: '1', luckAffected: true, hidden: false },
      ],
    },
  }, expect.any(String)))
  expect(masterDataApi.file).toHaveBeenCalledTimes(2)
  expect(masterDataApi.save).not.toHaveBeenCalled()
})

it('updates an exchange ingredient reference and category together from the detail picker', async () => {
  const category = '45.features.shop'
  const path = category + '/v1.exchange.yml'
  const currencyPath = '10.features.item/99.currency/v1.coin.yml'
  const currency = { id: 'coin', name: '交換通貨', icon: 'GOLD_NUGGET' }
  const cost = { itemId: { ref: 'im:10a00002' }, category: 'material', amount: 2 }
  const offer = { id: 'offer', itemId: 'item:10a00003', category: 'material', slot: 0, requiredItems: [cost] }
  const content = { id: 'exchange', name: '交換所', items: [offer] }
  vi.mocked(masterDataApi.catalog).mockResolvedValue({ root: '/filebase', backups: '/backups', referencePrefixes: { im: 'item' }, categories: [
    { id: category, directory: category, label: 'ショップ', fields: [], documents: [], jsonSchemas: [], templates: [], fileCount: 1 },
  ] })
  vi.mocked(masterDataApi.files).mockResolvedValue([summary(path, content), summary(peltPath, pelt), summary(sprigPath, sprig), summary(currencyPath, currency)])
  vi.mocked(masterDataApi.candidates).mockResolvedValue([
    { path: peltPath, label: pelt.name, value: pelt.id, kind: 'item', pointer: '/id' },
    { path: currencyPath, label: currency.name, value: currency.id, kind: 'item', pointer: '/id' },
  ])
  vi.mocked(masterDataApi.file).mockResolvedValue({ path, format: 'yaml', raw: JSON.stringify(content), revision: 'rev', content, issues: [] })
  render(<MasterDataEditor />)
  fireEvent.click(await screen.findByRole('button', { name: /交換所.*exchange/ }))
  fireEvent.click(await screen.findByRole('button', { name: '/items/0 を開く' }))
  fireEvent.click(screen.getByRole('button', { name: '/items/0/requiredItems/0 を開く' }))
  fireEvent.click(screen.getByLabelText('ref（/items/0/requiredItems/0/itemId/ref）の候補を選択'))
  fireEvent.click(screen.getByRole('button', { name: /交換通貨.*coin/ }))
  expect(screen.getByRole('textbox', { name: '/items/0/requiredItems/0/category' })).toHaveValue('currency')
  fireEvent.click(screen.getByRole('button', { name: '原稿 YAML' }))
  await waitFor(() => expect(masterDataApi.render).toHaveBeenCalledWith(path, {
    ...content, items: [{ ...offer, requiredItems: [{ ...cost, itemId: { ref: 'im:coin' }, category: 'currency' }] }],
  }, expect.any(String)))
})

it('keeps keyboard focus on the moved drop across list pages', async () => {
  const content = { ...mob, drops: { items: Array.from({ length: 21 }, (_, index) => ({ itemId: 'item:10a00002', rate: index + 1 })) } }
  vi.mocked(masterDataApi.file).mockResolvedValue({ path: mobPath, format: 'yaml', raw: JSON.stringify(content), revision: 'rev', content, issues: [] })
  render(<MasterDataEditor />)
  fireEvent.click(await screen.findByRole('button', { name: /野イノシシ.*boar/ }))
  fireEvent.click(await screen.findByRole('button', { name: '/drops を開く' }))
  const from = screen.getByRole('button', { name: '/drops/items/19 を並べ替え' })
  from.focus()
  fireEvent.keyDown(from, { key: 'ArrowDown' })
  const to = screen.getByRole('button', { name: '/drops/items/20 を並べ替え' })
  await waitFor(() => expect(to).toHaveFocus())
  expect(screen.getByRole('textbox', { name: '/drops/items/20/rate' })).toHaveValue('20')
  fireEvent.keyDown(to, { key: 'ArrowUp' })
  await waitFor(() => expect(screen.getByRole('button', { name: '/drops/items/19 を並べ替え' })).toHaveFocus())
  expect(screen.getByRole('textbox', { name: '/drops/items/19/rate' })).toHaveValue('20')
})

it('does not change another row when undo changes the open picker target', async () => {
  render(<MasterDataEditor />)
  fireEvent.click(await screen.findByRole('button', { name: /野イノシシ.*boar/ }))
  fireEvent.click(await screen.findByRole('button', { name: '/drops を開く' }))
  openRowActions('/drops/items/1')
  fireEvent.click(screen.getByRole('button', { name: '/drops/items/1 上へ' }))
  openRowActions('/drops/items/0')
  fireEvent.click(screen.getByRole('button', { name: '/drops/items/0 のアイテムを変更' }))
  expect(screen.getByRole('searchbox', { name: 'アイテムを検索' })).toBeInTheDocument()
  fireEvent.click(screen.getByRole('button', { name: '元に戻す Ctrl+Z' }))
  expect(screen.queryByRole('searchbox', { name: 'アイテムを検索' })).not.toBeInTheDocument()
  expect(screen.getByText('行の内容が変わりました。変更する行を選び直してください。')).toBeVisible()
  fireEvent.click(screen.getByRole('button', { name: '閉じる' }))
  const [first, second] = within(screen.getByRole('region', { name: /ドロップアイテム/ })).getAllByRole('listitem')
  expect(within(first).getByRole('button', { name: '毛皮 の定義を開く' })).toBeInTheDocument()
  expect(within(second).getByText('item:missing')).toBeInTheDocument()
})

it('does not offer an old ID while only the updated file summaries have arrived', async () => {
  vi.mocked(masterDataApi.files).mockResolvedValue([...originalDocuments].map(([path, content]) => summary(path,
    path === peltPath ? { ...content, id: '10a00004' } : content)))
  render(<MasterDataEditor />)
  fireEvent.click(await screen.findByRole('button', { name: /野イノシシ.*boar/ }))
  fireEvent.click(await screen.findByRole('button', { name: '/drops を開く' }))
  expect(screen.queryByRole('button', { name: '毛皮 の定義を開く' })).not.toBeInTheDocument()
  fireEvent.click(screen.getByRole('button', { name: '＋ アイテムを追加' }))
  fireEvent.change(screen.getByRole('searchbox', { name: 'アイテムを検索' }), { target: { value: '10a00002' } })
  expect(screen.getByText(/一致する候補がありません/)).toBeVisible()
})

it('chooses a wrapped recipe result and reorders integer ingredients as one draft change', async () => {
  const category = '85.shared.recipe'
  const path = category + '/v1.tonic.yml'
  const recipe: JsonObject = { id: 'tonic', name: '調合レシピ', result: { itemId: { ref: 'im:10a00002' }, amount: 1 }, ingredients: [
    { itemId: { ref: 'item:10a00002' }, amount: 1, note: 'keep' },
    { itemId: { ref: 'item:10a00003' }, amount: 2 },
  ] }
  vi.mocked(masterDataApi.catalog).mockResolvedValue({ root: '/filebase', backups: '/backups', referencePrefixes: { im: 'item' }, categories: [
    { id: category, directory: category, label: 'レシピ', fields: [], documents: [], jsonSchemas: [], templates: [], fileCount: 1 },
  ] })
  vi.mocked(masterDataApi.files).mockResolvedValue([summary(path, recipe), summary(peltPath, pelt), summary(sprigPath, sprig)])
  vi.mocked(masterDataApi.file).mockResolvedValue({ path, format: 'yaml', raw: JSON.stringify(recipe), revision: 'rev', content: recipe, issues: [] })
  render(<MasterDataEditor />)
  fireEvent.click(await screen.findByRole('button', { name: /調合レシピ.*tonic/ }))
  fireEvent.click(await screen.findByRole('button', { name: '/result を開く' }))
  expect(screen.getByLabelText('/result/itemId/ref').closest('details')).not.toHaveAttribute('open')
  expect(screen.getByRole('button', { name: '/result/itemId/ref の定義を開く' })).toHaveTextContent('毛皮')
  fireEvent.click(screen.getByLabelText('ref（/result/itemId/ref）の候補を選択'))
  fireEvent.change(screen.getByRole('searchbox', { name: '/result/itemId/ref 候補を検索' }), { target: { value: '風葉' } })
  fireEvent.click(screen.getByRole('button', { name: /風葉.*10a00003/ }))

  const navigation = within(screen.getByRole('navigation', { name: '編集セクション' }))
  fireEvent.click(navigation.getByRole('button', { name: /素材.*2 件/ }))
  const rows = within(screen.getByRole('region', { name: /素材/ })).getAllByRole('listitem')
  const dragHandle = within(rows[1]).getByRole('button', { name: '/ingredients/1 を並べ替え' })
  const dataTransfer = { setData: vi.fn(), effectAllowed: '', dropEffect: '' }
  fireEvent.dragStart(dragHandle, { dataTransfer })
  fireEvent.dragOver(rows[0], { dataTransfer })
  fireEvent.drop(rows[0], { dataTransfer })
  expect(screen.getByRole('textbox', { name: '/ingredients/0/amount' })).toHaveValue('2')
  fireEvent.click(screen.getByRole('button', { name: '元に戻す Ctrl+Z' }))
  expect(screen.getByRole('textbox', { name: '/ingredients/0/amount' })).toHaveValue('1')
  fireEvent.keyDown(screen.getByRole('button', { name: '/ingredients/0 を並べ替え' }), { key: 'ArrowDown' })
  fireEvent.change(screen.getByRole('textbox', { name: '/ingredients/0/amount' }), { target: { value: '3' } })
  fireEvent.click(screen.getByRole('button', { name: '原稿 YAML' }))
  await waitFor(() => expect(masterDataApi.render).toHaveBeenCalledWith(path, {
    ...recipe,
    result: { itemId: { ref: 'im:10a00003' }, amount: 1 },
    ingredients: [
      { itemId: { ref: 'item:10a00003' }, amount: 3 },
      { itemId: { ref: 'item:10a00002' }, amount: 1, note: 'keep' },
    ],
  }, expect.any(String)))
  expect(masterDataApi.save).not.toHaveBeenCalled()
})

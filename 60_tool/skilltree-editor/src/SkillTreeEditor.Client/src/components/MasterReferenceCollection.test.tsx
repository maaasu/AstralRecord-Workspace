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
    { itemId: { ref: 'im:10a00002' }, rate: 35, amount: 1, hidden: true },
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
  fireEvent.click(screen.getByRole('button', { name: '/drops/items/1 を削除' }))
  expect(screen.queryByText('参照先が見つかりません')).not.toBeInTheDocument()
  fireEvent.click(screen.getByRole('button', { name: '元に戻す Ctrl+Z' }))
  expect(screen.getByText('参照先が見つかりません')).toBeInTheDocument()
  fireEvent.click(screen.getByRole('button', { name: '/drops/items/1 を削除' }))

  fireEvent.click(within(screen.getByRole('tabpanel')).getByRole('button', { name: '原稿 YAML' }))
  await waitFor(() => expect(masterDataApi.render).toHaveBeenCalledWith(mobPath, {
    ...mob, drops: {
      exp: 8, lootTable: { ref: 'loot_table:forest' }, items: [
        { itemId: { ref: 'im:10a00002' }, rate: 45.5, amount: '1~3', hidden: true },
        { itemId: { ref: 'item:10a00003' }, rate: 0, amount: '1', luckAffected: true, hidden: false },
      ],
    },
  }, expect.any(String)))
  expect(masterDataApi.file).toHaveBeenCalledTimes(2)
  expect(masterDataApi.save).not.toHaveBeenCalled()
})

it('does not change another row when undo changes the open picker target', async () => {
  render(<MasterDataEditor />)
  fireEvent.click(await screen.findByRole('button', { name: /野イノシシ.*boar/ }))
  fireEvent.click(await screen.findByRole('button', { name: '/drops を開く' }))
  fireEvent.click(screen.getByRole('button', { name: '/drops/items/1 上へ' }))
  fireEvent.click(screen.getByRole('button', { name: '/drops/items/0 のアイテムを変更' }))
  expect(screen.getByRole('searchbox', { name: 'アイテムを検索' })).toBeInTheDocument()
  fireEvent.click(screen.getByRole('button', { name: '元に戻す Ctrl+Z' }))
  expect(screen.queryByRole('searchbox', { name: 'アイテムを検索' })).not.toBeInTheDocument()
  expect(screen.getByText('行の内容が変わりました。変更する行を選び直してください。')).toBeVisible()
  fireEvent.click(screen.getByRole('button', { name: '閉じる' }))
  const [first, second] = within(screen.getByRole('region', { name: /ドロップアイテム/ })).getAllByRole('listitem')
  expect(within(first).getByRole('button', { name: '毛皮 の定義を開く' })).toBeInTheDocument()
  expect(within(second).getByText('item:missing', { selector: 'code' })).toBeInTheDocument()
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

import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { masterDataApi } from '../api/masterDataApi'
import type { MasterCategory, MasterFileSummary, MasterFilterIndex } from '../types/masterData'
import { MasterDataEditor } from './MasterDataEditor'

const category = (directory: string, label: string): MasterCategory => ({ id: directory, directory, label, fileCount: 0, documents: [], fields: [], jsonSchemas: [], templates: [] })
const file = (path: string, id: string, name: string): MasterFileSummary => ({ path, id, name, category: path.slice(0, path.lastIndexOf('/')), format: 'yaml', revision: id, size: 1, modifiedUtc: '' })
const mob = category('40.features.mob', 'モブ')
const gathering = category('42.features.gathering', '採集')
const item = category('10.features.item', 'アイテム')
const skill = category('30.features.skill', 'スキル')
const mobA = file(`${mob.directory}/enemy/a.yml`, 'a', '敵A')
const mobB = file(`${mob.directory}/enemy/b.yml`, 'b', '敵B')
const npc = file(`${mob.directory}/npc/c.yml`, 'c', '商人C')
const herb = file(`${gathering.directory}/a.yml`, 'herb', '採集A')
const stone = file(`${gathering.directory}/b.yml`, 'stone', '採集B')
const sword = file(`${item.directory}/20.equipment/a.yml`, '20a00001', '&6銅の剣')
const ore = file(`${item.directory}/10.material/b.yml`, '10a00001', '&a銅鉱石')
const source = (entry: MasterFileSummary, itemIds: string[], kind: 'mob' | 'gathering' = 'mob') => ({ path: entry.path, id: entry.id!, name: entry.name!, kind, itemIds })
const index: MasterFilterIndex = {
  sources: [source(mobA, [sword.id!, ore.id!]), source(mobB, [ore.id!]), source(npc, []), source(herb, [ore.id!], 'gathering'), source(stone, [], 'gathering')],
  skillTags: [], tags: [{ id: 'physical', name: '物理' }],
  entries: [
    { path: mobA.path, facets: { category: ['ENEMY'], entityType: ['PIG'], tags: ['physical'] }, numbers: { mobLevel: [1, 20] } },
    { path: mobB.path, facets: { category: ['ENEMY'], entityType: ['SPIDER'] }, numbers: { mobLevel: [10] } },
    { path: npc.path, facets: { category: ['NPC'], entityType: ['VILLAGER'] }, numbers: { mobLevel: [1] } },
    { path: sword.path, facets: { rarity: ['RARE'], slot: ['WEAPON'], tags: ['physical'], unTradeable: ['true'] }, numbers: { requiredLevel: [15] } },
    { path: ore.path, facets: { rarity: ['COMMON'], unTradeable: ['false'] } },
    { path: herb.path, facets: { requiredToolTags: ['HOE'] } },
  ],
}

beforeEach(() => {
  vi.restoreAllMocks()
  vi.spyOn(masterDataApi, 'catalog').mockResolvedValue({ root: '/filebase', backups: '/backups', categories: [mob, gathering, item, skill] })
  vi.spyOn(masterDataApi, 'files').mockResolvedValue([mobA, mobB, npc, herb, stone, sword, ore])
  vi.spyOn(masterDataApi, 'filterIndex').mockResolvedValue(index)
  vi.spyOn(masterDataApi, 'candidates').mockResolvedValue([])
})

const results = () => within(screen.getByLabelText('マスターの検索結果'))
const resultButton = (name: RegExp) => results().queryByRole('button', { name })
async function selectCategory(entry: MasterCategory) {
  const number = entry.directory.match(/^\d+/)![0]
  fireEvent.keyDown(await screen.findByRole('treeitem', { name: new RegExp(`^${number} ${entry.label} \\(`) }), { key: 'Enter' })
}
function dialog() { return within(screen.getByRole('dialog', { name: '詳細フィルター' })) }
function openFilters() { fireEvent.click(screen.getByRole('button', { name: /^詳細フィルター/ })); return dialog() }
function select(label: string, value: string) { fireEvent.change(dialog().getByRole('combobox', { name: label }), { target: { value } }) }
function range(label: string, min: string, max: string) {
  fireEvent.change(dialog().getByRole('textbox', { name: `${label} 下限` }), { target: { value: min } })
  fireEvent.change(dialog().getByRole('textbox', { name: `${label} 上限` }), { target: { value: max } })
}
function applyFilters() { fireEvent.click(dialog().getByRole('button', { name: '条件を適用' })) }

describe('master list filters', () => {
  it('keeps search and folder navigation outside the editor tab panels', async () => {
    render(<MasterDataEditor />); await screen.findByRole('button', { name: /銅の剣/ })
    expect(screen.queryByRole('combobox', { name: 'マスターのカテゴリ' })).not.toBeInTheDocument()
    expect(screen.getByRole('tree', { name: 'マスターカテゴリ' })).toBeInTheDocument()
    expect(screen.getByRole('textbox', { name: 'マスター検索' })).toBeInTheDocument()
    expect(screen.getByLabelText('マスターの検索結果').closest('[role="tabpanel"]')).toBeNull()
  })

  it('previews a draft without changing results until it is submitted', async () => {
    render(<MasterDataEditor />); await screen.findByRole('button', { name: /銅の剣/ })
    openFilters(); select('レアリティ', 'RARE')
    expect(resultButton(/銅鉱石/)).toBeInTheDocument()
    expect(dialog().getByText('1 件が一致')).toBeInTheDocument()
    applyFilters()
    expect(screen.queryByRole('dialog', { name: '詳細フィルター' })).not.toBeInTheDocument()
    expect(resultButton(/銅の剣/)).toBeInTheDocument(); expect(resultButton(/銅鉱石/)).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: /レアリティ: .*RARE.*を解除/ })).toBeInTheDocument()
  })

  it('keeps the name query when clearing detailed filters and clears it separately', async () => {
    render(<MasterDataEditor />); await screen.findByRole('button', { name: /銅の剣/ })
    openFilters(); select('レアリティ', 'RARE'); applyFilters()
    fireEvent.change(screen.getByRole('textbox', { name: 'マスター検索' }), { target: { value: '&6銅の剣' } })
    expect(resultButton(/銅の剣/)).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'すべて解除' }))
    expect(screen.getByRole('textbox', { name: 'マスター検索' })).toHaveValue('&6銅の剣')
    fireEvent.click(screen.getByRole('button', { name: 'ファイル検索をクリア' }))
    expect(resultButton(/銅鉱石/)).toBeInTheDocument()
  })

  it('discards canceled and Escape-closed drafts while keeping applied conditions', async () => {
    render(<MasterDataEditor />); await screen.findByRole('button', { name: /銅の剣/ })
    openFilters(); select('レアリティ', 'RARE'); applyFilters()
    openFilters(); select('レアリティ', 'COMMON')
    fireEvent.click(dialog().getByRole('button', { name: 'キャンセル' }))
    expect(resultButton(/銅の剣/)).toBeInTheDocument(); expect(resultButton(/銅鉱石/)).not.toBeInTheDocument()
    openFilters(); expect(dialog().getByRole('combobox', { name: 'レアリティ' })).toHaveValue('RARE')
    select('レアリティ', 'COMMON')
    fireEvent.keyDown(dialog().getByRole('combobox', { name: 'レアリティ' }), { key: 'Escape' })
    expect(screen.queryByRole('dialog', { name: '詳細フィルター' })).not.toBeInTheDocument()
    openFilters(); expect(dialog().getByRole('combobox', { name: 'レアリティ' })).toHaveValue('RARE')
  })

  it('finds mobs by a named drop item and combines it with entity type', async () => {
    render(<MasterDataEditor />); await selectCategory(mob); openFilters()
    fireEvent.change(dialog().getByRole('textbox', { name: 'ドロップ品を名前で検索' }), { target: { value: '銅鉱石' } })
    const items = dialog().getByRole('combobox', { name: 'ドロップするアイテム' })
    expect(within(items).getByRole('option', { name: '銅鉱石 (10a00001)' })).toBeInTheDocument()
    expect(within(items).queryByRole('option', { name: /銅の剣/ })).not.toBeInTheDocument()
    select('ドロップするアイテム', ore.id!); applyFilters()
    expect(resultButton(/敵A/)).toBeInTheDocument(); expect(resultButton(/敵B/)).toBeInTheDocument(); expect(resultButton(/商人C/)).not.toBeInTheDocument()
    openFilters(); select('エンティティ種別', 'PIG'); applyFilters()
    expect(resultButton(/敵A/)).toBeInTheDocument(); expect(resultButton(/敵B/)).not.toBeInTheDocument()
    expect(screen.getByLabelText('検索結果の件数')).toHaveTextContent('1 / 3 件')
    fireEvent.click(screen.getByRole('button', { name: 'すべて解除' }))
    expect(resultButton(/商人C/)).toBeInTheDocument()
    openFilters(); expect(dialog().getByRole('combobox', { name: 'ドロップするアイテム' })).toHaveValue('')
    expect(dialog().getByRole('combobox', { name: 'エンティティ種別' })).toHaveValue('')
  })

  it('filters gathering by drop item and drop presence and clears inapplicable folder conditions', async () => {
    render(<MasterDataEditor />); await selectCategory(gathering)
    openFilters(); select('ドロップするアイテム', ore.id!); applyFilters()
    expect(resultButton(/採集A/)).toBeInTheDocument(); expect(resultButton(/採集B/)).not.toBeInTheDocument()
    openFilters(); select('ドロップするアイテム', ''); select('アイテムドロップの有無', 'none'); applyFilters()
    expect(resultButton(/採集A/)).not.toBeInTheDocument(); expect(resultButton(/採集B/)).toBeInTheDocument()
    await selectCategory(item); expect(resultButton(/銅の剣/)).toBeInTheDocument()
    await selectCategory(gathering); openFilters()
    expect(dialog().getByRole('combobox', { name: 'アイテムドロップの有無' })).toHaveValue('')
  })

  it('combines rarity, slot and named tags and clears irrelevant folder conditions', async () => {
    render(<MasterDataEditor />); await screen.findByRole('button', { name: /銅の剣/ })
    openFilters(); select('レアリティ', 'RARE'); select('装備スロット', 'WEAPON'); select('タグ', 'physical')
    expect(dialog().getByRole('option', { name: '物理 (physical)' })).toBeInTheDocument(); applyFilters()
    expect(resultButton(/銅の剣/)).toBeInTheDocument(); expect(resultButton(/銅鉱石/)).not.toBeInTheDocument()
    await selectCategory(gathering); openFilters()
    expect(dialog().queryByRole('combobox', { name: 'レアリティ' })).not.toBeInTheDocument()
    expect(dialog().getByRole('combobox', { name: '必要な道具タグ' })).toBeInTheDocument()
    fireEvent.click(dialog().getByRole('button', { name: 'キャンセル' })); expect(resultButton(/採集A/)).toBeInTheDocument()
    await selectCategory(item); openFilters(); expect(dialog().getByRole('combobox', { name: 'レアリティ' })).toHaveValue('')
  })

  it('combines trade prohibition with required level and removes only the selected chip', async () => {
    const high = file(`${item.directory}/20.equipment/high.yml`, 'high', '上位の剣')
    const free = file(`${item.directory}/20.equipment/free.yml`, 'free', '交換できる剣')
    vi.mocked(masterDataApi.files).mockResolvedValue([sword, ore, high, free])
    vi.mocked(masterDataApi.filterIndex).mockResolvedValue({ ...index, entries: [...index.entries!,
      { path: high.path, facets: { unTradeable: ['true'] }, numbers: { requiredLevel: [30] } },
      { path: free.path, facets: { unTradeable: ['false'] }, numbers: { requiredLevel: [15] } },
    ] })
    render(<MasterDataEditor />); await screen.findByRole('button', { name: /銅の剣/ })
    openFilters(); select('トレード', 'true'); range('装備に必要なプレイヤーレベル', '10', '20'); applyFilters()
    expect(resultButton(/銅の剣/)).toBeInTheDocument(); expect(resultButton(/上位の剣/)).not.toBeInTheDocument()
    expect(resultButton(/交換できる剣/)).not.toBeInTheDocument(); expect(resultButton(/銅鉱石/)).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '装備に必要なプレイヤーレベル: 10〜20 を解除' }))
    expect(resultButton(/上位の剣/)).toBeInTheDocument(); expect(resultButton(/交換できる剣/)).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'トレード: トレード禁止 を解除' }))
    expect(resultButton(/交換できる剣/)).toBeInTheDocument(); expect(resultButton(/銅鉱石/)).toBeInTheDocument()
  })

  it('matches actual mob profile values rather than the minimum-to-maximum envelope', async () => {
    render(<MasterDataEditor />); await selectCategory(mob)
    openFilters(); range('モブの定義レベル', '5', '15'); applyFilters()
    expect(resultButton(/敵A/)).not.toBeInTheDocument(); expect(resultButton(/敵B/)).toBeInTheDocument(); expect(resultButton(/商人C/)).not.toBeInTheDocument()
    openFilters(); range('モブの定義レベル', '20', '20'); applyFilters()
    expect(resultButton(/敵A/)).toBeInTheDocument(); expect(resultButton(/敵B/)).not.toBeInTheDocument()
  })

  it.each([{ min: '20', max: '1' }, { min: 'NaN', max: '' }])('rejects invalid range draft %j without changing applied results', async ({ min, max }) => {
    render(<MasterDataEditor />); await selectCategory(mob)
    openFilters(); range('モブの定義レベル', min, max)
    expect(dialog().getByRole('alert')).toBeInTheDocument(); expect(dialog().getByRole('button', { name: '条件を適用' })).toBeDisabled()
    expect(dialog().getByText('範囲を修正してください')).toBeInTheDocument()
    expect(resultButton(/敵A/)).toBeInTheDocument(); expect(resultButton(/商人C/)).toBeInTheDocument()
    fireEvent.click(dialog().getByRole('button', { name: 'キャンセル' }))
    openFilters(); expect(dialog().getByRole('textbox', { name: 'モブの定義レベル 下限' })).toHaveValue('')
  })

  it('clears a removed drop item on refresh and disables dependent controls when the index fails', async () => {
    render(<MasterDataEditor />); await selectCategory(mob)
    openFilters(); select('ドロップするアイテム', sword.id!); applyFilters()
    vi.mocked(masterDataApi.filterIndex).mockResolvedValue({ ...index, sources: [source(mobA, [ore.id!]), source(mobB, [ore.id!]), source(npc, [])] })
    fireEvent.click(screen.getByRole('button', { name: '一覧と定義を再読込' }))
    await waitFor(() => expect(resultButton(/商人C/)).toBeInTheDocument())
    openFilters(); expect(dialog().getByRole('combobox', { name: 'ドロップするアイテム' })).toHaveValue('')
    select('ドロップするアイテム', ore.id!); select('エンティティ種別', 'PIG'); range('モブの定義レベル', '20', '20'); applyFilters()
    vi.mocked(masterDataApi.filterIndex).mockRejectedValue(new Error('unavailable'))
    fireEvent.click(screen.getByRole('button', { name: '一覧と定義を再読込' }))
    await waitFor(() => expect(resultButton(/商人C/)).toBeInTheDocument()); openFilters()
    expect(dialog().getByRole('combobox', { name: 'ドロップするアイテム' })).toBeDisabled()
    expect(dialog().queryByRole('combobox', { name: 'エンティティ種別' })).not.toBeInTheDocument()
    expect(dialog().queryByRole('textbox', { name: 'モブの定義レベル 下限' })).not.toBeInTheDocument()
  })

  it('filters formats, parse errors and read-only files without an attribute index', async () => {
    vi.mocked(masterDataApi.files).mockResolvedValue([{ ...sword, parseError: 'broken yaml' }, { ...ore, format: 'json', readOnly: true }])
    vi.mocked(masterDataApi.filterIndex).mockRejectedValue(new Error('unavailable'))
    render(<MasterDataEditor />); await screen.findByRole('button', { name: /銅の剣/ })
    openFilters(); select('ファイル形式', 'json'); applyFilters()
    expect(resultButton(/銅の剣/)).not.toBeInTheDocument(); expect(resultButton(/銅鉱石/)).toBeInTheDocument()
    openFilters(); select('ファイル形式', ''); select('ファイル状態', 'error'); applyFilters()
    expect(resultButton(/銅の剣/)).toBeInTheDocument(); expect(resultButton(/銅鉱石/)).not.toBeInTheDocument()
    openFilters(); select('ファイル状態', 'readOnly'); applyFilters()
    expect(resultButton(/銅鉱石/)).toBeInTheDocument(); expect(resultButton(/銅の剣/)).not.toBeInTheDocument()
  })

  it('shows only skill tags and combines them with passive configuration', async () => {
    const active = file(`${skill.directory}/active.yml`, 'active', '能動スキル')
    const passive = file(`${skill.directory}/passive.yml`, 'passive', '常時スキル')
    vi.mocked(masterDataApi.files).mockResolvedValue([active, passive, sword])
    vi.mocked(masterDataApi.filterIndex).mockResolvedValue({ ...index, tags: [...index.tags, { id: 'magic', name: '魔法' }],
      skillTags: [{ path: active.path, tags: ['magic'] }, { path: passive.path, tags: ['magic'] }],
      entries: [{ path: active.path, facets: { passiveConfigured: ['false'] } }, { path: passive.path, facets: { passiveConfigured: ['true'] } }],
    })
    render(<MasterDataEditor />); await selectCategory(skill); openFilters()
    expect(within(dialog().getByRole('combobox', { name: 'スキルタグ' })).queryByRole('option', { name: '物理 (physical)' })).not.toBeInTheDocument()
    select('スキルタグ', 'magic'); select('パッシブ設定', 'true'); applyFilters()
    expect(resultButton(/常時スキル/)).toBeInTheDocument(); expect(resultButton(/能動スキル/)).not.toBeInTheDocument()
    openFilters(); expect(within(dialog().getByRole('combobox', { name: 'パッシブ設定' })).getByRole('option', { name: 'あり' })).toBeInTheDocument()
  })

  it('finds items by their named drop source', async () => {
    render(<MasterDataEditor />); await screen.findByRole('button', { name: /銅の剣/ }); openFilters()
    fireEvent.change(dialog().getByRole('textbox', { name: 'ドロップ元を名前で検索' }), { target: { value: '敵B' } })
    expect(dialog().queryByRole('option', { name: /敵A/ })).not.toBeInTheDocument()
    select('ドロップ元', mobB.path); applyFilters()
    expect(resultButton(/銅鉱石/)).toBeInTheDocument(); expect(resultButton(/銅の剣/)).not.toBeInTheDocument()
  })

  it('searches across folders and keeps the query when switching scope', async () => {
    render(<MasterDataEditor />); await screen.findByRole('button', { name: /銅の剣/ })
    fireEvent.change(screen.getByRole('textbox', { name: 'マスター検索' }), { target: { value: '敵A' } })
    expect(resultButton(/敵A/)).not.toBeInTheDocument()
    fireEvent.change(screen.getByRole('combobox', { name: '検索範囲' }), { target: { value: 'all' } })
    expect(resultButton(/敵A/)).toBeInTheDocument()
    fireEvent.change(screen.getByRole('combobox', { name: '検索範囲' }), { target: { value: 'folder' } })
    expect(resultButton(/敵A/)).not.toBeInTheDocument(); expect(screen.getByRole('textbox', { name: 'マスター検索' })).toHaveValue('敵A')
  })

  it('returns an expanded list to fifty results after applying a new facet', async () => {
    const files = Array.from({ length: 60 }, (_, number) => file(`${item.directory}/10.material/${number}.yml`, `${number}`, `素材${number}`))
    vi.mocked(masterDataApi.files).mockResolvedValue(files)
    vi.mocked(masterDataApi.filterIndex).mockResolvedValue({ ...index, sources: [], entries: files.map((entry, number) => ({ path: entry.path, facets: { rarity: [number < 55 ? 'RARE' : 'COMMON'] } })) })
    render(<MasterDataEditor />); fireEvent.click(await screen.findByRole('button', { name: /さらに表示/ }))
    expect(resultButton(/素材54/)).toBeInTheDocument()
    openFilters(); select('レアリティ', 'RARE'); applyFilters()
    expect(resultButton(/素材54/)).not.toBeInTheDocument(); expect(screen.getByRole('button', { name: 'さらに表示 (50 / 55)' })).toBeInTheDocument()
  })
})

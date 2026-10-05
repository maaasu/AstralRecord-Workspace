import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { masterDataApi } from '../api/masterDataApi'
import type { MasterCategory, MasterFileSummary, MasterFilterIndex } from '../types/masterData'
import { MasterDataEditor } from './MasterDataEditor'

const category = (directory: string): MasterCategory => ({ id: directory, directory, label: directory, fileCount: 0, documents: [], fields: [], jsonSchemas: [], templates: [] })
const file = (path: string, id: string, name: string): MasterFileSummary => ({ path, id, name, category: path.slice(0, path.lastIndexOf('/')), format: 'yaml', revision: id, size: 1, modifiedUtc: '' })
const mob = category('40.features.mob')
const gathering = category('42.features.gathering')
const item = category('10.features.item')
const skill = category('30.features.skill')
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
    { path: mobA.path, facets: { category: ['ENEMY'], entityType: ['PIG'], tags: ['physical'] } },
    { path: mobB.path, facets: { category: ['ENEMY'], entityType: ['SPIDER'] } },
    { path: npc.path, facets: { category: ['NPC'], entityType: ['VILLAGER'] } },
    { path: sword.path, facets: { rarity: ['RARE'], slot: ['WEAPON'], tags: ['physical'] } },
    { path: ore.path, facets: { rarity: ['COMMON'] } },
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

async function selectCategory(entry: MasterCategory) {
  fireEvent.change(await screen.findByRole('combobox', { name: 'マスターのカテゴリ' }), { target: { value: entry.id } })
}
function select(label: string, value: string) { fireEvent.change(screen.getByRole('combobox', { name: label }), { target: { value } }) }
function advanced() { fireEvent.click(screen.getByText(/^詳細な絞り込み/)) }

describe('master list filters', () => {
  it('minimizes all filter controls while keeping active conditions and allows clearing them', async () => {
    render(<MasterDataEditor />)
    await screen.findByRole('button', { name: /銅の剣/ })
    advanced(); select('レアリティ', 'RARE')
    fireEvent.change(screen.getByRole('textbox', { name: 'マスター検索' }), { target: { value: '銅の剣' } })
    fireEvent.click(screen.getByRole('button', { name: 'フィルタを最小化' }))
    expect(screen.getByRole('button', { name: 'フィルタを展開' })).toHaveAttribute('aria-expanded', 'false')
    expect(screen.queryByRole('combobox', { name: 'マスターのカテゴリ' })).not.toBeInTheDocument()
    expect(screen.queryByRole('textbox', { name: 'マスター検索' })).not.toBeInTheDocument()
    expect(screen.queryByRole('combobox', { name: 'ドロップ元' })).not.toBeInTheDocument()
    expect(screen.getByText('フィルタ · 2 条件')).toBeInTheDocument()
    expect(screen.getByText(item.label, { selector: '.master-filter-heading small' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /銅の剣/ })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /銅鉱石/ })).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'フィルタを展開' }))
    expect(screen.getByRole('textbox', { name: 'マスター検索' })).toHaveValue('銅の剣')
    expect(screen.getByRole('combobox', { name: 'レアリティ' })).toHaveValue('RARE')
    fireEvent.click(screen.getByRole('button', { name: 'フィルタを最小化' }))
    fireEvent.click(screen.getByRole('button', { name: '絞り込みを解除' }))
    expect(screen.getByRole('button', { name: /銅鉱石/ })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'フィルタを展開' })).toBeInTheDocument()
    expect(screen.queryByText('フィルタ · 2 条件')).not.toBeInTheDocument()
  })

  it('finds mobs by a named drop item and combines that item with entity type', async () => {
    render(<MasterDataEditor />)
    await selectCategory(mob)
    fireEvent.change(screen.getByRole('textbox', { name: 'ドロップ品を名前で検索' }), { target: { value: '銅鉱石' } })
    const items = screen.getByRole('combobox', { name: 'ドロップするアイテム' })
    expect(within(items).getByRole('option', { name: '銅鉱石 (10a00001)' })).toBeInTheDocument()
    expect(within(items).queryByRole('option', { name: /銅の剣/ })).not.toBeInTheDocument()
    select('ドロップするアイテム', ore.id!)
    expect(screen.getByRole('button', { name: /敵A/ })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /敵B/ })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /商人C/ })).not.toBeInTheDocument()
    advanced(); select('エンティティ種別', 'PIG')
    expect(screen.getByRole('button', { name: /敵A/ })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /敵B/ })).not.toBeInTheDocument()
    expect(screen.getByText('1 件 / カテゴリ内 3 件')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '絞り込みを解除' }))
    expect(items).toHaveValue('')
    expect(screen.getByRole('combobox', { name: 'エンティティ種別' })).toHaveValue('')
    expect(screen.getByRole('button', { name: /商人C/ })).toBeInTheDocument()
  })

  it('filters gathering by a drop item and by the presence of item drops', async () => {
    render(<MasterDataEditor />); await selectCategory(gathering)
    select('ドロップするアイテム', ore.id!)
    expect(screen.getByRole('button', { name: /採集A/ })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /採集B/ })).not.toBeInTheDocument()
    select('ドロップするアイテム', ''); select('アイテムドロップの有無', 'none')
    expect(screen.queryByRole('button', { name: /採集A/ })).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: /採集B/ })).toBeInTheDocument()
    await selectCategory(mob)
    expect(screen.getByRole('button', { name: /商人C/ })).toBeInTheDocument()
    await selectCategory(item)
    expect(screen.getByRole('button', { name: /銅の剣/ })).toBeInTheDocument()
    await selectCategory(gathering)
    expect(screen.getByRole('combobox', { name: 'アイテムドロップの有無' })).toHaveValue('')
  })

  it('combines item rarity, slot and named tags and clears irrelevant category conditions', async () => {
    render(<MasterDataEditor />)
    await screen.findByRole('button', { name: /銅の剣/ })
    advanced(); select('レアリティ', 'RARE'); select('装備スロット', 'WEAPON'); select('タグ', 'physical')
    expect(screen.getByRole('button', { name: /銅の剣/ })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /銅鉱石/ })).not.toBeInTheDocument()
    expect(screen.getByRole('option', { name: '物理 (physical)' })).toBeInTheDocument()
    await selectCategory(gathering)
    expect(screen.queryByRole('combobox', { name: 'レアリティ' })).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: /採集A/ })).toBeInTheDocument()
    expect(screen.getByRole('combobox', { name: '必要な道具タグ' })).toBeInTheDocument()
    await selectCategory(item)
    expect(screen.getByRole('combobox', { name: 'レアリティ' })).toHaveValue('')
    expect(screen.getByRole('button', { name: /銅鉱石/ })).toBeInTheDocument()
  })

  it('clears a removed drop item on refresh and disables dependent filters if the index fails', async () => {
    render(<MasterDataEditor />); await selectCategory(mob)
    select('ドロップするアイテム', sword.id!)
    vi.mocked(masterDataApi.filterIndex).mockResolvedValue({ ...index, sources: [source(mobA, [ore.id!]), source(mobB, [ore.id!]), source(npc, [])] })
    fireEvent.click(screen.getByTitle('一覧と定義メタデータを再読込'))
    await waitFor(() => expect(screen.getByRole('combobox', { name: 'ドロップするアイテム' })).toHaveValue(''))
    select('ドロップするアイテム', ore.id!); advanced(); select('エンティティ種別', 'PIG')
    vi.mocked(masterDataApi.filterIndex).mockRejectedValue(new Error('unavailable'))
    fireEvent.click(screen.getByTitle('一覧と定義メタデータを再読込'))
    await waitFor(() => expect(screen.getByRole('combobox', { name: 'ドロップするアイテム' })).toBeDisabled())
    expect(screen.getByRole('button', { name: /商人C/ })).toBeInTheDocument()
    expect(screen.queryByRole('combobox', { name: 'エンティティ種別' })).not.toBeInTheDocument()
  })

  it('finds parse errors and read-only files without requiring an attribute index', async () => {
    vi.mocked(masterDataApi.files).mockResolvedValue([{ ...sword, parseError: 'broken yaml' }, { ...ore, readOnly: true }])
    vi.mocked(masterDataApi.filterIndex).mockRejectedValue(new Error('unavailable'))
    render(<MasterDataEditor />)
    await screen.findByRole('button', { name: /銅の剣/ }); advanced()
    select('ファイル状態', 'error')
    expect(screen.getByRole('button', { name: /銅の剣/ })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /銅鉱石/ })).not.toBeInTheDocument()
    select('ファイル状態', 'readOnly')
    expect(screen.getByRole('button', { name: /銅鉱石/ })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /銅の剣/ })).not.toBeInTheDocument()
  })

  it('shows only tags used by skills and combines them with passive configuration', async () => {
    const active = file(`${skill.directory}/active.yml`, 'active', '能動スキル')
    const passive = file(`${skill.directory}/passive.yml`, 'passive', '常時スキル')
    vi.mocked(masterDataApi.files).mockResolvedValue([active, passive, sword])
    vi.mocked(masterDataApi.filterIndex).mockResolvedValue({ ...index,
      tags: [...index.tags, { id: 'magic', name: '魔法' }],
      skillTags: [{ path: active.path, tags: ['magic'] }, { path: passive.path, tags: ['magic'] }],
      entries: [{ path: active.path, facets: { passiveConfigured: ['false'] } }, { path: passive.path, facets: { passiveConfigured: ['true'] } }],
    })
    render(<MasterDataEditor />); await selectCategory(skill)
    const tags = screen.getByRole('combobox', { name: 'スキルタグ' })
    expect(within(tags).queryByRole('option', { name: '物理 (physical)' })).not.toBeInTheDocument()
    select('スキルタグ', 'magic'); advanced(); select('パッシブ設定', 'true')
    expect(screen.getByRole('button', { name: /常時スキル/ })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /能動スキル/ })).not.toBeInTheDocument()
    expect(within(screen.getByRole('combobox', { name: 'パッシブ設定' })).getByRole('option', { name: 'あり' })).toBeInTheDocument()
  })

  it('returns a previously expanded list to fifty entries after applying a new facet', async () => {
    const files = Array.from({ length: 60 }, (_, number) => file(`${item.directory}/10.material/${number}.yml`, `${number}`, `素材${number}`))
    vi.mocked(masterDataApi.files).mockResolvedValue(files)
    vi.mocked(masterDataApi.filterIndex).mockResolvedValue({ ...index, sources: [], entries: files.map((entry, number) => ({ path: entry.path, facets: { rarity: [number < 55 ? 'RARE' : 'COMMON'] } })) })
    render(<MasterDataEditor />)
    fireEvent.click(await screen.findByRole('button', { name: /さらに表示/ }))
    expect(screen.getByRole('button', { name: /素材54/ })).toBeInTheDocument()
    advanced(); select('レアリティ', 'RARE')
    expect(screen.queryByRole('button', { name: /素材54/ })).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'さらに表示 (50 / 55)' })).toBeInTheDocument()
  })
})

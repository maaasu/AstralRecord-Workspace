import type { ComponentProps } from 'react'
import { fireEvent, render, screen, within } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { NodeMaster } from '../types/editor'
import { NodeSidebar } from './NodeSidebar'
import * as nodePresentation from '../data/nodeEffectPresentation'

afterEach(() => vi.restoreAllMocks())

const node: NodeMaster = {
  $schema: '../schemas/node.v1.schema.json',
  schemaVersion: 1,
  nodeId: '1000',
  name: '始まりのノード',
  icon: 'NETHER_STAR',
  tags: ['root'],
  pointType: 'PP',
  pointCost: 0,
  effects: [],
}

describe('NodeSidebar', () => {
  function pagingFixture(count = 242, placedCount = 121) {
    const nodes = Array.from({ length: count }, (_, index) => ({ ...node, nodeId: String(2000 + index), name: `ノード${index}` }))
    let props: ComponentProps<typeof NodeSidebar> = { nodes, placedIds: new Set(nodes.slice(0, placedCount).map(item => item.nodeId)),
      query: '', selectedTag: '', paginationContext: 'main', onQueryChange: vi.fn(), onTagChange: vi.fn(), onEdit: vi.fn(), onCreate: vi.fn(), onPlace: vi.fn(), skillMasters: [] }
    const view = render(<NodeSidebar {...props} />)
    return {
      nodes, initialProps: props,
      update: (patch: Partial<typeof props>) => { props = { ...props, ...patch }; view.rerender(<NodeSidebar {...props} />) },
    }
  }

  it('pages each group independently, focuses its first edit action and keeps off-page actions accurate', () => {
    const { nodes, initialProps, update } = pagingFixture()
    const unplaced = within(screen.getByRole('region', { name: '未配置 121' }))
    const placed = within(screen.getByRole('region', { name: '配置済み 121' }))
    const list = unplaced.getAllByRole('article')[0].parentElement!
    list.scrollTop = 600
    fireEvent.click(unplaced.getByRole('button', { name: '未配置ノードの次のページ' }))
    expect(unplaced.getByRole('status')).toHaveTextContent('51–100件 · 2 / 3ページ')
    expect(list.scrollTop).toBe(0)
    let first = unplaced.getAllByRole('article')[0]
    expect(first).toHaveTextContent('ノード171')
    expect(within(first).getByRole('button', { name: '編集' })).toHaveFocus()
    expect(placed.getByRole('status')).toHaveTextContent('1–50件 · 1 / 3ページ')
    const transfer = { setData: vi.fn(), effectAllowed: '' }
    fireEvent.dragStart(first, { dataTransfer: transfer })
    expect(transfer.setData).toHaveBeenCalledWith('application/x-astral-node', '2171')
    fireEvent.click(within(first).getByRole('button', { name: '編集' }))
    fireEvent.click(within(first).getByRole('button', { name: 'ノード171を座標指定で配置' }))
    expect(vi.mocked(initialProps.onEdit).mock.calls[0][0]).toBe(nodes[171])
    expect(vi.mocked(initialProps.onPlace!).mock.calls[0][0]).toBe(nodes[171])
    fireEvent.click(placed.getByRole('button', { name: '配置済みノードの次のページ' }))
    first = placed.getAllByRole('article')[0]
    expect(first).toHaveTextContent('ノード50')
    expect(first).toHaveAttribute('draggable', 'false')
    expect(within(first).queryByRole('button', { name: /座標指定/ })).not.toBeInTheDocument()
    expect(unplaced.getByRole('status')).toHaveTextContent('2 / 3ページ')
    fireEvent.click(unplaced.getByRole('button', { name: '未配置ノードの次のページ' }))
    expect(unplaced.getAllByRole('article')).toHaveLength(21)
    expect(unplaced.getByRole('button', { name: '未配置ノードの次のページ' })).toBeDisabled()
    expect(unplaced.getAllByRole('article').at(-1)).toHaveTextContent('ノード241')
    update({ placementDisabled: true })
    expect(unplaced.getByRole('button', { name: 'ノード221を座標指定で配置' })).toBeDisabled()
    fireEvent.click(unplaced.getByRole('button', { name: '未配置ノードの前のページ' }))
    expect(unplaced.getAllByRole('article')).toHaveLength(50)
    expect(unplaced.getAllByRole('article')[0]).toHaveTextContent('ノード171')
    expect(initialProps.onEdit).toHaveBeenCalledTimes(1)
    expect(initialProps.onPlace).toHaveBeenCalledTimes(1)
  })

  it('resets both pages for filters or view context without taking focus, and searches every page', () => {
    const { update, initialProps } = pagingFixture()
    const advance = () => {
      fireEvent.click(screen.getByRole('button', { name: '未配置ノードの次のページ' }))
      fireEvent.click(screen.getByRole('button', { name: '配置済みノードの次のページ' }))
    }
    const search = screen.getByRole('searchbox', { name: 'ノードを検索' })
    const tag = screen.getByRole('combobox', { name: 'ノードのタグ' })
    for (const [patch, focus] of [[{ query: 'ノード' }, search], [{ selectedTag: 'root' }, tag], [{ paginationContext: 'other' }, search]] as const) {
      advance()
      focus.focus()
      update(patch)
      expect(focus).toHaveFocus()
      expect(screen.getAllByRole('status').map(item => item.textContent)).toEqual(['1–50件 · 1 / 3ページ', '1–50件 · 1 / 3ページ'])
    }
    search.focus()
    update({ query: '2241' })
    expect(screen.getByRole('article')).toHaveTextContent('ノード241')
    expect(screen.queryByRole('group', { name: /ノードのページ/ })).not.toBeInTheDocument()
    expect(search).toHaveFocus()
    update({ query: '一致なし' })
    expect(screen.queryByRole('article')).not.toBeInTheDocument()
    update({ query: '' })
    expect(screen.getAllByRole('article')).toHaveLength(100)
    expect(initialProps.onEdit).not.toHaveBeenCalled()
    expect(initialProps.onPlace).not.toHaveBeenCalled()
  })

  it('clamps pages after placement or removal and does not restore an obsolete page on undo', () => {
    const { nodes, update } = pagingFixture(103, 0)
    fireEvent.click(screen.getByRole('button', { name: '未配置ノードの次のページ' }))
    fireEvent.click(screen.getByRole('button', { name: '未配置ノードの次のページ' }))
    expect(screen.getAllByRole('article')).toHaveLength(3)
    const search = screen.getByRole('searchbox', { name: 'ノードを検索' })
    search.focus()
    update({ placedIds: new Set(nodes.slice(100).map(item => item.nodeId)) })
    expect(screen.getByRole('status')).toHaveTextContent('51–100件 · 2 / 2ページ')
    expect(search).toHaveFocus()
    update({ placedIds: new Set() })
    expect(screen.getByRole('status')).toHaveTextContent('51–100件 · 2 / 3ページ')
    update({ nodes: nodes.slice(0, 50) })
    expect(screen.getAllByRole('article')).toHaveLength(50)
    expect(screen.queryByRole('group', { name: /ノードのページ/ })).not.toBeInTheDocument()
    update({ nodes: nodes.slice(0, 51) })
    expect(screen.getByRole('status')).toHaveTextContent('1–50件 · 1 / 2ページ')
    expect(screen.getByRole('button', { name: '未配置ノードの前のページ' })).toBeDisabled()
    update({ placedIds: new Set(nodes.map(item => item.nodeId)) })
    expect(screen.getByRole('region', { name: '未配置 0' })).toHaveClass('empty')
    expect(screen.getByRole('status')).toHaveTextContent('1–50件 · 1 / 2ページ')
    expect(search).toHaveFocus()
  })

  it('bounds rendered cards to 100 while retaining totals for 1158 matching nodes', () => {
    const nodes = Array.from({ length: 1158 }, (_, index) => ({ ...node, nodeId: String(2000 + index) }))
    render(<NodeSidebar nodes={nodes} placedIds={new Set(nodes.slice(0, 100).map(item => item.nodeId))} query="" selectedTag=""
      onQueryChange={vi.fn()} onTagChange={vi.fn()} onEdit={vi.fn()} onCreate={vi.fn()} skillMasters={[]} />)
    expect(screen.getByRole('heading', { name: '未配置 1058' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: '配置済み 100' })).toBeInTheDocument()
    expect(screen.getAllByRole('article')).toHaveLength(100)
  })

  it('combines words across fields with tag filtering while preserving grouping, order and actions', () => {
    const flame: NodeMaster = { ...node, name: '&6炎の&a道', pointType: 'CP', pointCost: 3, unlockCondition: { classId: 'adventurer' },
      effects: [{ type: 'skill', skillId: 'flame' }, { type: 'status', status: 'MAX_HEALTH', modifierType: 'SCALAR', value: 0.1 }] }
    const ice: NodeMaster = { ...flame, nodeId: '1001', name: '氷の道', effects: [{ type: 'skill', skillId: 'ice' }] }
    const support: NodeMaster = { ...flame, nodeId: '1002', name: '炎の道・補助', tags: ['custom_support'], pointType: 'PP' }
    const placed: NodeMaster = { ...flame, nodeId: '1003', name: '炎の道・派生' }
    let props: ComponentProps<typeof NodeSidebar> = { nodes: [flame, ice, support, placed], placedIds: new Set(['1003']), query: '', selectedTag: '',
      skillMasters: [{ id: 'flame', name: '&c火球', type: 'ACTIVE' }, { id: 'ice', name: '氷球', type: 'ACTIVE' }], classMasters: [{ id: 'adventurer', name: '&b冒険者', parentClassIds: [] }],
      onQueryChange: vi.fn(), onTagChange: vi.fn(), onEdit: vi.fn(), onCreate: vi.fn(), onPlace: vi.fn() }
    const view = render(<NodeSidebar {...props} />)
    const update = (patch: Partial<typeof props>) => { props = { ...props, ...patch }; view.rerender(<NodeSidebar {...props} />) }
    const names = () => screen.queryAllByRole('article').map(article => article.querySelector('strong')?.textContent)
    const search = screen.getByRole('searchbox', { name: 'ノードを検索' })
    expect(search).toHaveAccessibleDescription(expect.stringContaining('すべての語'))
    search.focus()
    fireEvent.change(search, { target: { value: '炎の道\t1000　CP 火球 冒険者 ROOT 最大HP' } })
    expect(props.onQueryChange).toHaveBeenCalledWith('炎の道\t1000　CP 火球 冒険者 ROOT 最大HP')
    update({ query: '炎の道\t1000　CP 火球 冒険者 ROOT 最大HP' })
    expect(names()).toEqual(['炎の道'])
    expect(search).toHaveFocus()
    update({ query: '&e冒険者 ROOT　&c火球 cp cp 炎の道 1000 MAX_HEALTH' })
    expect(names()).toEqual(['炎の道'])
    update({ query: 'スキルツリーの開始点 火球' })
    expect(names()).toEqual(['炎の道', '炎の道・派生'])
    expect(screen.getByRole('heading', { name: '未配置 1' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: '配置済み 1' })).toBeInTheDocument()
    update({ query: '火球' })
    expect(names()).toEqual(['炎の道', '炎の道・補助', '炎の道・派生'])
    fireEvent.change(screen.getByRole('combobox', { name: 'ノードのタグ' }), { target: { value: 'root' } })
    expect(props.onTagChange).toHaveBeenCalledWith('root')
    update({ selectedTag: 'root' })
    expect(names()).toEqual(['炎の道', '炎の道・派生'])
    update({ selectedTag: 'custom_support' })
    expect(names()).toEqual(['炎の道・補助'])
    update({ query: '火球 不在の語', selectedTag: '' })
    expect(names()).toEqual([])
    update({ query: ' \t　' })
    expect(names()).toEqual(['炎の道', '氷の道', '炎の道・補助', '炎の道・派生'])
    expect(props.onEdit).not.toHaveBeenCalled(); expect(props.onPlace).not.toHaveBeenCalled()
    update({ query: '火球 CP' })
    const articles = screen.getAllByRole('article')
    fireEvent.click(within(articles[0]).getByRole('button', { name: '炎の道を座標指定で配置' }))
    expect(vi.mocked(props.onPlace!).mock.calls[0][0]).toBe(flame)
    fireEvent.click(within(articles[1]).getByRole('button', { name: '編集' }))
    expect(vi.mocked(props.onEdit).mock.calls[0][0]).toBe(placed)
    expect(articles[1]).toHaveAttribute('draggable', 'false')
    const dataTransfer = { setData: vi.fn(), effectAllowed: '' }
    fireEvent.dragStart(articles[0], { dataTransfer })
    expect(dataTransfer.setData).toHaveBeenCalledWith('application/x-astral-node', '1000')
    expect(dataTransfer.effectAllowed).toBe('copy')
    update({ placementDisabled: true })
    expect(screen.getByRole('button', { name: '炎の道を座標指定で配置' })).toBeDisabled()
  })

  it('rebuilds search and display metadata when nodes, skills or classes change', () => {
    let props: ComponentProps<typeof NodeSidebar> = { nodes: [{ ...node, pointType: 'CP', unlockCondition: { classId: 'adventurer' }, effects: [{ type: 'skill', skillId: 'flame' }] }],
      skillMasters: [{ id: 'flame', name: '火球', type: 'ACTIVE' }], classMasters: [{ id: 'adventurer', name: '冒険者', parentClassIds: [] }],
      placedIds: new Set(), query: '火球', selectedTag: '', onQueryChange: vi.fn(), onTagChange: vi.fn(), onEdit: vi.fn(), onCreate: vi.fn() }
    const view = render(<NodeSidebar {...props} />)
    const update = (patch: Partial<typeof props>) => { props = { ...props, ...patch }; view.rerender(<NodeSidebar {...props} />) }
    expect(screen.getByRole('article')).toHaveAttribute('title', expect.stringContaining('火球'))
    update({ skillMasters: [{ id: 'flame', name: '大炎', type: 'ACTIVE' }] })
    expect(screen.queryByRole('article')).not.toBeInTheDocument()
    update({ query: '大炎 冒険者' })
    expect(screen.getByRole('article')).toHaveAttribute('title', expect.stringContaining('大炎'))
    update({ classMasters: [{ id: 'adventurer', name: '魔導士', parentClassIds: [] }] })
    expect(screen.queryByRole('article')).not.toBeInTheDocument()
    update({ query: '大炎 魔導士' })
    expect(screen.getByText('消費 CP[魔導士] 0')).toBeInTheDocument()
    update({ nodes: [{ ...props.nodes[0], name: '&a変更新名', icon: 'DIAMOND', tags: ['custom_edit'], pointCost: 9, effects: [{ type: 'skill', skillId: 'ice' }] }] })
    expect(screen.queryByRole('article')).not.toBeInTheDocument()
    update({ query: '変更新名 custom_edit 9 未定義スキル ICE' })
    expect(screen.getByRole('article')).toHaveAttribute('title', expect.stringContaining('消費: CP[魔導士] 9'))
    expect(screen.getByTitle('DIAMOND').querySelector('img')).toHaveAttribute('src', '/api/minecraft-icons/DIAMOND?revision=0')
    expect(screen.queryByRole('option', { name: 'ルート／根（root）' })).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '編集' }))
    expect(vi.mocked(props.onEdit).mock.calls[0][0]).toBe(props.nodes[0])
  })

  it('keeps omitted class catalogs stable and updates icon revisions without rebuilding metadata', () => {
    const effects = vi.spyOn(nodePresentation, 'describeNodeEffects')
    const props = { nodes: [node], skillMasters: [], placedIds: new Set<string>(), selectedTag: '', onQueryChange: vi.fn(), onTagChange: vi.fn(), onEdit: vi.fn(), onCreate: vi.fn() }
    const view = render(<NodeSidebar {...props} query="" />)
    expect(effects).toHaveBeenCalledTimes(1)
    view.rerender(<NodeSidebar {...props} query="1000 root" iconRevision={3} />)
    expect(screen.getByRole('article')).toHaveTextContent('始まりのノード')
    expect(screen.getByTitle('NETHER_STAR').querySelector('img')).toHaveAttribute('src', '/api/minecraft-icons/NETHER_STAR?revision=3')
    expect(effects).toHaveBeenCalledTimes(1)
  })

  it('reuses class cost metadata across four searches and placement changes for 100 nodes', () => {
    const classMasters = [{ id: 'adventurer', name: '&b冒険者', parentClassIds: [] }]
    const classLookup = vi.spyOn(classMasters, 'find')
    const nodes = Array.from({ length: 100 }, (_, index) => ({ ...node, nodeId: String(2000 + index), name: `ノード${index}`, pointType: 'CP',
      unlockCondition: { classId: 'adventurer' }, effects: [{ type: 'skill', skillId: 'flame' }] }))
    const props = { nodes, classMasters, skillMasters: [{ id: 'flame', name: '火球', type: 'ACTIVE' }], placedIds: new Set<string>(), selectedTag: '',
      onQueryChange: vi.fn(), onTagChange: vi.fn(), onEdit: vi.fn(), onCreate: vi.fn() }
    const view = render(<NodeSidebar {...props} query="" />)
    expect(classLookup).toHaveBeenCalled()
    classLookup.mockClear()
    for (const query of ['ノード', 'CP', '火球', '冒険者']) {
      view.rerender(<NodeSidebar {...props} query={query} />)
      expect(screen.getByRole('heading', { name: '未配置 100' })).toBeInTheDocument()
    }
    expect(classLookup).toHaveBeenCalledTimes(0)
    view.rerender(<NodeSidebar {...props} query="冒険者" placedIds={new Set(nodes.slice(0, 50).map(item => item.nodeId))} />)
    expect(screen.getByRole('heading', { name: '未配置 50' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: '配置済み 50' })).toBeInTheDocument()
    expect(classLookup).toHaveBeenCalledTimes(0)
  })

  it('offers explicit placement only for unplaced nodes and collapses empty groups', () => {
    const place = vi.fn()
    const props = { nodes: [node], placedIds: new Set<string>(), query: '', selectedTag: '', onQueryChange: vi.fn(), onTagChange: vi.fn(), onEdit: vi.fn(), onCreate: vi.fn(), onPlace: place, skillMasters: [] }
    const { rerender } = render(<NodeSidebar {...props} />)
    fireEvent.click(screen.getByRole('button', { name: '始まりのノードを座標指定で配置' }))
    expect(place).toHaveBeenCalledWith(node)
    expect(screen.getByText('始まりのノード').closest('article')).toHaveAttribute('draggable', 'true')
    expect(screen.getByText('配置済み 0').closest('section')).toHaveClass('empty')
    rerender(<NodeSidebar {...props} placedIds={new Set(['1000'])} />)
    expect(screen.queryByRole('button', { name: '始まりのノードを座標指定で配置' })).not.toBeInTheDocument()
    expect(screen.getByText('未配置 0').closest('section')).toHaveClass('empty')
    expect(screen.getByText('配置済み 1').closest('section')).not.toHaveClass('empty')
  })
  it('shows point consumption as prominently as node effects', () => {
    render(<NodeSidebar
      nodes={[{ ...node, pointType: 'CP', pointCost: 3 }]}
      placedIds={new Set<string>()}
      query=""
      selectedTag=""
      onQueryChange={vi.fn()}
      onTagChange={vi.fn()}
      onEdit={vi.fn()}
      onCreate={vi.fn()}
      skillMasters={[]}
    />)

    const cost = screen.getByText('消費 CP 3')
    expect(cost).toHaveClass('cost-chip', 'cp')
    expect(cost.closest('article')).toHaveAttribute('title', expect.stringContaining('消費: CP 3'))
  })

  it('shows the unlock class in class point consumption', () => {
    render(<NodeSidebar
      nodes={[{
        ...node,
        pointType: 'CP',
        pointCost: 1,
        unlockCondition: { classId: 'adventurer' },
      }]}
      placedIds={new Set<string>()}
      query=""
      selectedTag=""
      onQueryChange={vi.fn()}
      onTagChange={vi.fn()}
      onEdit={vi.fn()}
      onCreate={vi.fn()}
      skillMasters={[]}
      classMasters={[{ id: 'adventurer', name: '&6冒険者', parentClassIds: [] }]}
    />)

    const cost = screen.getByText('消費 CP[冒険者] 1')
    expect(cost.closest('article')).toHaveAttribute('title', expect.stringContaining('消費: CP[冒険者] 1'))
  })

  it('shows and searches shared tags by their Japanese definitions', () => {
    const props = {
      nodes: [node],
      placedIds: new Set<string>(),
      selectedTag: '',
      onQueryChange: vi.fn(),
      onTagChange: vi.fn(),
      onEdit: vi.fn(),
      onCreate: vi.fn(),
      skillMasters: [],
    }
    const { rerender } = render(<NodeSidebar {...props} query="" />)

    expect(screen.getByText('ルート／根')).toHaveAttribute('title', expect.stringContaining('root'))
    expect(screen.getByRole('option', { name: 'ルート／根（root）' })).toHaveValue('root')

    rerender(<NodeSidebar {...props} query="スキルツリーの開始点" />)
    expect(screen.getByText('始まりのノード')).toBeInTheDocument()
  })
})

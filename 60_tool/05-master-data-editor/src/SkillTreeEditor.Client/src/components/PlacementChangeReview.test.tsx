import { fireEvent, render, screen, within } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import type { WorldPlacement } from '../api/worldPlacementApi'
import { PlacementChangeReview } from './PlacementChangeReview'
import type { PlacementChange } from '../state/worldPlacementEditing'

const point: WorldPlacement = { placementId: 'one', kind: 'spawner', worldId: 'eriva', worldName: 'エリヴァ', templateId: 'wolf', x: 3, y: 70, z: 3, yaw: 0, pitch: 0, enabled: true, waystone: null }
const catalog = { spawners: [{ id: 'wolf', name: '森林狼', radiusMeters: 32, levels: [10] }], npcs: [], worlds: [] }
describe('変更確認パネル', () => {
  it('閉じた間は詳細DOMを生成せず、ページを往復しても比較表は50件までに制限する', () => {
    const changes: PlacementChange[] = Array.from({ length: 125 }, (_, index) => ({ id: `id-${index}`, kind: index < 100 ? 'added' : 'deleted', ...(index < 100 ? { after: { ...point, placementId: `id-${index}` } } : { before: { ...point, placementId: `id-${index}` } }) }))
    render(<PlacementChangeReview changes={changes} catalog={catalog} locked={false} hasDrafts={false} onLocate={vi.fn()} onRevert={vi.fn()} />)
    expect(screen.queryByRole('table')).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: /変更内容を確認/ }))
    expect(screen.getAllByRole('table')).toHaveLength(50)
    const list = screen.getByRole('article', { name: '追加 id-0' }).parentElement!
    list.scrollTop = 400
    fireEvent.click(screen.getByRole('button', { name: '次の変更ページ' }))
    expect(screen.getAllByRole('table')).toHaveLength(50)
    expect(screen.queryByRole('article', { name: '追加 id-0' })).not.toBeInTheDocument()
    expect(screen.getByRole('article', { name: '追加 id-50' })).toBeInTheDocument()
    expect(list.scrollTop).toBe(0)
    fireEvent.click(screen.getByRole('button', { name: '次の変更ページ' }))
    expect(screen.getAllByRole('table')).toHaveLength(25)
    expect(screen.getByRole('status')).toHaveTextContent('101–125 / 125 件 · 3 / 3 ページ')
    expect(screen.getByRole('button', { name: '次の変更ページ' })).toBeDisabled()
    fireEvent.click(screen.getByRole('button', { name: '前の変更ページ' }))
    expect(screen.getAllByRole('table')).toHaveLength(50)
    fireEvent.change(screen.getByLabelText('変更の種類'), { target: { value: 'deleted' } })
    expect(screen.getAllByRole('table')).toHaveLength(25)
    expect(screen.getByRole('status')).toHaveTextContent('1–25 / 25 件 · 1 / 1 ページ · 全変更 125 件')
    expect(screen.queryByRole('button', { name: 'この配置へ移動' })).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: /変更内容を確認/ }))
    expect(screen.queryByRole('table')).not.toBeInTheDocument()
  })
  it('前後両方の装飾付き名・ID・ワールド・座標を複数語で検索し、種類条件を維持する', () => {
    const changes: PlacementChange[] = [
      { id: 'swap-wolf', kind: 'changed', before: { ...point, x: -900 }, after: { ...point, templateId: 'golem', worldId: 'east', x: 400 } },
      { id: 'gone-guide', kind: 'deleted', before: { ...point, kind: 'npc', templateId: 'guide' } },
      { id: 'renamed-stone', kind: 'changed', before: { ...point, kind: 'waystone', templateId: null, waystone: { id: 'stone-old', name: '古&aい道標', lockEnabled: false, unlockGold: 0, icon: 'STONE', createdAt: null, createdBy: '' } }, after: { ...point, kind: 'waystone', templateId: null, waystone: { id: 'stone-new', name: '新&#aabbccしい道標', lockEnabled: false, unlockGold: 0, icon: 'STONE', createdAt: null, createdBy: '' } } },
    ]
    const locate = vi.fn(), revert = vi.fn()
    const masters = { ...catalog, spawners: [{ ...catalog.spawners[0], name: '森&a林&#aabbcc狼' }, { id: 'golem', name: 'ゴーレム', radiusMeters: 64, levels: [30] }], npcs: [{ id: 'guide', name: '案&b内人' }] }
    render(<PlacementChangeReview changes={changes} catalog={masters} locked={false} hasDrafts={false} onLocate={locate} onRevert={revert} />)
    fireEvent.click(screen.getByRole('button', { name: /変更内容を確認/ }))
    const query = screen.getByLabelText('変更を検索')
    fireEvent.change(query, { target: { value: '森林狼　ゴーレム -900\t400 WOLF EAST' } })
    expect(screen.getAllByRole('article')).toHaveLength(1)
    expect(screen.getByRole('article', { name: '変更 swap-wolf' })).toBeInTheDocument()
    expect(screen.getByRole('status')).toHaveTextContent('全変更 3 件')
    fireEvent.change(query, { target: { value: '案内人 GUIDE エリヴァ 70' } })
    expect(screen.getByRole('article', { name: '削除 gone-guide' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'この配置へ移動' })).not.toBeInTheDocument()
    fireEvent.change(query, { target: { value: '古い道標 新しい道標 STONE-OLD stone-new' } })
    expect(screen.getByRole('article', { name: '変更 renamed-stone' })).toBeInTheDocument()
    fireEvent.change(screen.getByLabelText('変更の種類'), { target: { value: 'deleted' } })
    expect(screen.queryByRole('article')).not.toBeInTheDocument()
    expect(screen.getByText('該当する確定済み変更はありません。')).toBeInTheDocument()
    fireEvent.change(query, { target: { value: ' \t　' } })
    expect(screen.getAllByRole('article')).toHaveLength(1)
    expect(screen.getByRole('article', { name: '削除 gone-guide' })).toBeInTheDocument()
    expect(locate).not.toHaveBeenCalled()
    expect(revert).not.toHaveBeenCalled()
  })
  it('取消などで末尾ページが消えたら戻り、検索は先頭へ戻って最新の変更とカタログに一致する', () => {
    const changes: PlacementChange[] = Array.from({ length: 101 }, (_, index) => ({ id: `id-${index}`, kind: 'changed', before: point, after: { ...point, x: index } }))
    const props = { changes, catalog, locked: false, hasDrafts: false, onLocate: vi.fn(), onRevert: vi.fn() }
    const view = render(<PlacementChangeReview {...props} />)
    fireEvent.click(screen.getByRole('button', { name: /変更内容を確認/ }))
    fireEvent.click(screen.getByRole('button', { name: '次の変更ページ' }))
    fireEvent.click(screen.getByRole('button', { name: '次の変更ページ' }))
    fireEvent.click(screen.getByRole('button', { name: 'この配置の変更を取り消す' }))
    expect(props.onRevert).toHaveBeenCalledWith(changes[100])
    view.rerender(<PlacementChangeReview {...props} changes={changes.slice(0, 100)} />)
    expect(screen.getByRole('status')).toHaveTextContent('51–100 / 100 件 · 2 / 2 ページ')
    view.rerender(<PlacementChangeReview {...props} />)
    expect(screen.getByRole('status')).toHaveTextContent('51–100 / 101 件 · 2 / 3 ページ')
    fireEvent.change(screen.getByLabelText('変更を検索'), { target: { value: '100 森林狼' } })
    expect(screen.getByRole('status')).toHaveTextContent('1–1 / 1 件 · 1 / 1 ページ')
    expect(screen.getByRole('article', { name: '変更 id-100' })).toBeInTheDocument()
    view.rerender(<PlacementChangeReview {...props} catalog={{ ...catalog, spawners: [{ ...catalog.spawners[0], name: '灰色狼' }] }} />)
    expect(screen.queryByRole('article')).not.toBeInTheDocument()
    fireEvent.change(screen.getByLabelText('変更を検索'), { target: { value: '100 灰色狼' } })
    expect(screen.getByRole('article', { name: '変更 id-100' })).toBeInTheDocument()
    view.rerender(<PlacementChangeReview {...props} changes={[]} />)
    expect(screen.getByRole('status')).toHaveTextContent('0 / 0 件 · 1 / 1 ページ · 全変更 0 件')
    expect(screen.queryByRole('article')).not.toBeInTheDocument()
  })
  it('検索中だけ索引を作り、入力とページ移動では再加工しない', () => {
    const changes: PlacementChange[] = Array.from({ length: 250 }, (_, index) => ({ id: `__review_perf_${index}`, kind: 'changed', before: point, after: { ...point, x: index } }))
    const lower = vi.spyOn(String.prototype, 'toLocaleLowerCase')
    const count = () => lower.mock.contexts.filter(value => String(value).includes('__review_perf_')).length
    render(<PlacementChangeReview changes={changes} catalog={catalog} locked={false} hasDrafts={false} onLocate={vi.fn()} onRevert={vi.fn()} />)
    expect(count()).toBe(0)
    fireEvent.click(screen.getByRole('button', { name: /変更内容を確認/ }))
    expect(count()).toBe(0)
    for (let page = 1; page < 5; page++) {
      fireEvent.click(screen.getByRole('button', { name: '次の変更ページ' }))
      expect(screen.getAllByRole('table')).toHaveLength(50)
      expect(screen.getByRole('article', { name: `変更 __review_perf_${page * 50}` })).toBeInTheDocument()
    }
    fireEvent.change(screen.getByLabelText('変更を検索'), { target: { value: '森' } })
    expect(count()).toBe(250)
    lower.mockClear()
    fireEvent.change(screen.getByLabelText('変更を検索'), { target: { value: '森林狼' } })
    fireEvent.click(screen.getByRole('button', { name: '次の変更ページ' }))
    expect(count()).toBe(0)
    expect(screen.getAllByRole('table')).toHaveLength(50)
    lower.mockRestore()
  })
  it('前後値と日本語名を表示し、下書き中は個別取消を止め移動は許可する', () => {
    const after = { ...point, x: 200 }, locate = vi.fn(), revert = vi.fn()
    const change: PlacementChange = { id: 'one', kind: 'changed', before: point, after }
    const props = { changes: [change], catalog, locked: false, hasDrafts: true, onLocate: locate, onRevert: revert }
    const view = render(<PlacementChangeReview {...props} />)
    fireEvent.click(screen.getByRole('button', { name: /変更内容を確認/ }))
    expect(screen.getByRole('heading', { name: '変更 · 森林狼' })).toBeInTheDocument()
    const row = screen.getByRole('row', { name: 'X 3 200' })
    expect(within(row).getByText('200')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'この配置の変更を取り消す' })).toBeDisabled()
    fireEvent.click(screen.getByRole('button', { name: 'この配置へ移動' }))
    expect(locate).toHaveBeenCalledWith(after)
    view.rerender(<PlacementChangeReview {...props} hasDrafts={false} />)
    fireEvent.click(screen.getByRole('button', { name: 'この配置の変更を取り消す' }))
    expect(revert).toHaveBeenCalledWith(change)
    view.rerender(<PlacementChangeReview {...props} hasDrafts={false} locked />)
    expect(screen.getByRole('button', { name: 'この配置の変更を取り消す' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'この配置へ移動' })).toBeDisabled()
  })
})

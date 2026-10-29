import { fireEvent, render, screen, within } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import type { WorldPlacement } from '../api/worldPlacementApi'
import { PlacementChangeReview } from './PlacementChangeReview'
import type { PlacementChange } from '../state/worldPlacementEditing'

const point: WorldPlacement = { placementId: 'one', kind: 'spawner', worldId: 'eriva', worldName: 'エリヴァ', templateId: 'wolf', x: 3, y: 70, z: 3, yaw: 0, pitch: 0, enabled: true, waystone: null }
const catalog = { spawners: [{ id: 'wolf', name: '森林狼', radiusMeters: 32, levels: [10] }], npcs: [], worlds: [] }
describe('変更確認パネル', () => {
  it('閉じた間は詳細DOMを生成せず、50件ずつ表示し変更種別で絞り込む', () => {
    const changes: PlacementChange[] = Array.from({ length: 125 }, (_, index) => ({ id: `id-${index}`, kind: index < 100 ? 'added' : 'deleted', ...(index < 100 ? { after: { ...point, placementId: `id-${index}` } } : { before: { ...point, placementId: `id-${index}` } }) }))
    render(<PlacementChangeReview changes={changes} catalog={catalog} locked={false} hasDrafts={false} onLocate={vi.fn()} onRevert={vi.fn()} />)
    expect(screen.queryByRole('table')).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: /変更内容を確認/ }))
    expect(screen.getAllByRole('table')).toHaveLength(50)
    fireEvent.click(screen.getByRole('button', { name: '変更をさらに50件表示' }))
    expect(screen.getAllByRole('table')).toHaveLength(100)
    fireEvent.change(screen.getByLabelText('変更の種類'), { target: { value: 'deleted' } })
    expect(screen.getAllByRole('table')).toHaveLength(25)
    expect(screen.queryByRole('button', { name: 'この配置へ移動' })).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: /変更内容を確認/ }))
    expect(screen.queryByRole('table')).not.toBeInTheDocument()
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

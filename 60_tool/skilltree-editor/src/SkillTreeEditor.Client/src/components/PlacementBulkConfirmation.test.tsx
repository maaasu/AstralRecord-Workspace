import { fireEvent, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { PlacementBulkConfirmation } from './PlacementBulkConfirmation'
import type { WorldPlacement } from '../api/worldPlacementApi'

const catalog = { spawners: [{ id: 'wolf', name: '森林狼', radiusMeters: 32, levels: [10, 20] }], npcs: [], worlds: [] }
const items: WorldPlacement[] = Array.from({ length: 125 }, (_, i) => ({ placementId: `point-${i}`, kind: 'spawner', worldId: 'eriva', worldName: 'worlds/eriva', templateId: 'wolf', x: i, y: 70, z: 3, yaw: 0, pitch: 0, enabled: i % 2 === 0, waystone: null }))
describe('配置操作の対象確認', () => {
  it('全対象を50件ずつ確認し、条件外だけの一覧でも実行対象の合計を変えない', () => {
    const confirm = vi.fn()
    render(<PlacementBulkConfirmation action="disable" items={items} matchingIds={new Set(items.slice(0, 60).map(item => item.placementId))} catalog={catalog} stale={false} onCancel={vi.fn()} onConfirm={confirm} />)
    const dialog = screen.getByRole('dialog', { name: '配置の無効化を確認' })
    expect(within(dialog).getByText('合計 125 件 · 現在の表示条件内 60 件 · 表示条件外 65 件')).toBeInTheDocument()
    expect(within(dialog).getAllByRole('article')).toHaveLength(50)
    const first = within(dialog).getByRole('article', { name: '対象 point-0' })
    expect(within(first).getByRole('heading', { name: '森林狼' })).toBeInTheDocument()
    expect(within(first).getByText('出現レベル: 10 / 20 · 現在の表示条件内')).toBeInTheDocument()
    expect(within(first).getByText('X 0 / Y 70 / Z 3 · ワールド: worlds/eriva (eriva)')).toBeInTheDocument()
    expect(within(first).getByText('有効状態: 有効 → 無効')).toBeInTheDocument()
    fireEvent.click(within(dialog).getByRole('button', { name: '次の50件' }))
    expect(within(dialog).queryByRole('article', { name: '対象 point-0' })).not.toBeInTheDocument()
    expect(within(dialog).getByRole('article', { name: '対象 point-50' })).toBeInTheDocument()
    fireEvent.click(within(dialog).getByRole('button', { name: '次の50件' }))
    expect(within(dialog).getAllByRole('article')).toHaveLength(25)
    expect(within(dialog).getByRole('button', { name: '次の50件' })).toBeDisabled()
    fireEvent.click(within(dialog).getByRole('button', { name: '前の50件' }))
    fireEvent.click(within(dialog).getByLabelText('表示条件外の対象だけを確認'))
    expect(within(dialog).getAllByRole('article')).toHaveLength(50)
    expect(within(dialog).getByRole('article', { name: '対象 point-60' })).toBeInTheDocument()
    expect(within(dialog).getByText(/対象一覧 1〜50 \/ 65 件/)).toBeInTheDocument()
    fireEvent.click(within(dialog).getByRole('button', { name: '125 件を無効にする' }))
    expect(confirm).toHaveBeenCalledTimes(1)
  })
  it('キャンセルへフォーカスし、EnterとEscapeを即時確定にせず、背景へTab移動しない', async () => {
    const cancel = vi.fn(), confirm = vi.fn(), user = userEvent.setup()
    render(<><button>背景</button><PlacementBulkConfirmation action="delete" items={items.slice(0, 1)} matchingIds={new Set()} catalog={catalog} stale={false} onCancel={cancel} onConfirm={confirm} /></>)
    const dialog = screen.getByRole('dialog')
    expect(within(dialog).getByRole('button', { name: 'キャンセル' })).toHaveFocus()
    await user.keyboard('{Enter}')
    expect(cancel).toHaveBeenCalledTimes(1)
    expect(confirm).not.toHaveBeenCalled()
    await user.keyboard('{Escape}')
    expect(cancel).toHaveBeenCalledTimes(2)
    await user.keyboard('{Shift>}{Tab}{/Shift}')
    expect(within(dialog).getByRole('button', { name: '1 件を削除対象にする' })).toHaveFocus()
    expect(within(dialog).getByText('有効状態: 有効 → 削除対象')).toBeInTheDocument()
    await user.keyboard('{Control>}s{/Control}')
    expect(confirm).not.toHaveBeenCalled()
  })
  it('前提変更を説明し確定を止める', () => {
    render(<PlacementBulkConfirmation action="enable" items={items.slice(0, 1)} matchingIds={new Set()} catalog={catalog} stale onCancel={vi.fn()} onConfirm={vi.fn()} />)
    expect(screen.getByRole('alert')).toHaveTextContent('確認を開いた後に')
    expect(screen.getByRole('button', { name: '1 件を有効にする' })).toBeDisabled()
  })
})

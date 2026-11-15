import { fireEvent, render, screen } from '@testing-library/react'
import { expect, it, vi } from 'vitest'
import type { JsonValue } from '../types/editor'
import { MasterGridPlacement } from './MasterGridPlacement'

it('changes only the selected product placement and discards a drag when the draft changes', () => {
  const legacy = { id: 'legacy', itemId: { ref: 'item:legacy' }, row: 1, column: 1, requiredItems: [{ itemId: 'token', amount: 2 }] }
  const explicit = { id: 'explicit', itemId: 'item:explicit', page: 2, slot: '8', row: 1, column: 1 }
  const duplicate = { id: 'duplicate', itemId: 'item:duplicate', page: 2, row: 2, column: 2 }
  const values = [legacy, explicit, duplicate]
  const onChange = vi.fn<(values: JsonValue[]) => void>()
  const component = (entries: JsonValue[]) => <MasterGridPlacement kind="shop" values={entries} fileForEntry={() => undefined} onChange={onChange} />
  const { rerender } = render(component(values))
  const button = (name: string) => screen.getByRole('button', { name })
  const dataTransfer = { setData: vi.fn() }

  fireEvent.dragStart(button('スロット 0 · item:legacy'), { dataTransfer })
  rerender(component([explicit, duplicate]))
  rerender(component(values))
  fireEvent.drop(button('スロット 1 · 空き'), { dataTransfer })
  expect(onChange).not.toHaveBeenCalled()

  fireEvent.click(button('スロット 0 · item:legacy'))
  const pageInput = screen.getByRole('spinbutton', { name: '配置ページ' })
  fireEvent.change(pageInput, { target: { value: '2' } })
  fireEvent.blur(pageInput)
  expect(screen.getByTitle('スロット 8 · 重複 2 件')).toBeVisible()
  fireEvent.click(button('スロット 9 · 空き'))
  const next = onChange.mock.calls[0][0]
  expect(next[0]).toEqual({ ...legacy, page: 2, row: 2, column: 3 })
  expect(next[1]).toBe(explicit)
  expect(next[2]).toBe(duplicate)

  rerender(component(next))
  fireEvent.dragStart(button('item:explicit · 商品 2 · スロット 8 で重複'), { dataTransfer })
  fireEvent.drop(button('スロット 10 · 空き'), { dataTransfer })
  expect(onChange).toHaveBeenLastCalledWith([next[0], { ...explicit, slot: 10 }, duplicate])
})

it('uses empty and new pages and leaves unconfigured products out of the grid', () => {
  const first = { id: 'first', itemId: 'item:first', slot: 0 }
  const third = { id: 'third', itemId: 'item:third', page: 3, slot: 0 }
  const incomplete = { id: 'draft', itemId: { ref: '' }, page: 2, slot: 0 }
  const onChange = vi.fn<(values: JsonValue[]) => void>()
  const component = (values: JsonValue[]) => <MasterGridPlacement kind="shop" values={values} fileForEntry={() => undefined} onChange={onChange} />
  const { rerender } = render(component([first, third, incomplete]))
  const button = (name: string) => screen.getByRole('button', { name })
  const selectPage = (page: number) => {
    const input = screen.getByRole('spinbutton', { name: '配置ページ' })
    fireEvent.change(input, { target: { value: String(page) } })
    fireEvent.blur(input)
  }

  fireEvent.click(button('スロット 0 · item:first'))
  selectPage(2)
  expect(screen.getByText('商品 3 · 商品ID・アイテムを確認')).toBeVisible()
  fireEvent.click(button('スロット 0 · 空き'))
  expect(onChange).toHaveBeenLastCalledWith([{ ...first, page: 2 }, third, incomplete])
  rerender(component(onChange.mock.calls[0][0]))
  fireEvent.click(button('スロット 0 · item:first'))
  selectPage(4)
  expect(screen.getByText('新しいページ')).toBeVisible()
  fireEvent.click(button('スロット 1 · 空き'))
  expect(onChange).toHaveBeenLastCalledWith([{ ...first, page: 4, slot: 1 }, third, incomplete])
})

it('places a new quest without a shop entry ID', () => {
  const quest = { questId: { ref: 'quest:forest' } }
  const onChange = vi.fn<(values: JsonValue[]) => void>()
  render(<MasterGridPlacement kind="quest_board" values={[quest]} fileForEntry={() => undefined} onChange={onChange} />)
  expect(screen.getByRole('heading', { name: '掲示板の配置' })).toBeVisible()
  fireEvent.click(screen.getByRole('button', { name: 'quest:forest · クエスト 1 · 未配置' }))
  fireEvent.click(screen.getByRole('button', { name: 'スロット 7 · 空き' }))
  expect(onChange).toHaveBeenLastCalledWith([{ ...quest, slot: 7 }])
})

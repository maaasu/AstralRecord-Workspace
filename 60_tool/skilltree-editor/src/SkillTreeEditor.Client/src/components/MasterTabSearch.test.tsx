import { fireEvent, render, screen, within } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import type { MasterFileTab } from '../state/masterDataTabs'
import { MasterTabSearch } from './MasterTabSearch'

const tabs: MasterFileTab[] = [
  { id: 'a', path: '40.features.mob/forest/v1.wolf.yml', name: '&a森のウルフ', dirty: true, busy: false },
  { id: 'b', path: '40.features.mob/desert/v1.wolf.yml', name: '&6森のウルフ', dirty: false, busy: true },
  { id: 'c', path: '41.features.mob.spawner/v1.forest.yml', name: '森のスポナー', dirty: false, busy: false },
]
const results = () => within(screen.getByLabelText('開いているファイルの検索結果'))
const query = () => screen.getByRole('textbox', { name: '開いているファイルの検索語' })

describe('open tab search', () => {
  it('focuses the query and searches Japanese names and paths with multiple AND terms', () => {
    render(<MasterTabSearch tabs={tabs} activeId="c" onSelect={vi.fn()} onClose={vi.fn()} />)
    expect(query()).toHaveFocus()
    expect(results().getAllByRole('button')).toHaveLength(3)
    expect(results().getByRole('button', { name: /表示中/ })).toHaveAttribute('aria-current', 'true')
    fireEvent.change(query(), { target: { value: 'ウルフ　FOREST' } })
    expect(results().getAllByRole('button')).toHaveLength(1)
    expect(results().getByRole('button')).toHaveTextContent('40.features.mob/forest/v1.wolf.yml')
    fireEvent.change(query(), { target: { value: 'wolf.yml' } })
    expect(results().getAllByRole('button')).toHaveLength(2)
    fireEvent.change(query(), { target: { value: '存在しない' } })
    expect(results().queryByRole('button')).not.toBeInTheDocument()
    expect(results().getByText(/一致するタブはありません/)).toBeInTheDocument()
  })

  it('filters unsaved tabs and updates the result when a background save finishes', () => {
    const view = render(<MasterTabSearch tabs={tabs} activeId="c" onSelect={vi.fn()} onClose={vi.fn()} />)
    fireEvent.click(screen.getByRole('checkbox', { name: '未保存のみ' }))
    expect(results().getAllByRole('button')).toHaveLength(1)
    expect(screen.getByRole('status')).toHaveTextContent('1 / 3 件 · 未保存 1 件')
    fireEvent.keyDown(query(), { key: 'ArrowDown' })
    expect(results().getByRole('button')).toHaveFocus()
    view.rerender(<MasterTabSearch tabs={tabs.map(tab => ({ ...tab, dirty: false }))} activeId="c" onSelect={vi.fn()} onClose={vi.fn()} />)
    expect(results().queryByRole('button')).not.toBeInTheDocument()
    expect(screen.getByRole('status')).toHaveTextContent('0 / 3 件 · 未保存 0 件')
    expect(query()).toHaveFocus()
  })

  it('does not move focus away from another dialog control when tab status changes', () => {
    const view = render(<MasterTabSearch tabs={tabs} activeId="c" onSelect={vi.fn()} onClose={vi.fn()} />)
    const close = screen.getByRole('button', { name: '閉じる' })
    close.focus()
    view.rerender(<MasterTabSearch tabs={tabs.map(tab => ({ ...tab, busy: false }))} activeId="c" onSelect={vi.fn()} onClose={vi.fn()} />)
    expect(close).toHaveFocus()
  })

  it('allows selecting a busy tab and keeps Japanese IME confirmation from selecting a result', () => {
    const select = vi.fn()
    render(<MasterTabSearch tabs={tabs} activeId="c" onSelect={select} onClose={vi.fn()} />)
    fireEvent.keyDown(query(), { key: 'Enter', isComposing: true })
    fireEvent.keyDown(query(), { key: 'Enter', keyCode: 229 })
    expect(select).not.toHaveBeenCalled()
    fireEvent.change(query(), { target: { value: 'desert' } })
    expect(results().getByRole('button', { name: /処理中/ })).toBeEnabled()
    fireEvent.keyDown(query(), { key: 'Enter' })
    expect(select).toHaveBeenCalledWith('b')
  })

  it('navigates results by arrows and returns to the search input', () => {
    render(<MasterTabSearch tabs={tabs} activeId="c" onSelect={vi.fn()} onClose={vi.fn()} />)
    const buttons = results().getAllByRole('button')
    fireEvent.keyDown(query(), { key: 'ArrowDown' })
    expect(buttons[0]).toHaveFocus()
    fireEvent.keyDown(buttons[0], { key: 'ArrowDown' })
    expect(buttons[1]).toHaveFocus()
    fireEvent.keyDown(buttons[1], { key: 'End' })
    expect(buttons[2]).toHaveFocus()
    fireEvent.keyDown(buttons[2], { key: 'Home' })
    expect(buttons[0]).toHaveFocus()
    fireEvent.keyDown(buttons[0], { key: 'ArrowUp' })
    expect(query()).toHaveFocus()
  })

  it('renders at most 50 results, pages through all matches, and resets the page on filtering', () => {
    const many = Array.from({ length: 125 }, (_, index) => ({ ...tabs[0], id: `tab-${index}`, name: `ファイル ${index}`, path: `class/v1.${index}.yml`, dirty: index === 0 }))
    const view = render(<MasterTabSearch tabs={many} activeId="tab-124" onSelect={vi.fn()} onClose={vi.fn()} />)
    expect(results().getAllByRole('button')).toHaveLength(50)
    fireEvent.click(screen.getByRole('button', { name: '次のタブ一覧' }))
    expect(results().getAllByRole('button')).toHaveLength(50)
    expect(results().getAllByRole('button')[0]).toHaveTextContent('ファイル 50')
    expect(results().getAllByRole('button')[0]).toHaveFocus()
    fireEvent.click(screen.getByRole('button', { name: '次のタブ一覧' }))
    expect(results().getAllByRole('button')).toHaveLength(25)
    expect(results().getAllByRole('button')[0]).toHaveTextContent('ファイル 100')
    expect(screen.getByRole('button', { name: '次のタブ一覧' })).toBeDisabled()
    fireEvent.click(screen.getByRole('checkbox', { name: '未保存のみ' }))
    expect(results().getAllByRole('button')).toHaveLength(1)
    expect(screen.getByText('1 / 1 ページ · 50件ずつ表示')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('checkbox', { name: '未保存のみ' }))
    fireEvent.click(screen.getByRole('button', { name: '次のタブ一覧' }))
    fireEvent.change(query(), { target: { value: 'ファイル 124' } })
    expect(results().getAllByRole('button')).toHaveLength(1)
    expect(screen.getByText('1 / 1 ページ · 50件ずつ表示')).toBeInTheDocument()
    view.rerender(<MasterTabSearch tabs={[]} activeId="" onSelect={vi.fn()} onClose={vi.fn()} />)
    expect(results().queryByRole('button')).not.toBeInTheDocument()
    expect(screen.getByRole('status')).toHaveTextContent('0 / 0 件')
  })
})

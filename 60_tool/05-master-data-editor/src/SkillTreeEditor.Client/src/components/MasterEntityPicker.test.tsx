import { useState } from 'react'
import { fireEvent, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { MasterEntityPicker, type MasterEntityPickerChoice } from './MasterEntityPicker'

const choices: MasterEntityPickerChoice[] = [
  { id: 'iron_sword', name: '&a鉄の剣', category: '武器', icon: 'IRON_SWORD', description: '片手装備' },
  { id: 'iron_helmet', name: '鉄の兜', category: '防具', icon: 'IRON_HELMET', disabledReason: '頭装備には設定できません' },
  { id: 'gold_sword', name: '金の剣', category: '武器', icon: 'GOLDEN_SWORD' },
]
const catalog = Array.from({ length: 82 }, (_, index) => ({ id: `item_${index}`, name: `候補 ${index}`, icon: 'BOOK' }))
const defaults = () => ({ title: '装備を選択', choices, onSelect: vi.fn(), onClose: vi.fn() })

describe('MasterEntityPicker', () => {
  it('filters Japanese names and IDs with multiple terms and a category', () => {
    const props = defaults()
    render(<MasterEntityPicker {...props} value="iron_sword" />)
    const search = screen.getByRole('searchbox')
    const list = screen.getByRole('list', { name: '選択候補' })
    const selected = within(list).getByRole('button', { name: /鉄の剣/ })
    expect(selected).toHaveAttribute('aria-pressed', 'true')
    expect(selected).toHaveTextContent('片手装備')
    expect(selected.querySelector('img')).toHaveAttribute('src', '/api/minecraft-icons/IRON_SWORD?revision=0')
    fireEvent.change(search, { target: { value: '鉄 ＩＲＯＮ' } })
    expect(within(list).getAllByRole('button')).toEqual([selected])
    fireEvent.change(screen.getByRole('combobox', { name: 'カテゴリ' }), { target: { value: '防具' } })
    expect(screen.getByRole('status')).toHaveTextContent('0件')
    fireEvent.click(screen.getByRole('checkbox', { name: /選択できない候補も表示/ }))
    const disabled = screen.getByRole('button', { name: /鉄の兜/ })
    expect(disabled).toBeDisabled()
    expect(disabled).toHaveTextContent('頭装備には設定できません')
    fireEvent.click(disabled)
    expect(props.onSelect).not.toHaveBeenCalled()
  })

  it('shows 40 candidates per page, starts on the selected page, and resets pagination on search', () => {
    render(<MasterEntityPicker {...defaults()} choices={catalog} value="item_45" />)
    expect(screen.getByRole('status')).toHaveTextContent('82件 · 41〜80件')
    expect(screen.getByRole('button', { name: /item_45/ })).toHaveAttribute('aria-pressed', 'true')
    expect(within(screen.getByRole('list')).getAllByRole('button')).toHaveLength(40)
    fireEvent.click(screen.getByRole('button', { name: '次のページ' }))
    expect(screen.getByRole('status')).toHaveTextContent('82件 · 81〜82件')
    fireEvent.change(screen.getByRole('searchbox'), { target: { value: '候補' } })
    expect(screen.getByRole('status')).toHaveTextContent('82件 · 1〜40件')
  })

  it('skips disabled candidates and moves across pages with Arrow keys, Home, and End', async () => {
    const user = userEvent.setup()
    const props = defaults()
    const entries = catalog.map((entry, index) => index === 40 ? { ...entry, disabledReason: '選択できません' } : entry)
    render(<MasterEntityPicker {...props} choices={entries} />)
    await user.click(screen.getByRole('checkbox', { name: /選択できない候補も表示/ }))
    const search = screen.getByRole('searchbox')
    search.focus()
    await user.keyboard('{ArrowDown}')
    expect(screen.getByRole('button', { name: /item_0$/ })).toHaveFocus()
    screen.getByRole('button', { name: /item_39$/ }).focus()
    await user.keyboard('{ArrowDown}')
    expect(screen.getByRole('button', { name: /item_41$/ })).toHaveFocus()
    await user.keyboard('{ArrowUp}')
    expect(screen.getByRole('button', { name: /item_39$/ })).toHaveFocus()
    await user.keyboard('{End}')
    expect(screen.getByRole('button', { name: /item_81$/ })).toHaveFocus()
    await user.keyboard('{Home}')
    expect(screen.getByRole('button', { name: /item_0$/ })).toHaveFocus()
    await user.keyboard('{Enter}')
    expect(props.onSelect).toHaveBeenCalledExactlyOnceWith('item_0')
  })

  it('keeps composition and modified editing keys in the search input', async () => {
    const user = userEvent.setup()
    const props = defaults()
    const submit = vi.fn(event => event.preventDefault())
    render(<form onSubmit={submit}><MasterEntityPicker {...props} /></form>)
    const search = screen.getByRole('searchbox')
    fireEvent.compositionStart(search)
    for (const key of ['Enter', 'Escape', 'ArrowDown']) expect(fireEvent.keyDown(search, { key })).toBe(true)
    fireEvent.compositionEnd(search)
    for (const flags of [{ isComposing: true }, { keyCode: 229 }, { ctrlKey: true }]) {
      expect(fireEvent.keyDown(search, { key: 'Enter', ...flags })).toBe(true)
    }
    expect(props.onClose).not.toHaveBeenCalled()
    expect(props.onSelect).not.toHaveBeenCalled()
    expect(search).toHaveFocus()
    await user.keyboard('{Enter}')
    expect(props.onSelect).toHaveBeenCalledExactlyOnceWith('iron_sword')
    expect(submit).not.toHaveBeenCalled()
  })

  it('traps focus and restores the opener when canceled without changing the selection', async () => {
    const user = userEvent.setup()
    const props = defaults()
    function Harness() {
      const [open, setOpen] = useState(false)
      return <>
        <button type="button" onClick={() => setOpen(true)}>装備の変更</button>
        {open && <MasterEntityPicker {...props} value="iron_sword" onClose={() => setOpen(false)} />}
      </>
    }
    render(<Harness />)
    const opener = screen.getByRole('button', { name: '装備の変更' })
    await user.click(opener)
    const search = screen.getByRole('searchbox')
    expect(search).toHaveFocus()
    opener.focus()
    expect(search).toHaveFocus()
    screen.getByRole('button', { name: 'キャンセル' }).focus()
    expect(screen.getByRole('button', { name: 'キャンセル' })).toHaveFocus()
    await user.tab()
    expect(screen.getByRole('button', { name: '閉じる' })).toHaveFocus()
    await user.tab({ shift: true })
    expect(screen.getByRole('button', { name: 'キャンセル' })).toHaveFocus()
    await user.keyboard('{Escape}')
    expect(opener).toHaveFocus()
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(props.onSelect).not.toHaveBeenCalled()
    await user.click(opener)
    await user.click(screen.getByRole('button', { name: 'キャンセル' }))
    expect(opener).toHaveFocus()
    expect(props.onSelect).not.toHaveBeenCalled()
  })

  it('displays loading, retries errors, and reveals a late-loaded current choice', () => {
    const props = defaults()
    const retry = vi.fn()
    const { rerender } = render(<MasterEntityPicker {...props} choices={[]} value="item_45" loading />)
    expect(screen.getByRole('status')).toHaveTextContent('候補を読み込み中')
    expect(screen.queryByRole('list')).not.toBeInTheDocument()
    rerender(<MasterEntityPicker {...props} choices={[]} value="item_45" error="候補を取得できませんでした" onRetry={retry} />)
    expect(screen.getByRole('alert')).toHaveTextContent('候補を取得できませんでした')
    fireEvent.click(screen.getByRole('button', { name: '再試行' }))
    expect(retry).toHaveBeenCalledOnce()
    rerender(<MasterEntityPicker {...props} choices={catalog} value="item_45" />)
    expect(screen.getByRole('button', { name: /item_45/ })).toHaveAttribute('aria-pressed', 'true')
  })
})

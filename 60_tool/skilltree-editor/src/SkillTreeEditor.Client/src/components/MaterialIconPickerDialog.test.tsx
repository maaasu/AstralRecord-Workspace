import { useState } from 'react'
import { fireEvent, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import minecraftMaterials from '../data/minecraft-materials.1.21.11.json'
import { MaterialIconPickerDialog } from './MaterialIconPickerDialog'

const callbacks = () => ({ onSelect: vi.fn(), onClose: vi.fn() })

describe('MaterialIconPickerDialog', () => {
  it('shows only the current page of lazy images from the item Material catalog', () => {
    render(<MaterialIconPickerDialog value="" {...callbacks()} />)

    const list = screen.getByRole('list', { name: 'Material一覧' })
    expect(within(list).getAllByRole('button')).toHaveLength(48)
    const images = list.querySelectorAll('img')
    expect(images).toHaveLength(48)
    expect([...images].every((image) => image.getAttribute('loading') === 'lazy')).toBe(true)
    expect(screen.getByRole('status')).toHaveTextContent(`${minecraftMaterials.length}件・1〜48件を表示`)
    expect(screen.getByRole('button', { name: '前のページ' })).toBeDisabled()
  })

  it('paginates the results and resets to the first page after searching', () => {
    const materials = Array.from({ length: 60 }, (_, index) => `MATERIAL_${String(index).padStart(2, '0')}`)
    render(<MaterialIconPickerDialog value="" materials={materials} {...callbacks()} />)

    fireEvent.click(screen.getByRole('button', { name: '次のページ' }))
    expect(screen.queryByRole('button', { name: 'MATERIAL_00' })).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'MATERIAL_59' })).toBeInTheDocument()
    expect(within(screen.getByRole('list')).getAllByRole('button')).toHaveLength(12)
    expect(screen.getByRole('status')).toHaveTextContent('60件・49〜60件を表示')
    expect(screen.getByRole('button', { name: '次のページ' })).toBeDisabled()

    fireEvent.change(screen.getByRole('searchbox'), { target: { value: 'material' } })
    expect(screen.getByRole('button', { name: 'MATERIAL_00' })).toBeInTheDocument()
    expect(screen.getByRole('status')).toHaveTextContent('60件・1〜48件を表示')
    fireEvent.click(screen.getByRole('button', { name: '次のページ' }))
    fireEvent.click(screen.getByRole('button', { name: '前のページ' }))
    expect(screen.getByRole('button', { name: 'MATERIAL_00' })).toBeInTheDocument()
  })

  it('searches with case-insensitive tokens separated by spaces or underscores', () => {
    render(<MaterialIconPickerDialog value="" materials={['IRON_SWORD', 'IRON_INGOT', 'GOLDEN_SWORD']} {...callbacks()} />)
    const search = screen.getByRole('searchbox', { name: 'Materialを検索' })

    for (const query of ['iRoN sword', 'sword_iron', 'minecraft:iron_sword']) {
      fireEvent.change(search, { target: { value: query } })
      expect(screen.getByRole('button', { name: 'IRON_SWORD' })).toBeInTheDocument()
      expect(within(screen.getByRole('list')).getAllByRole('button')).toHaveLength(1)
      expect(screen.getByRole('status')).toHaveTextContent('1件・1〜1件を表示')
    }

    fireEvent.change(search, { target: { value: 'unknown' } })
    expect(screen.getByText('該当するMaterialがありません。検索語を変更してください。')).toBeInTheDocument()
    expect(screen.getByRole('status')).toHaveTextContent('0件')
    expect(screen.getByRole('button', { name: 'このページの画像を再読込' })).toBeDisabled()
    expect(screen.getByRole('button', { name: '次のページ' })).toBeDisabled()
  })

  it('highlights the current normalized value and returns a canonical ID without calling onClose', () => {
    const props = callbacks()
    render(<MaterialIconPickerDialog value=" minecraft:iron_sword " materials={['iron_sword', 'minecraft:iron_ingot', 'IRON_SWORD']} {...props} />)

    const selected = screen.getByRole('button', { name: 'IRON_SWORD' })
    expect(selected).toHaveAttribute('aria-pressed', 'true')
    expect(selected).toHaveTextContent('選択中')
    expect(within(screen.getByRole('list')).getAllByRole('button')).toHaveLength(2)
    fireEvent.click(screen.getByRole('button', { name: 'IRON_INGOT' }))
    expect(props.onSelect).toHaveBeenCalledExactlyOnceWith('IRON_INGOT')
    expect(props.onClose).not.toHaveBeenCalled()
  })

  it('opens at the page containing the current material', () => {
    const materials = Array.from({ length: 60 }, (_, index) => `MATERIAL_${index}`)
    render(<MaterialIconPickerDialog value="minecraft:material_59" materials={materials} {...callbacks()} />)

    expect(screen.getByRole('button', { name: 'MATERIAL_59' })).toHaveAttribute('aria-pressed', 'true')
    expect(screen.getByRole('status')).toHaveTextContent('60件・49〜60件を表示')
  })

  it('keeps unavailable images selectable and retries only the displayed page', () => {
    const props = callbacks()
    const { container, rerender } = render(<MaterialIconPickerDialog value="" materials={['IRON_SWORD']} revision={3} {...props} />)
    const image = container.querySelector('img')!
    expect(image).toHaveAttribute('src', '/api/minecraft-icons/IRON_SWORD?revision=3')
    fireEvent.error(image)
    expect(screen.getByLabelText('アイコンを取得できません')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'IRON_SWORD' }))
    expect(props.onSelect).toHaveBeenCalledWith('IRON_SWORD')

    fireEvent.click(screen.getByRole('button', { name: 'このページの画像を再読込' }))
    expect(container.querySelector('img')).toHaveAttribute('src', '/api/minecraft-icons/IRON_SWORD?revision=4')
    expect(screen.queryByLabelText('アイコンを取得できません')).not.toBeInTheDocument()
    rerender(<MaterialIconPickerDialog value="" materials={['IRON_SWORD']} revision={8} {...props} />)
    expect(container.querySelector('img')).toHaveAttribute('src', '/api/minecraft-icons/IRON_SWORD?revision=9')
  })

  it('cancels with buttons, backdrop, or Escape without selecting or closing a parent dialog', () => {
    const props = callbacks()
    const parentKeyboard = vi.fn()
    const { container } = render(
      <div onKeyDown={parentKeyboard}>
        <MaterialIconPickerDialog value="BOOK" materials={['BOOK']} {...props} />
      </div>,
    )
    fireEvent.click(screen.getByRole('button', { name: 'キャンセル' }))
    fireEvent.click(screen.getByRole('button', { name: '素材アイコン選択を閉じる' }))
    fireEvent.click(container.querySelector('.material-picker-backdrop')!)
    fireEvent.keyDown(screen.getByRole('searchbox'), { key: 'Escape' })
    expect(props.onClose).toHaveBeenCalledTimes(4)
    expect(props.onSelect).not.toHaveBeenCalled()
    expect(parentKeyboard).not.toHaveBeenCalled()
    fireEvent.click(screen.getByRole('heading', { name: '素材アイコンを選択' }))
    expect(props.onClose).toHaveBeenCalledTimes(4)
  })

  it('blocks save shortcuts and parent Undo while preserving the search input default Undo', () => {
    const parentKeyboard = vi.fn()
    const globalSave = vi.fn()
    window.addEventListener('keydown', globalSave)
    try {
      render(<div onKeyDown={parentKeyboard}><MaterialIconPickerDialog value="" materials={['BOOK']} {...callbacks()} /></div>)
      const search = screen.getByRole('searchbox')
      for (const modifier of [{ ctrlKey: true }, { metaKey: true }]) {
        expect(fireEvent.keyDown(search, { key: 's', ...modifier })).toBe(false)
        expect(fireEvent.keyDown(search, { key: 'z', ...modifier })).toBe(true)
        expect(fireEvent.keyDown(search, { key: 'y', ...modifier })).toBe(true)
      }
      expect(parentKeyboard).not.toHaveBeenCalled()
      expect(globalSave).not.toHaveBeenCalled()
    } finally {
      window.removeEventListener('keydown', globalSave)
    }
  })

  it('focuses search, traps Tab, restores the opener, and supports keyboard selection', async () => {
    const user = userEvent.setup()
    const selected = vi.fn()
    function Harness() {
      const [open, setOpen] = useState(false)
      return (
        <>
          <button type="button" onClick={() => setOpen(true)}>アイコンを選択</button>
          {open && <MaterialIconPickerDialog value="" materials={['BOOK']} onClose={() => setOpen(false)} onSelect={(material) => { selected(material); setOpen(false) }} />}
        </>
      )
    }
    render(<Harness />)
    const opener = screen.getByRole('button', { name: 'アイコンを選択' })
    await user.click(opener)
    expect(screen.getByRole('searchbox')).toHaveFocus()
    const cancel = screen.getByRole('button', { name: 'キャンセル' })
    const close = screen.getByRole('button', { name: '素材アイコン選択を閉じる' })
    cancel.focus()
    await user.tab()
    expect(close).toHaveFocus()
    await user.tab({ shift: true })
    expect(cancel).toHaveFocus()
    opener.focus()
    expect(screen.getByRole('searchbox')).toHaveFocus()
    await user.keyboard('{Escape}')
    expect(opener).toHaveFocus()
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(selected).not.toHaveBeenCalled()

    await user.click(opener)
    screen.getByRole('button', { name: 'BOOK' }).focus()
    await user.keyboard('{Enter}')
    expect(selected).toHaveBeenCalledExactlyOnceWith('BOOK')
    expect(opener).toHaveFocus()
  })

  it('does not submit a surrounding form on Enter in search or on selection', async () => {
    const user = userEvent.setup()
    const submit = vi.fn((event) => event.preventDefault())
    const props = callbacks()
    render(<form onSubmit={submit}><MaterialIconPickerDialog value="" materials={['BOOK']} {...props} /></form>)
    await user.keyboard('{Enter}')
    await user.click(screen.getByRole('button', { name: 'BOOK' }))
    expect(submit).not.toHaveBeenCalled()
    expect(props.onSelect).toHaveBeenCalledWith('BOOK')
  })
})

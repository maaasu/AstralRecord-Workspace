import { fireEvent, render, screen } from '@testing-library/react'
import { useState } from 'react'
import { describe, expect, it, vi } from 'vitest'
import { MaterialIconInput, MaterialPickerLoading } from './MaterialIconInput'

vi.mock('./MaterialIconPickerDialog', () => ({
  MaterialIconPickerDialog: ({ onSelect, onClose }: { onSelect: (material: string) => void; onClose: () => void }) =>
    <div role="dialog" aria-label="素材アイコンを選択"><button onClick={() => onSelect('BOOK')}>BOOKを選択</button><button onClick={onClose}>キャンセル</button></div>,
}))

function Editable({ locked = false }: { locked?: boolean }) {
  const [value, setValue] = useState('minecraft:iron_ingot')
  return <fieldset disabled={locked}><MaterialIconInput value={value} onChange={setValue} ariaLabel="素材" suggestions={['IRON_INGOT', 'BOOK']} /><output data-testid="selected">{value}</output></fieldset>
}

describe('MaterialIconInput', () => {
  it('blocks background save and allows Escape cancellation before the dialog module loads', () => {
    const onClose = vi.fn()
    const backgroundSave = vi.fn()
    window.addEventListener('keydown', backgroundSave)
    const view = render(<MaterialPickerLoading onClose={onClose} />)
    const cancel = screen.getByRole('button', { name: 'キャンセル' })
    expect(cancel).toHaveFocus()
    fireEvent.keyDown(cancel, { key: 's', ctrlKey: true })
    expect(backgroundSave).not.toHaveBeenCalled()
    fireEvent.keyDown(cancel, { key: 'Escape' })
    expect(onClose).toHaveBeenCalledOnce()
    view.unmount()
    window.removeEventListener('keydown', backgroundSave)
  })
  it('shows a preview and keeps free text and namespaced values intact', () => {
    render(<Editable />)
    expect(screen.getByRole('img', { name: '素材アイコン: minecraft:iron_ingot' }).querySelector('img'))
      .toHaveAttribute('src', '/api/minecraft-icons/minecraft%3Airon_ingot?revision=0')
    fireEvent.change(screen.getByRole('combobox', { name: '素材' }), { target: { value: 'custom_material' } })
    expect(screen.getByTestId('selected')).toHaveTextContent('custom_material')
    expect(screen.getByRole('img', { name: '素材アイコン: custom_material' }).querySelector('img'))
      .toHaveAttribute('src', '/api/minecraft-icons/custom_material?revision=0')
  })
  it('applies selection to the draft and closes the dialog without saving', async () => {
    render(<Editable />)
    fireEvent.click(screen.getByRole('button', { name: '画像一覧から選択' }))
    fireEvent.click(await screen.findByRole('button', { name: 'BOOKを選択' }))
    expect(screen.getByTestId('selected')).toHaveTextContent('BOOK')
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })
  it('cancels without changing the original icon', async () => {
    render(<Editable />)
    fireEvent.click(screen.getByRole('button', { name: '画像一覧から選択' }))
    fireEvent.click(await screen.findByRole('button', { name: 'キャンセル' }))
    expect(screen.getByTestId('selected')).toHaveTextContent('minecraft:iron_ingot')
  })
  it('honors an inherited disabled fieldset even when dialog controls live in a portal', async () => {
    const view = render(<Editable />)
    fireEvent.click(screen.getByRole('button', { name: '画像一覧から選択' }))
    const select = await screen.findByRole('button', { name: 'BOOKを選択' })
    view.rerender(<Editable locked />)
    fireEvent.click(select)
    expect(screen.getByTestId('selected')).toHaveTextContent('minecraft:iron_ingot')
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: '画像一覧から選択' })).toBeDisabled()
  })
})

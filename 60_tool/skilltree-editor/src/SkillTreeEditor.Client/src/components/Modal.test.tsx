import { fireEvent, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useState } from 'react'
import { createPortal } from 'react-dom'
import { describe, expect, it, vi } from 'vitest'
import { Modal } from './Modal'

function DialogExample({ closeDisabled = false }: { closeDisabled?: boolean }) {
  const [open, setOpen] = useState(false)
  return <><button onClick={() => setOpen(true)}>開く</button>{open && <Modal ariaLabel="テスト画面" onClose={() => setOpen(false)} closeDisabled={closeDisabled}>
    <button>最初</button><input aria-label="入力" /><details><summary>説明</summary><button>非表示</button></details><button>最後</button>
  </Modal>}</>
}

describe('modal keyboard and focus', () => {
  it('focuses the dialog, traps Tab including closed details, restores focus and scroll on Escape', async () => {
    const user = userEvent.setup()
    render(<DialogExample />)
    const opener = screen.getByRole('button', { name: '開く' })
    await user.click(opener)
    expect(screen.getByRole('button', { name: '最初' })).toHaveFocus()
    expect(document.body.style.overflow).toBe('hidden')
    await user.keyboard('{Shift>}{Tab}{/Shift}')
    expect(screen.getByRole('button', { name: '最後' })).toHaveFocus()
    await user.tab()
    expect(screen.getByRole('button', { name: '最初' })).toHaveFocus()
    await user.tab(); await user.tab()
    expect(screen.getByText('説明')).toHaveFocus()
    await user.tab()
    expect(screen.getByRole('button', { name: '最後' })).toHaveFocus()
    await user.keyboard('{Escape}')
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(opener).toHaveFocus()
    expect(document.body.style.overflow).not.toBe('hidden')
  })

  it('blocks background shortcuts and Escape dismissal while processing', async () => {
    const background = vi.fn()
    window.addEventListener('keydown', background)
    try {
      render(<DialogExample closeDisabled />)
      fireEvent.click(screen.getByRole('button', { name: '開く' }))
      const input = screen.getByRole('textbox', { name: '入力' })
      fireEvent.keyDown(input, { key: 's', ctrlKey: true })
      fireEvent.keyDown(input, { key: 'z', ctrlKey: true })
      fireEvent.keyDown(input, { key: 'Escape' })
      expect(background).not.toHaveBeenCalled()
      expect(screen.getByRole('dialog')).toBeInTheDocument()
    } finally { window.removeEventListener('keydown', background) }
  })

  it('lets a nested picker own focus and Escape, then restores its parent', async () => {
    function NestedDialogs() {
      const [open, setOpen] = useState(false)
      return <Modal ariaLabel="親の編集" onClose={vi.fn()}>
        <button onClick={() => setOpen(true)}>選択画面を開く</button>
        {open && createPortal(<Modal ariaLabel="候補の選択" onClose={() => setOpen(false)}><input aria-label="候補を検索" /><button>候補を選ぶ</button></Modal>, document.body)}
      </Modal>
    }
    const user = userEvent.setup()
    render(<NestedDialogs />)
    await user.click(screen.getByRole('button', { name: '選択画面を開く' }))
    expect(screen.getByRole('textbox', { name: '候補を検索' })).toHaveFocus()
    await user.tab()
    expect(screen.getByRole('button', { name: '候補を選ぶ' })).toHaveFocus()
    await user.keyboard('{Escape}')
    expect(screen.queryByRole('dialog', { name: '候補の選択' })).not.toBeInTheDocument()
    expect(screen.getByRole('dialog', { name: '親の編集' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '選択画面を開く' })).toHaveFocus()
    expect(document.body.style.overflow).toBe('hidden')
  })
})

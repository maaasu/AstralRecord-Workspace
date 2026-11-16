import { act, fireEvent, render, screen, within } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { useConfirmationDialog } from './ConfirmationDialog'

const panel = vi.hoisted(() => ({ document: undefined as Document | undefined, visible: true }))
vi.mock('../docking/dockPanel', () => ({
  usePanelDocument: () => panel.document ?? document,
  usePanelVisibility: () => panel.visible,
}))

function Prompt({ answered, owner }: { answered: (value: boolean) => void; owner?: Document }) {
  const { confirm, dialog } = useConfirmationDialog()
  return <><button onClick={() => void confirm('編集中の原稿を破棄しますか？', { document: owner }).then(answered)}>確認を表示</button>{dialog}</>
}

afterEach(() => { panel.document = undefined; panel.visible = true })

describe('themed confirmation', () => {
  it('focuses cancel, consumes Escape, and restores focus without discarding', async () => {
    const answered = vi.fn()
    render(<Prompt answered={answered} />)
    const opener = screen.getByRole('button', { name: '確認を表示' })
    opener.focus()
    fireEvent.click(opener)
    const cancel = screen.getByRole('button', { name: 'キャンセル' })
    expect(cancel).toHaveFocus()
    await act(async () => { fireEvent.keyDown(cancel, { key: 'Escape' }) })
    expect(answered).toHaveBeenCalledExactlyOnceWith(false)
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(opener).toHaveFocus()
  })

  it('accepts only the explicit discard action', async () => {
    const answered = vi.fn()
    render(<Prompt answered={answered} />)
    fireEvent.click(screen.getByRole('button', { name: '確認を表示' }))
    expect(answered).not.toHaveBeenCalled()
    await act(async () => { fireEvent.click(screen.getByRole('button', { name: '変更を破棄' })) })
    expect(answered).toHaveBeenCalledExactlyOnceWith(true)
  })

  it.each(['pagehide', 'beforeunload'])('cancels when the explicit popout owner emits %s, then permits another request', async event => {
    const iframe = document.createElement('iframe')
    document.body.append(iframe)
    const owner = iframe.contentDocument!
    const answered = vi.fn()
    const view = render(<Prompt answered={answered} owner={owner} />)
    fireEvent.click(screen.getByRole('button', { name: '確認を表示' }))
    expect(within(owner.body).getByRole('dialog')).toBeInTheDocument()
    await act(async () => { owner.defaultView!.dispatchEvent(new Event(event)) })
    expect(answered).toHaveBeenLastCalledWith(false)
    expect(within(owner.body).queryByRole('dialog')).not.toBeInTheDocument()
    view.rerender(<Prompt answered={answered} />)
    fireEvent.click(screen.getByRole('button', { name: '確認を表示' }))
    await act(async () => { fireEvent.click(screen.getByRole('button', { name: '変更を破棄' })) })
    expect(answered).toHaveBeenLastCalledWith(true)
    iframe.remove()
  })

  it('cancels a pending prompt when the panel changes document or becomes hidden', async () => {
    const answered = vi.fn()
    const view = render(<Prompt answered={answered} />)
    fireEvent.click(screen.getByRole('button', { name: '確認を表示' }))
    panel.document = document.implementation.createHTMLDocument('moved')
    await act(async () => { view.rerender(<Prompt answered={answered} />) })
    expect(answered).toHaveBeenLastCalledWith(false)
    panel.document = document
    view.rerender(<Prompt answered={answered} />)
    fireEvent.click(screen.getByRole('button', { name: '確認を表示' }))
    panel.visible = false
    await act(async () => { view.rerender(<Prompt answered={answered} />) })
    expect(answered).toHaveBeenCalledTimes(2)
    expect(answered).toHaveBeenLastCalledWith(false)
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })
})

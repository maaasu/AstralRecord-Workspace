import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { WorkspaceLayout } from './WorkspaceLayout'

describe('WorkspaceLayout', () => {
  it('renders only visible panes and resizes the left pane with the keyboard', () => {
    const { rerender } = render(
      <WorkspaceLayout
        left={<div>left pane</div>}
        center={<div>center pane</div>}
        right={<div>right pane</div>}
        leftVisible
        centerVisible
        rightVisible
      />,
    )

    const separator = screen.getByRole('separator', { name: 'ノード一覧の幅を変更' })
    expect(separator).toHaveAttribute('aria-valuenow', '280')
    fireEvent.keyDown(separator, { key: 'ArrowRight' })
    expect(separator).toHaveAttribute('aria-valuenow', '296')

    rerender(
      <WorkspaceLayout
        left={<div>left pane</div>}
        center={<div>center pane</div>}
        right={<div>right pane</div>}
        leftVisible={false}
        centerVisible
        rightVisible
      />,
    )
    expect(screen.queryByText('left pane')).not.toBeInTheDocument()
    expect(screen.getByText('center pane')).toBeInTheDocument()
    expect(screen.getByText('right pane')).toBeInTheDocument()
  })

  it('keeps draft inputs mounted when the compact working area changes', () => {
    render(<WorkspaceLayout left={<div>ノード一覧の内容</div>} center={<div>キャンバスの内容</div>} right={<input aria-label="詳細の編集中の名前" defaultValue="元の名前" />} leftVisible centerVisible rightVisible />)
    const input = screen.getByLabelText('詳細の編集中の名前')
    fireEvent.click(screen.getByRole('button', { name: '詳細・検証' }))
    expect(input.closest('.workspace-pane')).toHaveAttribute('data-compact-active', 'true')
    fireEvent.change(input, { target: { value: '未保存の名前' } })
    fireEvent.click(screen.getByRole('button', { name: 'キャンバス' }))
    expect(input.closest('.workspace-pane')).toHaveAttribute('data-compact-active', 'false')
    fireEvent.click(screen.getByRole('button', { name: '詳細・検証' }))
    expect(screen.getByLabelText('詳細の編集中の名前')).toBe(input)
    expect(input).toHaveValue('未保存の名前')
  })

  it('lets compact navigation restore a pane hidden in the wide layout', () => {
    const activate = vi.fn()
    render(<WorkspaceLayout left={<div>left</div>} center={<div>center</div>} right={<div>right</div>} leftVisible={false} centerVisible rightVisible={false} onActivatePane={activate} />)
    fireEvent.click(screen.getByRole('button', { name: '詳細・検証' }))
    expect(activate).toHaveBeenCalledWith('right')
  })
})

import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
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
})

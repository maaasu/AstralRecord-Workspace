import { fireEvent, render, screen } from '@testing-library/react'
import { useState } from 'react'
import { flushSync } from 'react-dom'
import { describe, expect, it, vi } from 'vitest'
import { MasterTabContextMenu } from './MasterTabContextMenu'

function NativeContextMenu() {
  const [open, setOpen] = useState(false)
  return <><button type="button" onContextMenu={(event) => {
    event.preventDefault()
    // A discrete browser event can mount effects before the event reaches document.
    flushSync(() => setOpen(true))
  }}>ファイルタブ</button>{open && <MasterTabContextMenu x={100} y={100} commands={[{ label: '閉じる', disabled: false, run: vi.fn() }]} onDismiss={() => setOpen(false)} />}</>
}

describe('native tab context menu events', () => {
  it('does not dismiss itself on the contextmenu event that opens it and still dismisses outside events', () => {
    render(<NativeContextMenu />)
    fireEvent.contextMenu(screen.getByRole('button', { name: 'ファイルタブ' }))
    expect(screen.getByRole('menu', { name: 'タブの操作' })).toBeInTheDocument()
    expect(screen.getByRole('menuitem', { name: '閉じる' })).toHaveFocus()
    fireEvent.contextMenu(document.body)
    expect(screen.queryByRole('menu')).not.toBeInTheDocument()
    fireEvent.contextMenu(screen.getByRole('button', { name: 'ファイルタブ' }))
    expect(screen.getByRole('menu')).toBeInTheDocument()
    fireEvent.pointerDown(document.body)
    expect(screen.queryByRole('menu')).not.toBeInTheDocument()
  })
})

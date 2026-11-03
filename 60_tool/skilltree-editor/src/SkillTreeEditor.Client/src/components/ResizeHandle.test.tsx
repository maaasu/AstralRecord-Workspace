import { useState } from 'react'
import { fireEvent, render, screen } from '@testing-library/react'
import { beforeEach, expect, it, vi } from 'vitest'
import { ResizeHandle, usePanelSize } from './ResizeHandle'

beforeEach(() => localStorage.clear())

function Panels({ horizontal = false, reverse = false }: { horizontal?: boolean; reverse?: boolean }) {
  const [size, setSize] = useState(280)
  return <div><div>一覧</div><ResizeHandle label="境界" size={size} min={100} max={600} defaultSize={280} orientation={horizontal ? 'horizontal' : 'vertical'} direction={reverse ? -1 : 1} onResize={setSize} /><div>編集</div></div>
}

it('resizes with pointer movement, ignores other pointers, and releases listeners when cancelled', () => {
  const resize = vi.fn()
  const { unmount } = render(<div><div /><ResizeHandle label="境界" size={280} min={100} max={600} defaultSize={280} onResize={resize} /></div>)
  const divider = screen.getByRole('separator', { name: '境界' })
  fireEvent.pointerDown(divider, { button: 0, clientX: 400, pointerId: 1 })
  expect(document.body).toHaveClass('resizing-panels')
  fireEvent.pointerMove(window, { clientX: 300, pointerId: 2 })
  expect(resize).not.toHaveBeenCalled()
  fireEvent.pointerMove(window, { clientX: 1000, pointerId: 1 })
  expect(resize).toHaveBeenLastCalledWith(600)
  fireEvent.pointerMove(window, { clientX: -1000, pointerId: 1 })
  expect(resize).toHaveBeenLastCalledWith(100)
  fireEvent.pointerCancel(window, { pointerId: 1 })
  expect(document.body).not.toHaveClass('resizing-panels')
  resize.mockClear()
  fireEvent.pointerMove(window, { clientX: 500, pointerId: 1 })
  expect(resize).not.toHaveBeenCalled()
  fireEvent.pointerDown(divider, { button: 0, clientX: 400, pointerId: 1 })
  unmount()
  expect(document.body).not.toHaveClass('resizing-panels')
})

it('supports reversed and horizontal keyboard resizing, bounds and reset', () => {
  const { rerender } = render(<Panels reverse />)
  const divider = screen.getByRole('separator', { name: '境界' })
  fireEvent.keyDown(divider, { key: 'ArrowLeft' })
  expect(divider).toHaveAttribute('aria-valuenow', '296')
  fireEvent.keyDown(divider, { key: 'End' })
  expect(divider).toHaveAttribute('aria-valuenow', '600')
  fireEvent.keyDown(divider, { key: 'ArrowLeft' })
  expect(divider).toHaveAttribute('aria-valuenow', '600')
  fireEvent.doubleClick(divider)
  expect(divider).toHaveAttribute('aria-valuenow', '280')
  rerender(<Panels horizontal />)
  fireEvent.keyDown(divider, { key: 'ArrowDown', shiftKey: true })
  expect(divider).toHaveAttribute('aria-orientation', 'horizontal')
  expect(divider).toHaveAttribute('aria-valuenow', '328')
  fireEvent.keyDown(divider, { key: 'Enter' })
  expect(divider).toHaveAttribute('aria-valuenow', '280')
})

function SavedPanels() {
  const [size, setSize] = usePanelSize('test', 280, 100, 600)
  return <ResizeHandle label="保存する境界" size={size} min={100} max={600} defaultSize={280} onResize={setSize} />
}

it('restores the preferred size and handles invalid or unavailable storage', () => {
  const first = render(<SavedPanels />)
  fireEvent.keyDown(screen.getByRole('separator'), { key: 'ArrowRight' })
  first.unmount()
  const second = render(<SavedPanels />)
  expect(screen.getByRole('separator')).toHaveAttribute('aria-valuenow', '296')
  second.unmount()
  localStorage.setItem('astralrecord.layout.test', 'NaN')
  const third = render(<SavedPanels />)
  expect(screen.getByRole('separator')).toHaveAttribute('aria-valuenow', '280')
  third.unmount()
  const getter = vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => { throw new Error('blocked') })
  const setter = vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new Error('blocked') })
  render(<SavedPanels />)
  expect(screen.getByRole('separator')).toHaveAttribute('aria-valuenow', '280')
  getter.mockRestore()
  setter.mockRestore()
})

import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { expect, it, vi } from 'vitest'
import { GameFlowNodeNotes } from './GameFlowNodeNotes'
import { GameFlowNoteResize } from './GameFlowNoteResize'

const view = vi.hoisted(() => ({
  canvas: null as HTMLElement | null,
  zoom: 1,
  nodes: [],
  edges: [],
}))

vi.mock('@xyflow/react', () => ({
  useReactFlow: () => ({
    getZoom: () => view.zoom,
  }),
  useStore: (select: (state: unknown) => unknown) => select({
    domNode: view.canvas, nodes: view.nodes, edges: view.edges, transform: [0, 0, view.zoom],
  }),
}))

it('previews a sticky-note resize in flow coordinates and commits its final dimensions once', () => {
  view.zoom = .5
  const onPreview = vi.fn()
  const onCommit = vi.fn()
  render(<GameFlowNoteResize width={140} height={64} onPreview={onPreview} onCommit={onCommit} />)
  const handle = screen.getByRole('button', { name: '付箋のサイズを変更' })

  fireEvent.pointerDown(handle, { button: 0, pointerId: 4, clientX: 60, clientY: 60 })
  fireEvent.pointerMove(window, { pointerId: 4, clientX: 80, clientY: 68 })
  expect(onPreview).toHaveBeenLastCalledWith({ width: 180, height: 80 })
  expect(onCommit).not.toHaveBeenCalled()
  fireEvent.pointerUp(window, { pointerId: 4, clientX: 90, clientY: 75 })
  expect(onPreview).toHaveBeenLastCalledWith(null)
  expect(onCommit).toHaveBeenCalledExactlyOnceWith({ width: 200, height: 94 })
})

it('clamps sticky notes to their minimum size and discards a canceled drag', () => {
  view.zoom = 1
  const onPreview = vi.fn()
  const onCommit = vi.fn()
  render(<GameFlowNoteResize width={140} height={64} onPreview={onPreview} onCommit={onCommit} />)
  const handle = screen.getByRole('button', { name: '付箋のサイズを変更' })

  fireEvent.pointerDown(handle, { button: 0, pointerId: 5, clientX: 160, clientY: 160 })
  fireEvent.pointerMove(window, { pointerId: 5, clientX: 0, clientY: 0 })
  expect(onPreview).toHaveBeenLastCalledWith({ width: 96, height: 48 })
  fireEvent.keyDown(window, { key: 'Escape' })
  expect(onPreview).toHaveBeenLastCalledWith(null)
  fireEvent.pointerUp(window, { pointerId: 5, clientX: 0, clientY: 0 })
  expect(onCommit).not.toHaveBeenCalled()
})

it('shows summaries only at a readable zoom and away from other labels and edge strokes', async () => {
  view.zoom = 1
  const canvas = document.createElement('div')
  canvas.innerHTML = '<span class="game-flow-token-summary"></span><span class="game-flow-token-summary"></span>'
    + '<span class="game-flow-token-summary"></span><div class="game-flow-token-name"></div>'
    + '<svg class="react-flow__edges"><path class="react-flow__edge-path" d="M 0 0L 0 160L 150 160"></path></svg>'
  view.canvas = canvas
  const [clear, crossed, covered, label, , edge] = canvas.querySelectorAll('span, div, svg, path')
  vi.spyOn(clear, 'getBoundingClientRect').mockReturnValue(new DOMRect(20, 40, 150, 30))
  vi.spyOn(crossed, 'getBoundingClientRect').mockReturnValue(new DOMRect(20, 150, 150, 30))
  vi.spyOn(covered, 'getBoundingClientRect').mockReturnValue(new DOMRect(20, 250, 150, 30))
  vi.spyOn(label, 'getBoundingClientRect').mockReturnValue(new DOMRect(140, 245, 130, 22))
  Object.defineProperty(edge, 'getScreenCTM', { value: () => ({ a: 1, d: 1, e: 0, f: 0 }) })

  const { rerender } = render(<GameFlowNodeNotes />)
  await waitFor(() => expect(clear).toHaveAttribute('data-visible'))
  expect(crossed).not.toHaveAttribute('data-visible')
  expect(covered).not.toHaveAttribute('data-visible')
  view.zoom = .5
  rerender(<GameFlowNodeNotes />)
  expect(clear).not.toHaveAttribute('data-visible')
})

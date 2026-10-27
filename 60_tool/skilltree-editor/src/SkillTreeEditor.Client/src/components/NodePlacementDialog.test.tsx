import { fireEvent, render, screen } from '@testing-library/react'
import { afterEach, expect, it, vi } from 'vitest'
import { NodePlacementDialog } from './NodePlacementDialog'
import type { NodeMaster, StructureDocument } from '../types/editor'

const node: NodeMaster = { $schema: '', schemaVersion: 1, nodeId: '1001', name: '追加ノード', icon: 'BOOK', tags: [], pointType: 'CP', pointCost: 1, effects: [] }
const structure: StructureDocument = { $schema: '', schemaVersion: 1, structureId: 'main', name: 'メイン', rootNodeId: '1000', nodes: [{ nodeId: '1000', x: 0, y: 0, z: 0 }], edges: [] }
afterEach(() => vi.restoreAllMocks())

it('rejects occupied coordinates and submits exact tenth-block coordinates', () => {
  const place = vi.fn().mockReturnValue(true)
  const close = vi.fn()
  render(<NodePlacementDialog node={node} structure={structure} disabled={false} onPlace={place} onClose={close} onDirtyChange={vi.fn()} />)
  expect(screen.getByLabelText('配置先 X')).toHaveValue('2')
  fireEvent.change(screen.getByLabelText('配置先 X'), { target: { value: '0' } })
  expect(screen.getByRole('button', { name: '構造へ配置' })).toBeDisabled()
  expect(screen.getByRole('alert')).toHaveTextContent('同じ座標')
  fireEvent.change(screen.getByLabelText('配置先 X'), { target: { value: '-1.2' } })
  fireEvent.change(screen.getByLabelText('配置先 Y'), { target: { value: '2147483647' } })
  fireEvent.change(screen.getByLabelText('配置先 Z'), { target: { value: '-2147483648' } })
  fireEvent.click(screen.getByRole('button', { name: '構造へ配置' }))
  expect(place).toHaveBeenCalledExactlyOnceWith({ nodeId: '1001', x: -1.2, y: 2147483647, z: -2147483648 })
  expect(close).toHaveBeenCalledOnce()
})

it.each(['-', '', '0.01', '2147483648', '-2147483649', 'Infinity', '1e1'])('preserves invalid coordinate %s without placing a node', (value) => {
  const place = vi.fn()
  render(<NodePlacementDialog node={node} structure={structure} disabled={false} onPlace={place} onClose={vi.fn()} onDirtyChange={vi.fn()} />)
  fireEvent.change(screen.getByLabelText('配置先 X'), { target: { value } })
  expect(screen.getByLabelText('配置先 X')).toHaveValue(value)
  expect(screen.getByRole('button', { name: '構造へ配置' })).toBeDisabled()
  expect(place).not.toHaveBeenCalled()
})

it('preserves edited coordinates when dismissal is cancelled and locks while saving', () => {
  const close = vi.fn()
  const confirm = vi.spyOn(window, 'confirm').mockReturnValue(false)
  const props = { node, structure, disabled: false, onPlace: vi.fn(), onClose: close, onDirtyChange: vi.fn() }
  const { rerender } = render(<NodePlacementDialog {...props} />)
  fireEvent.change(screen.getByLabelText('配置先 Z'), { target: { value: '4.5' } })
  fireEvent.click(screen.getByRole('button', { name: 'キャンセル' }))
  expect(confirm).toHaveBeenCalledOnce()
  expect(close).not.toHaveBeenCalled()
  expect(screen.getByLabelText('配置先 Z')).toHaveValue('4.5')
  rerender(<NodePlacementDialog {...props} disabled />)
  expect(screen.getByLabelText('配置先 Z')).toBeDisabled()
  expect(screen.getByRole('button', { name: '構造へ配置' })).toBeDisabled()
})

it('prevents adding a node already placed by another workspace action', () => {
  render(<NodePlacementDialog node={node} structure={{ ...structure, nodes: [...structure.nodes, { nodeId: node.nodeId, x: 3, y: 0, z: 0 }] }} disabled={false} onPlace={vi.fn()} onClose={vi.fn()} onDirtyChange={vi.fn()} />)
  expect(screen.getByRole('button', { name: '構造へ配置' })).toBeDisabled()
  expect(screen.getByRole('alert')).toHaveTextContent('すでに配置')
})

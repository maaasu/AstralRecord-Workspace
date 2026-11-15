import { fireEvent, render, screen, within } from '@testing-library/react'
import { afterEach, expect, it, vi } from 'vitest'
import { SpawnerCoverageInspector } from './SpawnerCoverageInspector'
import * as geometry from '../data/spawnerCoverage'
import type { PlanningCell } from '../data/spawnerPlanning'

afterEach(() => vi.restoreAllMocks())

it('密集地の全件を数え、詳細DOMを50件ずつ表示し、地図操作の再描画では再照合しない', () => {
  const cells = [{ x: 0, z: 0 } as PlanningCell]
  const sources = Array.from({ length: 500 }, (_, index) => ({ id: String(index), label: `森狼 ${index} · Lv.15`, x: 8, z: 8, radius: 64 }))
  const props = { cells, cellSize: 16, indices: [[], [], [0]], sources, unknown: 0, selectedCell: 0, disabled: false, onFocus: vi.fn(), onSource: vi.fn() }
  const covers = vi.spyOn(geometry, 'coversCellCenter')
  const { rerender } = render(<SpawnerCoverageInspector {...props} />)
  const detail = screen.getByLabelText('選択区画の出現範囲')
  expect(detail).toHaveTextContent('既知範囲500件が重複')
  expect(within(detail).getAllByRole('button')).toHaveLength(51) // 50 sources plus load-more.
  expect(covers).toHaveBeenCalledTimes(500)
  covers.mockClear()
  rerender(<SpawnerCoverageInspector {...props} onFocus={vi.fn()} />)
  expect(covers).not.toHaveBeenCalled()
  fireEvent.click(screen.getByRole('button', { name: '範囲の詳細をさらに50件表示' }))
  expect(within(detail).getAllByRole('button')).toHaveLength(101)
  expect(covers).not.toHaveBeenCalled()
  fireEvent.click(screen.getByRole('button', { name: /森狼 99 · Lv.15/ }))
  expect(props.onSource).toHaveBeenCalledWith('99')
})

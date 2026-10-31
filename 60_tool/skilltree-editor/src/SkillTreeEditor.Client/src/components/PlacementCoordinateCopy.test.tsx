import { act, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { PlacementCoordinateCopy } from './PlacementCoordinateCopy'
import type { WorldPlacement } from '../api/worldPlacementApi'

const placement: WorldPlacement = { placementId: 'one', kind: 'spawner', worldId: 'eriva', worldName: 'worlds/eriva', templateId: 'wolf', x: -1150.5, y: 107, z: 1480.25, yaw: 0, pitch: 0, enabled: true, waystone: null }
const props = { placement, worldLabel: 'エリヴァ超大陸', pendingInput: false, disabled: false }
let write: ReturnType<typeof vi.fn>
beforeEach(() => { write = vi.fn().mockResolvedValue(undefined); vi.stubGlobal('navigator', { clipboard: { writeText: write } }) })
afterEach(() => { vi.unstubAllGlobals(); vi.restoreAllMocks() })
const copy = () => screen.getByRole('button', { name: '座標をコピー' })

it('copies unrounded negative/decimal XYZ and shows success only after completion, without duplicate writes', async () => {
  let resolve!: () => void
  write.mockReturnValue(new Promise<void>(done => { resolve = done }))
  render(<PlacementCoordinateCopy {...props} />)
  expect(screen.getByText('ワールド: エリヴァ超大陸 · worlds/eriva')).toBeInTheDocument()
  const button = copy(); button.focus()
  fireEvent.click(button); fireEvent.click(button)
  expect(write).toHaveBeenCalledExactlyOnceWith('-1150.5 107 1480.25')
  expect(screen.getByRole('button', { name: '座標をコピー中…' })).toBeDisabled()
  expect(screen.queryByRole('status')).not.toBeInTheDocument()
  await act(async () => resolve())
  expect(screen.getByRole('status')).toHaveTextContent('座標をコピーしました: -1150.5 107 1480.25')
  expect(copy()).toHaveFocus()
})

it.each(['denied', 'unavailable'])('offers selectable manual coordinates when clipboard is %s, without stealing focus', async mode => {
  if (mode === 'denied') write.mockRejectedValue(new DOMException('Denied', 'NotAllowedError'))
  else vi.stubGlobal('navigator', {})
  render(<PlacementCoordinateCopy {...props} />)
  copy().focus(); fireEvent.click(copy())
  expect(await screen.findByRole('alert')).toHaveTextContent('コピーできませんでした')
  expect(copy()).toHaveFocus()
  const field = screen.getByLabelText('手動コピー用の座標') as HTMLInputElement
  expect(field).toHaveAttribute('readonly')
  expect(field).toHaveValue('-1150.5 107 1480.25')
  act(() => field.focus())
  expect(field.selectionStart).toBe(0); expect(field.selectionEnd).toBe(field.value.length)
  expect(screen.queryByRole('status')).not.toBeInTheDocument()
})

it('can retry after rejection and removes the manual field after a successful retry', async () => {
  write.mockRejectedValueOnce(new Error('denied'))
  render(<PlacementCoordinateCopy {...props} />)
  fireEvent.click(copy()); await screen.findByRole('alert')
  fireEvent.click(copy())
  expect(await screen.findByRole('status')).toHaveTextContent('座標をコピーしました')
  expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  expect(screen.queryByLabelText('手動コピー用の座標')).not.toBeInTheDocument()
  expect(write).toHaveBeenCalledTimes(2)
})

it.each([{ pendingInput: true }, { disabled: true }, { placement: { ...placement, x: NaN } }, { placement: { ...placement, y: Infinity } }])('does not copy blocked or invalid coordinates: %j', override => {
  render(<PlacementCoordinateCopy {...props} {...override} />)
  expect(copy()).toBeDisabled()
  fireEvent.click(copy())
  expect(write).not.toHaveBeenCalled()
})

it.each([{ placementId: 'two' }, { worldId: 'other' }, { worldName: 'other/world' }, { x: 10 }, { y: -64 }, { z: 20 }])('does not apply a late completion to changed context %j or after returning to the original', async change => {
  let resolve!: () => void
  write.mockReturnValue(new Promise<void>(done => { resolve = done }))
  const { rerender } = render(<PlacementCoordinateCopy {...props} />)
  fireEvent.click(copy())
  rerender(<PlacementCoordinateCopy {...props} placement={{ ...placement, ...change }} />)
  expect(screen.getByRole('button', { name: '座標をコピー中…' })).toBeDisabled()
  rerender(<PlacementCoordinateCopy {...props} />)
  await act(async () => resolve())
  expect(screen.queryByRole('status')).not.toBeInTheDocument()
  expect(copy()).toBeEnabled()
})

it('clears stale failure text when a coordinate draft starts and ignores late rejection after unmount', async () => {
  write.mockRejectedValueOnce(new Error('denied'))
  const { rerender, unmount } = render(<PlacementCoordinateCopy {...props} />)
  fireEvent.click(copy()); await screen.findByRole('alert')
  rerender(<PlacementCoordinateCopy {...props} pendingInput />)
  expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  expect(screen.queryByLabelText('手動コピー用の座標')).not.toBeInTheDocument()
  rerender(<PlacementCoordinateCopy {...props} />)
  let reject!: (error: Error) => void
  write.mockReturnValue(new Promise<void>((_, fail) => { reject = fail }))
  fireEvent.click(copy()); unmount()
  await act(async () => reject(new Error('late denial')))
  expect(screen.queryByRole('alert')).not.toBeInTheDocument()
})

import { act, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { SpawnerPlanningMap } from './SpawnerPlanningMap'
import type { PlanningSnapshot } from '../data/spawnerPlanning'

const bounds = { minX: -2400, minZ: -2400, width: 4736, height: 4688, cellSize: 16 }
const snapshot: PlanningSnapshot = {
  manifest: { schemaVersion: 2, worldId: 'eriva', worldName: 'エリヴァ', terrainFingerprint: 'terrain', exportRevision: 'revision', previewOnly: true,
    bounds, spawn: { x: 0, y: 64, z: 0 }, capturedAt: '', radiusOptions: [64], reasons: {}, limitations: [], progressionPolicy: {} },
  cells: [], regions: [],
}
const props = { snapshot, plan: null, layer: 'terrain' as const, region: '', reason: '', radii: false, active: true,
  selectedCell: null, selectedProposal: null, focus: null, onCell: vi.fn(), onProposal: vi.fn(), onPlacement: vi.fn(), onPlacePoint: vi.fn() }
let frames: Map<number, FrameRequestCallback>
const canvas = () => screen.getByLabelText(/エリヴァ全大陸の地図/)
const marker = () => screen.getByRole('img', { name: /^座標の移動先/ })
const submit = () => fireEvent.submit(screen.getByRole('form', { name: '地図の座標移動' }))
function jump(x: string, z: string) {
  fireEvent.change(screen.getByLabelText('移動先X'), { target: { value: x } })
  fireEvent.change(screen.getByLabelText('移動先Z'), { target: { value: z } })
  submit()
}
function expectCentered(x = 50, z = 50) {
  expect(parseFloat(marker().style.left)).toBeCloseTo(x)
  expect(parseFloat(marker().style.top)).toBeCloseTo(z)
}
beforeEach(() => {
  frames = new Map(); let nextFrame = 0
  vi.stubGlobal('requestAnimationFrame', vi.fn((callback: FrameRequestCallback) => { frames.set(++nextFrame, callback); return nextFrame }))
  vi.stubGlobal('cancelAnimationFrame', vi.fn((id: number) => frames.delete(id)))
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false }))
  vi.spyOn(HTMLCanvasElement.prototype, 'getContext').mockReturnValue(null)
  vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockReturnValue({ left: 0, top: 0, width: 406, height: 500 } as DOMRect)
})
afterEach(() => { vi.restoreAllMocks(); vi.unstubAllGlobals(); vi.clearAllMocks() })

it('centers negative decimal coordinates and focuses the map without placing or changing selection', () => {
  render(<SpawnerPlanningMap {...props} placing />)
  jump(' -1150.5 ', '1480.25')
  expectCentered()
  expect(marker()).toHaveAccessibleName('座標の移動先 X -1150.5 / Z 1480.25')
  expect(screen.getByText(/0\.70 px\/m/)).toBeInTheDocument()
  expect(canvas()).toHaveFocus()
  for (const callback of [props.onCell, props.onProposal, props.onPlacement, props.onPlacePoint]) expect(callback).not.toHaveBeenCalled()
})

it('preserves close zoom and supports repeated coordinates after a pan, resize, or overview', () => {
  render(<SpawnerPlanningMap {...props} />)
  jump('-100', '200')
  fireEvent.click(screen.getByRole('button', { name: '地図を拡大' }))
  expect(screen.getByText(/1\.0 px\/m/)).toBeInTheDocument()
  fireEvent.keyDown(canvas(), { key: 'ArrowRight' })
  expectCentered(143 / 406 * 100, 50)
  submit()
  expectCentered()
  expect(screen.getByText(/1\.0 px\/m/)).toBeInTheDocument()
  vi.mocked(HTMLElement.prototype.getBoundingClientRect).mockReturnValue({ left: 0, top: 0, width: 600, height: 400 } as DOMRect)
  fireEvent(window, new Event('resize'))
  submit()
  expectCentered()
  fireEvent.click(screen.getByRole('button', { name: '全大陸' }))
  submit()
  expectCentered()
  expect(screen.getByText(/0\.70 px\/m/)).toBeInTheDocument()
})

it.each([['', '0'], ['text', '0'], ['NaN', '0'], ['0', 'Infinity'], ['1e309', '0'], ['-2400.1', '0'], ['2336', '0'], ['0', '-2401'], ['0', '2288']])('rejects invalid/outside coordinates %s,%s without changing the previous target or view', (x, z) => {
  render(<SpawnerPlanningMap {...props} />)
  jump('1', '2')
  jump(x, z)
  expect(screen.getByRole('alert')).toBeInTheDocument()
  expect(screen.getByLabelText('移動先X')).toHaveAttribute('aria-invalid', 'true')
  expectCentered()
  expect(marker()).toHaveAccessibleName('座標の移動先 X 1 / Z 2')
})

it.each([['-2400', '-2400'], ['2335.999', '2287.999']])('accepts lower-inclusive and upper-exclusive bounds at %s,%s', (x, z) => {
  render(<SpawnerPlanningMap {...props} />)
  jump(x, z)
  expectCentered()
  expect(screen.queryByRole('alert')).not.toBeInTheDocument()
})

it('cancels a queued drag before jumping and clears only the marker on request', () => {
  render(<SpawnerPlanningMap {...props} />)
  Object.defineProperty(canvas(), 'setPointerCapture', { value: vi.fn() })
  fireEvent.pointerDown(canvas(), { pointerId: 1, clientX: 20, clientY: 20, button: 0 })
  fireEvent.pointerMove(canvas(), { pointerId: 1, clientX: 80, clientY: 90, buttons: 1 })
  jump('1', '2')
  act(() => { const queued = [...frames.values()]; frames.clear(); queued.forEach(callback => callback(0)) })
  expectCentered()
  fireEvent.click(screen.getByRole('button', { name: '目印を消す' }))
  expect(screen.queryByRole('img', { name: /^座標の移動先/ })).not.toBeInTheDocument()
  expect(screen.getByLabelText('移動先X')).toHaveValue('1')
  expect(screen.getByText(/0\.70 px\/m/)).toBeInTheDocument()
  expect(props.onPlacePoint).not.toHaveBeenCalled()
})

it('keeps navigation while hidden, ignores hidden submissions, and resets on world or extent changes', () => {
  const { rerender } = render(<SpawnerPlanningMap {...props} />)
  jump('1', '2')
  fireEvent.change(screen.getByLabelText('移動先X'), { target: { value: '3' } })
  fireEvent.change(screen.getByLabelText('移動先Z'), { target: { value: '4' } })
  rerender(<SpawnerPlanningMap {...props} active={false} />)
  expect(screen.getByRole('button', { name: '座標へ移動' })).toBeDisabled()
  submit()
  expect(marker()).toHaveAccessibleName('座標の移動先 X 1 / Z 2')
  rerender(<SpawnerPlanningMap {...props} />)
  submit()
  expect(marker()).toHaveAccessibleName('座標の移動先 X 3 / Z 4')
  for (const manifest of [{ ...snapshot.manifest, worldId: 'other' }, snapshot.manifest, { ...snapshot.manifest, bounds: { ...bounds, width: 4000 } }]) {
    rerender(<SpawnerPlanningMap {...props} snapshot={{ ...snapshot, manifest }} />)
    expect(screen.getByLabelText('移動先X')).toHaveValue('')
    expect(screen.getByLabelText('移動先Z')).toHaveValue('')
    expect(screen.queryByRole('img', { name: /^座標の移動先/ })).not.toBeInTheDocument()
    jump('1', '2')
  }
})

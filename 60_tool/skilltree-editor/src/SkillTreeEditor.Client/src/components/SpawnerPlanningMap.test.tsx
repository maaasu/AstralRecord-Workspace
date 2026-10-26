import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { SpawnerPlanningMap } from './SpawnerPlanningMap'
import type { PlanningSnapshot } from '../data/spawnerPlanning'

const bounds = { minX: -2400, minZ: -2400, width: 4736, height: 4688, cellSize: 16 }
const snapshot: PlanningSnapshot = {
  manifest: { schemaVersion: 2, worldId: 'eriva', terrainFingerprint: 'fingerprint', exportRevision: 'revision',
    worldName: 'エリヴァ', previewOnly: true, bounds, spawn: { x: 0, y: 64, z: 0 }, capturedAt: '',
    radiusOptions: [64], reasons: {}, limitations: [], progressionPolicy: {} },
  cells: [], regions: [],
}
const props = { snapshot, plan: null, layer: 'terrain' as const, region: '', reason: '', radii: false,
  active: true, selectedCell: null, selectedProposal: null, focus: null, onCell: vi.fn(), onProposal: vi.fn() }

beforeEach(() => {
  vi.spyOn(HTMLCanvasElement.prototype, 'getContext').mockReturnValue(null)
  vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockReturnValue({ left: 0, top: 0, width: 406, height: 500 } as DOMRect)
})
afterEach(() => { vi.restoreAllMocks(); vi.unstubAllGlobals() })

it('fits the measured narrow map, keeps zoom after resize, and falls back without a detail image', async () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false }))
  const { rerender } = render(<SpawnerPlanningMap {...props} />)
  expect(screen.getByText(/0\.08 px\/m/)).toHaveTextContent('16m区画')
  fireEvent.click(screen.getByRole('button', { name: '地図を拡大' }))
  expect(screen.getByText(/0\.12 px\/m/)).toBeInTheDocument()
  rerender(<SpawnerPlanningMap {...props} active={false} />)
  vi.mocked(HTMLElement.prototype.getBoundingClientRect).mockReturnValue({ left: 0, top: 0, width: 500, height: 500 } as DOMRect)
  rerender(<SpawnerPlanningMap {...props} active />)
  expect(screen.getByText(/0\.12 px\/m/)).toHaveTextContent('16m区画')
})

it('uses matching 1m terrain once the map is close enough and can zoom to block scale', async () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, json: async () => ({ schemaVersion: 1,
    terrainFingerprint: 'fingerprint', minX: bounds.minX, minZ: bounds.minZ, width: bounds.width, height: bounds.height, blocksPerPixel: 1 }) }))
  vi.stubGlobal('Image', class {
    naturalWidth = bounds.width
    naturalHeight = bounds.height
    onload: (() => void) | null = null
    set src(_value: string) { setTimeout(() => this.onload?.(), 0) }
  })
  render(<SpawnerPlanningMap {...props} />)
  for (let index = 0; index < 5; index++) fireEvent.click(screen.getByRole('button', { name: '地図を拡大' }))
  await waitFor(() => expect(screen.getByText(/1m地形/)).toBeInTheDocument())
  for (let index = 0; index < 12; index++) fireEvent.click(screen.getByRole('button', { name: '地図を拡大' }))
  expect(screen.getByText(/16\.0 px\/m/)).toBeInTheDocument()
})

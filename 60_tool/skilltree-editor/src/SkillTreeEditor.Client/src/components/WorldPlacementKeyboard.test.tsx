import { useState } from 'react'
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { WorldPlacementEditor } from './WorldPlacementEditor'
import { worldPlacementApi, type WorldPlacement } from '../api/worldPlacementApi'
import type { PlanningSnapshot } from '../data/spawnerPlanning'

vi.mock('../api/worldPlacementApi', () => ({ worldPlacementApi: { read: vi.fn(), save: vi.fn() } }))
const snapshot: PlanningSnapshot = {
  manifest: { schemaVersion: 2, worldId: 'eriva', worldName: 'エリヴァ', terrainFingerprint: 'a', exportRevision: 'b', previewOnly: true,
    bounds: { minX: 0, minZ: 0, width: 16, height: 16, cellSize: 16 }, spawn: { x: 0, y: 70, z: 0 }, capturedAt: '', radiusOptions: [64], reasons: {}, limitations: [], progressionPolicy: {} },
  cells: [], regions: [],
}
const entries: WorldPlacement[] = Array.from({ length: 101 }, (_, index) => ({ placementId: `point-${index}`, kind: 'spawner', worldId: 'eriva', worldName: 'worlds/eriva', templateId: 'wolf', x: 101 - index, y: 70, z: 3, yaw: 0, pitch: 0, enabled: true, waystone: null }))
const onSelected = vi.fn(), onPlacements = vi.fn(), onDirty = vi.fn()
function Harness() {
  const [selected, setSelected] = useState<string | null>(null)
  return <WorldPlacementEditor snapshot={snapshot} point={null} selectedId={selected} onSelected={(id, location) => { setSelected(id); onSelected(id, location) }} onPlacements={onPlacements} placing={false} onPlacing={vi.fn()} onDirtyChange={onDirty} />
}
beforeEach(() => {
  vi.resetAllMocks()
  vi.mocked(worldPlacementApi.read).mockResolvedValue({ schemaVersion: 1, revision: 'rev', placements: entries })
  vi.stubGlobal('fetch', vi.fn(async () => ({ ok: true, json: async () => ({ spawners: [{ id: 'wolf', name: '森林狼 Lv.15', radiusMeters: 64, levels: [15] }], npcs: [], worlds: [] }) })))
})
afterEach(() => { vi.unstubAllGlobals(); vi.restoreAllMocks() })
const search = () => screen.getByRole('textbox', { name: '保存済み配置を検索' })
const list = () => screen.getByLabelText(/^(表示条件に合う配置の一覧|一括選択した配置の一覧)$/)
const rows = () => within(list()).getAllByRole('button')
const row = (id: string) => list().querySelector<HTMLButtonElement>(`button[data-placement-id="${id}"]`)!
const key = (value: string) => fireEvent.keyDown(document.activeElement!, { key: value })
async function open() { render(<Harness />); await screen.findByText(/101 件を表示/); search().focus() }

describe('saved placement keyboard navigation', () => {
  it('focuses from search, crosses page boundaries both ways and clamps Home/End without selecting or fetching', async () => {
    await open()
    const notifications = onPlacements.mock.calls.length
    key('ArrowDown')
    expect(row('point-0')).toHaveFocus()
    key('ArrowUp'); expect(row('point-0')).toHaveFocus()
    row('point-49').focus(); key('ArrowDown')
    expect(row('point-50')).toHaveFocus()
    expect(rows()).toHaveLength(50)
    key('ArrowUp'); expect(row('point-49')).toHaveFocus()
    key('End'); expect(row('point-100')).toHaveFocus()
    expect(rows()).toHaveLength(1)
    key('ArrowDown'); expect(row('point-100')).toHaveFocus()
    key('Home'); expect(row('point-0')).toHaveFocus()
    expect(rows()).toHaveLength(50)
    expect(onSelected).not.toHaveBeenCalled()
    expect(onPlacements).toHaveBeenCalledTimes(notifications)
    expect(onDirty).toHaveBeenLastCalledWith(false)
    expect(worldPlacementApi.read).toHaveBeenCalledTimes(1)
    expect(worldPlacementApi.save).not.toHaveBeenCalled()
  })

  it('returns to search with Escape and reenters the current page without changing query or page', async () => {
    await open()
    fireEvent.change(search(), { target: { value: '森林狼' } })
    key('Enter'); key('End'); key('Escape')
    expect(search()).toHaveFocus()
    expect(search()).toHaveValue('森林狼')
    expect(screen.getByText('一覧 101–101 / 101 件 · 3 / 3 ページ')).toBeInTheDocument()
    key('Enter'); expect(row('point-100')).toHaveFocus()
    expect(onSelected).not.toHaveBeenCalled()
  })

  it('uses filtered and sorted order and the bulk-selected-only list without changing the selection set', async () => {
    await open()
    fireEvent.click(screen.getByLabelText(/\(point-0\) を一括選択$/))
    search().focus(); key('Enter'); key('End')
    fireEvent.click(screen.getByLabelText(/\(point-100\) を一括選択$/))
    fireEvent.change(screen.getByLabelText('一覧の並び順'), { target: { value: 'distance' } })
    search().focus(); key('Enter')
    expect(row('point-100')).toHaveFocus()
    key('ArrowDown'); expect(row('point-99')).toHaveFocus()
    key('End'); expect(row('point-0')).toHaveFocus()
    fireEvent.click(screen.getByRole('button', { name: /^一括操作/ }))
    fireEvent.click(screen.getByLabelText('一括選択だけを一覧で確認（表示条件外を含む）'))
    fireEvent.change(search(), { target: { value: 'point-100' } })
    search().focus(); key('Enter'); expect(row('point-100')).toHaveFocus()
    key('End'); expect(row('point-0')).toHaveFocus()
    expect(rows()).toHaveLength(2)
    expect(screen.getByRole('button', { name: /^一括操作/ })).toHaveTextContent('合計 2 件 · 条件内 1 件 · 条件外 1 件')
    expect(screen.getByLabelText(/\(point-0\) を一括選択$/)).toBeChecked()
    expect(screen.getByLabelText(/\(point-100\) を一括選択$/)).toBeChecked()
    fireEvent.click(screen.getByLabelText('一括選択だけを一覧で確認（表示条件外を含む）'))
    search().focus(); key('Enter'); key('End')
    expect(row('point-100')).toHaveFocus()
    expect(rows()).toHaveLength(1)
    expect(onSelected).not.toHaveBeenCalled()
  })

  it('uses native Enter/Space to select and preserves unfinished numeric input while navigating', async () => {
    const user = userEvent.setup()
    await open()
    key('Enter'); await user.keyboard('{Enter}')
    expect(onSelected).toHaveBeenLastCalledWith('point-0', expect.objectContaining({ x: 101 }))
    fireEvent.change(screen.getByLabelText('X'), { target: { value: '-' } })
    search().focus(); key('ArrowDown'); key('End')
    expect(onSelected).toHaveBeenCalledTimes(1)
    expect(screen.getByLabelText('X')).toHaveValue('-')
    await user.keyboard(' ')
    expect(onSelected).toHaveBeenLastCalledWith('point-100', expect.objectContaining({ x: 1 }))
    key('Home'); await user.keyboard('{Enter}')
    expect(screen.getByLabelText('X')).toHaveValue('-')
    expect(onDirty).toHaveBeenLastCalledWith(true)
    expect(worldPlacementApi.save).not.toHaveBeenCalled()
  })

  it('leaves checkbox Space and numeric Enter/Escape to their original controls', async () => {
    const user = userEvent.setup()
    await open()
    const checkbox = screen.getByLabelText(/\(point-0\) を一括選択$/)
    checkbox.focus(); await user.keyboard(' ')
    expect(checkbox).toBeChecked()
    expect(onSelected).not.toHaveBeenCalled()
    key('End'); expect(checkbox).toHaveFocus()
    search().focus(); key('Enter'); await user.keyboard('{Enter}')
    const x = screen.getByLabelText('X')
    x.focus(); fireEvent.change(x, { target: { value: '222' } }); key('Enter')
    expect(x).toHaveFocus()
    expect(x).toHaveValue('222')
    fireEvent.change(x, { target: { value: '-' } }); key('Escape')
    expect(x).toHaveFocus()
    expect(x).toHaveValue('222')
    expect(worldPlacementApi.save).not.toHaveBeenCalled()
  })

  it('ignores IME and modified keys on search and rows, and keeps focus for an empty result', async () => {
    await open()
    for (const flags of [{ isComposing: true }, { keyCode: 229 }, { ctrlKey: true }, { metaKey: true }, { altKey: true }, { shiftKey: true }]) {
      for (const value of ['Enter', 'ArrowDown']) expect(fireEvent.keyDown(search(), { key: value, ...flags })).toBe(true)
      expect(search()).toHaveFocus()
    }
    key('Enter')
    const first = row('point-0')
    for (const flags of [{ isComposing: true }, { keyCode: 229 }, { ctrlKey: true }, { metaKey: true }, { altKey: true }, { shiftKey: true }]) {
      for (const value of ['ArrowDown', 'ArrowUp', 'Home', 'End', 'Escape']) expect(fireEvent.keyDown(first, { key: value, ...flags })).toBe(true)
      expect(first).toHaveFocus()
    }
    key('Escape'); fireEvent.change(search(), { target: { value: '一致しない配置' } })
    expect(key('Enter')).toBe(true)
    expect(key('ArrowDown')).toBe(true)
    expect(search()).toHaveFocus()
    expect(onSelected).not.toHaveBeenCalled()
  })

  it('does not navigate while a save or a bulk confirmation is pending', async () => {
    await open()
    fireEvent.click(row('point-0'))
    fireEvent.click(screen.getByLabelText('有効'))
    let reject!: (error: Error) => void
    vi.mocked(worldPlacementApi.save).mockReturnValue(new Promise((_resolve, fail) => { reject = fail }))
    fireEvent.click(screen.getByRole('button', { name: 'Dev に保存' }))
    await waitFor(() => expect(worldPlacementApi.save).toHaveBeenCalledOnce())
    search().focus(); key('ArrowDown'); expect(search()).toHaveFocus()
    row('point-0').focus(); key('End'); expect(row('point-0')).toHaveFocus()
    await act(async () => reject(new Error('保存失敗')))
    fireEvent.click(screen.getByLabelText(/\(point-0\) を一括選択$/))
    fireEvent.click(screen.getByRole('button', { name: /^一括操作/ }))
    fireEvent.click(screen.getByRole('button', { name: '選択した 1 件を削除' }))
    const modal = screen.getByRole('dialog')
    const focused = document.activeElement
    fireEvent.keyDown(search(), { key: 'ArrowDown' })
    fireEvent.keyDown(row('point-0'), { key: 'End' })
    expect(document.activeElement).toBe(focused)
    expect(modal).toContainElement(document.activeElement as HTMLElement)
  })
})

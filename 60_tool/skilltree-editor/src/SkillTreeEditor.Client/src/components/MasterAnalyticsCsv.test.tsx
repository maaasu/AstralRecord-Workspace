import { fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { MasterAnalytics } from './MasterAnalytics'
import { downloadCsv } from '../data/masterAnalytics'
import { equipmentCsvRows } from '../data/equipmentCsv'
import type { AnalyticsCatalog, MasterObject } from '../data/masterAnalytics'

vi.mock('../data/masterAnalytics', async original => ({ ...await original<typeof import('../data/masterAnalytics')>(), downloadCsv: vi.fn() }))
vi.mock('../data/equipmentCsv', async original => {
  const actual = await original<typeof import('../data/equipmentCsv')>()
  return { ...actual, equipmentCsvRows: vi.fn(actual.equipmentCsvRows) }
})
afterEach(() => { vi.unstubAllGlobals(); vi.restoreAllMocks(); vi.clearAllMocks() })

function gear(id: string, stats: MasterObject[] = [{ status: 'MELEE_ATTACK', value: 10 }]): AnalyticsCatalog['equipment'][number] {
  return { path: `item/test/${id}.yml`, progression: 3, content: { id, name: id, equipment: { slot: 'MAIN', tag: 'SWORD', requiredLevel: 10, stats,
    enhance: { maxLevel: 1, levels: [{ level: 1, statIncrease: [{ status: 'MELEE_ATTACK', value: 5 }] }] },
  } } }
}
function input(name: string, value: string) { fireEvent.change(screen.getByLabelText(name), { target: { value } }) }
function exportCsv() {
  fireEvent.click(screen.getByRole('button', { name: '比較表をCSV出力' }))
  return vi.mocked(downloadCsv).mock.lastCall![1]
}

describe('equipment CSV export context', () => {
  it('exports all compared pages and hidden columns only when requested, preserving sort and selection', async () => {
    const random = { offsetMin: -2, offsetMax: 2, step: 1, zeroChance: 0.1 }
    const equipment = Array.from({ length: 51 }, (_, i) => gear(`gear-${String(i).padStart(2, '0')}`, [
      { status: 'MELEE_ATTACK', value: 10, random }, { status: 'MAX_HEALTH', value: 100 },
    ]))
    const fetchMock = vi.fn(async () => ({ ok: true, json: async () => ({ equipment, classes: [], diagnostics: [] }) }))
    vi.stubGlobal('fetch', fetchMock)
    render(<MasterAnalytics />)
    await screen.findByRole('table')
    input('強化Lv', '99') // The requested level is exported, values are still capped per item.
    input('並び順', 'id')
    fireEvent.click(screen.getByRole('button', { name: '差のあるステータスだけ' }))
    fireEvent.click(screen.getByRole('button', { name: '次の50件' }))
    expect(equipmentCsvRows).not.toHaveBeenCalled()
    expect(screen.getByText(/差のあるステータスはありません/)).toBeInTheDocument()
    const csv = exportCsv()
    expect(csv).toHaveLength(52)
    expect(csv[0]).toEqual(['ID', '名前', 'スロット', 'タグ', '要求Lv', '進行度', 'MAX_HEALTH 下限', 'MAX_HEALTH 上限', 'MELEE_ATTACK 下限', 'MELEE_ATTACK 上限', 'MAX_HEALTH 期待値', 'MELEE_ATTACK 期待値', '指定強化Lv', 'ソースパス'])
    expect(csv[1]).toEqual(['gear-50', 'gear-50', 'MAIN', 'SWORD', 10, 3, 100, 100, 5, 17, undefined, 14, 99, 'item/test/gear-50.yml'])
    expect(csv[51][0]).toBe('gear-00')
    expect(screen.getByText('2 / 2ページ')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('checkbox', { name: 'gear-00を比較に選択' }))
    input('名前・ID', 'missing')
    expect(equipmentCsvRows).toHaveBeenCalledTimes(1)
    const selected = exportCsv()
    expect(selected).toHaveLength(2)
    expect(selected[1][0]).toBe('gear-00')
    expect(selected[1].slice(-2)).toEqual([99, 'item/test/gear-00.yml'])
    expect(fetchMock).toHaveBeenCalledTimes(1)
    expect(screen.getByRole('button', { name: '比較表をCSV出力' })).toHaveAccessibleDescription(/期待値（未定義は空欄）、指定強化Lv、40_filebaseからのソースパス/)
  })

  it('retains zero expectation, leaves absent expectation empty, and exports updated catalog values', async () => {
    const zero = gear('zero', [{ status: 'MELEE_ATTACK', value: 10, random: { offsetMin: -2, offsetMax: 2, step: 1, zeroChance: 1 } }])
    let catalog: AnalyticsCatalog = { equipment: [gear('fixed'), zero], classes: [], diagnostics: [] }
    vi.stubGlobal('fetch', vi.fn(async () => ({ ok: true, json: async () => catalog })))
    render(<MasterAnalytics />)
    await screen.findByRole('table')
    const initial = exportCsv()
    expect(initial[1].slice(-3)).toEqual([undefined, 0, 'item/test/fixed.yml'])
    expect(initial[2].slice(-3)).toEqual([0, 0, 'item/test/zero.yml'])
    fireEvent.click(screen.getByRole('checkbox', { name: 'zeroを比較に選択' }))
    const updated = gear('zero', [{ status: 'MELEE_ATTACK', value: 20, random: { offsetMin: -2, offsetMax: 2, step: 1, zeroChance: 0.25 } }])
    catalog = { ...catalog, equipment: [updated] }
    fireEvent.click(screen.getByRole('button', { name: 'マスター再読込' }))
    await screen.findByRole('cell', { name: '0～22 (期待値 15)' })
    expect(exportCsv()[1].slice(-3)).toEqual([15, 0, 'item/test/zero.yml'])
    fireEvent.click(screen.getByRole('button', { name: /^選択した装備（/ }))
    fireEvent.click(screen.getByRole('checkbox', { name: 'zeroを比較に選択' }))
    const calls = vi.mocked(equipmentCsvRows).mock.calls.length
    expect(screen.getByRole('button', { name: '比較表をCSV出力' })).toBeDisabled()
    fireEvent.click(screen.getByRole('button', { name: '比較表をCSV出力' }))
    expect(equipmentCsvRows).toHaveBeenCalledTimes(calls)
  })
})

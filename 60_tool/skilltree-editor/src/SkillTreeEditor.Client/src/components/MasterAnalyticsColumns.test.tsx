import { fireEvent, render, screen, within } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { MasterAnalytics } from './MasterAnalytics'
import { downloadCsv } from '../data/masterAnalytics'
import { differingEquipmentStatuses } from '../data/equipmentComparisonColumns'
import type { AnalyticsCatalog, MasterObject } from '../data/masterAnalytics'

vi.mock('../data/masterAnalytics', async importOriginal => ({ ...await importOriginal<typeof import('../data/masterAnalytics')>(), downloadCsv: vi.fn() }))
vi.mock('../data/equipmentComparisonColumns', async importOriginal => {
  const actual = await importOriginal<typeof import('../data/equipmentComparisonColumns')>()
  return { ...actual, differingEquipmentStatuses: vi.fn(actual.differingEquipmentStatuses) }
})
afterEach(() => { vi.unstubAllGlobals(); vi.restoreAllMocks(); vi.clearAllMocks() })

function gear(id: string, stats: MasterObject[] = [{ status: 'MELEE_ATTACK', value: 10 }, { status: 'MAX_HEALTH', value: 100 }], enhance?: MasterObject): AnalyticsCatalog['equipment'][number] {
  return { path: `item/${id}.yml`, progression: null, content: { id, name: id, equipment: { requiredLevel: 10, slot: 'MAIN', tag: 'SWORD', stats, enhance } } }
}
const toggle = () => screen.getByRole('button', { name: '差のあるステータスだけ' })
const headers = () => within(screen.getByRole('table')).getAllByRole('columnheader').map(cell => cell.textContent)
const input = (name: string, value: string) => fireEvent.change(screen.getByLabelText(name), { target: { value } })
const select = (id: string) => fireEvent.click(screen.getByRole('checkbox', { name: `${id}を比較に選択` }))
const list = (selected: boolean) => fireEvent.click(screen.getByRole('button', { name: selected ? /^選択した装備（/ : /^条件に一致する装備（/ }))
function exportRows() {
  fireEvent.click(screen.getByRole('button', { name: '比較表をCSV出力' }))
  return vi.mocked(downloadCsv).mock.lastCall![1]
}
async function setup(equipment: AnalyticsCatalog['equipment']) {
  const catalog: AnalyticsCatalog = { equipment, classes: [], diagnostics: [] }
  const fetchMock = vi.fn(async () => ({ ok: true, json: async () => catalog }))
  vi.stubGlobal('fetch', fetchMock)
  render(<MasterAnalytics />)
  await screen.findByRole('table')
  return { catalog, fetchMock }
}

describe('equipment difference columns', () => {
  it('compares across pages, keeps page/focus and CSV, and reuses results for navigation and sorting', async () => {
    const equipment = Array.from({ length: 51 }, (_, i) => gear(`gear-${String(i).padStart(2, '0')}`, [
      { status: 'MELEE_ATTACK', value: i === 50 ? 20 : 10 }, { status: 'MAX_HEALTH', value: 100 },
    ]))
    const { fetchMock } = await setup(equipment)
    expect(toggle()).toHaveAttribute('aria-pressed', 'false')
    expect(differingEquipmentStatuses).not.toHaveBeenCalled()
    const csv = exportRows()
    fireEvent.click(toggle())
    expect(headers()).toContain('近接攻撃力')
    expect(headers()).not.toContain('最大HP')
    expect(screen.getByText('ステータス列 1 / 2項目（一覧全51件で比較）')).toBeInTheDocument()
    expect(screen.getAllByRole('checkbox')).toHaveLength(50)
    fireEvent.click(screen.getByRole('button', { name: '次の50件' }))
    expect(headers()).toContain('近接攻撃力')
    expect(differingEquipmentStatuses).toHaveBeenCalledTimes(1)
    toggle().focus(); fireEvent.click(toggle())
    expect(toggle()).toHaveFocus()
    expect(screen.getByText('2 / 2ページ')).toBeInTheDocument()
    expect(exportRows()).toEqual(csv)
    fireEvent.click(toggle())
    input('並び順', 'name'); input('並べる方向', 'asc'); input('グラフのステータス', 'MAX_HEALTH')
    expect(differingEquipmentStatuses).toHaveBeenCalledTimes(2)
    expect(fetchMock).toHaveBeenCalledTimes(1)
  })

  it('uses the full selected list including out-of-filter equipment and keeps empty selection empty', async () => {
    await setup([gear('a'), gear('b'), gear('c', [{ status: 'MELEE_ATTACK', value: 30 }])])
    select('a'); select('b')
    fireEvent.click(toggle()); list(true)
    expect(headers()).toHaveLength(5)
    expect(screen.getByText(/差のあるステータスはありません/)).toBeInTheDocument()
    input('名前・ID', 'c')
    expect(screen.getByText(/現在の条件外 2件/)).toBeInTheDocument()
    expect(headers()).toHaveLength(5)
    expect(exportRows().slice(1).map(row => row[0])).toEqual(['a', 'b'])
    select('b')
    expect(headers()).toContain('近接攻撃力') // One selected item shows all columns.
    expect(screen.queryByText(/差のあるステータスはありません/)).not.toBeInTheDocument()
    select('a')
    expect(screen.getByRole('button', { name: '比較表をCSV出力' })).toBeDisabled()
    expect(screen.getByText('ステータス列 0 / 0項目')).toBeInTheDocument()
    list(false)
    expect(headers()).toContain('近接攻撃力') // One matching candidate.
    input('名前・ID', 'missing')
    expect(screen.getByText(/条件に一致する装備がありません/)).toBeInTheDocument()
    expect(toggle()).toHaveAttribute('aria-pressed', 'true')
  })

  it('retains zero versus missing and expected-value differences that are displayed in cells', async () => {
    const random = { offsetMin: -2, offsetMax: 2, step: 1 }
    await setup([
      gear('a', [{ status: 'MAX_HEALTH', value: 0 }, { status: 'MELEE_ATTACK', value: 10, random: { ...random, zeroChance: 0.1 } }]),
      gear('b', [{ status: 'MELEE_ATTACK', value: 10, random: { ...random, zeroChance: 0.5 } }]),
    ])
    fireEvent.click(toggle())
    expect(headers()).toEqual(expect.arrayContaining(['最大HP', '近接攻撃力']))
    const table = within(screen.getByRole('table'))
    expect(table.getByRole('cell', { name: '0' })).toBeInTheDocument()
    expect(table.getByRole('cell', { name: '—' })).toBeInTheDocument()
    expect(table.getByRole('cell', { name: '0～12 (期待値 9)' })).toBeInTheDocument()
    expect(table.getByRole('cell', { name: '0～12 (期待値 5)' })).toBeInTheDocument()
  })

  it('updates after enhancement and reload and retains the toggle across analysis tabs', async () => {
    const { catalog, fetchMock } = await setup([
      gear('a', undefined, { maxLevel: 1, levels: [{ level: 1, statIncrease: [{ status: 'MELEE_ATTACK', value: 5 }] }] }), gear('b'),
    ])
    fireEvent.click(toggle())
    expect(headers()).toHaveLength(5)
    input('強化Lv', '1')
    expect(headers()).toContain('近接攻撃力')
    select('a'); select('b'); list(true)
    fireEvent.click(screen.getByRole('tab', { name: 'クラス成長' }))
    fireEvent.click(screen.getByRole('tab', { name: '装備を比較' }))
    expect(toggle()).toHaveAttribute('aria-pressed', 'true')
    catalog.equipment = [gear('a'), gear('b')]
    fireEvent.click(screen.getByRole('button', { name: 'マスター再読込' }))
    await vi.waitFor(() => expect(headers()).toHaveLength(5))
    expect(screen.getAllByRole('checkbox').every(box => (box as HTMLInputElement).checked)).toBe(true)
    expect(fetchMock).toHaveBeenCalledTimes(2)
    expect(exportRows()[0]).toEqual(expect.arrayContaining(['MAX_HEALTH 下限', 'MELEE_ATTACK 上限']))
    expect(exportRows().slice(1).map(row => row[0])).toEqual(['a', 'b'])
  })
})

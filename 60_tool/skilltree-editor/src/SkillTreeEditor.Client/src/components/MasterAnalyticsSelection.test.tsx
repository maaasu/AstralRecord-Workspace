import { fireEvent, render, screen, within } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { MasterAnalytics } from './MasterAnalytics'
import { downloadCsv, equipmentRows } from '../data/masterAnalytics'
import type { AnalyticsCatalog } from '../data/masterAnalytics'

vi.mock('../data/masterAnalytics', async importOriginal => {
  const actual = await importOriginal<typeof import('../data/masterAnalytics')>()
  return { ...actual, downloadCsv: vi.fn(), equipmentRows: vi.fn(actual.equipmentRows) }
})

afterEach(() => { vi.unstubAllGlobals(); vi.restoreAllMocks(); vi.clearAllMocks() })

function equipment(id: string, name = id, level = 10, slot = 'MAIN', tag = 'SWORD', status = 'MELEE_ATTACK', value = 12): AnalyticsCatalog['equipment'][number] {
  return { path: `item/${id}.yml`, progression: null, content: { id, name, equipment: {
    requiredLevel: level, slot, tag, stats: [{ status, value }],
    enhance: { maxLevel: 1, levels: [{ level: 1, statIncrease: [{ status, value: 5 }] }] },
  } } }
}
function setInput(name: string, value: string) { fireEvent.change(screen.getByLabelText(name), { target: { value } }) }
function select(name: string) { fireEvent.click(screen.getByRole('checkbox', { name: `${name}を比較に選択` })) }
function listButton(selected: boolean) { return screen.getByRole('button', { name: selected ? /^選択した装備（/ : /^条件に一致する装備（/ }) }
function exportIds() {
  fireEvent.click(screen.getByRole('button', { name: '比較表をCSV出力' }))
  return vi.mocked(downloadCsv).mock.lastCall![1].slice(1).map(row => row[0])
}

describe('equipment comparison selection across search conditions', () => {
  it('compares equipment found under different names, levels, slots and tags, including CSV and table columns', async () => {
    const catalog: AnalyticsCatalog = { equipment: [equipment('staff', '銀の杖', 40, 'OFF', 'WAND', 'MAGIC_ATTACK', 31), equipment('sword', '&6黄金の剣'), equipment('shield', '盾')], classes: [], diagnostics: [] }
    const fetchMock = vi.fn(async () => ({ ok: true, json: async () => catalog }))
    vi.stubGlobal('fetch', fetchMock)
    render(<MasterAnalytics />)
    await screen.findByRole('table')
    setInput('名前・ID', '黄金')
    select('黄金の剣')
    setInput('名前・ID', '杖')
    setInput('要求Lvの中心', '40'); setInput('前後のLv幅', '0')
    setInput('スロット', 'OFF'); setInput('装備タグ', 'WAND')
    expect(screen.getByText(/1件を比較/)).toHaveTextContent('条件一致 1件')
    expect(screen.getByText(/現在の条件外 1件/)).toBeInTheDocument()
    expect(screen.getByLabelText('近接攻撃力の範囲比較')).toHaveTextContent('黄金の剣')
    expect(within(screen.getByRole('table')).getByRole('columnheader', { name: '魔法攻撃力' })).toBeInTheDocument()
    select('銀の杖')
    expect(exportIds()).toEqual(['sword', 'staff'])
    fireEvent.click(listButton(true))
    expect(listButton(true)).toHaveAttribute('aria-pressed', 'true')
    expect(within(screen.getByRole('table')).getAllByRole('rowheader').map(row => row.textContent)).toEqual([expect.stringContaining('黄金の剣'), expect.stringContaining('銀の杖')])
    setInput('名前・ID', '一致しない名前')
    expect(screen.getByText(/2件を比較/)).toHaveTextContent('条件一致 0件')
    expect(screen.getAllByRole('checkbox')).toHaveLength(2)
    expect(exportIds()).toEqual(['sword', 'staff'])
    setInput('名前・ID', '杖')
    screen.getByRole('checkbox', { name: '黄金の剣を比較に選択' }).focus()
    select('黄金の剣')
    expect(screen.getByRole('checkbox', { name: '銀の杖を比較に選択' })).toHaveFocus()
    expect(exportIds()).toEqual(['staff'])
    select('銀の杖')
    expect(listButton(true)).toHaveFocus()
    expect(screen.getByText(/0件を比較/)).toHaveTextContent('条件一致 1件')
    expect(screen.getByRole('button', { name: '比較表をCSV出力' })).toBeDisabled()
    expect(screen.getByText(/表示できる選択装備がないため比較は0件/)).toBeInTheDocument()
    fireEvent.click(listButton(false))
    expect(exportIds()).toEqual(['staff'])
    expect(fetchMock).toHaveBeenCalledTimes(1)
    expect(equipmentRows).toHaveBeenCalledTimes(2) // Empty catalog, then loaded catalog; selection and filters reuse rows.
  })

  it('clears all selection explicitly and keeps it across analysis tabs', async () => {
    const catalog: AnalyticsCatalog = { equipment: [equipment('sword', '剣'), equipment('shield', '盾')], classes: [], diagnostics: [] }
    vi.stubGlobal('fetch', vi.fn(async () => ({ ok: true, json: async () => catalog })))
    render(<MasterAnalytics />)
    await screen.findByRole('table')
    fireEvent.click(listButton(true))
    expect(screen.getByText(/0件を比較/)).toHaveTextContent('条件一致 2件')
    expect(screen.getByRole('button', { name: '比較表をCSV出力' })).toBeDisabled()
    fireEvent.click(listButton(false))
    select('剣')
    fireEvent.click(listButton(true))
    fireEvent.click(screen.getByRole('tab', { name: 'クラス成長' }))
    fireEvent.click(screen.getByRole('tab', { name: '装備を比較' }))
    expect(listButton(true)).toHaveAttribute('aria-pressed', 'true')
    expect(exportIds()).toEqual(['sword'])
    fireEvent.click(screen.getByRole('button', { name: '選択を解除' }))
    expect(listButton(false)).toHaveAttribute('aria-pressed', 'true')
    expect(screen.getByText(/2件を比較/)).toBeInTheDocument()
    expect(screen.getAllByRole('checkbox').every(checkbox => !(checkbox as HTMLInputElement).checked)).toBe(true)
    expect(exportIds()).toEqual(['shield', 'sword'])
  })

  it('uses updated enhancement and reloaded values while keeping missing selections explicit', async () => {
    let catalog: AnalyticsCatalog = { equipment: [equipment('sword', '剣'), equipment('staff', '杖'), equipment('shield', '盾')], classes: [], diagnostics: [] }
    let fail = false
    const fetchMock = vi.fn(async () => { if (fail) throw new Error('offline'); return { ok: true, json: async () => catalog } })
    vi.stubGlobal('fetch', fetchMock)
    render(<MasterAnalytics />)
    await screen.findByRole('table')
    select('剣'); select('杖')
    fireEvent.click(listButton(true))
    setInput('強化Lv', '1')
    expect(within(screen.getByRole('table')).getAllByRole('cell', { name: '17' })).toHaveLength(2)
    catalog = { ...catalog, equipment: [equipment('sword', '新しい剣', 10, 'MAIN', 'SWORD', 'MELEE_ATTACK', 50), equipment('shield', '盾')] }
    fireEvent.click(screen.getByRole('button', { name: 'マスター再読込' }))
    await screen.findByRole('checkbox', { name: '新しい剣を比較に選択' })
    expect(screen.getByText(/うち 1件が現在のマスターに見つかりません/)).toBeInTheDocument()
    expect(within(screen.getByRole('table')).getByRole('cell', { name: '55' })).toBeInTheDocument()
    expect(exportIds()).toEqual(['sword'])
    fireEvent.click(screen.getByRole('button', { name: '見つからない選択を解除' }))
    expect(screen.queryByText(/現在のマスターに見つかりません/)).not.toBeInTheDocument()
    expect(exportIds()).toEqual(['sword'])
    fail = true
    fireEvent.click(screen.getByRole('button', { name: 'マスター再読込' }))
    await screen.findByRole('alert')
    expect(exportIds()).toEqual(['sword'])
    expect(within(screen.getByRole('table')).getByRole('cell', { name: '55' })).toBeInTheDocument()
    fail = false
    catalog = { ...catalog, equipment: [equipment('shield', '盾')], diagnostics: [{ path: 'item/sword.yml', message: 'Invalid YAML fixture' }] }
    fireEvent.click(screen.getByRole('button', { name: 'マスター再読込' }))
    await screen.findByText(/Invalid YAML fixture/)
    fireEvent.click(listButton(false))
    expect(screen.getByText(/0件を比較/)).toHaveTextContent('条件一致 1件')
    expect(screen.getByRole('button', { name: '比較表をCSV出力' })).toBeDisabled()
    expect(screen.getByText(/うち 1件が現在のマスターに見つかりません/)).toBeInTheDocument()
    fireEvent.click(listButton(true))
    fireEvent.click(screen.getByRole('button', { name: '見つからない選択を解除' }))
    expect(screen.getByText(/0件を比較/)).toBeInTheDocument()
    fireEvent.click(listButton(false))
    expect(exportIds()).toEqual(['shield'])
    expect(fetchMock).toHaveBeenCalledTimes(4)
  })

  it('bounds both lists to 50 rows, retains selections across pages, and clamps after removal', async () => {
    const catalog: AnalyticsCatalog = { equipment: Array.from({ length: 120 }, (_, i) => equipment(`gear-${String(i).padStart(3, '0')}`)), classes: [], diagnostics: [] }
    vi.stubGlobal('fetch', vi.fn(async () => ({ ok: true, json: async () => catalog })))
    render(<MasterAnalytics />)
    await screen.findByRole('table')
    expect(screen.getAllByRole('checkbox')).toHaveLength(50)
    for (const checkbox of screen.getAllByRole('checkbox')) fireEvent.click(checkbox)
    fireEvent.click(screen.getByRole('button', { name: '次の50件' }))
    select('gear-050')
    expect(exportIds()).toHaveLength(51)
    expect(screen.getByLabelText('近接攻撃力の範囲比較').children).toHaveLength(30)
    fireEvent.click(listButton(true))
    expect(screen.getByText('1 / 2ページ')).toBeInTheDocument()
    expect(screen.getAllByRole('checkbox')).toHaveLength(50)
    fireEvent.click(screen.getByRole('button', { name: '次の50件' }))
    expect(screen.getAllByRole('checkbox')).toHaveLength(1)
    select('gear-050')
    expect(listButton(true)).toHaveFocus()
    expect(screen.getByText('1 / 1ページ')).toBeInTheDocument()
    expect(screen.getAllByRole('checkbox')).toHaveLength(50)
    expect(exportIds()).toHaveLength(50)
    fireEvent.click(listButton(false))
    expect(screen.getByText('1 / 3ページ')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '次の50件' }))
    setInput('名前・ID', 'gear') // Still 120 matches: reset must not rely on clamping.
    expect(screen.getByText('1 / 3ページ')).toBeInTheDocument()
    for (const [name, value] of [['スロット', 'MAIN'], ['装備タグ', 'SWORD'], ['要求Lvの中心', '11'], ['前後のLv幅', '11']]) {
      fireEvent.click(screen.getByRole('button', { name: '次の50件' }))
      setInput(name, value)
      expect(screen.getByText('1 / 3ページ')).toBeInTheDocument()
    }
    expect(equipmentRows).toHaveBeenCalledTimes(2)
  })
})

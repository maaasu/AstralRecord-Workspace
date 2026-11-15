import { fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { MasterAnalytics } from './MasterAnalytics'
import { downloadCsv } from '../data/masterAnalytics'
import type { AnalyticsCatalog } from '../data/masterAnalytics'

vi.mock('../data/masterAnalytics', async importOriginal => ({ ...await importOriginal<typeof import('../data/masterAnalytics')>(), downloadCsv: vi.fn() }))
afterEach(() => { vi.unstubAllGlobals(); vi.clearAllMocks() })

function equipment(id: string, name: string, value = 12): AnalyticsCatalog['equipment'][number] {
  return { path: `item/${id}.yml`, progression: null, content: { id, name, equipment: { requiredLevel: 10, slot: 'MAIN', tag: 'SWORD', stats: [{ status: 'MELEE_ATTACK', value }] } } }
}
const gear = (count: number) => Array.from({ length: count }, (_, index) => equipment(`gear-${String(index).padStart(3, '0')}`, `銀の剣${index}`, index))
const input = (label: string, value: string) => fireEvent.change(screen.getByLabelText(label), { target: { value } })
const add = () => screen.getByRole('button', { name: /^条件一致を比較に追加/ })
const remove = () => screen.getByRole('button', { name: /^条件一致の選択を解除/ })
const selectedView = () => screen.getByRole('button', { name: /^選択した装備（/ })
const candidateView = () => screen.getByRole('button', { name: /^条件に一致する装備（/ })
function exportIds() {
  fireEvent.click(screen.getByRole('button', { name: '比較表をCSV出力' }))
  return vi.mocked(downloadCsv).mock.lastCall![1].slice(1).map(row => row[0])
}
async function setup(equipment: AnalyticsCatalog['equipment']) {
  let catalog: AnalyticsCatalog = { equipment, classes: [], diagnostics: [] }
  const fetcher = vi.fn(async () => ({ ok: true, json: async () => catalog }))
  vi.stubGlobal('fetch', fetcher)
  render(<MasterAnalytics />)
  await screen.findByRole('table')
  return { fetcher, reload: (equipment: AnalyticsCatalog['equipment']) => { catalog = { ...catalog, equipment }; fireEvent.click(screen.getByRole('button', { name: 'マスター再読込' })) } }
}

describe('equipment comparison bulk selection', () => {
  it('adds all matching pages once, preserving existing selection order and the 50-row rendering limit', async () => {
    const items = gear(120)
    const { fetcher } = await setup(items)
    fireEvent.click(screen.getByRole('button', { name: '次の50件' }))
    fireEvent.click(screen.getByRole('button', { name: '次の50件' }))
    fireEvent.click(screen.getByRole('checkbox', { name: '銀の剣119を比較に選択' }))
    expect(add()).toHaveTextContent('119件')
    fireEvent.click(add())
    expect(exportIds()).toEqual(['gear-119', ...items.slice(0, 119).map(item => item.content.id)])
    expect(selectedView()).toHaveAttribute('aria-pressed', 'true')
    expect(screen.getAllByRole('checkbox')).toHaveLength(50)
    expect(screen.getByText('1 / 3ページ')).toBeInTheDocument()
    expect(add()).toBeDisabled()
    expect(remove()).toHaveTextContent('120件')
    fireEvent.click(add())
    expect(exportIds()).toHaveLength(120)
    expect(fetcher).toHaveBeenCalledTimes(1)
  })

  it('removes matching selections across pages while keeping other filters, then leaves zero comparisons after the last removal', async () => {
    await setup([...gear(120), equipment('shield', '銅の盾')])
    input('名前・ID', '盾')
    fireEvent.click(screen.getByRole('checkbox', { name: '銅の盾を比較に選択' }))
    input('名前・ID', '銀')
    fireEvent.click(add())
    expect(exportIds()).toHaveLength(121)
    fireEvent.click(screen.getByRole('button', { name: '次の50件' }))
    fireEvent.click(remove())
    expect(selectedView()).toHaveFocus()
    expect(screen.getByText('1 / 1ページ')).toBeInTheDocument()
    expect(exportIds()).toEqual(['shield'])
    expect(screen.getByLabelText('近接攻撃力の範囲比較')).toHaveTextContent('銅の盾')
    expect(remove()).toBeDisabled()
    expect(add()).toHaveTextContent('120件')
    input('名前・ID', '盾')
    fireEvent.click(remove())
    expect(selectedView()).toHaveFocus()
    expect(screen.getByText(/0件を比較/)).toHaveTextContent('条件一致 1件')
    expect(screen.queryByRole('checkbox')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: '比較表をCSV出力' })).toBeDisabled()
    fireEvent.click(candidateView())
    expect(exportIds()).toEqual(['shield'])
  })

  it('appends in the current sort order without moving existing selections and disables empty/no-op operations', async () => {
    await setup([equipment('a', '銀A', 1), equipment('b', '銀B', 30), equipment('c', '銀C', 20)])
    expect(remove()).toBeDisabled()
    fireEvent.click(screen.getByRole('checkbox', { name: '銀Aを比較に選択' }))
    input('並び順', 'max')
    fireEvent.click(add())
    expect(selectedView()).toHaveFocus()
    input('並び順', 'default')
    expect(exportIds()).toEqual(['a', 'b', 'c'])
    input('名前・ID', '銀 C')
    expect(remove()).toHaveTextContent('1件')
    fireEvent.click(remove())
    expect(exportIds()).toEqual(['a', 'b'])
    input('名前・ID', '存在しない')
    expect(add()).toBeDisabled(); expect(remove()).toBeDisabled()
    expect(exportIds()).toEqual(['a', 'b'])
  })

  it('uses reloaded candidates and preserves missing selections for later recovery', async () => {
    const initial = gear(3)
    const api = await setup(initial)
    fireEvent.click(add())
    input('名前・ID', '銀')
    api.reload([equipment('gear-000', '金の剣'), initial[1], equipment('new', '銀の新剣')])
    await screen.findByRole('checkbox', { name: '金の剣を比較に選択' })
    expect(screen.getByText(/1件が現在のマスターに見つかりません/)).toBeInTheDocument()
    expect(add()).toHaveTextContent('1件'); expect(remove()).toHaveTextContent('1件')
    fireEvent.click(add())
    expect(exportIds()).toEqual(['gear-000', 'gear-001', 'new'])
    fireEvent.click(remove())
    expect(exportIds()).toEqual(['gear-000'])
    expect(screen.getByText(/1件が現在のマスターに見つかりません/)).toBeInTheDocument()
    api.reload(initial)
    await screen.findByRole('checkbox', { name: '銀の剣2を比較に選択' })
    expect(exportIds()).toEqual(['gear-000', 'gear-002'])
    expect(screen.queryByText(/件が現在のマスターに見つかりません/)).not.toBeInTheDocument()
    expect(api.fetcher).toHaveBeenCalledTimes(3)
  })
})

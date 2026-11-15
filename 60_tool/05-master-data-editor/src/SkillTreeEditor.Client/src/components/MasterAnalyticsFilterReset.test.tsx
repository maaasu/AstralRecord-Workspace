import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { MasterAnalytics } from './MasterAnalytics'
import { downloadCsv } from '../data/masterAnalytics'
import type { AnalyticsCatalog } from '../data/masterAnalytics'

vi.mock('../data/masterAnalytics', async importOriginal => ({ ...await importOriginal<typeof import('../data/masterAnalytics')>(), downloadCsv: vi.fn() }))
afterEach(() => { vi.unstubAllGlobals(); vi.clearAllMocks() })

function equipment(id: string, level = 10, slot = 'MAIN', tag = 'SWORD', value = 10): AnalyticsCatalog['equipment'][number] {
  return { path: `item/${id}.yml`, progression: null, content: { id, name: `装備${id}`, equipment: {
    requiredLevel: level, slot, tag, stats: [{ status: 'MELEE_ATTACK', value }, { status: 'MAX_HEALTH', value: value + 100 }],
    enhance: { maxLevel: 1, levels: [{ level: 1, statIncrease: [{ status: 'MELEE_ATTACK', value: 5 }] }] },
  } } }
}
async function setup(items: AnalyticsCatalog['equipment']) {
  let catalog: AnalyticsCatalog = { equipment: items, classes: [], diagnostics: [] }
  const fetcher = vi.fn(async () => ({ ok: true, json: async () => catalog }))
  vi.stubGlobal('fetch', fetcher)
  render(<MasterAnalytics />)
  await screen.findByRole('table')
  return { fetcher, reload: (items: AnalyticsCatalog['equipment']) => {
    catalog = { ...catalog, equipment: items }
    fireEvent.click(screen.getByRole('button', { name: 'マスター再読込' }))
  } }
}
const input = (label: string, value: string) => fireEvent.change(screen.getByLabelText(label), { target: { value } })
const levelToggle = () => screen.getByRole('button', { name: '要求Lvで絞り込む' })
const reset = () => screen.getByRole('button', { name: '装備の条件を解除' })
const selectedView = () => screen.getByRole('button', { name: /^選択した装備（/ })
const candidateView = () => screen.getByRole('button', { name: /^条件に一致する装備（/ })
const names = () => within(screen.getByRole('table')).queryAllByRole('checkbox').map(item => item.getAttribute('aria-label'))
function exportedRows() {
  fireEvent.click(screen.getByRole('button', { name: '比較表をCSV出力' }))
  return vi.mocked(downloadCsv).mock.lastCall![1]
}

describe('equipment filter reset and optional required-level range', () => {
  it('keeps the initial inclusive range, shows all levels when off and restores the retained range when on', async () => {
    const { fetcher } = await setup([equipment('zero', 0), equipment('edge', 20), equipment('above', 21), equipment('high', 100)])
    expect(levelToggle()).toHaveAttribute('aria-pressed', 'true')
    expect(names()).toEqual(['装備zeroを比較に選択', '装備edgeを比較に選択'])
    input('要求Lvの中心', '100'); input('前後のLv幅', '1')
    expect(names()).toEqual(['装備highを比較に選択'])
    fireEvent.click(levelToggle())
    expect(levelToggle()).toHaveAttribute('aria-pressed', 'false')
    expect(screen.getByLabelText('要求Lvの中心')).toBeDisabled()
    expect(screen.getByLabelText('前後のLv幅')).toBeDisabled()
    expect(screen.getByLabelText('要求Lvの中心')).toHaveValue(100)
    expect(screen.getByLabelText('前後のLv幅')).toHaveValue(1)
    expect(names()).toHaveLength(4)
    fireEvent.click(screen.getByRole('tab', { name: 'クラス成長' }))
    fireEvent.click(screen.getByRole('tab', { name: '装備を比較' }))
    expect(levelToggle()).toHaveAttribute('aria-pressed', 'false')
    fireEvent.click(levelToggle())
    expect(screen.getByLabelText('要求Lvの中心')).toBeEnabled()
    expect(names()).toEqual(['装備highを比較に選択'])
    expect(fetcher).toHaveBeenCalledTimes(1)
  })

  it('clears all candidate filters and selected-only view while retaining comparison settings and CSV targets', async () => {
    const items = Array.from({ length: 120 }, (_, i) => equipment(`gear-${String(i).padStart(3, '0')}`, 10, 'MAIN', 'SWORD', i))
    const { fetcher } = await setup([...items, equipment('high', 100, 'OFF', 'SHIELD', 500)])
    fireEvent.click(screen.getByRole('button', { name: /^条件一致を比較に追加/ }))
    input('強化Lv', '1'); input('グラフのステータス', 'MAX_HEALTH')
    input('並び順', 'max'); input('並べる方向', 'desc')
    fireEvent.click(screen.getByRole('button', { name: '差のあるステータスだけ' }))
    input('名前・ID', 'no-match'); input('スロット', 'OFF'); input('装備タグ', 'SWORD')
    input('要求Lvの中心', '50'); input('前後のLv幅', '2')
    expect(screen.getByText(/120件を比較/)).toHaveTextContent('条件一致 0件')
    expect(selectedView()).toHaveAttribute('aria-pressed', 'true')
    fireEvent.click(screen.getByRole('button', { name: '次の50件' }))
    expect(screen.getByText('2 / 3ページ')).toBeInTheDocument()
    const before = exportedRows()
    reset().focus()
    fireEvent.click(reset())
    expect(screen.getByRole('textbox', { name: '名前・ID' })).toHaveValue('')
    expect(screen.getByRole('textbox', { name: '名前・ID' })).toHaveFocus()
    expect(screen.getByLabelText('スロット')).toHaveValue('')
    expect(screen.getByLabelText('装備タグ')).toHaveValue('')
    expect(levelToggle()).toHaveAttribute('aria-pressed', 'false')
    expect(screen.getByLabelText('要求Lvの中心')).toHaveValue(50)
    expect(screen.getByLabelText('前後のLv幅')).toHaveValue(2)
    expect(candidateView()).toHaveAttribute('aria-pressed', 'true')
    expect(screen.getByText('1 / 3ページ')).toBeInTheDocument()
    expect(names()).toHaveLength(50)
    expect(names()[0]).toBe('装備highを比較に選択')
    expect(screen.getByRole('checkbox', { name: '装備gear-119を比較に選択' })).toBeChecked()
    expect(screen.getByText(/120件を比較/)).toHaveTextContent('条件一致 121件')
    expect(screen.getByLabelText('強化Lv')).toHaveValue(1)
    expect(screen.getByLabelText('グラフのステータス')).toHaveValue('MAX_HEALTH')
    expect(screen.getByLabelText('並び順')).toHaveValue('max')
    expect(screen.getByLabelText('並べる方向')).toHaveValue('desc')
    expect(screen.getByRole('button', { name: '差のあるステータスだけ' })).toHaveAttribute('aria-pressed', 'true')
    expect(exportedRows()).toEqual(before)
    expect(before).toHaveLength(121) // Header + the original 120 selected items, not the new high-level candidate.
    expect(reset()).toBeDisabled()
    expect(fetcher).toHaveBeenCalledTimes(1)
  })

  it('recovers from zero matches and empty selected view without silently selecting items', async () => {
    const { fetcher } = await setup([equipment('low'), equipment('high', 100, 'OFF', 'SHIELD')])
    input('名前・ID', 'missing'); input('スロット', 'OFF')
    expect(names()).toHaveLength(0)
    expect(screen.getByText(/「装備の条件を解除」で全Lvの候補に戻れます/)).toBeInTheDocument()
    fireEvent.click(reset())
    expect(names()).toHaveLength(2)
    expect(exportedRows().slice(1).map(row => row[0])).toEqual(['low', 'high'])
    expect(screen.getByRole('button', { name: '選択を解除' })).toBeDisabled()
    expect(reset()).toBeDisabled()
    fireEvent.click(selectedView())
    expect(screen.getByRole('button', { name: '比較表をCSV出力' })).toBeDisabled()
    expect(reset()).toBeEnabled()
    fireEvent.click(reset())
    expect(candidateView()).toHaveAttribute('aria-pressed', 'true')
    expect(names()).toHaveLength(2)
    input('名前・ID', '　 ')
    expect(reset()).toBeEnabled()
    fireEvent.click(reset())
    expect(screen.getByRole('textbox', { name: '名前・ID' })).toHaveValue('')
    expect(fetcher).toHaveBeenCalledTimes(1)
  })

  it('preserves missing selections after reload and reports an empty catalog without suggesting a disabled reset', async () => {
    const api = await setup([equipment('lost'), equipment('high', 100)])
    fireEvent.click(screen.getByRole('checkbox', { name: '装備lostを比較に選択' }))
    api.reload([equipment('high', 100)])
    await screen.findByRole('button', { name: '見つからない選択を解除' })
    fireEvent.click(reset())
    expect(names()).toEqual(['装備highを比較に選択'])
    expect(screen.getByRole('button', { name: '見つからない選択を解除' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '比較表をCSV出力' })).toBeDisabled()
    expect(screen.getByText(/0件を比較/)).toHaveTextContent('条件一致 1件')
    api.reload([])
    await waitFor(() => expect(names()).toHaveLength(0))
    expect(levelToggle()).toHaveAttribute('aria-pressed', 'false')
    expect(reset()).toBeDisabled()
    expect(screen.getByText('読み込めた装備がありません。マスターの定義や読込エラーを確認してください。')).toBeInTheDocument()
    expect(screen.queryByText(/「装備の条件を解除」で全Lvの候補に戻れます/)).not.toBeInTheDocument()
    for (const query of ['　 ', 'missing', '&a']) {
      input('名前・ID', query)
      expect(screen.getByText('読み込めた装備がありません。マスターの定義や読込エラーを確認してください。')).toBeInTheDocument()
      expect(screen.queryByText(/「装備の条件を解除」で全Lvの候補に戻れます/)).not.toBeInTheDocument()
      fireEvent.click(reset())
      expect(reset()).toBeDisabled()
    }
    expect(api.fetcher).toHaveBeenCalledTimes(3)
  })
})

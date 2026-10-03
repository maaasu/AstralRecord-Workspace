import { fireEvent, render, screen, within } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { MasterAnalytics } from './MasterAnalytics'

afterEach(() => vi.unstubAllGlobals())

describe('equipment name presentation', () => {
  it('renders the original color in bars and table while searching the visible name', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, json: async () => ({
      equipment: [{ path: 'item/sword.yml', progression: null, content: { id: '20a00001', name: '&6黄金&aの剣', equipment: { requiredLevel: 10, stats: [{ status: 'MELEE_ATTACK', value: 5 }] } } }], classes: [], diagnostics: [],
    }) }))
    render(<MasterAnalytics />)
    const table = await screen.findByRole('table')
    expect(within(table).getByText('黄金')).toHaveStyle({ color: '#ffaa00' })
    expect(within(table).getByText('の剣')).toHaveStyle({ color: '#55ff55' })
    expect(screen.getAllByText('黄金')).toHaveLength(2)
    expect(screen.getByRole('checkbox', { name: '黄金の剣を比較に選択' })).toBeInTheDocument()
    fireEvent.change(screen.getByRole('textbox', { name: '名前・ID' }), { target: { value: '黄金の剣' } })
    expect(within(table).getByText(/20a00001/)).toBeInTheDocument()
    expect(within(table).getByRole('cell', { name: '5' })).toBeInTheDocument()
  })

  it('colors class choices, graph legends and the growth table', async () => {
    const definition = { id: 'mage', name: '§d魔法使い', maxLevel: 100, expRate: 100 }
    vi.stubGlobal('fetch', vi.fn().mockImplementation(async (url: string) => ({ ok: true, json: async () => url.includes('/growth?') ? {
      startPlayerLevel: 1, classes: [definition], points: [{ playerLevel: 1, playerTotalExperience: 0, classes: [{ id: 'mage', level: 1, totalExperience: 0, nextLevelExperience: 10 }] }], sources: [], assumption: '',
    } : { equipment: [], classes: [{ path: 'class/mage.yml', progression: null, content: definition }], diagnostics: [] } })))
    render(<MasterAnalytics />)
    await screen.findByRole('table')
    fireEvent.click(screen.getByRole('tab', { name: 'クラス成長' }))
    expect(screen.getByText('魔法使い')).toHaveStyle({ color: '#ff55ff' })
    fireEvent.click(screen.getByRole('button', { name: 'グラフを計算' }))
    const table = await screen.findByRole('table')
    expect(within(table).getByText('魔法使い')).toHaveStyle({ color: '#ff55ff' })
    expect(screen.getAllByText('魔法使い', { selector: '.minecraft-text span' })).toHaveLength(3)
  })
})

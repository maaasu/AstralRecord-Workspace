import { fireEvent, render, screen, within } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { MasterAnalytics } from './MasterAnalytics'
import { downloadCsv } from '../data/masterAnalytics'
import type { AnalyticsCatalog, GrowthSimulation } from '../data/masterAnalytics'

vi.mock('../data/masterAnalytics', async original => ({ ...await original<typeof import('../data/masterAnalytics')>(), downloadCsv: vi.fn() }))
afterEach(() => { vi.unstubAllGlobals(); vi.restoreAllMocks(); vi.clearAllMocks() })

describe('growth series identity', () => {
  it.each([
    ['&6賢者', '§d賢者'],
    ['賢者', '賢者'],
  ])('distinguishes duplicate names %s / %s across view changes and recalculation', async (firstName, secondName) => {
    const errors = vi.spyOn(console, 'error').mockImplementation(() => {})
    const definitions = [{ id: 'adventurer', name: firstName, maxLevel: 100, expRate: 100 }, { id: 'mage', name: secondName, maxLevel: 100, expRate: 100 }]
    let resultDefinitions = definitions
    const catalog: AnalyticsCatalog = { equipment: [], diagnostics: [], classes: definitions.map(definition => ({
      path: `class/${definition.id}.yml`, progression: null,
      content: { ...definition, baseStats: [{ status: 'MAX_HEALTH', value: 10 }], growthPerLevel: [{ status: 'MAX_HEALTH', value: 2 }] },
    })) }
    const simulation = (): GrowthSimulation => ({
      accountUuid: 'fixture', startPlayerLevel: 1, startClassLevel: 1, classes: resultDefinitions, sources: [], assumption: 'fixture',
      points: [1, 2, 3].map(playerLevel => ({ playerLevel, playerTotalExperience: playerLevel * 100,
        classes: resultDefinitions.map(definition => ({ id: definition.id, level: playerLevel * (definition.id === 'mage' ? 2 : 1), totalExperience: playerLevel * (definition.id === 'mage' ? 20 : 10), nextLevelExperience: 10 })),
      })),
    })
    const fetchMock = vi.fn(async (url: string) => ({ ok: true, json: async () => url.includes('/growth?') ? simulation() : catalog }))
    vi.stubGlobal('fetch', fetchMock)
    const { container } = render(<MasterAnalytics />)
    await screen.findByRole('table')
    fireEvent.click(screen.getByRole('tab', { name: 'クラス成長' }))
    fireEvent.click(screen.getByRole('button', { name: '見つからない選択を解除（2件）' }))
    const calculate = () => fireEvent.click(screen.getByRole('button', { name: 'グラフを計算' }))
    calculate()
    await screen.findByRole('slider')
    expect(errors).not.toHaveBeenCalled()
    const line = (label: string) => screen.getByText(label, { selector: 'path > title' }).closest('path')!
    const legend = (id: string) => screen.getByText(id, { selector: '.chart-legend small' }).parentElement!
    const firstLine = line('賢者（adventurer）'), secondLine = line('賢者（mage）')
    const firstLegend = legend('adventurer'), secondLegend = legend('mage')
    expect(firstLine).not.toBe(secondLine)
    expect(firstLegend).toHaveTextContent('賢者adventurer')
    expect(secondLegend).toHaveTextContent('賢者mage')
    if (firstName.startsWith('&')) {
      expect(within(firstLegend).getByText('賢者')).toHaveStyle({ color: '#ffaa00' })
      expect(within(secondLegend).getByText('賢者')).toHaveStyle({ color: '#ff55ff' })
    }
    for (const value of ['experience', 'bonus', 'level']) {
      fireEvent.change(screen.getByLabelText('グラフの種類'), { target: { value } })
      expect(line('賢者（adventurer）')).toBe(firstLine)
      expect(line('賢者（mage）')).toBe(secondLine)
    }
    expect(fetchMock).toHaveBeenCalledTimes(2)
    resultDefinitions = [definitions[1], { ...definitions[0], name: '&a大賢者' }]
    calculate()
    await screen.findByText('大賢者（adventurer）', { selector: 'path > title' })
    expect(line('大賢者（adventurer）')).toBe(firstLine)
    expect(line('賢者（mage）')).toBe(secondLine)
    expect(legend('adventurer')).toBe(firstLegend)
    expect(legend('mage')).toBe(secondLegend)
    expect(Array.from(container.querySelectorAll('.chart-legend small')).map(item => item.textContent)).toEqual(['mage', 'adventurer'])
    expect(container.querySelectorAll('.analytics-chart svg path')).toHaveLength(2)
    expect(within(firstLegend).getByText('大賢者')).toHaveStyle({ color: '#55ff55' })
    fireEvent.click(screen.getByRole('button', { name: '成長表をCSV出力' }))
    expect(vi.mocked(downloadCsv).mock.lastCall![1][0]).toEqual(['プレイヤーLv', '累積プレイヤーEXP', 'mage クラスLv', 'adventurer クラスLv'])
    fireEvent.click(screen.getByRole('checkbox', { name: /mage$/ }))
    resultDefinitions = [resultDefinitions[1]]
    calculate()
    await vi.waitFor(() => expect(container.querySelectorAll('.analytics-chart svg path')).toHaveLength(1))
    expect(line('大賢者（adventurer）')).toBe(firstLine)
    expect(legend('adventurer')).toBe(firstLegend)
    expect(screen.queryByText('賢者（mage）', { selector: 'path > title' })).not.toBeInTheDocument()
    expect(container.querySelectorAll('.chart-legend > span')).toHaveLength(1)
    expect(errors).not.toHaveBeenCalled()
    expect(fetchMock).toHaveBeenCalledTimes(4)
  })
})

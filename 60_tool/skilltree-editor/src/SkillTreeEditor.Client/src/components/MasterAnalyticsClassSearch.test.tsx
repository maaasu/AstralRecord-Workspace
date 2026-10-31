import { fireEvent, render, screen, within } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { MasterAnalytics } from './MasterAnalytics'
import type { AnalyticsCatalog, AnalyticsMaster, GrowthSimulation } from '../data/masterAnalytics'

afterEach(() => { vi.unstubAllGlobals(); vi.restoreAllMocks() })

const defaults = ['adventurer', 'swordsman', 'hunter', 'mage']
const baseNames = ['冒険者', '剣士', '狩人', '§b魔法使い']
const master = (id: string, name: string, path = `classes/base/${id}.yml`): AnalyticsMaster => ({ path, content: { id, name }, progression: null })
const classCatalog = (): AnalyticsCatalog => ({ equipment: [], diagnostics: [], classes: [
  ...defaults.map((id, index) => master(id, baseNames[index])),
  ...Array.from({ length: 10 }, (_, index) => {
    const number = String(index + 1).padStart(2, '0')
    return master(`specialist_${number}`, `§a専門職${number}`, `classes/advanced/job-${number}.yml`)
  }),
] })
const simulation: GrowthSimulation = {
  accountUuid: 'test-account', startPlayerLevel: 1, startClassLevel: 1, sources: [], assumption: 'fixture',
  classes: [{ id: 'mage', name: '魔法使い', maxLevel: 100, expRate: 100 }],
  points: [{ playerLevel: 1, playerTotalExperience: 0, classes: [{ id: 'mage', level: 1, totalExperience: 0, nextLevelExperience: 53 }] }],
}

async function fixture(initial = classCatalog()) {
  let catalog = initial
  const fetchMock = vi.fn(async (input: RequestInfo | URL) => ({ ok: true, json: async () => String(input).includes('/growth?') ? simulation : catalog }))
  vi.stubGlobal('fetch', fetchMock)
  render(<MasterAnalytics />)
  await screen.findByRole('table')
  fireEvent.click(screen.getByRole('tab', { name: 'クラス成長' }))
  return { fetchMock, replaceCatalog: (value: AnalyticsCatalog) => { catalog = value },
    sentIds: () => new URL(String(fetchMock.mock.calls.find(([url]) => String(url).includes('/growth?'))![0]), 'http://localhost').searchParams.get('classIds')!.split(','),
  }
}
const search = (value: string) => fireEvent.change(screen.getByRole('textbox', { name: '職業を検索' }), { target: { value } })
const choices = () => within(screen.getByRole('group', { name: '比較する職業（最大12件）' }))
const choice = (id: string) => choices().getByRole('checkbox', { name: new RegExp(id) })
const onlySelected = () => fireEvent.click(screen.getByRole('button', { name: '選択済みだけ表示' }))
const clear = () => fireEvent.click(screen.getByRole('button', { name: '職業の条件を解除' }))

describe('growth class candidate search', () => {
  it('searches formatted names, IDs and paths with case-insensitive AND terms and full-width spaces', async () => {
    const { fetchMock } = await fixture()
    expect(screen.getByRole('button', { name: '職業の条件を解除' })).toBeDisabled()
    search('§b使い　MAGE BASE')
    expect(choices().getAllByRole('checkbox')).toHaveLength(1)
    expect(choice('mage')).toBeChecked()
    search('&a専門職 SPECIALIST_02 ADVANCED')
    expect(choices().getAllByRole('checkbox')).toHaveLength(1)
    expect(choice('specialist_02')).not.toBeChecked()
    search('魔法使い specialist_02')
    expect(choices().queryAllByRole('checkbox')).toHaveLength(0)
    expect(choices().getByText(/表示条件に一致する職業候補はありません/)).toBeInTheDocument()
    expect(screen.getByText(/^表示 \d+ \/ \d+ 職業/)).toHaveTextContent('表示 0 / 14 職業 · 条件外の選択 4 件')
    search('　 ')
    expect(choices().getAllByRole('checkbox')).toHaveLength(14)
    expect(fetchMock).toHaveBeenCalledTimes(1)
  })

  it('keeps hidden selections in the calculation request and does not fetch on display changes', async () => {
    const { fetchMock, sentIds } = await fixture()
    search('specialist_01')
    fireEvent.click(choice('specialist_01'))
    search('mage')
    onlySelected()
    expect(choices().getAllByRole('checkbox')).toHaveLength(1)
    expect(screen.getByText(/^表示 \d+ \/ \d+ 職業/)).toHaveTextContent('表示 1 / 14 職業 · 条件外の選択 4 件')
    expect(screen.getByText('5 / 12職業を選択')).toBeInTheDocument()
    expect(fetchMock).toHaveBeenCalledTimes(1)
    fireEvent.click(screen.getByRole('button', { name: 'グラフを計算' }))
    await screen.findByRole('slider')
    expect(sentIds()).toEqual([...defaults, 'specialist_01'])
    expect(fetchMock).toHaveBeenCalledTimes(2)
  })

  it('enforces the 12-class limit across filtered candidates while allowing selected classes to be removed', async () => {
    const { sentIds } = await fixture()
    for (let index = 1; index <= 8; index++) fireEvent.click(choice(`specialist_0${index}`))
    search('specialist_09')
    expect(choice('specialist_09')).toBeDisabled()
    search('mage')
    expect(choice('mage')).toBeEnabled()
    fireEvent.click(choice('mage'))
    search('specialist_09')
    expect(choice('specialist_09')).toBeEnabled()
    fireEvent.click(choice('specialist_09'))
    expect(screen.getByText('12 / 12職業を選択')).toBeInTheDocument()
    search('specialist_10')
    expect(choice('specialist_10')).toBeDisabled()
    fireEvent.click(screen.getByRole('button', { name: 'グラフを計算' }))
    await screen.findByRole('slider')
    expect(sentIds()).toHaveLength(12)
    expect(new Set(sentIds()).size).toBe(12)
    expect(sentIds()).not.toContain('mage')
    expect(sentIds()).toContain('specialist_09')
  })

  it('moves focus to a remaining checkbox or the search field when selected-only rows disappear', async () => {
    await fixture()
    onlySelected()
    expect(screen.getByRole('button', { name: '選択済みだけ表示' })).toHaveAttribute('aria-pressed', 'true')
    expect(choices().getAllByRole('checkbox')).toHaveLength(4)
    choice('swordsman').focus()
    fireEvent.click(choice('swordsman'))
    expect(choice('hunter')).toHaveFocus()
    choice('mage').focus()
    fireEvent.click(choice('mage'))
    expect(choice('hunter')).toHaveFocus()
    fireEvent.click(choice('hunter'))
    expect(choice('adventurer')).toHaveFocus()
    fireEvent.click(choice('adventurer'))
    expect(screen.getByRole('textbox', { name: '職業を検索' })).toHaveFocus()
    expect(choices().queryAllByRole('checkbox')).toHaveLength(0)
    expect(choices().getByText(/表示条件に一致する選択済みの職業候補はありません/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'グラフを計算' })).toBeDisabled()
  })

  it('clears only display conditions and keeps inputs, selections and the existing graph', async () => {
    const { fetchMock } = await fixture()
    fireEvent.change(screen.getByRole('textbox', { name: 'アカウントUUID' }), { target: { value: 'kept-account' } })
    fireEvent.change(screen.getByRole('spinbutton', { name: '表示上限プレイヤーLv' }), { target: { value: '80' } })
    fireEvent.click(screen.getByRole('button', { name: 'グラフを計算' }))
    await screen.findByRole('slider')
    const chart = screen.getByRole('img', { name: /の比較グラフ/ })
    search('mage'); onlySelected(); clear()
    expect(screen.getByRole('textbox', { name: '職業を検索' })).toHaveValue('')
    expect(screen.getByRole('textbox', { name: '職業を検索' })).toHaveFocus()
    expect(screen.getByRole('button', { name: '選択済みだけ表示' })).toHaveAttribute('aria-pressed', 'false')
    expect(screen.getByRole('button', { name: '職業の条件を解除' })).toBeDisabled()
    expect(choices().getAllByRole('checkbox')).toHaveLength(14)
    expect(choices().getAllByRole('checkbox').filter(input => (input as HTMLInputElement).checked)).toHaveLength(4)
    expect(screen.getByRole('textbox', { name: 'アカウントUUID' })).toHaveValue('kept-account')
    expect(screen.getByRole('spinbutton', { name: '表示上限プレイヤーLv' })).toHaveValue(80)
    expect(screen.getByRole('img', { name: /の比較グラフ/ })).toBe(chart)
    expect(fetchMock).toHaveBeenCalledTimes(2)
  })

  it('retains conditions through tab switches and reloads while using updated catalog entries', async () => {
    const { replaceCatalog, fetchMock } = await fixture()
    fireEvent.click(choice('specialist_01'))
    search('advanced'); onlySelected()
    fireEvent.click(screen.getByRole('tab', { name: '装備を比較' }))
    fireEvent.click(screen.getByRole('tab', { name: 'クラス成長' }))
    expect(screen.getByRole('textbox', { name: '職業を検索' })).toHaveValue('advanced')
    expect(screen.getByRole('button', { name: '選択済みだけ表示' })).toHaveAttribute('aria-pressed', 'true')
    expect(choice('specialist_01')).toBeChecked()
    const updated = classCatalog()
    updated.classes = updated.classes.filter(entry => entry.content.id !== 'specialist_01')
    updated.classes.find(entry => entry.content.id === 'specialist_02')!.content.name = '更新候補'
    replaceCatalog(updated)
    fireEvent.click(screen.getByRole('button', { name: 'マスター再読込' }))
    await screen.findByText('現在のカタログにない選択ID: specialist_01。選択は保持しています。')
    expect(screen.getByRole('textbox', { name: '職業を検索' })).toHaveValue('advanced')
    expect(screen.getByRole('button', { name: '選択済みだけ表示' })).toHaveAttribute('aria-pressed', 'true')
    expect(choices().queryAllByRole('checkbox')).toHaveLength(0)
    expect(screen.getByText(/^表示 \d+ \/ \d+ 職業/)).toHaveTextContent('表示 0 / 13 職業 · 条件外の選択 4 件')
    expect(screen.getByText('現在のカタログにない選択ID: specialist_01。選択は保持しています。')).toBeInTheDocument()
    expect(screen.getByText('5 / 12職業を選択')).toBeInTheDocument()
    onlySelected(); search('更新')
    expect(choices().getAllByRole('checkbox')).toHaveLength(1)
    expect(choice('specialist_02')).toHaveAccessibleName(/更新候補\s*specialist_02/)
    expect(fetchMock).toHaveBeenCalledTimes(2)
  })

  it('distinguishes catalog-missing default IDs from selections hidden by search without changing the selected IDs', async () => {
    const { sentIds } = await fixture({ equipment: [], diagnostics: [], classes: [master('mage', '魔法使い')] })
    onlySelected()
    expect(screen.getByText(/^表示 \d+ \/ \d+ 職業/)).toHaveTextContent('表示 1 / 1 職業 · 条件外の選択 0 件')
    expect(screen.getByText('現在のカタログにない選択ID: adventurer, swordsman, hunter。選択は保持しています。')).toBeInTheDocument()
    search('unknown')
    expect(screen.getByText(/^表示 \d+ \/ \d+ 職業/)).toHaveTextContent('表示 0 / 1 職業 · 条件外の選択 1 件')
    clear()
    expect(screen.getByText('4 / 12職業を選択')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'グラフを計算' }))
    await screen.findByRole('slider')
    expect(sentIds()).toEqual(defaults)
  })
})

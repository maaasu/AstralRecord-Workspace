import { fireEvent, render, screen, within } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { MasterAnalytics } from './MasterAnalytics'
import { classBonus, equipmentRows, formatNumber, normalizeStatus, plainName } from '../data/masterAnalytics'
import type { AnalyticsCatalog, GrowthSimulation } from '../data/masterAnalytics'

vi.mock('../data/masterAnalytics', async importOriginal => {
  const actual = await importOriginal<typeof import('../data/masterAnalytics')>()
  return { ...actual, classBonus: vi.fn(actual.classBonus), equipmentRows: vi.fn(actual.equipmentRows), formatNumber: vi.fn(actual.formatNumber), normalizeStatus: vi.fn(actual.normalizeStatus), plainName: vi.fn(actual.plainName) }
})

afterEach(() => { vi.unstubAllGlobals(); vi.restoreAllMocks(); vi.clearAllMocks() })

describe('equipment name presentation', () => {
  it('switches analysis tabs with the keyboard while keeping comparison conditions', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, json: async () => ({ equipment: [], classes: [], diagnostics: [] }) }))
    render(<MasterAnalytics />)
    await screen.findByRole('table')
    const equipmentTab = screen.getByRole('tab', { name: '装備を比較' })
    const growthTab = screen.getByRole('tab', { name: 'クラス成長' })
    fireEvent.change(screen.getByRole('textbox', { name: '名前・ID' }), { target: { value: '剣' } })
    equipmentTab.focus()
    fireEvent.keyDown(equipmentTab, { key: 'ArrowRight' })
    expect(growthTab).toHaveFocus()
    expect(growthTab).toHaveAttribute('aria-selected', 'true')
    expect(equipmentTab).toHaveAttribute('tabindex', '-1')
    expect(screen.getByRole('tabpanel', { name: 'クラス成長' })).toHaveAttribute('id', growthTab.getAttribute('aria-controls'))
    expect(screen.getByRole('heading', { name: '成長の計算条件' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: '職業ごとの成長を比較' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'グラフを計算' }).compareDocumentPosition(screen.getByRole('textbox', { name: 'アカウントUUID' })) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    fireEvent.keyDown(growthTab, { key: 'Home' })
    expect(equipmentTab).toHaveFocus()
    expect(screen.getByRole('textbox', { name: '名前・ID' })).toHaveValue('剣')
    expect(screen.getByRole('region', { name: '装備の比較表' })).toHaveAttribute('tabindex', '0')
  })

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

describe('analysis calculation reuse', () => {
  it('sorts the standard candidate order once rather than on each search', async () => {
    const masters = Array.from({ length: 1000 }, (_, i) => ({ path: `item/sort-perf-${999 - i}.yml`, progression: null, content: { id: `sort-perf-${999 - i}`, name: `Gear ${999 - i}`, equipment: { requiredLevel: 10 } } }))
    vi.stubGlobal('fetch', vi.fn(async () => ({ ok: true, json: async () => ({ equipment: masters, classes: [], diagnostics: [] }) })))
    const originalCompare = String.prototype.localeCompare
    let comparisons = 0
    vi.spyOn(String.prototype, 'localeCompare').mockImplementation(function (this: string, other, ...options) {
      if (String(this).startsWith('sort-perf-') && String(other).startsWith('sort-perf-')) comparisons++
      return originalCompare.call(this, other, ...options)
    })
    const queries = ['gear', 'GEAR', 'item/', 'sort-perf-']
    // Prior behavior sorted the matching rows again for every query.
    for (const query of queries) masters.filter(master => `${master.content.name} ${master.content.id} ${master.path}`.toLowerCase().includes(query.toLowerCase())).sort((a, b) => a.content.id.localeCompare(b.content.id))
    const previousSearchComparisons = comparisons
    expect(previousSearchComparisons).toBeGreaterThan(0)
    comparisons = 0
    render(<MasterAnalytics />)
    await screen.findByRole('table')
    expect(comparisons).toBeGreaterThan(0)
    comparisons = 0
    for (const value of queries) fireEvent.change(screen.getByRole('textbox', { name: '名前・ID' }), { target: { value } })
    expect(comparisons).toBe(0)
    expect(screen.getAllByRole('checkbox')).toHaveLength(50)
    expect(screen.getByText(/1000件を比較/)).toBeInTheDocument()
    expect(equipmentRows).toHaveBeenCalledTimes(2)
    expect(fetch).toHaveBeenCalledTimes(1)
    console.info('1000 equipment × 4 searches: ID comparisons', { before: previousSearchComparisons, after: comparisons })
  })

  it('reuses 12-class × 200-point graphs for inspection and unrelated inputs, and refreshes their dependencies', async () => {
    const definitions = Array.from({ length: 12 }, (_, index) => ({ id: `class-${index}`, name: `職業${index}`, maxLevel: 1000, expRate: 100 }))
    let catalog: AnalyticsCatalog = { equipment: [], diagnostics: [], classes: definitions.map((definition, index) => ({
      path: `class/${definition.id}.yml`, progression: null, content: { ...definition,
        baseStats: [{ status: 'MAX_HEALTH', value: 100 + index }, { status: 'MELEE_ATTACK', value: 10 + index }],
        growthPerLevel: [{ status: 'MAX_HEALTH', value: 2 }, { status: 'MELEE_ATTACK', value: 1 }],
      },
    })) }
    const masterLookup = vi.spyOn(catalog.classes, 'find')
    let offset = 0
    const simulation = (): GrowthSimulation => ({
      accountUuid: `result-${offset}`, startPlayerLevel: 1, startClassLevel: 1, classes: definitions, sources: [], assumption: 'fixture',
      points: Array.from({ length: 200 }, (_, index) => ({ playerLevel: index + 1, playerTotalExperience: index * 100,
        classes: definitions.map(definition => ({ id: definition.id, level: index + 1 + offset, totalExperience: index * 100 + offset, nextLevelExperience: 100 })),
      })),
    })
    vi.stubGlobal('fetch', vi.fn(async (url: string) => ({ ok: true, json: async () => url.includes('/growth?') ? simulation() : catalog })))
    render(<MasterAnalytics />)
    await screen.findByRole('table')
    fireEvent.click(screen.getByRole('tab', { name: 'クラス成長' }))
    fireEvent.click(screen.getByRole('button', { name: 'グラフを計算' }))
    await screen.findByRole('slider')
    expect.soft(masterLookup).toHaveBeenCalledTimes(12) // Only the inspection table searches in level view.
    const initialLevelPaths = Array.from(screen.getByRole('img', { name: /の比較グラフ/ }).querySelectorAll('path'), path => path.getAttribute('d'))
    const observations: { action: string; graphNames: number; bonusCalls: number; masterLookups: number }[] = []
    function measure(action: string, change: () => void, graphNames: number, bonusCalls: number, masterLookups: number) {
      vi.mocked(plainName).mockClear(); vi.mocked(classBonus).mockClear(); masterLookup.mockClear()
      change()
      const observed = { action, graphNames: vi.mocked(plainName).mock.calls.length, bonusCalls: vi.mocked(classBonus).mock.calls.length, masterLookups: masterLookup.mock.calls.length }
      observations.push(observed)
      expect.soft(observed.graphNames, action).toBe(graphNames)
      expect.soft(observed.bonusCalls, action).toBe(bonusCalls)
      expect.soft(observed.masterLookups, action).toBe(masterLookups)
    }
    measure('switch to bonus', () => fireEvent.change(screen.getByRole('combobox', { name: 'グラフの種類' }), { target: { value: 'bonus' } }), 12, 2412, 24)
    const chart = () => screen.getByRole('img', { name: /の比較グラフ/ })
    const firstPath = chart().querySelector('path')!.getAttribute('d')
    const firstRow = () => within(screen.getByRole('region', { name: '指定レベルの成長表' })).getAllByRole('row')[1]
    vi.mocked(formatNumber).mockClear(); vi.mocked(normalizeStatus).mockClear()
    measure('inspect level 20', () => fireEvent.change(screen.getByRole('slider'), { target: { value: '20' } }), 0, 12, 12)
    expect(formatNumber).toHaveBeenCalledTimes(49) // Caption + 4 cells × 12 rows; chart ticks are reused.
    expect(normalizeStatus).toHaveBeenCalledTimes(12) // Inspection rows only; catalog status options are reused.
    expect(within(firstRow()).getByRole('cell', { name: '138' })).toBeInTheDocument()
    expect(chart().querySelector('path')!.getAttribute('d')).toBe(firstPath)
    measure('edit UUID', () => fireEvent.change(screen.getByRole('textbox', { name: 'アカウントUUID' }), { target: { value: 'new-request-uuid' } }), 0, 12, 12)
    measure('edit pending maximum', () => fireEvent.change(screen.getByRole('spinbutton', { name: '表示上限プレイヤーLv' }), { target: { value: '150' } }), 0, 12, 12)
    measure('open equipment tab', () => fireEvent.click(screen.getByRole('tab', { name: '装備を比較' })), 0, 0, 0)
    measure('edit equipment search', () => fireEvent.change(screen.getByRole('textbox', { name: '名前・ID' }), { target: { value: 'gear' } }), 0, 0, 0)
    measure('return to growth', () => fireEvent.click(screen.getByRole('tab', { name: 'クラス成長' })), 0, 12, 12)
    measure('change bonus status', () => fireEvent.change(screen.getByRole('combobox', { name: 'ステータス' }), { target: { value: 'MELEE_ATTACK' } }), 12, 2412, 24)
    expect(within(firstRow()).getByRole('cell', { name: '29' })).toBeInTheDocument()
    expect(chart().querySelector('path')!.getAttribute('d')).not.toBe(firstPath)
    measure('change to experience', () => fireEvent.change(screen.getByRole('combobox', { name: 'グラフの種類' }), { target: { value: 'experience' } }), 12, 12, 12)
    expect(chart()).toHaveAccessibleName('プレイヤーLvに対するクラス累積EXPの比較グラフ')
    measure('change to level', () => fireEvent.change(screen.getByRole('combobox', { name: 'グラフの種類' }), { target: { value: 'level' } }), 12, 12, 12)
    expect(chart()).toHaveAccessibleName('プレイヤーLvに対するクラスLvの比較グラフ')
    expect(Array.from(chart().querySelectorAll('path'), path => path.getAttribute('d'))).toEqual(initialLevelPaths)
    fireEvent.change(screen.getByRole('combobox', { name: 'グラフの種類' }), { target: { value: 'bonus' } })
    vi.mocked(plainName).mockClear(); vi.mocked(classBonus).mockClear(); masterLookup.mockClear()
    offset = 1
    fireEvent.click(screen.getByRole('button', { name: 'グラフを計算' }))
    await screen.findByText('アカウントUUID: result-1。EXPERIENCE_GAIN_RATEは両方へ加算する前に適用されるため、この累積EXP対レベルの比較には影響しません。')
    observations.push({ action: 'new simulation', graphNames: vi.mocked(plainName).mock.calls.length, bonusCalls: vi.mocked(classBonus).mock.calls.length, masterLookups: masterLookup.mock.calls.length })
    expect.soft(masterLookup).toHaveBeenCalledTimes(36) // 12 graph lookups + two inspection-table renders.
    expect.soft(plainName).toHaveBeenCalledTimes(12)
    expect.soft(classBonus).toHaveBeenCalledTimes(2424) // Pending render keeps the inspection table current.
    expect(within(firstRow()).getByRole('cell', { name: '30' })).toBeInTheDocument()
    catalog = { ...catalog, classes: catalog.classes.map(master => ({ ...master, content: { ...master.content, baseStats: [{ status: 'MELEE_ATTACK', value: 200 }], growthPerLevel: [{ status: 'MELEE_ATTACK', value: 3 }] } })) }
    const reloadedMasterLookup = vi.spyOn(catalog.classes, 'find')
    fireEvent.click(screen.getByRole('button', { name: 'マスター再読込' }))
    await screen.findByRole('heading', { name: '職業ごとの成長を比較' })
    expect(screen.queryByRole('img', { name: /の比較グラフ/ })).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'グラフを計算' }))
    await screen.findByRole('slider')
    expect.soft(reloadedMasterLookup).toHaveBeenCalledTimes(24)
    expect(within(firstRow()).getByRole('cell', { name: '260' })).toBeInTheDocument()
    expect(screen.getByRole('combobox', { name: 'ステータス' }).querySelector('option[value="MAX_HEALTH"]')).toBeNull()
    console.info('12 classes × 200 points: graph names, bonus calls and master searches', observations)
  })

  it('keeps equipment sorting independent of selection, graph status, pagination and pending growth inputs', async () => {
    const catalog: AnalyticsCatalog = { classes: [], diagnostics: [], equipment: Array.from({ length: 120 }, (_, position) => {
      const index = 119 - position, suffix = String(index).padStart(3, '0')
      return { path: `item/gear-${suffix}.yml`, progression: index, content: { id: `perf-item-${suffix}`, name: `Gear-${suffix}`, equipment: {
        requiredLevel: index === 119 ? 30 : 10, slot: index % 2 ? 'HEAD' : 'MAIN', tag: index % 2 ? 'ARMOR' : 'SWORD',
        stats: [{ status: 'MELEE_ATTACK', value: 10 }, ...(index === 0 ? [{ status: 'MAGIC_ATTACK', value: 7 }] : [])],
        enhance: { maxLevel: 1, levels: [{ level: 1, statIncrease: [{ status: 'MELEE_ATTACK', value: 5 }] }] },
      } } }
    }) }
    vi.stubGlobal('fetch', vi.fn(async () => ({ ok: true, json: async () => catalog })))
    const originalCompare = String.prototype.localeCompare
    let comparisons = 0
    vi.spyOn(String.prototype, 'localeCompare').mockImplementation(function (this: string, other, ...options) {
      if (String(this).startsWith('perf-item-') && String(other).startsWith('perf-item-')) comparisons++
      return originalCompare.call(this, other, ...options)
    })
    render(<MasterAnalytics />)
    await screen.findByRole('table')
    const observations: { action: string; sortComparisons: number }[] = [{ action: 'initial catalog', sortComparisons: comparisons }]
    expect(screen.getByText(/119件を比較/)).toBeInTheDocument()
    function unchanged(action: string, change: () => void) {
      comparisons = 0
      change()
      observations.push({ action, sortComparisons: comparisons })
      expect.soft(comparisons, action).toBe(0)
    }
    unchanged('next page', () => fireEvent.click(screen.getByRole('button', { name: '次の50件' })))
    expect(screen.getByText(/perf-item-050/)).toBeInTheDocument()
    unchanged('previous page', () => fireEvent.click(screen.getByRole('button', { name: '前の50件' })))
    unchanged('select equipment', () => fireEvent.click(screen.getByRole('checkbox', { name: 'Gear-000を比較に選択' })))
    expect(screen.getByText(/1件を比較/)).toBeInTheDocument()
    unchanged('equipment graph status', () => fireEvent.change(screen.getByRole('combobox', { name: 'グラフのステータス' }), { target: { value: 'MAGIC_ATTACK' } }))
    expect(screen.getByLabelText('魔法攻撃力の範囲比較')).toHaveTextContent('7')
    unchanged('clear selection', () => fireEvent.click(screen.getByRole('button', { name: '選択を解除' })))
    fireEvent.change(screen.getByRole('spinbutton', { name: '強化Lv' }), { target: { value: '1' } })
    expect(equipmentRows).toHaveBeenLastCalledWith(catalog.equipment, 1)
    expect(within(screen.getByRole('table')).getAllByRole('cell', { name: '15' })).toHaveLength(50)
    expect(comparisons).toBeGreaterThan(0)
    fireEvent.change(screen.getByRole('combobox', { name: 'スロット' }), { target: { value: 'MAIN' } })
    expect(screen.getByText(/60件を比較/)).toBeInTheDocument()
    fireEvent.change(screen.getByRole('combobox', { name: '装備タグ' }), { target: { value: 'ARMOR' } })
    expect(screen.getByText(/0件を比較/)).toBeInTheDocument()
    fireEvent.change(screen.getByRole('combobox', { name: 'スロット' }), { target: { value: '' } })
    expect(screen.getByText(/59件を比較/)).toBeInTheDocument()
    fireEvent.change(screen.getByRole('spinbutton', { name: '要求Lvの中心' }), { target: { value: '30' } })
    fireEvent.change(screen.getByRole('spinbutton', { name: '前後のLv幅' }), { target: { value: '0' } })
    expect(screen.getByText(/1件を比較/)).toBeInTheDocument()
    expect(screen.getByText(/perf-item-119/)).toBeInTheDocument()
    fireEvent.change(screen.getByRole('spinbutton', { name: '前後のLv幅' }), { target: { value: '30' } })
    fireEvent.change(screen.getByRole('textbox', { name: '名前・ID' }), { target: { value: 'gEaR-119' } })
    expect(screen.getByText(/1件を比較/)).toBeInTheDocument()
    expect(screen.getByText(/perf-item-119/)).toBeInTheDocument()
    fireEvent.change(screen.getByRole('textbox', { name: '名前・ID' }), { target: { value: '' } })
    unchanged('growth tab', () => fireEvent.click(screen.getByRole('tab', { name: 'クラス成長' })))
    unchanged('pending UUID', () => fireEvent.change(screen.getByRole('textbox', { name: 'アカウントUUID' }), { target: { value: 'pending' } }))
    unchanged('equipment tab', () => fireEvent.click(screen.getByRole('tab', { name: '装備を比較' })))
    console.info('120 equipment masters: ID sort comparison counts', observations)
  })
})

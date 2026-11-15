import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, expect, it, vi } from 'vitest'
import { MasterAnalytics } from './MasterAnalytics'
import { createMasterNameSearchCache } from '../data/masterDataFilters'
import type { AnalyticsCatalog } from '../data/masterAnalytics'

vi.mock('../data/masterDataFilters', async importOriginal => {
  const actual = await importOriginal<typeof import('../data/masterDataFilters')>()
  return { ...actual, createMasterNameSearchCache: vi.fn(actual.createMasterNameSearchCache) }
})
afterEach(() => { vi.unstubAllGlobals(); vi.restoreAllMocks(); vi.clearAllMocks() })

it('retains separate equipment/class caches through query, enhancement, selection and tabs, and replaces them on reload', async () => {
  let catalog: AnalyticsCatalog = { equipment: [
    { path: 'items/eriva/silver.yml', progression: null, content: { id: 'SILVER', name: '&a銀の剣', equipment: { requiredLevel: 10, stats: [{ status: 'MELEE_ATTACK', value: 5 }] } } },
  ], classes: [{ path: 'classes/base/mage.yml', progression: null, content: { id: 'mage', name: '§b魔法使い' } }], diagnostics: [] }
  const fetchMock = vi.fn(async () => ({ ok: true, json: async () => catalog }))
  vi.stubGlobal('fetch', fetchMock)
  render(<MasterAnalytics />)
  await screen.findByRole('checkbox', { name: '銀の剣を比較に選択' })
  const factory = vi.mocked(createMasterNameSearchCache)
  const initialCount = factory.mock.calls.length
  expect(initialCount).toBeGreaterThanOrEqual(2)
  const equipmentCache = factory.mock.results.at(-1)!.value
  const classCache = factory.mock.results.at(-2)!.value
  expect(equipmentCache).not.toBe(classCache)
  const search = () => screen.getByRole('textbox', { name: '名前・ID' })
  fireEvent.change(search(), { target: { value: '銀 ERIVA SILVER' } })
  fireEvent.click(screen.getByRole('checkbox', { name: '銀の剣を比較に選択' }))
  fireEvent.change(screen.getByLabelText('強化Lv'), { target: { value: '1' } })
  fireEvent.click(screen.getByRole('tab', { name: 'クラス成長' }))
  fireEvent.change(screen.getByRole('textbox', { name: '職業を検索' }), { target: { value: 'MAGE　魔法 BASE' } })
  expect(within(screen.getByRole('group', { name: '比較する職業（最大12件）' })).getAllByRole('checkbox')).toHaveLength(1)
  fireEvent.click(screen.getByRole('tab', { name: '装備を比較' }))
  expect(search()).toHaveValue('銀 ERIVA SILVER')
  expect(screen.getByRole('checkbox', { name: '銀の剣を比較に選択' })).toBeChecked()
  expect(factory).toHaveBeenCalledTimes(initialCount)
  expect(fetchMock).toHaveBeenCalledTimes(1)

  catalog = { ...catalog, equipment: [{ ...catalog.equipment[0], content: { ...catalog.equipment[0].content, name: '&6金の剣' } }],
    classes: [{ ...catalog.classes[0], content: { id: 'mage', name: '§d術師' } }] }
  fireEvent.click(screen.getByRole('button', { name: 'マスター再読込' }))
  await waitFor(() => expect(factory).toHaveBeenCalledTimes(initialCount + 2))
  expect(factory.mock.results.at(-1)!.value).not.toBe(equipmentCache)
  expect(factory.mock.results.at(-2)!.value).not.toBe(classCache)
  expect(screen.queryByRole('checkbox', { name: '銀の剣を比較に選択' })).not.toBeInTheDocument()
  fireEvent.change(search(), { target: { value: '金 ERIVA SILVER' } })
  expect(screen.getByRole('checkbox', { name: '金の剣を比較に選択' })).toBeChecked()
  fireEvent.click(screen.getByRole('tab', { name: 'クラス成長' }))
  fireEvent.change(screen.getByRole('textbox', { name: '職業を検索' }), { target: { value: 'MAGE 術師 BASE' } })
  expect(within(screen.getByRole('group', { name: '比較する職業（最大12件）' })).getAllByRole('checkbox')).toHaveLength(1)
  expect(fetchMock).toHaveBeenCalledTimes(2)
})

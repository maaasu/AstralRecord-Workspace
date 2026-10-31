import { act, fireEvent, render, screen, within } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { MasterAnalytics } from './MasterAnalytics'
import { downloadCsv } from '../data/masterAnalytics'
import type { AnalyticsCatalog, GrowthSimulation } from '../data/masterAnalytics'

vi.mock('../data/masterAnalytics', async importOriginal => ({
  ...await importOriginal<typeof import('../data/masterAnalytics')>(), downloadCsv: vi.fn(),
}))
afterEach(() => { vi.unstubAllGlobals(); vi.restoreAllMocks(); vi.clearAllMocks() })

const initialUuid = '00000000-0000-0000-0000-000000000001'
const catalog: AnalyticsCatalog = { equipment: [], diagnostics: [], classes: [
  ['adventurer', '冒険者'], ['swordsman', '剣士'], ['hunter', '狩人'], ['mage', '魔法使い'],
].map(([id, name]) => ({ path: `class/${id}.yml`, progression: null, content: { id, name,
  baseStats: [{ status: 'MAX_HEALTH', value: 100 }, { status: 'MELEE_ATTACK', value: 10 }],
} })) }

function deferred<T>() {
  let resolve!: (value: T) => void, reject!: (reason: unknown) => void
  const promise = new Promise<T>((success, failure) => { resolve = success; reject = failure })
  return { promise, resolve, reject }
}
async function fixture() {
  const requests: { url: string; pending: ReturnType<typeof deferred<Response>>; signal?: AbortSignal | null }[] = []
  vi.stubGlobal('fetch', vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
    const pending = deferred<Response>()
    requests.push({ url: String(input), pending, signal: init?.signal })
    return pending.promise // Deliberately allow late aborted responses to test the component's guard.
  }))
  const reply = async (index: number, value: unknown) => { await act(async () => requests[index].pending.resolve({ ok: true, json: async () => value } as Response)) }
  const params = (index: number) => new URL(requests[index].url, 'http://localhost').searchParams
  const result = (index: number): GrowthSimulation => {
    const input = params(index), startPlayerLevel = Number(input.get('startPlayerLevel')), startClassLevel = Number(input.get('startClassLevel'))
    const ids = input.get('classIds')!.split(',')
    const definitions = catalog.classes.filter(master => ids.includes(String(master.content.id))).map(master => ({ id: String(master.content.id), name: String(master.content.name), maxLevel: 100, expRate: 100 }))
    return { accountUuid: input.get('uuid')!.trim().toLowerCase(), startPlayerLevel, startClassLevel, classes: definitions, sources: [], assumption: 'fixture',
      points: Array.from({ length: Number(input.get('maxPlayerLevel')) - startPlayerLevel + 1 }, (_, offset) => ({
        playerLevel: startPlayerLevel + offset, playerTotalExperience: offset * 100,
        classes: definitions.map(definition => ({ id: definition.id, level: startClassLevel + offset, totalExperience: offset * 100, nextLevelExperience: 100 })),
      })),
    }
  }
  render(<MasterAnalytics />)
  await reply(0, catalog)
  fireEvent.click(screen.getByRole('tab', { name: 'クラス成長' }))
  return { requests, reply, params, result, finish: (index: number) => reply(index, result(index)),
    reject: async (index: number) => { await act(async () => requests[index].pending.reject(new Error('calculation-failed'))) },
  }
}
const calculate = () => fireEvent.click(screen.getByRole('button', { name: 'グラフを計算' }))
const recalculate = () => fireEvent.click(screen.getByRole('button', { name: '変更した条件で再計算' }))
const notice = () => screen.queryByRole('group', { name: '成長結果の計算条件' })
const change = (name: string, value: string) => fireEvent.change(screen.getByRole(name === 'アカウントUUID' ? 'textbox' : 'spinbutton', { name }), { target: { value } })
const selectAdventurer = () => fireEvent.click(screen.getByRole('checkbox', { name: /冒険者/ }))
const chart = () => screen.getByRole('img', { name: /の比較グラフ/ })
const exportCsv = () => fireEvent.click(screen.getByRole('button', { name: '成長表をCSV出力' }))

describe('growth result input conditions', () => {
  it.each([
    ['アカウントUUID', () => change('アカウントUUID', 'new-account'), () => change('アカウントUUID', initialUuid)],
    ['開始プレイヤーLv', () => change('開始プレイヤーLv', '5'), () => change('開始プレイヤーLv', '1')],
    ['開始クラスLv', () => change('開始クラスLv', '7'), () => change('開始クラスLv', '1')],
    ['表示上限プレイヤーLv', () => change('表示上限プレイヤーLv', '80'), () => change('表示上限プレイヤーLv', '100')],
    ['比較する職業', selectAdventurer, selectAdventurer],
  ] as const)('marks changed %s and clears the notice when the original input is restored', async (label, edit, restore) => {
    const test = await fixture()
    expect(notice()).not.toBeInTheDocument()
    calculate(); await test.finish(1)
    const original = chart()
    expect(notice()).not.toBeInTheDocument()
    edit()
    expect(notice()).toHaveTextContent(`変更項目: ${label}`)
    expect(notice()).toHaveTextContent('表示中のグラフ・表・CSVは前回の計算結果です。')
    expect(chart()).toBe(original)
    restore()
    expect(notice()).not.toBeInTheDocument()
    expect(test.requests).toHaveLength(2)
  })

  it('ignores search, selected-only display, chart controls and class selection order', async () => {
    const test = await fixture()
    calculate(); await test.finish(1)
    fireEvent.change(screen.getByRole('textbox', { name: '職業を検索' }), { target: { value: 'mage' } })
    fireEvent.click(screen.getByRole('button', { name: '選択済みだけ表示' }))
    fireEvent.change(screen.getByRole('combobox', { name: 'グラフの種類' }), { target: { value: 'bonus' } })
    fireEvent.change(screen.getByRole('combobox', { name: 'ステータス' }), { target: { value: 'MELEE_ATTACK' } })
    fireEvent.change(screen.getByRole('slider'), { target: { value: '20' } })
    expect(notice()).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '職業の条件を解除' }))
    selectAdventurer(); selectAdventurer()
    expect(notice()).not.toBeInTheDocument()
    expect(test.requests).toHaveLength(2)
  })

  it('compares the submitted UUID spelling rather than its normalized response representation', async () => {
    const test = await fixture()
    const upper = 'ABCDEF00-0000-0000-0000-000000000001'
    change('アカウントUUID', upper)
    calculate(); await test.finish(1)
    expect(notice()).not.toBeInTheDocument()
    change('アカウントUUID', upper.toLowerCase())
    expect(notice()).toHaveTextContent('変更項目: アカウントUUID')
    change('アカウントUUID', upper)
    expect(notice()).not.toBeInTheDocument()
  })

  it('uses each request start input even when fields change while that calculation is in flight', async () => {
    const test = await fixture()
    calculate()
    change('アカウントUUID', 'next-account'); change('表示上限プレイヤーLv', '80')
    expect(notice()).not.toBeInTheDocument()
    await test.finish(1)
    expect(notice()).toHaveTextContent('変更項目: アカウントUUID、表示上限プレイヤーLv')
    recalculate()
    expect(screen.getByRole('button', { name: '変更した条件で再計算' })).toBeDisabled()
    recalculate()
    expect(test.requests).toHaveLength(3)
    change('開始クラスLv', '7')
    await test.finish(2)
    expect(notice()).toHaveTextContent('変更項目: 開始クラスLv')
    expect(notice()).not.toHaveTextContent('変更項目: アカウントUUID')
    recalculate(); await test.finish(3)
    expect(notice()).not.toBeInTheDocument()
    expect(test.params(3).get('startClassLevel')).toBe('7')
  })

  it('retains the successful result and CSV after a failed retry, then replaces them after a successful retry', async () => {
    const test = await fixture()
    calculate(); await test.finish(1)
    const original = chart()
    exportCsv()
    const originalCsv = vi.mocked(downloadCsv).mock.calls[0][1]
    change('表示上限プレイヤーLv', '20')
    recalculate(); await test.reject(2)
    expect(screen.getByRole('alert')).toHaveTextContent('calculation-failed')
    expect(chart()).toBe(original)
    expect(notice()).toHaveTextContent('変更項目: 表示上限プレイヤーLv')
    exportCsv()
    expect(downloadCsv).toHaveBeenLastCalledWith('class-growth.csv', originalCsv)
    expect(originalCsv.at(-1)?.[0]).toBe(100)
    change('表示上限プレイヤーLv', '100')
    expect(notice()).not.toBeInTheDocument()
    change('表示上限プレイヤーLv', '20')
    recalculate(); await test.finish(3)
    expect(notice()).not.toBeInTheDocument()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
    exportCsv()
    expect(vi.mocked(downloadCsv).mock.calls.at(-1)?.[1].at(-1)?.[0]).toBe(20)
  })

  it('preserves the notice across tabs and clears result and input history together on reload, ignoring old responses', async () => {
    const test = await fixture()
    calculate(); await test.finish(1)
    change('アカウントUUID', 'old-pending')
    fireEvent.click(screen.getByRole('tab', { name: '装備を比較' }))
    fireEvent.click(screen.getByRole('tab', { name: 'クラス成長' }))
    expect(notice()).toHaveTextContent('変更項目: アカウントUUID')
    recalculate()
    fireEvent.click(screen.getByRole('button', { name: 'マスター再読込' }))
    expect(test.requests[2].signal?.aborted).toBe(true)
    expect(notice()).not.toBeInTheDocument()
    expect(screen.queryByRole('img', { name: /の比較グラフ/ })).not.toBeInTheDocument()
    await test.reply(3, catalog)
    change('アカウントUUID', 'fresh-account')
    calculate(); await test.finish(4)
    change('表示上限プレイヤーLv', '80')
    await test.reply(2, { ...test.result(2), accountUuid: 'obsolete-result' })
    expect(notice()).toHaveTextContent('変更項目: 表示上限プレイヤーLv')
    expect(within(notice()!).getByRole('button', { name: '変更した条件で再計算' })).toBeEnabled()
    expect(screen.queryByText(/obsolete-result/)).not.toBeInTheDocument()
    change('表示上限プレイヤーLv', '100')
    expect(notice()).not.toBeInTheDocument()
  })

  it('keeps the old result visible but prevents recalculation when all classes are deselected', async () => {
    const test = await fixture()
    calculate(); await test.finish(1)
    for (const checkbox of screen.getAllByRole('checkbox')) fireEvent.click(checkbox)
    expect(notice()).toHaveTextContent('変更項目: 比較する職業')
    expect(screen.getByRole('button', { name: '変更した条件で再計算' })).toBeDisabled()
    recalculate()
    expect(test.requests).toHaveLength(2)
    expect(chart()).toBeInTheDocument()
  })
})

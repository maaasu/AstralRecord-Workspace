import { act, fireEvent, render, screen } from '@testing-library/react'
import { StrictMode } from 'react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { MasterAnalytics } from './MasterAnalytics'
import type { AnalyticsCatalog, GrowthSimulation } from '../data/masterAnalytics'

afterEach(() => { vi.unstubAllGlobals(); vi.restoreAllMocks() })

const catalog: AnalyticsCatalog = { equipment: [], diagnostics: [], classes: [{ path: 'class/adventurer.yml', progression: null,
  content: { id: 'adventurer', name: '冒険者', baseStats: [{ status: 'MAX_HEALTH', value: 10 }] } }] }
function result(id: string): GrowthSimulation {
  return { accountUuid: id, startPlayerLevel: 1, startClassLevel: 1, sources: [], assumption: id,
    classes: [{ id: 'adventurer', name: '冒険者', maxLevel: 100, expRate: 100 }],
    points: [{ playerLevel: 1, playerTotalExperience: 0, classes: [{ id: 'adventurer', level: 1, totalExperience: 0, nextLevelExperience: 53 }] }] }
}
function deferred<T>() {
  let resolve!: (value: T) => void, reject!: (reason: unknown) => void
  const promise = new Promise<T>((success, failure) => { resolve = success; reject = failure })
  return { promise, resolve, reject }
}
function fixture(strict = false) {
  const requests: { url: string; signal: AbortSignal | null | undefined; pending: ReturnType<typeof deferred<Response>> }[] = []
  const fetchMock = vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
    const pending = deferred<Response>()
    requests.push({ url: String(input), signal: init?.signal, pending })
    // Intentionally ignore abort: a late response must also be rejected by the component.
    return pending.promise
  })
  vi.stubGlobal('fetch', fetchMock)
  const view = render(strict ? <StrictMode><MasterAnalytics /></StrictMode> : <MasterAnalytics />)
  return { requests, fetchMock, view,
    reply: async (index: number, value: unknown) => { await act(async () => requests[index].pending.resolve({ ok: true, json: async () => value } as Response)) },
    reject: async (index: number, reason: string) => { await act(async () => requests[index].pending.reject(new Error(reason))) },
  }
}
const calculate = () => fireEvent.click(screen.getByRole('button', { name: 'グラフを計算' }))
const reload = () => fireEvent.click(screen.getByRole('button', { name: 'マスター再読込' }))
async function openGrowth(test: ReturnType<typeof fixture>) {
  await test.reply(0, catalog)
  fireEvent.click(screen.getByRole('tab', { name: 'クラス成長' }))
}

describe('growth request lifecycle', () => {
  it('does not restore a pre-reload growth response into the reloaded catalog', async () => {
    const test = fixture()
    await openGrowth(test)
    calculate()
    reload()
    expect(test.requests[1].signal?.aborted).toBe(true)
    expect(screen.getByRole('button', { name: 'グラフを計算' })).toBeDisabled()
    await test.reply(2, catalog)
    await test.reply(1, result('obsolete-result'))
    expect(screen.queryByRole('img', { name: /の比較グラフ/ })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '成長表をCSV出力' })).not.toBeInTheDocument()
    expect(screen.getByRole('heading', { name: '職業ごとの成長を比較' })).toBeInTheDocument()
    calculate()
    await test.reply(3, result('fresh-result'))
    expect(screen.getByRole('img', { name: /の比較グラフ/ })).toBeInTheDocument()
    expect(screen.getByText('fresh-result')).toBeInTheDocument()
  })

  it.each(['success', 'failure'] as const)('ignores stale growth %s without clearing a newer request busy state', async outcome => {
    const test = fixture()
    await openGrowth(test)
    calculate()
    reload()
    await test.reply(2, catalog)
    calculate()
    if (outcome === 'success') await test.reply(1, result('obsolete-result'))
    else await test.reject(1, 'obsolete-error')
    expect(screen.getByRole('button', { name: '計算中…' })).toBeDisabled()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
    expect(screen.queryByRole('img', { name: /の比較グラフ/ })).not.toBeInTheDocument()
    expect(test.requests[3].signal?.aborted).toBe(false)
    await test.reply(3, result('fresh-result'))
    expect(screen.getByText('fresh-result')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'グラフを計算' })).toBeEnabled()
  })

  it('keeps the newest catalog when an older response body finishes after another reload', async () => {
    const test = fixture()
    await openGrowth(test)
    reload()
    const oldBody = deferred<AnalyticsCatalog>()
    await act(async () => test.requests[1].pending.resolve({ ok: true, json: () => oldBody.promise } as Response))
    reload()
    expect(test.requests[1].signal?.aborted).toBe(true)
    await test.reply(2, { ...catalog, classes: catalog.classes.map(master => ({ ...master, content: { ...master.content, name: '新しい冒険者' } })) })
    await act(async () => oldBody.resolve(catalog))
    expect(screen.getByRole('checkbox', { name: /^新しい冒険者\s*adventurer$/ })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'グラフを計算' })).toBeEnabled()
    calculate()
    await test.reply(3, result('new-catalog-result'))
    expect(screen.getByText('new-catalog-result')).toBeInTheDocument()
  })

  it('requires a successful catalog reload after failure and preserves entered conditions', async () => {
    const test = fixture()
    await openGrowth(test)
    fireEvent.change(screen.getByRole('textbox', { name: 'アカウントUUID' }), { target: { value: 'draft-account' } })
    fireEvent.change(screen.getByRole('spinbutton', { name: '開始クラスLv' }), { target: { value: '15' } })
    calculate()
    reload()
    calculate()
    expect(test.requests).toHaveLength(3)
    expect(screen.getByRole('status')).toHaveTextContent('マスターを再読込しています')
    await test.reject(2, 'catalog-error')
    await test.reject(1, 'obsolete-growth-error')
    expect(screen.getByRole('alert')).toHaveTextContent('catalog-error')
    expect(screen.getByRole('button', { name: 'グラフを計算' })).toBeDisabled()
    expect(screen.getByRole('status')).toHaveTextContent('再読込が成功すると計算できます')
    calculate()
    expect(test.requests).toHaveLength(3)
    reload()
    await test.reply(3, catalog)
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
    expect(screen.getByRole('textbox', { name: 'アカウントUUID' })).toHaveValue('draft-account')
    expect(screen.getByRole('spinbutton', { name: '開始クラスLv' })).toHaveValue(15)
    calculate()
    expect(test.requests[4].url).toContain('uuid=draft-account')
    expect(test.requests[4].url).toContain('startClassLevel=15')
    await test.reply(4, result('retried-result'))
    expect(screen.getByText('retried-result')).toBeInTheDocument()
  })

  it('aborts obsolete catalog work in StrictMode and pending growth on unmount', async () => {
    const test = fixture(true)
    expect(test.requests).toHaveLength(2)
    expect(test.requests[0].signal?.aborted).toBe(true)
    await test.reply(1, catalog)
    await test.reply(0, { equipment: [], classes: [], diagnostics: [] })
    fireEvent.click(screen.getByRole('tab', { name: 'クラス成長' }))
    expect(screen.getByRole('checkbox', { name: /^冒険者\s*adventurer$/ })).toBeInTheDocument()
    calculate()
    expect(test.requests[2].signal?.aborted).toBe(false)
    test.view.unmount()
    expect(test.requests[1].signal?.aborted).toBe(true)
    expect(test.requests[2].signal?.aborted).toBe(true)
    await test.reply(2, result('unmounted-result'))
    expect(screen.queryByText('unmounted-result')).not.toBeInTheDocument()
  })

  it('allows tab changes during calculation and retains the completed result', async () => {
    const test = fixture()
    await openGrowth(test)
    calculate()
    fireEvent.click(screen.getByRole('tab', { name: '装備を比較' }))
    expect(test.requests[1].signal?.aborted).toBe(false)
    await test.reply(1, result('background-result'))
    fireEvent.click(screen.getByRole('tab', { name: 'クラス成長' }))
    expect(screen.getByText('background-result')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'グラフを計算' })).toBeEnabled()
    expect(test.requests).toHaveLength(2)
  })

  it('reports current calculation errors, keeps the previous result and allows a retry', async () => {
    const test = fixture()
    await openGrowth(test)
    calculate()
    await test.reply(1, result('previous-result'))
    calculate()
    await test.reject(2, 'current-growth-error')
    expect(screen.getByRole('alert')).toHaveTextContent('current-growth-error')
    expect(screen.getByText('previous-result')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'グラフを計算' })).toBeEnabled()
    calculate()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
    await test.reply(3, result('retry-success'))
    expect(screen.getByText('retry-success')).toBeInTheDocument()
    expect(screen.queryByText('previous-result')).not.toBeInTheDocument()
  })
})

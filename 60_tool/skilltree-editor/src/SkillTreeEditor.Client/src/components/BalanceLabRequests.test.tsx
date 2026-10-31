import { StrictMode } from 'react'
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { BalanceApiError, cancelBalanceJob, getBalanceCatalog, getBalanceJob, startBalanceJob } from '../api/balanceLabApi'
import type { BalanceCatalog, BalanceJob } from '../data/balanceLab'
import { BalanceLab } from './BalanceLab'

vi.mock('../api/balanceLabApi', async importOriginal => ({
  ...await importOriginal<typeof import('../api/balanceLabApi')>(),
  getBalanceCatalog: vi.fn(), getBalanceJob: vi.fn(), startBalanceJob: vi.fn(), cancelBalanceJob: vi.fn(),
}))

const jobKey = 'astralrecord.balance-lab.active-job.v1'
const catalog: BalanceCatalog = {
  masterHash: 'master-current', sourceHash: 'source-current', modelVersion: 'model-1', sources: [], diagnostics: [],
  masters: [
    { path: 'class/mage.yml', kind: 'class', content: { id: 'mage', name: '魔法使い', baseStats: [] } },
    { path: 'mob/wolf.yml', kind: 'mob', content: { id: 'wolf', name: '狼' } },
  ],
}
const job = (id = 'old', status: BalanceJob['status'] = 'running', completed = 1): BalanceJob => ({ id, status, completed, total: 10 })
function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (reason: unknown) => void
  const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no })
  return { promise, resolve, reject }
}
const click = async (name: string) => { await act(async () => { fireEvent.click(screen.getByRole('button', { name })) }) }
const advance = async (ms: number) => { await act(async () => { await vi.advanceTimersByTimeAsync(ms) }) }
async function mount(strict = false) {
  await act(async () => { render(strict ? <StrictMode><BalanceLab /></StrictMode> : <BalanceLab />) })
}

beforeEach(() => {
  vi.useFakeTimers()
  vi.resetAllMocks()
  localStorage.clear()
  sessionStorage.clear()
  vi.mocked(getBalanceCatalog).mockResolvedValue(catalog)
  vi.mocked(getBalanceJob).mockResolvedValue(job())
  vi.mocked(cancelBalanceJob).mockResolvedValue(job('old', 'cancelled'))
  vi.mocked(startBalanceJob).mockResolvedValue(job('new'))
})
afterEach(() => {
  cleanup()
  vi.useRealTimers()
  localStorage.clear()
  sessionStorage.clear()
})

describe('BalanceLab request ordering', () => {
  it('does not resurrect a cancelled job when an older progress response arrives late', async () => {
    const progress = deferred<BalanceJob>()
    sessionStorage.setItem(jobKey, 'old')
    vi.mocked(getBalanceJob).mockResolvedValueOnce(job()).mockReturnValueOnce(progress.promise)
    await mount()
    await advance(600)
    const signal = vi.mocked(getBalanceJob).mock.calls[1][1]!
    await click('実行をキャンセル')
    expect(screen.getByText('キャンセル · 1/10')).toBeInTheDocument()
    expect(signal.aborted).toBe(true)
    // Deliberately ignore abort, as an already received response can finish later.
    await act(async () => { progress.resolve(job('old', 'running', 2)) })
    expect(screen.getByText('キャンセル · 1/10')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'この条件で実行' })).toBeEnabled()
    await advance(2400)
    expect(getBalanceJob).toHaveBeenCalledTimes(2)
  })

  it('locks duplicate cancellation and execution until DELETE settles, then follows a running response', async () => {
    const progress = deferred<BalanceJob>(), cancel = deferred<BalanceJob>()
    sessionStorage.setItem(jobKey, 'old')
    vi.mocked(getBalanceJob).mockResolvedValueOnce(job()).mockReturnValueOnce(progress.promise).mockResolvedValueOnce(job('old', 'cancelled', 3))
    vi.mocked(cancelBalanceJob).mockReturnValueOnce(cancel.promise)
    await mount()
    await advance(600)
    await click('実行をキャンセル')
    expect(screen.getByRole('button', { name: 'キャンセル要求中…' })).toBeDisabled()
    await click('キャンセル要求中…')
    await act(async () => { progress.resolve(job('old', 'completed', 10)) })
    expect(screen.getByRole('button', { name: '実行中…' })).toBeDisabled()
    expect(screen.getByRole('button', { name: '条件JSON読込' })).toBeDisabled()
    await click('実行中…')
    await advance(3000)
    expect(startBalanceJob).not.toHaveBeenCalled()
    expect(cancelBalanceJob).toHaveBeenCalledTimes(1)
    expect(getBalanceJob).toHaveBeenCalledTimes(2)
    await act(async () => { cancel.resolve(job('old', 'running', 2)) })
    expect(screen.getByText('計算中 · 2/10')).toBeInTheDocument()
    await advance(600)
    expect(screen.getByText('キャンセル · 3/10')).toBeInTheDocument()
    await click('この条件で実行')
    expect(startBalanceJob).toHaveBeenCalledTimes(1)
    expect(sessionStorage.getItem(jobKey)).toBe('new')
  })

  it.each(['running', 'completed', '404'] as const)('ignores old progress %s after cancellation and a new run', async status => {
    const progress = deferred<BalanceJob>()
    sessionStorage.setItem(jobKey, 'old')
    vi.mocked(getBalanceJob).mockResolvedValueOnce(job()).mockReturnValueOnce(progress.promise)
    await mount()
    await advance(600)
    await click('実行をキャンセル')
    await click('この条件で実行')
    await act(async () => {
      if (status === '404') progress.reject(new BalanceApiError('expired old job', 404))
      else progress.resolve(job('old', status, 8))
    })
    expect(sessionStorage.getItem(jobKey)).toBe('new')
    expect(screen.getByText('計算中 · 1/10')).toBeInTheDocument()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  it('resumes progress and permits retry when cancellation fails', async () => {
    const progress = deferred<BalanceJob>()
    sessionStorage.setItem(jobKey, 'old')
    vi.mocked(getBalanceJob).mockResolvedValueOnce(job()).mockReturnValueOnce(progress.promise).mockResolvedValueOnce(job('old', 'running', 4))
    vi.mocked(cancelBalanceJob).mockRejectedValueOnce(new Error('cancel disconnected'))
    await mount()
    await advance(600)
    await click('実行をキャンセル')
    expect(screen.getByRole('alert')).toHaveTextContent('cancel disconnected')
    expect(screen.getByRole('button', { name: '実行をキャンセル' })).toBeEnabled()
    await act(async () => { progress.resolve(job('old', 'running', 2)) })
    expect(screen.getByText('計算中 · 1/10')).toBeInTheDocument()
    await advance(600)
    expect(screen.getByText('計算中 · 4/10')).toBeInTheDocument()
    await click('実行をキャンセル')
    expect(cancelBalanceJob).toHaveBeenCalledTimes(2)
    expect(screen.getByText('キャンセル · 1/10')).toBeInTheDocument()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  it('ignores the abandoned StrictMode restoration after the active restoration finishes', async () => {
    const abandoned = deferred<BalanceJob>()
    sessionStorage.setItem(jobKey, 'old')
    vi.mocked(getBalanceJob).mockReturnValueOnce(abandoned.promise).mockResolvedValueOnce(job('old', 'completed', 10))
    await mount(true)
    expect(vi.mocked(getBalanceJob).mock.calls[0][1]?.aborted).toBe(true)
    expect(screen.getByText('完了 · 10/10')).toBeInTheDocument()
    await act(async () => { abandoned.resolve(job()) })
    expect(screen.getByText('完了 · 10/10')).toBeInTheDocument()
    await advance(1200)
    expect(getBalanceJob).toHaveBeenCalledTimes(2)
  })

  it('keeps the latest catalog and entered scenario when an older reload finishes later', async () => {
    const abandoned = deferred<BalanceCatalog>()
    vi.mocked(getBalanceCatalog).mockResolvedValueOnce(catalog).mockReturnValueOnce(abandoned.promise).mockResolvedValueOnce({ ...catalog, masterHash: 'master-latest' })
    await mount()
    fireEvent.change(screen.getByLabelText('プレイヤーLv'), { target: { value: '37' } })
    await click('カタログ再読込')
    await click('カタログ再読込')
    expect(vi.mocked(getBalanceCatalog).mock.calls[1][0]?.aborted).toBe(true)
    expect(screen.getByText('master-latest')).toBeInTheDocument()
    await act(async () => { abandoned.resolve({ ...catalog, masterHash: 'master-abandoned' }) })
    expect(screen.getByText('master-latest')).toBeInTheDocument()
    expect(screen.queryByText('master-abandoned')).not.toBeInTheDocument()
    expect(screen.getByLabelText('プレイヤーLv')).toHaveValue(37)
  })

  it('cleans up a pending progress request and schedules no retry after unmount', async () => {
    const progress = deferred<BalanceJob>()
    sessionStorage.setItem(jobKey, 'old')
    vi.mocked(getBalanceJob).mockResolvedValueOnce(job()).mockReturnValueOnce(progress.promise)
    await mount()
    await advance(600)
    cleanup()
    expect(vi.mocked(getBalanceJob).mock.calls[1][1]?.aborted).toBe(true)
    await act(async () => { progress.reject(new Error('late network failure')) })
    await advance(5000)
    expect(getBalanceJob).toHaveBeenCalledTimes(2)
  })
})

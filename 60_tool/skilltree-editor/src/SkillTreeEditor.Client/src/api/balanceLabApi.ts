import type { BalanceCatalog, BalanceJob, BalanceScenario } from '../data/balanceLab'

export class BalanceApiError extends Error {
  constructor(message: string, readonly status: number) { super(message) }
}

async function request<T>(url: string, init?: RequestInit): Promise<T> {
  const response = await fetch(url, init)
  const payload = await response.json().catch(() => ({}))
  if (!response.ok) throw new BalanceApiError(payload.message ?? payload.title ?? `HTTP ${response.status}`, response.status)
  return payload as T
}

export const getBalanceCatalog = (signal?: AbortSignal) => request<BalanceCatalog>('/api/balance-lab/catalog', { signal })
export const startBalanceJob = (scenario: BalanceScenario) => request<BalanceJob>('/api/balance-lab/jobs', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(scenario) })
export const getBalanceJob = (id: string, signal?: AbortSignal) => request<BalanceJob>(`/api/balance-lab/jobs/${encodeURIComponent(id)}`, { signal })
export const cancelBalanceJob = (id: string) => request<BalanceJob>(`/api/balance-lab/jobs/${encodeURIComponent(id)}`, { method: 'DELETE' })

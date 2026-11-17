import { ApiError } from './editorApi'
import type { AiProposalRequest, AiProposalResponse, AiSettings } from '../types/aiAssist'

async function request<T>(path: string, method: string, body?: unknown, signal?: AbortSignal): Promise<T> {
  let response: Response
  try {
    response = await fetch(`/api/master-data/ai/${path}`, { method, headers: { 'Content-Type': 'application/json' }, ...(body === undefined ? {} : { body: JSON.stringify(body) }), signal })
  } catch (reason) {
    if (signal?.aborted) throw reason
    throw new ApiError('AIアシストに接続できません。エディタのサーバーを確認して再試行してください。', 0)
  }
  const payload = await response.json().catch(() => null)
  if (!response.ok) throw new ApiError(payload?.message ?? payload?.title ?? `AIアシストに失敗しました (${response.status})`, response.status)
  if (!payload) throw new ApiError('AIアシストの応答を読み取れませんでした。再試行してください。', response.status)
  return payload as T
}

export const aiAssistApi = {
  settings: (signal?: AbortSignal) => request<AiSettings>('settings', 'GET', undefined, signal),
  configure: (settings: { apiKey?: string | null; model: string; clearApiKey?: boolean }, signal?: AbortSignal) => request<AiSettings>('settings', 'PUT', settings, signal),
  propose: (body: AiProposalRequest, signal?: AbortSignal) => request<AiProposalResponse>('proposals', 'POST', body, signal),
}

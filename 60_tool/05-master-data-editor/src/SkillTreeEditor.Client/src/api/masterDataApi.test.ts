import { afterEach, describe, expect, it, vi } from 'vitest'
import { masterDataApi } from './masterDataApi'

afterEach(() => vi.unstubAllGlobals())

describe('master API connection errors', () => {
  it('explains failed connections in Japanese', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('Failed to fetch')))
    await expect(masterDataApi.files()).rejects.toThrow('エディタのサーバーに接続できません')
  })
  it('explains response-body connection failures in Japanese', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ status: 200, text: () => Promise.reject(new TypeError('network interrupted')) }))
    await expect(masterDataApi.files()).rejects.toThrow('サーバーの応答を受信できませんでした')
  })
  it('rejects damaged successful JSON responses instead of installing them into workspace state', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('{broken', { headers: { 'content-type': 'application/json' } })))
    await expect(masterDataApi.files()).rejects.toThrow('サーバーの応答を読み取れませんでした')
  })
})

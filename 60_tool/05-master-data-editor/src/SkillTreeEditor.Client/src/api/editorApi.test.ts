import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiError, editorApi } from './editorApi'
import type { NodeMaster } from '../types/editor'

const node: NodeMaster = { $schema: 'node.schema.json', schemaVersion: 1, nodeId: '1000', name: 'test', icon: 'STONE', tags: [], pointType: 'CP', pointCost: 1, effects: [] }

afterEach(() => vi.unstubAllGlobals())

describe('スキルツリーAPIの版情報', () => {
  it('更新元の版をIf-Matchで送り、更新後の版はJSON原稿と分けて返す', async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify(node), { status: 200, headers: { 'Content-Type': 'application/json', ETag: '"new-revision"' } }))
    vi.stubGlobal('fetch', fetchMock)
    const saved = await editorApi.saveNode(node, 'old-revision')
    expect(fetchMock.mock.calls[0][1].headers['If-Match']).toBe('"old-revision"')
    expect(JSON.parse(fetchMock.mock.calls[0][1].body)).not.toHaveProperty('revision')
    expect(saved).toEqual({ content: node, revision: 'new-revision' })
  })

  it('削除も読み込んだ版を送る', async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(null, { status: 204 }))
    vi.stubGlobal('fetch', fetchMock)
    await editorApi.deleteNode('1000', 'old-revision')
    expect(fetchMock.mock.calls[0][1]).toMatchObject({ method: 'DELETE', headers: { 'If-Match': '"old-revision"' } })
  })

  it('競合は日本語メッセージ付きのApiErrorとして伝える', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify({ message: 'ファイルが変更されています。' }), { status: 409, headers: { 'Content-Type': 'application/json' } })))
    await expect(editorApi.saveNode(node, 'old')).rejects.toMatchObject({ status: 409, message: 'ファイルが変更されています。' })
  })

  it('通信失敗を日本語で知らせる', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('Failed to fetch')))
    await expect(editorApi.listNodes()).rejects.toBeInstanceOf(ApiError)
    await expect(editorApi.listNodes()).rejects.toThrow('サーバーに接続できませんでした')
  })
  it('ProblemDetailsの競合理由を日本語のまま表示する', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify({ title: '別の編集でファイルが変更されました。再読込してください。' }), { status: 409, headers: { 'Content-Type': 'application/problem+json' } })))
    await expect(editorApi.saveNode(node, 'old')).rejects.toThrow('別の編集でファイルが変更されました')
  })
  it('応答の途中切断を日本語で知らせる', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ status: 200, headers: new Headers({ 'Content-Type': 'application/json' }), json: () => Promise.reject(new TypeError('network failure')) }))
    await expect(editorApi.listNodes()).rejects.toThrow('サーバーの応答を読み取れませんでした')
  })
})

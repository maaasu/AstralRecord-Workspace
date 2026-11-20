import { expect, test } from 'claude-code/testing'
import type { LiveRequest, RequestDetail } from '../types'
import { emptyBoard, emptyDetail } from '../hooks/model'
import { attachSession, claudeActor, observeCodex, observeSessions, resolveCodexCallers } from '../hooks/observe'
import { pollCodex, sessionFolders } from '../hooks/watch-codex'
import { pollSessions } from '../hooks/watch-sessions'
import type { Draft } from '../hooks/state'

function draftWith(...requests: LiveRequest[]): Draft {
  const changedDetails = new Map<string, RequestDetail>()
  return {
    value: { ...emptyBoard, actors: [], requests },
    opened: new Set(), changedDetails,
    detail: async id => changedDetails.get(id) ?? emptyDetail,
  }
}

test('Codex の末尾と未完成行を読み、CLI の依頼を 1 ターンへ結び付ける', async () => {
  const now = new Date(2026, 0, 2, 10).getTime()
  const root = 'C:/test/.codex'
  const today = sessionFolders(root, now)[0]
  if (!today) throw new Error('日付フォルダがありません')
  const path = today + '/rollout-test.jsonl'
  const indexPath = root + '/session_index.jsonl'
  const row = (type: string, payload: object) => JSON.stringify({ timestamp: new Date(now).toISOString(), type, payload }) + '\n'
  const opening = row('session_meta', { cwd: 'C:/WORK/Project/', id: 'rollout-id', originator: 'codex_exec', source: 'exec' })
    + 'x'.repeat(66_000) + '\n' + row('event_msg', { type: 'task_started', turn_id: 'turn-a' })
    + row('response_item', { type: 'message', role: 'user', content: [{ text: '差分をレビュー' }] })
  const encoder = new TextEncoder()
  const ending = encoder.encode(row('event_msg', { type: 'task_complete', last_agent_message: '結果 日本語' }))
  const head = encoder.encode(opening)
  const content = encoder.encode(opening + new TextDecoder().decode(ending))
  let current = content.subarray(0, content.length - 5)
  let hasIndex = true
  const index = encoder.encode('{"id":"rollout-id","thread_name":"インデックスの名前"}\n')
  const reads: { path: string; offset: number; length: number }[] = []
  const source: Parameters<typeof pollCodex>[0] = {
    env: { get: async name => name === 'USERPROFILE' ? 'C:/test' : undefined },
    fs: {
      exists: async candidate => [root + '/sessions', today, ...(hasIndex ? [indexPath] : [])].includes(candidate),
      list: async () => [{ name: 'rollout-test.jsonl', kind: 'file', size: current.length, mtimeMs: now, isLink: false }],
      stat: async () => ({ kind: 'file', size: index.length, mtimeMs: now, isLink: false }),
      read: async (candidate, options) => {
        const offset = options.offset ?? 0
        const length = options.length ?? 0
        reads.push({ path: candidate, offset, length })
        const bytes = (candidate === indexPath ? index : current).subarray(offset, offset + length)
        return { base64: btoa(bytes.reduce((str, byte) => str + String.fromCharCode(byte), '')) }
      },
    },
  }
  const first = await pollCodex(source, { files: [] }, now)
  expect(first.monitor.status).toBe('ok')
  expect(first.changes[0]?.label).toBe('インデックスの名前')
  expect(first.changes[0]?.turns[0]?.prompt).toBe('差分をレビュー')
  expect(reads.every(read => read.length <= 256 * 1024)).toBe(true)
  expect(reads[0]?.offset).toBeGreaterThan(0)

  const launch: LiveRequest = {
    id: 'launch', from: 'self', to: 'codex:pending:tool', verb: '実装を依頼', summary: 'Codex exec',
    at: now, status: 'running', stepCount: 0, counts: {}, codexLaunch: { cwd: 'c:\\work\\project', mode: 'exec', readOnly: false },
  }
  const draft = draftWith(launch)
  await observeCodex(draft, first.changes, now)
  expect(draft.value.requests).toHaveLength(1)
  expect(launch.to).toBe('codex:rollout-id')
  expect(launch.verb).toBe('レビューを依頼')

  current = content
  reads.length = 0
  const appended = await pollCodex(source, first.checkpoint, now + 2_000)
  expect(reads[0]).toEqual({ path, offset: head.length, length: ending.length })
  await observeCodex(draft, appended.changes, now + 2_000)
  expect(draft.value.requests).toHaveLength(1)
  expect(launch.status).toBe('done')
  expect((await draft.detail('launch')).steps[0]?.full).toBe('結果 日本語')
  expect((await draft.detail('launch')).promptSource).toBe('Codex の記録')
  reads.length = 0
  await pollCodex(source, appended.checkpoint, now + 4_000)
  expect(reads).toHaveLength(0)
  hasIndex = false
  const fallback = await pollCodex(source, appended.checkpoint, now + 6_000)
  expect(fallback.changes[0]?.label).toBe('差分をレビュー')
  await observeCodex(draft, fallback.changes, now + 6_000)
  expect(draft.value.actors.find(item => item.id === 'codex:rollout-id')?.label).toBe('Codex（差分をレビュー）')
})

test('Claude は最大 3 件を読み、停止したセッションを優先して完了を取り込む', async () => {
  const lastRead = new Map<string, number>()
  const fetched: string[] = []
  const ids = ['local_a', 'local_b', 'local_c', 'session_d', 'local_own']
  let stopped = false
  const source: Parameters<typeof pollSessions>[0] = {
    mcp: {
      call: async (server, tool, args) => {
        expect(server).toBe('ccd_session_mgmt')
        expect(args?.limit).toBe(20)
        if (tool === 'list_sessions') {
          const listing = ids.map(sessionId => ({
            sessionId, title: sessionId, isRunning: !stopped || sessionId !== 'local_a',
            startedBy: sessionId === 'local_a' ? 'local_own' : '',
          }))
          return { content: [{ type: 'text', text: JSON.stringify(listing) }], isError: false }
        }
        const id = String(args?.session_id)
        fetched.push(id)
        const text = stopped && id === 'local_a' ? '[user] 開始\n\n[assistant] 本文\n\n[result] 終了' : '[user] 開始'
        return { content: [{ type: 'text', text }], isError: false }
      },
    },
  }
  const first = await pollSessions(source, { sessions: [], retryAt: 0 }, lastRead, 4_000, 'own')
  expect(fetched).toEqual(ids.slice(0, 3))
  expect(first.changes).toHaveLength(4)
  const draft = draftWith()
  await observeSessions(draft, first.changes, 'own')
  expect(draft.value.requests.find(item => item.to === 'claude:local_a')?.from).toBe('self')
  stopped = true
  fetched.length = 0
  const second = await pollSessions(source, first.checkpoint, lastRead, 8_000, 'own')
  expect(fetched).toHaveLength(3)
  expect(fetched[0]).toBe('local_a')
  expect(fetched).toContain('session_d')
  await observeSessions(draft, second.changes, 'own')
  const entry = draft.value.requests.find(item => item.to === 'claude:local_a')
  expect(entry?.status).toBe('done')
  expect(entry?.stepCount).toBe(2)
  fetched.length = 0
  await pollSessions(source, second.checkpoint, lastRead, 12_000, 'own')
  expect(fetched.includes('local_a')).toBe(false)
})

test('既知のセッション開始と監視中に先着した本文を 1 件にまとめる', async () => {
  const launch: LiveRequest = {
    id: 'launch', from: 'self', to: 'claude:pending:1', verb: 'クラウドで作業を依頼', summary: '調査',
    at: 100, status: 'running', stepCount: 0, counts: {}, awaitingSession: 'start',
  }
  const draft = draftWith(launch)
  draft.value.actors.push(claudeActor('pending:1', '調査', true))
  draft.changedDetails.set('launch', { prompt: '依頼\n\n背景', promptSource: 'ツール', steps: [] })
  await observeSessions(draft, [{
    row: { id: 'session_child', label: '調査', startedBy: 'local_own', isRunning: true, isRemote: true, isArchived: false },
    turns: [{ id: 'observed', at: 110, prompt: '依頼', steps: [{ id: 'step', at: 110, kind: 'command', icon: 'terminal', title: 'pwd', full: 'pwd' }] }],
  }], 'own')
  await attachSession(draft, launch, 'session_child', 120)
  expect(draft.value.requests).toHaveLength(1)
  expect(launch.to).toBe('claude:session_child')
  expect(launch.observation).toBe('observed')
  expect((await draft.detail('launch')).prompt).toBe('依頼\n\n背景')
  expect((await draft.detail('launch')).steps[0]?.full).toBe('pwd')
})

test('Claude の取得エラーを表示して 60 秒後に再試行する', async () => {
  const result = await pollSessions({
    mcp: { call: async () => { throw new Error('未接続') } },
  }, { sessions: [], retryAt: 0 }, new Map(), 4_000)
  expect(result.monitor).toEqual({ status: 'unavailable', reason: '未接続' })
  expect(result.checkpoint.retryAt).toBe(64_000)
})

test('Claude の完了通知を最新の依頼に加え、空の入力は数えない', async () => {
  const body = '<task-notification>\n' + '完了'.repeat(2_100) + '\n</task-notification>'
  let transcript = '[user] 最初の依頼\n\n[assistant] 回答\n\n[result] 完了'
  const source: Parameters<typeof pollSessions>[0] = {
    mcp: { call: async (_server, tool) => ({
      content: [{ type: 'text', text: tool === 'list_sessions'
        ? JSON.stringify([{ sessionId: 'local_guide', title: 'ゲームガイドの改善', cwd: 'C:/Work', isRunning: true }])
        : transcript }],
      isError: false,
    }) },
  }
  const lastRead = new Map<string, number>()
  const draft = draftWith()
  const first = await pollSessions(source, { sessions: [], retryAt: 0 }, lastRead, 4_000)
  await observeSessions(draft, first.changes, '')
  transcript += '\n\n[user]   \n\n[user] ' + body + '\n\n[user] <system-reminder>補足</system-reminder>'
  const second = await pollSessions(source, first.checkpoint, lastRead, 8_000)
  await observeSessions(draft, second.changes, '')
  expect(draft.value.requests).toHaveLength(1)
  const entry = draft.value.requests[0]!
  const steps = (await draft.detail(entry.id)).steps
  expect(steps.slice(-2).map(step => step.title)).toEqual(Array(2).fill('バックグラウンド作業の完了通知'))
  expect(steps.slice(-2).map(step => step.icon)).toEqual(['bell', 'bell'])
  expect(steps.at(-2)?.full).toBe(body.slice(0, 4_000))
  expect(entry.status).toBe('done')
})

test('最近停止した Claude の cwd を保持し、先に見つけた Codex の依頼元も更新する', async () => {
  const now = Date.parse('2026-11-20T02:00:00Z')
  const draft = draftWith()
  await observeCodex(draft, [{
    meta: { id: 'codex', cwd: 'C:/WORK/Guide/', originator: 'codex_exec', source: 'exec',
      parentThreadId: '', timestamp: now - 10_000 },
    label: 'レビュー', turns: [{ id: 'codex-turn', at: now - 10_000, prompt: 'レビュー', steps: [] }],
  }], now)
  expect(draft.value.requests[0]?.from).toBe('unknown')
  const result = await pollSessions({
    mcp: { call: async (_server, tool) => ({ isError: false, content: [{ type: 'text',
      text: tool === 'list_sessions' ? JSON.stringify([
        { sessionId: 'local_guide', title: 'ゲームガイドの改善', cwd: 'c:\\work\\guide\\', updatedAt: now - 20_000, isRunning: false },
        { sessionId: 'local_other', title: '別の作業', cwd: 'C:/WORK/GUIDE', updatedAt: now - 60_000, isRunning: false },
        { sessionId: 'local_old', title: '期限切れ', cwd: 'C:/Work/Guide', updatedAt: now - 31 * 60_000, isRunning: false },
      ]) : '[user] ガイドの修正\n\n[result] 完了' }] }) },
  }, { sessions: [], retryAt: 0 }, new Map(), now)
  expect(result.checkpoint.sessions.map(item => item.id)).toEqual(['local_guide', 'local_other'])
  expect(result.checkpoint.sessions[0]?.cwd).toBe('c:\\work\\guide\\')
  resolveCodexCallers(draft, result.checkpoint.sessions, now)
  expect(draft.value.requests[0]?.from).toBe('claude:local_guide')
  expect(draft.value.actors.find(item => item.id === 'claude:local_guide')?.label).toBe('Claude（ゲームガイドの改善）')
})

test('一覧上は停止のままでも活動時刻が進んだ Claude のログを取り直す', async () => {
  const now = Date.parse('2026-11-20T02:00:00Z')
  let updatedAt = now
  let transcript = '[user] 最初の依頼\n\n[result] 完了'
  let reads = 0
  const source: Parameters<typeof pollSessions>[0] = {
    mcp: { call: async (_server, tool) => {
      const listing = tool === 'list_sessions'
      if (!listing) reads++
      return { isError: false, content: [{ type: 'text', text: listing
        ? JSON.stringify([{ sessionId: 'local_stopped', isRunning: false, updatedAt }])
        : transcript }] }
    } },
  }
  const lastRead = new Map<string, number>()
  const first = await pollSessions(source, { sessions: [], retryAt: 0 }, lastRead, now)
  const same = await pollSessions(source, first.checkpoint, lastRead, now + 4_000)
  expect(reads).toBe(1)
  expect(same.changes[0]?.turns).toHaveLength(0)
  updatedAt = now + 5_000
  transcript += '\n\n[user] 周期の間の依頼\n\n[result] error'
  const second = await pollSessions(source, same.checkpoint, lastRead, now + 8_000)
  expect(reads).toBe(2)
  expect(second.checkpoint.sessions[0]?.fetchedActivityAt).toBe(updatedAt)
  expect(second.changes[0]?.turns).toMatchObject([{ prompt: '周期の間の依頼', status: 'error' }])
  updatedAt = now + 9_000
  transcript += '\n\n[user] 結果のない依頼\n\n[assistant] 中途まで'
  const third = await pollSessions(source, second.checkpoint, lastRead, now + 12_000)
  const draft = draftWith()
  await observeSessions(draft, third.changes, '')
  expect(draft.value.requests[0]?.status).toBe('stopped')
  updatedAt = now + 13_000
  transcript += '\n\n[result] 完了'
  const fourth = await pollSessions(source, third.checkpoint, lastRead, now + 16_000)
  await observeSessions(draft, fourth.changes, '')
  expect(draft.value.requests).toHaveLength(1)
  expect(draft.value.requests[0]?.status).toBe('done')
})

test('Codex の過大行は改行までカーソルを進めず、省略を表示し、同じ長さの置換を読み直す', async () => {
  const now = Date.parse('2026-11-20T02:00:00Z')
  const root = 'C:/test/.codex'
  const today = sessionFolders(root, now)[0]
  const row = (type: string, payload: object) => JSON.stringify({ type, payload, timestamp: new Date(now).toISOString() })
  const header = [
    row('session_meta', { id: 'thread', cwd: 'C:/work' }),
    row('event_msg', { type: 'task_started', turn_id: 'one' }),
    row('response_item', { type: 'message', role: 'user', content: [{ text: '最初の依頼' }] }),
  ].join('\n') + '\n'
  const big = row('event_msg', { type: 'exec_command_end', aggregated_output: 'x'.repeat(270 * 1024) })
  const ending = row('event_msg', { type: 'task_complete', last_agent_message: '結果A' }) + '\n'
  const encode = (value: string) => new TextEncoder().encode(value)
  let current = encode(header)
  let mtimeMs = now
  const source: Parameters<typeof pollCodex>[0] = {
    env: { get: async () => 'C:/test' },
    fs: {
      exists: async path => path === today || path === root + '/sessions',
      list: async () => [{ name: 'rollout-thread.jsonl', kind: 'file', size: current.length, mtimeMs, isLink: false }],
      stat: async () => ({ kind: 'file', size: current.length, mtimeMs, isLink: false }),
      read: async (_path, { offset = 0, length = 0 }) => {
        expect(length <= 256 * 1024).toBe(true)
        return { base64: btoa(Array.from(current.subarray(offset, offset + length), byte => String.fromCharCode(byte)).join('')) }
      },
    },
  }
  const first = await pollCodex(source, { files: [] }, now)
  current = encode(header + big)
  mtimeMs++
  const partial = await pollCodex(source, first.checkpoint, now + 2_000)
  expect(partial.checkpoint.files[0]?.offset).toBe(encode(header).length)
  expect(partial.changes[0]?.turns).toHaveLength(0)
  current = encode(header + big + '\n' + ending)
  mtimeMs++
  const finished = await pollCodex(source, partial.checkpoint, now + 4_000)
  expect(finished.checkpoint.files[0]?.offset).toBe(current.length)
  expect(finished.changes[0]?.turns[0]?.steps.map(step => step.full)).toEqual(['（長すぎるため省略: 271 KB）', '結果A'])
  expect(finished.changes[0]?.turns[0]?.status).toBe('done')
  current = encode(header.replace('最初', '別件') + big + '\n' + ending.replace('結果A', '結果B'))
  mtimeMs++
  const replaced = await pollCodex(source, finished.checkpoint, now + 6_000)
  expect(replaced.changes[0]?.turns[0]).toMatchObject({ prompt: '別件の依頼', status: 'done' })
  expect(replaced.changes[0]?.turns[0]?.steps.at(-1)?.full).toBe('結果B')
})

test('先に見つかった無関係な Codex 起動を除外し、依頼文のない照合だけ推定とする', async () => {
  const at = 30_000
  const launch: LiveRequest = {
    id: 'launch', from: 'self', to: 'codex:pending:tool', summary: '確認', verb: '調査を依頼',
    at, status: 'running', stepCount: 0, counts: {}, codexLaunch: { cwd: 'C:/work', mode: 'exec', readOnly: true },
  }
  const draft = draftWith(launch)
  const known = '調べる'.repeat(80)
  draft.changedDetails.set(launch.id, { ...emptyDetail, prompt: '  ' + known + ' 元の続き  ' })
  const observation = (id: string, prompt: string) => ({
    meta: { id, cwd: 'C:/work', originator: 'codex_exec', source: 'exec', parentThreadId: '' },
    label: id, turns: [{ id: 'turn:' + id, at, prompt, steps: [] }],
  })
  await observeCodex(draft, [observation('other', '無関係な依頼')], at)
  expect(launch.observation).toBeUndefined()
  await observeCodex(draft, [observation('right', known + ' 記録の続き')], at)
  expect(launch.observation).toBe('turn:right')
  expect(launch.estimatedObservation).toBeUndefined()
  const unknown: LiveRequest = { ...launch, id: 'unknown', awaitingPrompt: true, observation: undefined }
  const otherDraft = draftWith(unknown)
  await observeCodex(otherDraft, [observation('estimated', '記録から補う依頼文')], at)
  expect(unknown.estimatedObservation).toBe(true)
  expect(unknown.awaitingPrompt).toBeUndefined()
  expect((await otherDraft.detail(unknown.id)).prompt).toBe('記録から補う依頼文')
})

test('追加指示を送信前の同文ターンに結び付けない', async () => {
  const request: LiveRequest = {
    id: 'send', from: 'self', to: 'claude:local_child', verb: '追加の指示を送信',
    summary: '同じ依頼文', at: 200, status: 'running', stepCount: 0, counts: {}, awaitingSession: 'message',
  }
  const draft = draftWith(request)
  draft.changedDetails.set(request.id, { ...emptyDetail, prompt: '同じ依頼文' })
  const row = { id: 'local_child', label: '', startedBy: '', isRunning: false, isRemote: false, isArchived: false }
  await observeSessions(draft, [{ row, turns: [
    { id: 'past', at: 100, prompt: '同じ依頼文', steps: [], endedAt: 110, status: 'done' },
    { id: 'later', at: 210, prompt: '同じ依頼文', steps: [], endedAt: 220, status: 'done' },
  ] }], 'own')
  expect(request.observation).toBe('later')
  expect(request.endedAt).toBe(220)
  expect(draft.value.requests.find(item => item.id === 'past')?.endedAt).toBe(110)
})

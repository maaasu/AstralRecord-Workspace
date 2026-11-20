import { expect, test } from 'claude-code/testing'
import { displayMarkdown } from '../hooks/display-markdown'
import { stepIcon } from '../hooks/icons'
import type { LiveRequest, RolloutMeta } from '../types'
import { emptyBoard, emptyDetail, inferVerb, summarize } from '../hooks/model'
import { codexCaller, findCodexLaunch, sessionCaller } from '../hooks/observe'
import { collectCodexTurns, completeLines, parseCodexLine } from '../hooks/parse-codex'
import type { CodexLine } from '../hooks/parse-codex'
import { newSessionEvents, parseSessionList, parseSessionTranscript, sessionStep } from '../hooks/parse-sessions'
import { codexVerb, instructionSource, parseCodexCommand, sessionId, sessionMessageInput, startSessionInput } from '../hooks/parse-tool'

test('依頼文のタスク見出し、Markdown と文字数を扱う', () => {
  expect(summarize('前置き\n# タスク: **表示**を[修正](https://example.com)\n補足')).toBe('表示を修正')
  expect(summarize('\n> 最初の行\n2 行目')).toBe('最初の行')
  expect(summarize('🦊'.repeat(42))).toBe('🦊'.repeat(40))
  expect(['修正のレビュー', 'ログを読んで', 'implement the pane', '作業を進めて'].map(inferVerb))
    .toEqual(['レビューを依頼', '調査を依頼', '実装を依頼', '作業を依頼'])
})

test('Claude の平文ログでも操作とコマンドの失敗を区別する', () => {
  expect(stepIcon(sessionStep('tool', 'Bash echo Edit'))).toBe('terminal')
  expect(stepIcon(sessionStep('tool_use: grep', '検索する文字列'))).toBe('search')
  expect(stepIcon(sessionStep('tool', 'Glob **/*.ts'))).toBe('folder')
  expect(stepIcon(sessionStep('tool', 'mcp__ccd_session__start_session 調査'))).toBe('delegate')
  expect(stepIcon(sessionStep('tool', 'PowerShell {"command":"git status","exitCode":1}'))).toBe('error')
})

test('Codex のリダイレクト、パイプ、review サブコマンドと cwd を読む', () => {
  const redirect = parseCodexCommand('codex.exe exec -C "C:\\Work Space" -s read-only - < "docs/指示書.md"')
  const piped = parseCodexCommand('echo "差分をレビュー" | codex.cmd exec --cd=C:/work')
  const printf = parseCodexCommand("printf '調べる' | codex exec")
  const review = parseCodexCommand('codex review --uncommitted')
  if (!redirect || !piped || !printf || !review) throw new Error('コマンドを読めません')
  expect(redirect.file).toBe('docs/指示書.md')
  expect(redirect.cwd).toBe('C:\\Work Space')
  expect(instructionSource(redirect.file)).toBe('指示書: 指示書.md')
  expect(codexVerb(redirect, '内容を確認')).toBe('調査を依頼')
  expect(piped.prompt).toBe('差分をレビュー')
  expect(piped.cwd).toBe('C:/work')
  expect(codexVerb(piped, piped.prompt)).toBe('レビューを依頼')
  expect(printf.prompt).toBe('調べる')
  expect(codexVerb(printf, printf.prompt)).toBe('実装を依頼')
  expect(codexVerb(review, '')).toBe('レビューを依頼')
  expect(parseCodexCommand('echo "旧依頼" | codex exec - < "$input"')?.prompt).toBe('')
})

test('Codex のターンごとに最後のユーザー依頼と具体的な実行内容を取り出す', () => {
  const event = (type: string, payload: object = {}) => ({ type: 'event_msg', payload: { type, ...payload } })
  const user = (...texts: string[]) => ({
    type: 'response_item', payload: { type: 'message', role: 'user', content: texts.map(text => ({ text })) },
  })
  const rows = [
    event('task_started', { turn_id: 'turn-a' }),
    user('# AGENTS.md instructions\n規則', '最初の依頼'),
    user('<environment_context>\n情報', '最後の依頼\n  追記', '<INSTRUCTIONS>\n規則'),
    event('item_completed', { item: {
      type: 'CommandExecution', command: ['pwsh', '-Command', 'Get-Date'], exit_code: 1, aggregated_output: '標準出力',
    } }),
    event('item_completed', { item: {
      type: 'FileChange', changes: [{ path: 'C:/repo/a.ts', kind: { type: 'update' }, unified_diff: '@@\n+行' }],
    } }),
    event('item_completed', { item: { type: 'AgentMessage', content: [{ text: '途中\nの報告' }] } }),
    event('task_complete', { last_agent_message: '結果\n続き' }),
    event('task_started', { turn_id: 'turn-b' }),
    user('次の依頼'),
  ]
  const records = rows.flatMap((value, offset) => {
    const row = parseCodexLine(JSON.stringify(value), 100 + offset)
    return row && 'event' in row ? [{ offset, row }] : []
  }) satisfies { offset: number; row: Exclude<CodexLine, { meta: RolloutMeta }> }[]
  const parsed = collectCodexTurns('thread', records, undefined)
  expect(parsed.turns.map(turn => turn.prompt)).toEqual(['最後の依頼\n  追記', '次の依頼'])
  expect(parsed.active?.id).toBe('codex-turn:thread:turn-b')
  const first = parsed.turns[0]
  expect(first?.status).toBe('done')
  expect(first?.steps.map(step => step.icon)).toEqual(['error', 'edit', 'message', 'done'])
  expect(first?.steps[0]?.full).toBe('pwsh -Command Get-Date\n終了コード 1\n標準出力')
  expect(first?.steps[0]?.language).toBe('powershell')
  expect(first?.steps[1]?.full).toContain('C:/repo/a.ts (update)')
  expect(first?.steps[1]?.full).toContain('@@\n+行')
  expect(first?.steps[2]?.full).toBe('途中\nの報告')
  expect(first?.steps[3]?.full).toBe('結果\n続き')
  expect(parseCodexLine('{broken')).toBeUndefined()
})

test('originator・時刻・cwd で Codex exec を照合し、親スレッドとアプリを区別する', async () => {
  const meta: RolloutMeta = {
    id: 'thread', cwd: 'C:/WORK/Project/', originator: 'codex_exec', source: 'exec', timestamp: 10_000, parentThreadId: '',
  }
  const request: LiveRequest = {
    id: 'launch', from: 'self', to: 'codex:pending:tool', summary: '確認', verb: '調査を依頼',
    at: 10_000, status: 'running', stepCount: 1, counts: {}, codexLaunch: { cwd: 'c:\\work\\project', mode: 'exec', readOnly: true },
  }
  const draft = { value: { ...emptyBoard, requests: [request] }, changedDetails: new Map(),
    opened: new Set<string>(), detail: async () => emptyDetail }
  expect((await findCodexLaunch(draft, meta, 5_000))?.request.id).toBe('launch')
  expect((await findCodexLaunch(draft, meta, 190_000))?.request.id).toBe('launch')
  expect(await findCodexLaunch(draft, meta, 4_999)).toBeUndefined()
  expect(await findCodexLaunch(draft, meta, 190_001)).toBeUndefined()
  expect(await findCodexLaunch(draft, { ...meta, cwd: 'C:/another' }, 10_001)).toBeUndefined()
  expect(await findCodexLaunch(draft, { ...meta, originator: 'Codex Desktop' }, 10_001)).toBeUndefined()
  expect(await findCodexLaunch(draft, { ...meta, parentThreadId: 'parent' }, 10_001)).toBeUndefined()
  request.observation = 'old'
  expect(await findCodexLaunch(draft, meta, 10_001)).toBeUndefined()
  expect(codexCaller(meta).id).toBe('unknown')
  expect(codexCaller({ ...meta, originator: 'Codex Desktop' }).id).toBe('user')
  expect(codexCaller({ ...meta, parentThreadId: 'parent' }).id).toBe('codex:parent')
  const row = parseCodexLine(JSON.stringify({ type: 'session_meta', payload: {
    id: meta.id, cwd: meta.cwd, originator: meta.originator, source: meta.source,
    timestamp: new Date(meta.timestamp ?? 0).toISOString(), parent_thread_id: 'parent',
  } }))
  expect(row && 'meta' in row ? row.meta : undefined).toEqual({ ...meta, parentThreadId: 'parent' })
})

test('UTF-8 の未完成行を次の読み取りに残す', () => {
  const bytes = new TextEncoder().encode('途中の尾\n{"text":"再開"}\r\n{"text":"日本"}\n')
  const split = bytes.length - 6
  const first = completeLines(bytes.subarray(0, split), true)
  expect(first.lines.map(line => JSON.parse(line.text))).toEqual([{ text: '再開' }])
  expect(completeLines(bytes.subarray(first.consumed), first.skipping).lines.map(line => JSON.parse(line.text)))
    .toEqual([{ text: '日本' }])
})

test('Codex の空入力と自動通知から依頼を作らず、前のターンに本文を記録する', () => {
  const event = (type: string, payload: object = {}) => ({ type: 'event_msg', payload: { type, ...payload } })
  const user = (text: string) => ({ type: 'response_item',
    payload: { type: 'message', role: 'user', content: [{ text }] } })
  const parse = (values: unknown[]) => values.flatMap((value, offset) => {
    const row = parseCodexLine(JSON.stringify(value), 100 + offset)
    return row && 'event' in row ? [{ offset, row }] : []
  })
  const first = collectCodexTurns('thread', parse([
    event('task_started', { turn_id: 'one' }), user('   '), user('最初の依頼'), event('task_complete'),
  ]), undefined)
  const body = '<local-command-stdout>\n' + '全文'.repeat(2_100) + '\n</local-command-stdout>'
  const result = collectCodexTurns('thread', parse([
    event('task_started', { turn_id: 'two' }), user(body), event('task_complete'),
  ]).map(record => ({ ...record, offset: record.offset + 10 })), first.active, first.lastRequest)
  expect(result.turns.map(turn => turn.id)).toEqual(['codex-turn:thread:one'])
  expect(result.turns[0]?.steps[0]?.title).toBe('バックグラウンド作業の完了通知')
  expect(result.turns[0]?.steps[0]?.full).toBe(body.slice(0, 4_000))
  expect(result.turns[0]?.status).toBeUndefined()
})

test('Claude のセッション識別子と依頼文、平文ログの増分を読む', () => {
  expect(startSessionInput({ title: '確認', target: 'cloud', prompt: '依頼', background: '背景' }))
    .toEqual({ title: '確認', remote: true, prompt: '依頼\n\n背景' })
  expect(sessionId('Started session: session_abc-de')).toBe('session_abc-de')
  expect(sessionId('Started session: local_abc-de')).toBe('local_abc-de')
  expect(sessionMessageInput('SendMessage', { to: 'bridge:session_abc-de', message: '追加' }))
    .toEqual({ id: 'session_abc-de', prompt: '追加' })
  const [local, remote] = parseSessionList(JSON.stringify([
    { sessionId: 'local_a', title: '局所', startedBy: 'local_self', isRunning: true },
    { sessionId: 'session_b', title: '遠隔', startedBy: 'local_a' },
  ]))
  expect(local?.isRemote).toBe(false)
  expect(remote?.isRemote).toBe(true)
  expect(sessionCaller(local?.startedBy ?? '', 'self').id).toBe('self')
  expect(sessionCaller(remote?.startedBy ?? '', 'self').id).toBe('claude:local_a')
  expect(sessionCaller('', 'self').id).toBe('user')
  const transcript = ['Session overview', '', '[user] 依頼', '', '[assistant] 本文', '続き', '', '[tool] Bash git status'].join('\n')
  expect(parseSessionTranscript(transcript)).toEqual([
    { tag: 'user', body: '依頼' }, { tag: 'assistant', body: '本文\n続き' }, { tag: 'tool', body: 'Bash git status' },
  ])
  const first = newSessionEvents(transcript, [])
  expect(newSessionEvents('[assistant] 本文\n続き\n\n[tool] Bash git status\n\n[tool] Bash git status', first.seen).events)
    .toEqual([{ tag: 'tool', body: 'Bash git status' }])
  const grown = newSessionEvents(transcript + ' --short\n\n[assistant] 続き', first.seen)
  expect(grown.revised).toEqual({ tag: 'tool', body: 'Bash git status --short' })
  expect(grown.events).toEqual([{ tag: 'assistant', body: '続き' }])
})

test('取得した Markdown のリンク、画像、URL と絶対パスを無効にし、コード内は保持する', () => {
  const tick = '\x60'
  const fence = tick.repeat(3)
  const input = [
    '[設計](https://example.test/docs_(v1)) ![画像](file:///tmp/preview.png)',
    'http://example.test file:///tmp/out C:\\work\\file.md /tmp/input.txt',
    '<https://example.test/> [参照][doc]',
    '[doc]: https://example.test/ref',
    tick + 'https://example.test/in-code /tmp/raw' + tick,
    fence + 'md', '[そのまま](https://example.test/fenced)', fence,
  ].join('\n')
  const result = displayMarkdown(input)
  expect(result).toContain('設計（' + tick + 'https://example.test/docs_(v1)' + tick + '）')
  expect(result).toContain('画像（' + tick + 'file:///tmp/preview.png' + tick + '）')
  expect(result).toContain(['http://example.test', 'file:///tmp/out', 'C:\\work\\file.md', '/tmp/input.txt']
    .map(value => tick + value + tick).join(' '))
  expect(result).toContain(tick + 'https://example.test/' + tick)
  expect(result).not.toContain('[doc]:')
  expect(result).toContain(tick + 'https://example.test/in-code /tmp/raw' + tick)
  expect(result).toContain(fence + 'md\n[そのまま](https://example.test/fenced)\n' + fence)
})

test('平文の行頭とコードフェンス内の角括弧を結果と誤認しない', () => {
  const fence = '\x60'.repeat(3)
  const input = ['[user] 修正', '', '[assistant] 回答例', '[result] 続き', '',
    fence + 'text', '', '[result] コード例', fence, '', '[result] failed to finish'].join('\n')
  const events = parseSessionTranscript(input)
  expect(events.map(event => event.tag)).toEqual(['user', 'assistant', 'result'])
  expect(events[1]?.body).toContain('[result] 続き')
  expect(events[1]?.body).toContain('[result] コード例')
  expect(sessionStep(events[2]!.tag, events[2]!.body).kind).toBe('error')
  expect(sessionStep('result', '処理に失敗しました').kind).toBe('error')
  expect(sessionStep('result', '完了\n報告書の見出しは failed です').kind).toBe('done')
})

test('Codex の同じ操作の開始と終了を別周期でも一つにし、ID がなければ同種の開始を使う', () => {
  const event = (type: string, payload: object = {}) => JSON.stringify({ type: 'event_msg', payload: { type, ...payload } })
  const records = (start: number, values: string[]) => values.flatMap((line, index) => {
    const offset = start + index
    const row = parseCodexLine(line, offset)
    return row && 'event' in row ? [{ offset, row }] : []
  })
  const first = collectCodexTurns('thread', records(100, [
    event('task_started', { turn_id: 'turn-a' }),
    event('exec_command_begin', { call_id: 'shared', command: ['git', 'status'] }),
    event('patch_apply_begin', { id: 'patch', changes: { 'a.ts': 'update' } }),
    event('exec_command_begin', { command: ['ls'] }),
  ]), undefined)
  const second = collectCodexTurns('thread', records(200, [
    event('exec_command_end', { call_id: 'shared', exit_code: 2, aggregated_output: 'failed' }),
    event('patch_apply_end', { id: 'patch', stdout: 'applied' }),
    event('exec_command_end', { exit_code: 0, output: 'listed' }),
    event('item_started', { item: { type: 'CommandExecution', id: 'long', command: 'x'.repeat(4_500) } }),
    event('item_completed', { item: { type: 'CommandExecution', id: 'long', exit_code: 3, output: '最後の出力' } }),
    event('task_complete'),
    event('task_started', { turn_id: 'turn-b' }),
    event('exec_command_begin', { call_id: 'shared', command: ['git', 'diff'] }),
    event('exec_command_end', { call_id: 'shared', exit_code: 0, output: 'diff' }),
  ]), first.active)
  const initial = first.turns[0]!.steps
  const updated = second.turns[0]!.steps
  expect(updated.slice(0, 3).map(step => step.id)).toEqual(initial.map(step => step.id))
  expect(updated.slice(0, 3).map(step => step.full)).toEqual([
    'git status\n終了コード 2\nfailed', 'a.ts (update)\napplied', 'ls\n終了コード 0\nlisted',
  ])
  expect(updated[0]?.kind).toBe('error')
  expect(updated[3]?.full).toContain('終了コード 3\n最後の出力')
  expect(second.turns[1]!.steps).toHaveLength(1)
  expect(second.turns[1]!.steps[0]?.id).not.toBe(updated[0]?.id)
})

test('引用符付きの直接引数を取得し、オプションの値は依頼文にしない', () => {
  expect(parseCodexCommand('codex exec "指示"')?.prompt).toBe('指示')
  expect(parseCodexCommand("codex exec -C 'C:/Work Space' -m 'model' '直接の指示'")?.prompt).toBe('直接の指示')
  expect(parseCodexCommand('codex exec --cd="C:/Work Space" --sandbox "read-only" "直接の指示"')?.prompt).toBe('直接の指示')
  expect(parseCodexCommand('codex review --base "develop"')?.prompt).toBe('')
})

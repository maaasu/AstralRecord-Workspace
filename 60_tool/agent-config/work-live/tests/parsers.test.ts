import assert from 'node:assert/strict'
import { test } from 'node:test'
import { text } from '../public/app.js'
import { completeLines } from '../src/jsonl.ts'
import type { LiveRequest, RolloutMeta } from '../types/index.d.ts'
import { inferVerb, summarize } from '../src/model.ts'
import { codexCaller, findCodexLaunch } from '../src/observe.ts'
import { collectCodexTurns, parseCodexLine } from '../src/parse-codex.ts'
import type { CodexLine } from '../src/parse-codex.ts'
import { codexVerb, instructionSource, parseCodexCommand, sessionId, sessionMessageInput, startSessionInput } from '../src/parse-tool.ts'
import { newDraft } from '../src/state.ts'

test('依頼文のタスク見出し、Markdown と文字数を扱う', () => {
  assert.equal(summarize('前置き\n# タスク: **表示**を[修正](https://example.com)\n補足'), '表示を修正')
  assert.equal(summarize('\n> 最初の行\n2 行目'), '最初の行')
  assert.equal(summarize('🦊'.repeat(42)), '🦊'.repeat(40))
  assert.deepEqual(['修正のレビュー', 'ログを読んで', 'implement the pane', '作業を進めて'].map(inferVerb), ['レビューを依頼', '調査を依頼', '実装を依頼', '作業を依頼'])
})

test('要約では冒頭のタグを飛ばし、スキルだけの依頼では名前または本文を使う', () => {
  const wrapped = [
    ' <skill><name>見本スキル</name>\n# タスク: スキルの説明\n</skill>',
    '<environment_context>作業環境</environment_context>',
    '<user_instructions>共通の指示</user_instructions>',
    '<system-reminder>確認事項</system-reminder>',
    '<task-notification>別作業の通知</task-notification>',
    '<tool_context source="fixture" />',
    '# **画面を更新**',
  ].join('\r\n')
  assert.equal(summarize(wrapped), '画面を更新')
  assert.equal(summarize('<skill>\n<name>見本スキル</name>\n手順\n</skill>'), '見本スキル')
  assert.equal(summarize('<skill>\nname: 包装済みの名前\n手順\n</skill>'), '包装済みの名前')
  assert.equal(summarize('<skill>\n# 最初の手順\n次の手順\n</skill>'), '最初の手順')
  assert.equal(summarize('<environment_context />'), '')
  assert.equal(summarize('<skill>\n切り詰められた説明'), '切り詰められた説明')
  const withWrapper = (tag: string) => `<${tag}>補足</${tag}>\n画面を直す`
  for (const prompt of [wrapped, withWrapper('environment_context'), withWrapper('system-reminder'), withWrapper('task-notification')]) {
    const parsed = parseCodexLine(JSON.stringify({ type: 'response_item', payload: {
      type: 'message', role: 'user', content: [{ text: prompt }],
    } }))
    assert.deepEqual(parsed && 'event' in parsed ? parsed.event : undefined, { kind: 'prompt', prompt })
  }
})

test('Codex のリダイレクト、パイプ、review サブコマンドと cwd を読む', () => {
  const redirect = parseCodexCommand('codex.exe exec -C "C:\\Work Space" -s read-only - < "docs/指示書.md"')
  const piped = parseCodexCommand('echo "差分をレビュー" | codex.cmd exec --cd=C:/work')
  const printf = parseCodexCommand("printf '調べる' | codex exec")
  const review = parseCodexCommand('codex review --uncommitted')
  if (!redirect || !piped || !printf || !review) throw new Error('コマンドを読めません')
  assert.equal(redirect.file, 'docs/指示書.md')
  assert.equal(redirect.cwd, 'C:\\Work Space')
  assert.equal(instructionSource(redirect.file), '指示書: 指示書.md')
  assert.equal(codexVerb(redirect, '内容を確認'), '調査を依頼')
  assert.equal(piped.prompt, '差分をレビュー')
  assert.equal(piped.cwd, 'C:/work')
  assert.equal(codexVerb(piped, piped.prompt), 'レビューを依頼')
  assert.equal(printf.prompt, '調べる')
  assert.equal(codexVerb(printf, printf.prompt), '実装を依頼')
  assert.equal(codexVerb(review, ''), 'レビューを依頼')
  assert.equal(parseCodexCommand('echo "旧依頼" | codex exec - < "$input"')?.prompt, '')
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
  assert.deepEqual(parsed.turns.map(turn => turn.prompt), ['最後の依頼\n  追記', '次の依頼'])
  assert.equal(parsed.active?.id, 'codex-turn:thread:turn-b')
  const first = parsed.turns[0]
  assert.equal(first?.status, 'done')
  assert.deepEqual(first?.steps.map(step => step.icon), ['error', 'edit', 'message', 'done'])
  assert.equal(first?.steps[0]?.full, 'pwsh -Command Get-Date\n終了コード 1\n標準出力')
  assert.equal(first?.steps[0]?.language, 'powershell')
  assert.ok((first?.steps[1]?.full)?.includes('C:/repo/a.ts (update)'))
  assert.ok((first?.steps[1]?.full)?.includes('@@\n+行'))
  assert.equal(first?.steps[2]?.full, '途中\nの報告')
  assert.equal(first?.steps[3]?.full, '結果\n続き')
  assert.equal(parseCodexLine('{broken'), undefined)
})

test('originator・時刻・cwd で Codex exec を照合し、親スレッドとアプリを区別する', () => {
  const meta: RolloutMeta = {
    id: 'thread', cwd: 'C:/WORK/Project/', originator: 'codex_exec', source: 'exec', timestamp: 10_000, parentThreadId: '',
  }
  const request: LiveRequest = {
    id: 'launch', from: 'self', to: 'codex:pending:tool', summary: '確認', verb: '調査を依頼',
    at: 10_000, status: 'running', stepCount: 1, counts: {}, codexLaunch: { cwd: 'c:\\work\\project', mode: 'exec', readOnly: true },
  }
  const draft = newDraft()
  draft.value.requests.push(request)
  assert.equal(findCodexLaunch(draft, meta, 5_000)?.request.id, 'launch')
  assert.equal(findCodexLaunch(draft, meta, 190_000)?.request.id, 'launch')
  assert.equal(findCodexLaunch(draft, meta, 4_999), undefined)
  assert.equal(findCodexLaunch(draft, meta, 190_001), undefined)
  assert.equal(findCodexLaunch(draft, { ...meta, cwd: 'C:/another' }, 10_001), undefined)
  assert.equal(findCodexLaunch(draft, { ...meta, originator: 'Codex Desktop' }, 10_001), undefined)
  assert.equal(findCodexLaunch(draft, { ...meta, parentThreadId: 'parent' }, 10_001), undefined)
  request.observation = 'old'
  assert.equal(findCodexLaunch(draft, meta, 10_001), undefined)
  assert.equal(codexCaller(meta).id, 'unknown')
  assert.equal(codexCaller({ ...meta, originator: 'Codex Desktop' }).id, 'user')
  assert.equal(codexCaller({ ...meta, parentThreadId: 'parent' }).id, 'codex:parent')
  const row = parseCodexLine(JSON.stringify({ type: 'session_meta', payload: {
    id: meta.id, cwd: meta.cwd, originator: meta.originator, source: meta.source,
    timestamp: new Date(meta.timestamp ?? 0).toISOString(), parent_thread_id: 'parent',
  } }))
  assert.deepEqual(row && 'meta' in row ? row.meta : undefined, { ...meta, parentThreadId: 'parent' })
  const nested = parseCodexLine(JSON.stringify({ type: 'session_meta', payload: {
    id: 'child', originator: 'Codex Desktop', source: { subagent: { thread_spawn: { parent_thread_id: 'parent' } } },
  } }))
  assert.equal(nested && 'meta' in nested ? codexCaller(nested.meta).id : '', 'codex:parent')
  assert.equal(codexCaller({ ...meta, originator: 'Codex Desktop', source: 'vscode' }).id, 'user')
  assert.equal(codexCaller(meta, [
    { id: 'far', cwd: meta.cwd, label: '別の作業', isRunning: true, lastActiveAt: 18_000 },
    { id: 'actual', cwd: 'c:\\work\\project', label: '確認', isRunning: false, lastActiveAt: 10_100 },
  ], 10_000, 20_000).label, 'Claude（確認）')
})

test('UTF-8 の未完成行を次の読み取りに残す', () => {
  const bytes = new TextEncoder().encode('途中の尾\n{"text":"再開"}\r\n{"text":"日本"}\n')
  const split = bytes.length - 6
  const first = completeLines(bytes.subarray(0, split), true)
  assert.deepEqual(first.lines.map(line => JSON.parse(line.text)), [{ text: '再開' }])
  assert.deepEqual(completeLines(bytes.subarray(first.consumed), first.skipping).lines.map(line => JSON.parse(line.text)), [{ text: '日本' }])
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
  assert.deepEqual(result.turns.map(turn => turn.id), ['codex-turn:thread:one'])
  assert.equal(result.turns[0]?.steps[0]?.title, 'バックグラウンド作業の完了通知')
  assert.equal(result.turns[0]?.steps[0]?.full, body.slice(0, 4_000))
  assert.equal(result.turns[0]?.status, undefined)
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
  assert.deepEqual(updated.slice(0, 3).map(step => step.id), initial.map(step => step.id))
  assert.deepEqual(updated.slice(0, 3).map(step => step.full), [
    'git status\n終了コード 2\nfailed', 'a.ts (update)\napplied', 'ls\n終了コード 0\nlisted',
  ])
  assert.equal(updated[0]?.kind, 'error')
  assert.ok((updated[3]?.full)?.includes('終了コード 3\n最後の出力'))
  assert.equal((second.turns[1]!.steps)?.length, 1)
  assert.notEqual(second.turns[1]!.steps[0]?.id, updated[0]?.id)
})

test('引用符付きの直接引数を取得し、オプションの値は依頼文にしない', () => {
  assert.equal(parseCodexCommand('codex exec "指示"')?.prompt, '指示')
  assert.equal(parseCodexCommand("codex exec -C 'C:/Work Space' -m 'model' '直接の指示'")?.prompt, '直接の指示')
  assert.equal(parseCodexCommand('codex exec --cd="C:/Work Space" --sandbox "read-only" "直接の指示"')?.prompt, '直接の指示')
  assert.equal(parseCodexCommand('codex exec -o "result.md" "直接の指示"')?.prompt, '直接の指示')
  assert.equal(parseCodexCommand('codex review --base "develop"')?.prompt, '')
})

test('開始ツールと追加指示の宛先・依頼文を読み取る', () => {
  assert.deepEqual(startSessionInput({ title: '確認', target: 'cloud', prompt: '依頼', background: '背景' }),
    { title: '確認', remote: true, prompt: '依頼\n\n背景' })
  assert.equal(sessionId('Started session: session_abc-de'), 'session_abc-de')
  assert.equal(sessionId('Started session: local_abc-de'), 'local_abc-de')
  const uuid = '01234567-89ab-4cde-8012-3456789abcde'
  assert.equal(sessionId('{"sessionId":"' + uuid + '"}'), uuid)
  assert.deepEqual(sessionMessageInput('SendMessage', { to: 'bridge:local_abc-de', message: '追加' }),
    { id: 'local_abc-de', prompt: '追加' })
})

test('Markdown、URL、パス、HTML は依頼文とコマンドにプレーンテキストとして残る', () => {
  const captured = { textContent: '', set innerHTML(_value: string) { assert.fail('ログを HTML として扱いました') } }
  const prompt = '# 確認\n[設計](https://example.test/) ![画像](file:///tmp/preview.png)\nC:\\作業\\資料.md <script>"確認"</script>'
  text(captured, prompt)
  assert.equal(captured.textContent, prompt)
  text(captured, 'echo "<script>"')
  assert.equal(captured.textContent, 'echo "<script>"')
})

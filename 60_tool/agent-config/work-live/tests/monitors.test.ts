import assert from 'node:assert/strict'
import { appendFile, utimes, writeFile } from 'node:fs/promises'
import { join } from 'node:path'
import { test } from 'node:test'
import type { LiveRequest } from '../types/index.d.ts'
import { boardFor } from '../src/board.ts'
import { createMonitor } from '../src/monitor.ts'
import { attachSession, claudeActor, matchSessionRequest, observeCodex, resolveCodexCallers } from '../src/observe.ts'
import { createRequest, detailOf, editRequest, newDraft } from '../src/state.ts'
import { USER } from '../src/model.ts'
import { pollCodex, sessionFolders } from '../src/watch-codex.ts'
import { assistantMessage, call, claude, codexRows, jsonl, result, temporaryHome, userMessage, writeFixture, writeWorkFixture } from './fixtures/records.ts'

const now = new Date(2026, 0, 2, 10).getTime()
const codexEvent = (at: number, type: string, fields: object) => ({
  type: 'event_msg', timestamp: new Date(at).toISOString(), payload: { type, ...fields },
})

test('Claude の JSONL から依頼、Codex、Agent、別セッションの実行内容を結び付ける', async t => {
  const home = await temporaryHome(t)
  const fixture = await writeWorkFixture(home, now)
  const monitor = createMonitor(home)
  await monitor.poll(now)
  const { draft, sessions } = monitor
  const { requests, actors } = draft.value
  assert.equal(draft.value.monitors.sessions.status, 'ok')
  assert.equal(draft.value.monitors.codex.status, 'ok')
  assert.equal(requests.length, 6)
  assert.ok(!actors.some(actor => actor.kind === 'claude-remote'))
  assert.equal(sessions.find(session => session.id === 'parent')?.isRunning, true)
  assert.equal(actors.find(actor => actor.id === 'claude:parent')?.label, 'Claude（見本の親セッション）')
  assert.equal(actors.find(actor => actor.id === 'claude:child')?.label, 'Claude（別の作業を進める）')
  assert.equal(actors.find(actor => actor.id === 'codex:cli')?.label, 'Codex（照合した Codex）')
  const parent = requests.find(request => request.to === 'claude:parent')!
  assert.equal(parent.from, USER.id)
  assert.equal(parent.status, 'done')
  assert.equal(parent.result, '確認しました')
  const detail = detailOf(draft, parent.id)
  assert.equal(detail.prompt, fixture.prompt)
  assert.equal(detail.steps.filter(step => step.id.endsWith(':raw:tool')).length, 1)
  const raw = detail.steps.find(step => step.id.endsWith(':raw:tool'))!
  assert.equal(raw.kind, 'error')
  assert.equal(raw.full, fixture.command + '\n終了コード 7\n<script>"出力"</script>')
  assert.equal(detail.steps.at(-1)?.icon, 'bell')
  assert.equal(detail.steps.at(-1)?.full, fixture.notice.slice(0, 4_000))
  const agent = requests.find(request => request.to === 'agent:parent:a1')!
  const codex = requests.find(request => request.to === 'codex:cli')!
  const child = requests.find(request => request.to === 'claude:child')!
  for (const request of [agent, child, codex]) {
    assert.ok(request)
    assert.equal(request.from, 'claude:parent')
    assert.equal(request.parentId, parent.id)
    assert.equal(request.status, 'done')
  }
  assert.equal(codex.verb, '調査を依頼')
  assert.equal(detailOf(draft, codex.id).promptSource, 'コマンド引数')
  assert.ok(detailOf(draft, agent.id).steps.some(step => step.icon === 'file' && step.full.includes('ファイル本文')))
  assert.equal(detailOf(draft, child.id).prompt, '別の作業を進める\n\n背景')
  assert.equal(detailOf(draft, child.id).promptSource, 'セッション開始ツール')
  assert.equal(requests.find(request => request.to === 'codex:desktop')?.from, USER.id)
  assert.equal(requests.find(request => request.to === 'codex:subagent')?.from, 'codex:desktop')
  assert.equal(actors.find(actor => actor.id === 'codex:desktop')?.label, 'Codex（アプリの名前）')

  const selected = boardFor(draft, sessions, null, 'hour', now)
  assert.equal(selected.tabs[0]?.id, 'codex:desktop')
  assert.equal(selected.session, 'codex:desktop')
  assert.equal(selected.activeCount, 1)
  assert.equal(selected.tabLimit, 8)
  const parentBoard = boardFor(draft, sessions, 'claude:parent', 'all', now)
  assert.equal(parentBoard.heading?.label, 'Claude（見本の親セッション）')
  assert.equal(parentBoard.flows[0]?.rows.length, 4)
  await monitor.poll(now + 4_000)
  assert.equal(draft.value.requests.length, 6)
  assert.deepEqual(detailOf(draft, parent.id), detail)
})

test('Claude の未終了ターンは 3 分で応答なしになり、追記で実行中に戻る', async t => {
  const home = await temporaryHome(t)
  const transcript = join(home, '.claude', 'projects', 'fixture', 'unfinished.jsonl')
  const start = now - 180_000
  await writeFixture(transcript, jsonl(claude(start, 'user', 'first', null, userMessage('試験を進める'))), start)
  const work = createMonitor(home)
  await work.poll(now)
  assert.equal(boardFor(work.draft, work.sessions, 'all', 'all', now).flows[0]?.rows[0]?.status, 'idle')
  const next = now + 4_000
  await appendFile(transcript, jsonl(claude(next, 'assistant', 'tool', 'first',
    assistantMessage([call('ls', 'Bash', { command: 'ls' })], 'tool_use'))))
  await utimes(transcript, new Date(next), new Date(next))
  await work.poll(next)
  assert.equal(boardFor(work.draft, work.sessions, 'all', 'running', next).flows[0]?.rows[0]?.status, 'running')
  assert.equal(work.draft.value.requests[0]?.stepCount, 1)
})

test('PowerShell のフォアグラウンド呼び出しを先に見つかった rollout に結び付ける', async t => {
  const home = await temporaryHome(t)
  const cwd = join(home, 'workspace')
  const at = now - 10_000
  const path = join(home, '.claude', 'projects', 'fixture', 'parent.jsonl')
  const rollout = join(sessionFolders(join(home, '.codex'), now)[0]!, 'rollout-cli.jsonl')
  const prompt = '画面の差分を確認する'
  await writeFixture(path, jsonl(claude(at - 2_000, 'user', 'prompt', null, {
    ...userMessage('画面の作業を頼む'), cwd: join(home, 'initial'),
  })), now)
  await writeFixture(rollout, jsonl(...codexRows('cli', prompt, at + 1_000, cwd, 'exec', 'codex_exec', false),
    codexEvent(at + 2_000, 'exec_command_end', { command: ['git', 'diff'], exit_code: 0 })), now)
  const work = createMonitor(home)
  await work.poll(now)
  assert.ok(work.draft.value.requests.some(request => request.id === 'codex-turn:cli:task-cli'))

  const command = `codex exec -C (Get-Location).Path -s read-only -o "result.md" "${prompt}"`
  await appendFile(path, jsonl(
    claude(at - 1_000, 'assistant', 'cwd', 'prompt', { ...assistantMessage([], 'tool_use'), cwd }),
    claude(at, 'assistant', 'cli', 'cwd', assistantMessage([call('fg', 'PowerShell', { command })], 'tool_use')),
  ))
  await utimes(path, new Date(now + 4_000), new Date(now + 4_000))
  await work.poll(now + 4_000)
  const launch = work.draft.value.requests.find(request => request.id === 'claude-tool:parent:fg')!
  assert.equal(launch.observation, 'codex-turn:cli:task-cli')
  assert.equal(launch.status, 'running')
  assert.equal(launch.estimatedObservation, undefined)
  assert.deepEqual(detailOf(work.draft, launch.id).steps.map(step => step.full), ['git diff\n終了コード 0'])

  const endedAt = now + 5_000
  await appendFile(rollout, jsonl(codexEvent(endedAt, 'task_complete', { last_agent_message: '差分を確認した' })))
  await appendFile(path, jsonl(claude(endedAt + 500, 'user', 'result', 'cli', {
    ...userMessage([result('fg', 'シェルの出力')]), toolUseResult: { exitCode: 0 },
  })))
  await work.poll(now + 8_000)
  assert.equal(launch.status, 'done')
  assert.equal(launch.endedAt, endedAt)
  assert.equal(launch.result, '差分を確認した')
  assert.equal(detailOf(work.draft, launch.id).promptSource, 'コマンド引数')
  assert.deepEqual(detailOf(work.draft, launch.id).steps.map(step => step.kind), ['command', 'done'])
  assert.equal(work.draft.value.requests.filter(request => request.to === 'codex:cli').length, 1)
})

test('Bash のバックグラウンド起動と終了を区別し、長い rollout の記録を優先する', async t => {
  const home = await temporaryHome(t)
  const cwd = join(home, 'workspace')
  const at = now - 4 * 60_000
  const path = join(home, '.claude', 'projects', 'fixture', 'parent.jsonl')
  const rollout = join(sessionFolders(join(home, '.codex'), now)[0]!, 'rollout-cli.jsonl')
  const endings = [['completed', 'done'], ['failed', 'error'], ['killed', 'stopped']] as const
  const prompt = '長い作業の依頼'
  await writeFixture(path, jsonl(
    claude(at - 500, 'user', 'prompt', null, { ...userMessage('作業を依頼する'), cwd }),
    claude(at, 'assistant', 'tools', 'prompt', assistantMessage(endings.map(([id]) => call(id, 'Bash', {
      command: id === 'completed'
        ? 'codex exec -C "$(pwd -W)" -s workspace-write --color never -o "result.md" - < "brief.md" > "codex.log" 2>&1'
        : `codex exec -C "$(pwd -W)" "${id} の作業"`,
      run_in_background: true,
    })), 'tool_use')),
    claude(at + 100, 'user', 'started', 'tools', userMessage(endings.map(([id]) =>
      result(id, 'Command running in background with ID: ' + id)))),
  ), now)
  const head = jsonl(...codexRows('cli', prompt, at + 1_000, cwd, 'exec', 'codex_exec', false))
  const filler = jsonl({ type: 'response_item', payload: { type: 'reasoning', text: 'x'.repeat(24_000) } }).repeat(48)
  await writeFixture(rollout, head + filler, now)
  const work = createMonitor(home)
  await work.poll(now)
  const launches = endings.map(([id]) => work.draft.value.requests.find(request => request.id === 'claude-tool:parent:' + id)!)
  for (const launch of launches) {
    assert.equal(launch.status, 'running')
    assert.equal(launch.observation, undefined)
    assert.equal(launch.codexLaunch?.shellEndedAt, undefined)
  }

  const noticesAt = now + 3_000
  await appendFile(path, jsonl(...endings.map(([id], index) => claude(noticesAt + index, 'user', 'notice-' + id, 'started',
    userMessage('<task-notification>\n<task-id>bg-' + id + '</task-id>\n<tool-use-id>' + id + '</tool-use-id>\n' +
      '<status>' + id + '</status>\n<summary>シェルからの終了通知</summary>\n</task-notification>')))))
  await work.poll(now + 4_000)
  launches.forEach((launch, index) => {
    assert.equal(launch.status, endings[index]![1])
    assert.equal(launch.endedAt, noticesAt + index)
    assert.equal(launch.result, 'シェルからの終了通知')
  })

  await appendFile(rollout, jsonl(
    codexEvent(now + 2_000, 'exec_command_end', { turn_id: 'task-cli', command: ['node', '--test'], exit_code: 0 }),
    codexEvent(now + 2_500, 'task_complete', { turn_id: 'task-cli', last_agent_message: '検証を完了した' }),
  ))
  await work.poll(now + 8_000)
  const launch = launches[0]!
  const detail = detailOf(work.draft, launch.id)
  assert.equal(launch.to, 'codex:cli')
  assert.equal(launch.observation, 'codex-turn:cli:task-cli')
  assert.equal(launch.status, 'done')
  assert.equal(launch.endedAt, now + 2_500)
  assert.equal(launch.result, '検証を完了した')
  assert.equal(detail.prompt, prompt)
  assert.equal(detail.promptSource, 'Codex の記録')
  assert.deepEqual(detail.steps.map(step => step.kind), ['command', 'done'])
  assert.equal(work.draft.value.requests.filter(request => request.to === 'codex:cli').length, 1)
})

test('Codex の末尾・分割 UTF-8・過大行・追記・同じ長さでの置換を扱う', async t => {
  const home = await temporaryHome(t)
  const root = join(home, '.codex')
  const path = join(sessionFolders(root, now)[0]!, 'rollout-one.jsonl')
  const index = join(root, 'session_index.jsonl')
  const [meta, started, prompt] = codexRows('one', '試験の依頼', now - 4_000, home, 'exec', 'codex_exec', false)
  const header = jsonl(meta) + 'x'.repeat(67_000) + '\n' + jsonl(started, prompt)
  const event = (type: string, payload: object) => ({ type: 'event_msg', timestamp: new Date(now).toISOString(), payload: { type, ...payload } })
  const long = JSON.stringify(event('exec_command_end', { output: 'x'.repeat(270 * 1024) }))
  const end = jsonl(event('task_complete', { last_agent_message: '結果は日本語' }))
  const encoder = new TextEncoder()
  const appended = encoder.encode('\n' + end)
  const split = encoder.encode('\n' + end.slice(0, end.indexOf('日'))).length + 1
  await writeFixture(path, header, now)
  await writeFixture(index, '{"id":"one","thread_name":"見本のスレッド名"}', now)
  const first = await pollCodex(root, { files: new Map() }, now)
  assert.equal(first.monitor.status, 'ok')
  assert.equal(first.changes[0]?.label, '見本のスレッド名')
  assert.equal(first.changes[0]?.turns[0]?.prompt, '試験の依頼')
  assert.equal(first.checkpoint.files.get(path)?.bytes.offset, encoder.encode(header).length)
  await appendFile(path, long)
  const partial = await pollCodex(root, first.checkpoint, now + 2_000)
  assert.equal(partial.checkpoint.files.get(path)?.bytes.offset, encoder.encode(header).length)
  assert.equal(partial.changes[0]?.turns.length, 0)
  await appendFile(path, appended.subarray(0, split))
  const splitResult = await pollCodex(root, partial.checkpoint, now + 4_000)
  assert.deepEqual(splitResult.changes[0]?.turns[0]?.steps.map(step => step.full), ['（長すぎるため省略: 271 KB）'])
  await appendFile(path, appended.subarray(split))
  const finished = await pollCodex(root, splitResult.checkpoint, now + 6_000)
  assert.equal(finished.changes[0]?.turns[0]?.status, 'done')
  assert.equal(finished.changes[0]?.turns[0]?.steps.at(-1)?.full, '結果は日本語')
  const same = await pollCodex(root, finished.checkpoint, now + 8_000)
  assert.equal(same.changes[0]?.turns.length, 0)
  assert.equal(same.checkpoint.files.get(path)?.bytes, finished.checkpoint.files.get(path)?.bytes)
  await writeFile(path, header.replace('試験', '別件') + long + '\n' + end.replace('日本語', '別結果'))
  const replaced = await pollCodex(root, same.checkpoint, now + 10_000)
  assert.equal(replaced.changes[0]?.turns[0]?.prompt, '別件の依頼')
  assert.equal(replaced.changes[0]?.turns[0]?.steps.at(-1)?.full, '結果は別結果')
})

test('先に見つかった無関係な Codex 起動を除外し、依頼文のない照合だけ推定とする', () => {
  const at = 30_000
  const makeLaunch = (id: string, draft: ReturnType<typeof newDraft>) => {
    const request = createRequest(draft, id, claudeActor('parent'), { id: 'pending', kind: 'codex', label: 'Codex' }, '調査を依頼', '', at)
    request.codexLaunch = { cwd: 'C:/work', mode: 'exec', readOnly: true }
    return request
  }
  const draft = newDraft()
  const launch = makeLaunch('launch', draft)
  const known = '調べる'.repeat(80)
  editRequest(draft, launch, { prompt: '  ' + known + ' 元の続き  ' })
  const observation = (id: string, prompt: string) => ({
    meta: { id, cwd: 'C:/work', originator: 'codex_exec', source: 'exec', parentThreadId: '' },
    label: id, turns: [{ id: 'turn:' + id, at, prompt, steps: [] }],
  })
  observeCodex(draft, [observation('other', '無関係な依頼')], at)
  assert.equal(launch.observation, undefined)
  observeCodex(draft, [observation('right', known + ' 記録の続き')], at)
  assert.equal(launch.observation, 'turn:right')
  assert.equal(launch.estimatedObservation, undefined)
  const otherDraft = newDraft()
  const unknown = makeLaunch('unknown', otherDraft)
  observeCodex(otherDraft, [observation('estimated', '記録から補う依頼文')], at)
  assert.equal(unknown.estimatedObservation, true)
  assert.equal(detailOf(otherDraft, unknown.id).prompt, '記録から補う依頼文')
})

test('CLI の呼び出し元は直近の Claude 記録が見つかった時点で更新される', () => {
  const draft = newDraft()
  observeCodex(draft, [{ meta: { id: 'cli', cwd: 'C:/WORK/Guide/', originator: 'codex_exec', source: 'exec', parentThreadId: '',
    timestamp: now - 10_000 }, label: '見本', turns: [{ id: 'turn', at: now - 10_000, prompt: 'レビュー', steps: [] }] }], now)
  assert.equal(draft.value.requests[0]?.from, 'unknown')
  resolveCodexCallers(draft, [
    { id: 'old', cwd: 'c:/work/guide', label: '過去', isRunning: false, lastActiveAt: now - 31 * 60_000 },
    { id: 'nearest', cwd: 'c:/work/guide', label: '今回', isRunning: false, lastActiveAt: now - 20_000 },
  ], now)
  assert.equal(draft.value.requests[0]?.from, 'claude:nearest')
})

test('先に見つけた Claude の子セッションをまとめ、追加指示を送信前の同文ターンに結び付けない', () => {
  const draft = newDraft()
  const child = claudeActor('child', '子セッション')
  const launch = createRequest(draft, 'launch', claudeActor('parent'), claudeActor('pending'), '作業を依頼', '依頼', 100)
  launch.awaitingSession = 'start'
  editRequest(draft, launch, { prompt: '依頼\n\n背景', promptSource: 'セッション開始ツール' })
  const turn = createRequest(draft, 'first', USER, child, '作業を依頼', '依頼', 110)
  turn.observation = 'first'
  editRequest(draft, turn, { prompt: '依頼', status: 'done', endedAt: 115 })
  attachSession(draft, launch, 'local_child', 120)
  assert.equal(draft.value.requests.length, 1)
  assert.equal(launch.observation, 'first')
  assert.equal(launch.endedAt, 115)
  assert.equal(detailOf(draft, launch.id).prompt, '依頼\n\n背景')
  const extra: LiveRequest = createRequest(draft, 'send', claudeActor('parent'), child, '追加の指示を送信', '同じ依頼', 200)
  extra.awaitingSession = 'message'
  editRequest(draft, extra, { prompt: '同じ依頼' })
  assert.equal(matchSessionRequest(draft, child, '同じ依頼', 100), undefined)
  assert.equal(matchSessionRequest(draft, child, '同じ依頼', 210), extra)
})

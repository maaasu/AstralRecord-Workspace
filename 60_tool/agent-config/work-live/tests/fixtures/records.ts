import { mkdir, mkdtemp, rm, utimes, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { dirname, join, resolve } from 'node:path'
import process from 'node:process'
import type { TestContext } from 'node:test'
import { sessionFolders } from '../../src/watch-codex.ts'

export async function temporaryHome(t: TestContext) {
  const root = resolve(tmpdir())
  const home = await mkdtemp(join(root, 'work-live-test-'))
  if (dirname(home) !== root) throw new Error('検証用フォルダの作成先が不正です')
  t.after(() => rm(home, { recursive: true, force: true }))
  return home
}

export function jsonl(...rows: unknown[]) {
  return rows.map(row => JSON.stringify(row) + '\n').join('')
}

export async function writeFixture(path: string, contents: string | Uint8Array, now: number) {
  await mkdir(dirname(path), { recursive: true })
  await writeFile(path, contents)
  await utimes(path, new Date(now), new Date(now))
}

export function claude(at: number, type: string, uuid: string, parentUuid: string | null, data: object) {
  return { type, uuid, parentUuid, timestamp: new Date(at).toISOString(), ...data }
}

export const userMessage = (content: unknown) => ({ message: { role: 'user', content } })
export const assistantMessage = (content: unknown, stop_reason: string) => ({ message: { role: 'assistant', content, stop_reason } })
export const textBlock = (text: string) => ({ type: 'text', text })
export const call = (id: string, name: string, input: object) => ({ type: 'tool_use', id, name, input })
export const result = (tool_use_id: string, content: unknown, is_error = false) => ({ type: 'tool_result', tool_use_id, content, is_error })

export function codexRows(id: string, prompt: string, at: number, cwd: string, source: string | object,
  originator = 'Codex Desktop', done = true) {
  function event(type: string, payload: object, time = at) {
    return { timestamp: new Date(time).toISOString(), type, payload }
  }
  return [
    event('session_meta', { id, cwd, originator, source, timestamp: new Date(at).toISOString() }),
    event('event_msg', { type: 'task_started', turn_id: 'task-' + id }),
    event('response_item', { type: 'message', role: 'user', content: [textBlock(prompt)] }),
    ...(done ? [event('event_msg', { type: 'task_complete', last_agent_message: '確認完了' }, at + 1_000)] : []),
  ]
}

export async function writeWorkFixture(home: string, now: number) {
  const cwd = join(home, 'workspace')
  const claudeRoot = join(home, '.claude')
  const project = join(claudeRoot, 'projects', 'workspace')
  const codexRoot = join(home, '.codex')
  const rollout = sessionFolders(codexRoot, now)[0]!
  const prompt = '# タスク: 表示を確認\n\n[資料](https://example.test/) <script>"依頼"</script>'
  const command = 'echo "<script>window.injected = true</script>"'
  const notice = '<task-notification>' + '通知'.repeat(2_101) + '</task-notification>'
  const at = now - 10_000
  const parentPath = join(project, 'parent.jsonl')
  const agentPath = join(project, 'parent', 'subagents', 'agent-a1.jsonl')
  const childPath = join(project, 'child.jsonl')
  await Promise.all([
    writeFixture(parentPath, jsonl(
      claude(at, 'user', 'meta', null, { ...userMessage('メタ入力'), isMeta: true, cwd }),
      claude(at, 'user', 'slash', 'meta', { ...userMessage('/help'), cwd }),
      claude(at, 'user', 'prompt', 'slash', { ...userMessage(prompt), cwd }),
      claude(at + 500, 'attachment', 'attachment', 'prompt', {}),
      claude(at + 1_000, 'assistant', 'tools', 'attachment', assistantMessage([
        call('cli', 'PowerShell', { command: 'codex exec -s read-only "照合する依頼"' }),
        call('agent', 'Agent', { prompt: '差分を調査', description: '差分調査', subagent_type: 'Explore' }),
        call('child', 'mcp__ccd_session__start_session', { title: '関連作業', prompt: '別の作業を進める', background: '背景', target: 'local' }),
        call('cloud', 'mcp__ccd_session__start_session', { title: '非表示', prompt: '遠隔の作業', target: 'cloud' }),
        call('raw', 'Bash', { command }),
      ], 'tool_use')),
      claude(at + 2_000, 'user', 'result-cli', 'tools', { ...userMessage([result('cli', '終了しました')]), toolUseResult: { exitCode: 0 } }),
      claude(at + 2_500, 'user', 'result-agent', 'result-cli', { ...userMessage([result('agent', '調査完了')]), toolUseResult: { agentId: 'a1' } }),
      claude(at + 2_800, 'user', 'result-child', 'result-agent', { ...userMessage([result('child', [textBlock('Started session: local_child')])]) }),
      claude(at + 3_000, 'user', 'result-raw', 'result-child', { ...userMessage([result('raw', '<script>"出力"</script>', true)]),
        toolUseResult: { exit_code: 7 } }),
      claude(at + 3_000, 'assistant', 'answer', 'result-raw', assistantMessage([textBlock('確認しました')], 'end_turn')),
      claude(at + 3_100, 'system', 'stop', 'answer', { subtype: 'stop_hook_summary', preventedContinuation: false }),
      claude(at + 3_500, 'user', 'notice', 'stop', userMessage(notice)),
    ), now),
    writeFixture(agentPath, jsonl(
      claude(at + 1_200, 'user', 'agent-prompt', null, { ...userMessage('差分を調査'), agentId: 'a1', isSidechain: true }),
      claude(at + 1_300, 'assistant', 'agent-tool', 'agent-prompt', { ...assistantMessage([
        call('read', 'Read', { file_path: 'example.txt' }),
      ], 'tool_use'), isSidechain: true }),
      claude(at + 1_500, 'user', 'agent-result', 'agent-tool', { ...userMessage([result('read', 'ファイル本文')]), isSidechain: true }),
      claude(at + 2_000, 'assistant', 'agent-answer', 'agent-result', { ...assistantMessage([textBlock('調査完了')], 'end_turn'),
        isSidechain: true }),
    ), now),
    writeFixture(join(project, 'parent', 'subagents', 'agent-a1.meta.json'), JSON.stringify({ agentType: 'Explore', finished: true,
      toolUseId: 'agent', spawnDepth: 1 }), now),
    writeFixture(childPath, jsonl(
      claude(at + 2_200, 'user', 'child-prompt', null, userMessage('別の作業を進める')),
      claude(at + 4_000, 'assistant', 'child-answer', 'child-prompt', assistantMessage([textBlock('別作業を完了')], 'end_turn')),
    ), now),
    writeFixture(join(project, 'parent', 'custom-title.json'), '{"customTitle":"見本の親セッション"}', now),
    writeFixture(join(claudeRoot, 'sessions', process.pid + '.json'), JSON.stringify({
      pid: process.pid, sessionId: 'parent', hostSessionId: 'local_host-parent', cwd, startedAt: at - 1_000,
    }), now),
    writeFixture(join(project, 'session_cloud.jsonl'), jsonl(claude(at, 'user', 'cloud-prompt', null, userMessage('遠隔の作業'))), now),
    writeFixture(join(rollout, 'rollout-cli.jsonl'), jsonl(...codexRows('cli', '照合する依頼', at + 1_100, cwd, 'exec', 'codex_exec')), now),
    writeFixture(join(rollout, 'rollout-desktop.jsonl'), jsonl(...codexRows('desktop', 'アプリで作業', at + 5_000, cwd, 'exec',
      'Codex Desktop', false)), now),
    writeFixture(join(rollout, 'rollout-subagent.jsonl'), jsonl(...codexRows('subagent', '補助の作業', at + 5_400, cwd,
      { subagent: { thread_spawn: { parent_thread_id: 'desktop' } } })), now),
    writeFixture(join(codexRoot, 'session_index.jsonl'), jsonl({ id: 'cli', thread_name: '照合した Codex' },
      { id: 'desktop', thread_name: 'アプリの名前' }), now),
  ])
  return { cwd, prompt, command, notice, parentPath, childPath, agentPath, at }
}

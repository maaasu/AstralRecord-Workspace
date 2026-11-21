import { basename, join } from 'node:path'
import process from 'node:process'
import type { ClaudeSession, Monitor } from '../types/index.d.ts'
import { entries, fileStamp, inspectHead, isDirectory, readJsonl, smallJson } from './jsonl.ts'
import type { JsonlCursor, SmallJson } from './jsonl.ts'
import { dateOf, FLOW_WINDOW, recordOf, summarize, textOf, unavailable } from './model.ts'
import { claudePrompt, claudeRow, cloudSession, isClaudeStart } from './parse-claude.ts'
import type { ClaudeRow } from './parse-claude.ts'
import { sessionKey } from './parse-tool.ts'

export type AgentRecord = { id: string; name: string; toolUseId: string; finished: boolean; depth: number }
export type Transcript = {
  path: string
  sessionId: string
  reset: boolean
  fromTail: boolean
  records: ClaudeRow[]
  agent?: AgentRecord
}
type Head = { cwd?: string; summary?: string; startedAt?: number; remote?: boolean }
type TranscriptCursor = { bytes: JsonlCursor; head: Head }
export type ClaudeCheckpoint = {
  files: Map<string, TranscriptCursor>
  metadata: Map<string, SmallJson>
  sessions: ClaudeSession[]
  aliases: Map<string, string>
}

function processAlive(pid: number): boolean {
  try {
    process.kill(pid, 0)
    return true
  } catch (error) {
    return recordOf(error).code === 'EPERM'
  }
}

async function processSessions(root: string, readJson: (path: string) => Promise<Record<string, unknown>>) {
  const sessions = new Map<string, ClaudeSession>()
  const aliases = new Map<string, string>()
  const stopped = new Set<string>()
  for (const path of await entries(join(root, 'sessions'), 'file')) {
    const filename = basename(path)
    if (!/^\d+\.json$/u.test(filename)) continue
    const pid = Number(filename.slice(0, -5))
    if (!Number.isSafeInteger(pid) || pid < 1 || pid > 0x7fffffff) continue
    const row = await readJson(path)
    const id = sessionKey(textOf(row.sessionId))
    if (!id || cloudSession(row) || (row.pid !== undefined && pid !== row.pid)) continue
    const host = sessionKey(textOf(row.hostSessionId))
    if (host) aliases.set(host, id)
    if (!processAlive(pid)) {
      stopped.add(id)
      continue
    }
    sessions.set(id, { id, label: '', cwd: textOf(row.cwd), isRunning: true,
      startedAt: dateOf(row.startedAt), lastActiveAt: dateOf(row.updatedAt) ?? dateOf(row.statusUpdatedAt) })
  }
  return { sessions, aliases, stopped }
}

function fillHead(head: Head, row: ClaudeRow, sidechain: boolean) {
  head.remote ||= cloudSession(row.data)
  head.cwd ||= textOf(row.data.cwd)
  head.startedAt ??= dateOf(row.data.timestamp)
  if (!head.summary && (sidechain || row.data.isSidechain !== true)) {
    const prompt = claudePrompt(row)
    if (prompt) head.summary = summarize(prompt) || '作業'
  }
}

async function readTranscript(path: string, stamp: NonNullable<Awaited<ReturnType<typeof fileStamp>>>,
  prior: TranscriptCursor | undefined, now: number, sidechain: boolean) {
  const fallback = Math.min(stamp.mtimeMs, now)
  const log = await readJsonl(path, stamp, prior?.bytes, { isStart: isClaudeStart })
  const head: Head = log.initial ? {} : { ...prior?.head }
  if (log.fromTail) await inspectHead(path, stamp.size, text => {
    const row = claudeRow({ offset: 0, text }, fallback)
    if (row) fillHead(head, row, sidechain)
    return !!(head.remote || (head.summary && head.cwd && head.startedAt !== undefined))
  })
  const records: ClaudeRow[] = []
  for (const line of log.lines) {
    const row = claudeRow(line, fallback)
    if (!row) continue
    fillHead(head, row, sidechain)
    if (['user', 'assistant', 'system', 'attachment', 'last-prompt', 'oversized'].includes(row.type) &&
      (sidechain || row.data.isSidechain !== true)) records.push(row)
  }
  return { bytes: log.cursor, head, records, reset: log.initial, fromTail: log.fromTail }
}

async function transcripts(root: string, now: number, aliases: Map<string, string>) {
  const paths = new Map<string, { path: string; sessionId: string; stamp: NonNullable<Awaited<ReturnType<typeof fileStamp>>>; agentId?: string }>()
  async function add(path: string, sessionId: string, agentId?: string) {
    const id = aliases.get(sessionKey(sessionId)) ?? sessionKey(sessionId)
    if (sessionId.startsWith('session_') || !id) return
    const stamp = await fileStamp(path)
    if (!stamp || now - stamp.mtimeMs > FLOW_WINDOW) return
    const key = id + '/' + (agentId ?? '')
    const other = paths.get(key)
    if (!other || stamp.mtimeMs > other.stamp.mtimeMs) paths.set(key, { path, sessionId: id, stamp, agentId })
  }
  for (const project of await entries(root, 'directory')) {
    for (const file of await entries(project, 'file')) {
      if (file.endsWith('.jsonl')) await add(file, basename(file, '.jsonl'))
    }
    for (const folder of await entries(project, 'directory')) {
      const sessionId = basename(folder)
      if (sessionId.startsWith('session_')) continue
      const agents = join(folder, 'subagents')
      if (!(await isDirectory(agents))) continue
      for (const file of await entries(agents, 'file')) {
        const name = basename(file)
        if (/^agent-.+\.jsonl$/u.test(name)) await add(file, sessionId, basename(file, '.jsonl').slice(6))
      }
    }
  }
  return [...paths.values()]
}

export async function pollClaude(root: string, prior: ClaudeCheckpoint, now: number): Promise<{
  checkpoint: ClaudeCheckpoint; changes: Transcript[]; stopped: Set<string>; monitor: Monitor
}> {
  const projectRoot = join(root, 'projects')
  const files = new Map<string, TranscriptCursor>()
  const metadata = new Map<string, SmallJson>()
  const cache = new Map(prior.metadata)
  const changes: Transcript[] = []
  let failure: Monitor | undefined
  async function readJson(path: string) {
    try {
      const row = await smallJson(path, cache)
      const entry = cache.get(path)
      if (entry) metadata.set(path, entry)
      return row
    } catch (error) {
      failure ??= unavailable(error, 'Claude のセッション情報を読めません')
      return cache.get(path)?.row ?? {}
    }
  }
  try {
    if (!(await isDirectory(projectRoot))) throw new Error('Claude の projects フォルダがありません')
    const { sessions, aliases, stopped } = await processSessions(root, readJson)
    for (const id of sessions.keys()) stopped.delete(id)
    for (const session of prior.sessions) {
      if (session.isRunning && !sessions.has(session.id)) stopped.add(session.id)
    }
    for (const { path, stamp, sessionId, agentId } of await transcripts(projectRoot, now, aliases)) {
      const previous = prior.files.get(path)
      try {
        const log = await readTranscript(path, stamp, previous, now, agentId !== undefined)
        if (log.head.remote) continue
        files.set(path, { bytes: log.bytes, head: log.head })
        const active = sessions.get(sessionId)
        let agent: AgentRecord | undefined
        if (agentId !== undefined) {
          const info = await readJson(path.slice(0, -6) + '.meta.json')
          agent = { id: agentId, name: textOf(info.agentType) || textOf(info.description),
            toolUseId: textOf(info.toolUseId), finished: info.finished === true,
            depth: typeof info.spawnDepth === 'number' ? info.spawnDepth : 1 }
        } else {
          const folder = path.slice(0, -6)
          const title = await isDirectory(folder) ? await readJson(join(folder, 'custom-title.json')) : {}
          sessions.set(sessionId, {
            id: sessionId, cwd: active?.cwd || log.head.cwd || '',
            label: textOf(title.customTitle).trim() || log.head.summary || '',
            isRunning: active?.isRunning ?? false, startedAt: active?.startedAt ?? log.head.startedAt,
            lastActiveAt: Math.max(active?.lastActiveAt ?? 0, Math.min(stamp.mtimeMs, now)),
          })
        }
        changes.push({ path, sessionId, reset: log.reset, fromTail: log.fromTail, records: log.records, agent })
      } catch (error) {
        if (previous) files.set(path, previous)
        failure ??= unavailable(error, 'Claude の記録を読めません')
      }
    }
    const checkpoint = { files, metadata, sessions: [...sessions.values()], aliases }
    return { checkpoint, changes, stopped, monitor: failure ?? { status: 'ok' } }
  } catch (error) {
    return { checkpoint: prior, changes, stopped: new Set(), monitor: unavailable(error, 'Claude の記録を読めません') }
  }
}

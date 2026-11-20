import type { EngineInterface, FsBytes, FsEntry, FsReadBytesOptions } from 'claude-code'
import type { CodexCheckpoint, CodexCursor, Monitor, RolloutMeta } from '../types'
import { FLOW_WINDOW, recordOf, summarize, textOf, unavailable } from './model'
import { collectCodexTurns, completeLines, parseCodexLine, parseJson } from './parse-codex'
import type { CodexLine, CodexTurn } from './parse-codex'

const TAIL_BYTES = 64 * 1024
const READ_BYTES = 256 * 1024
const MAX_LINE_BYTES = 256 * 1024
const INITIAL_BYTES = 1024 * 1024

export type CodexObservation = { meta: RolloutMeta; label: string; turns: CodexTurn[] }
type Files = Pick<EngineInterface['fs'], 'exists' | 'list' | 'stat'> & {
  read(path: string, options: FsReadBytesOptions): Promise<FsBytes>
}
type Host = { env: Pick<EngineInterface['env'], 'get'>; fs: Files }

export function sessionFolders(root: string, now: number): string[] {
  const day = new Date(now)
  return [0, 1].map(back => {
    const date = new Date(day.getFullYear(), day.getMonth(), day.getDate() - back)
    const month = String(date.getMonth() + 1).padStart(2, '0')
    const number = String(date.getDate()).padStart(2, '0')
    return root + '/sessions/' + date.getFullYear() + '/' + month + '/' + number
  })
}

async function bytesAt($: Host, path: string, offset: number, length: number): Promise<Uint8Array> {
  const { base64 } = await $.fs.read(path, { as: 'bytes', offset, length })
  return Uint8Array.from(atob(base64), character => character.charCodeAt(0))
}

async function readFirstSummary($: Host, path: string, end: number): Promise<string | undefined> {
  let skipping = false
  for (let offset = 0; offset < end;) {
    const bytes = await bytesAt($, path, offset, Math.min(READ_BYTES, end - offset))
    if (!bytes.length) break
    const { lines, consumed } = completeLines(bytes, skipping)
    for (const line of lines) {
      const row = parseCodexLine(line.text)
      if (row && 'event' in row && row.event.kind === 'prompt') return summarize(row.event.prompt) || '作業'
    }
    skipping = consumed === 0 || (skipping && !bytes.includes(10))
    offset += consumed || bytes.length
  }
}

async function readRollout($: Host, path: string, entry: FsEntry, prior: CodexCursor | undefined, now: number) {
  let cursor = prior && entry.size >= prior.size ? prior : undefined
  if (cursor && entry.mtimeMs > cursor.mtimeMs && cursor.scannedTo > 0) {
    if (await readBoundary($, path, cursor.scannedTo) !== cursor.boundary) cursor = undefined
  }
  if (cursor?.size === entry.size && cursor.scannedTo >= entry.size &&
    (cursor.mtimeMs === entry.mtimeMs || cursor.offset === cursor.scannedTo)) {
    return { cursor: { ...cursor, mtimeMs: entry.mtimeMs }, turns: [] }
  }

  const start = cursor ? (cursor.oversized || cursor.skipping ? cursor.scannedTo : cursor.offset)
    : prior ? 0 : await initialOffset($, path, entry.size)
  const startsMidLine = !cursor && start > 0 && (await bytesAt($, path, start - 1, 1))[0] !== 10
  const { offset, scannedTo, skipping, oversized, lines } = await readRolloutLines(
    $, path, Math.min(entry.size, start + INITIAL_BYTES), start, cursor?.offset ?? start,
    cursor?.skipping ?? startsMidLine, cursor?.oversized ?? false,
  )
  const records: { offset: number; row: Exclude<CodexLine, { meta: RolloutMeta }> }[] = []
  let meta = cursor?.meta ?? {
    id: entry.name.match(/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?=\.jsonl$)/iu)?.[0]
      ?? entry.name.slice('rollout-'.length, -'.jsonl'.length),
    cwd: '', originator: '', source: '', parentThreadId: '',
  }
  if (!cursor && start > 0) {
    const header = await bytesAt($, path, 0, Math.min(entry.size, READ_BYTES))
    const newline = header.indexOf(10)
    const row = newline < 0 ? undefined : parseCodexLine(new TextDecoder().decode(header.subarray(0, newline)))
    if (row && 'meta' in row) meta = { ...row.meta, id: row.meta.id || meta.id }
  }
  for (const line of lines) {
    const row: CodexLine | undefined = 'text' in line ? parseCodexLine(line.text, now) : {
      at: now, event: { kind: 'step', step: {
        kind: 'tool', title: '長い記録を省略', full: '（長すぎるため省略: ' + Math.ceil(line.bytes / 1024) + ' KB）',
      } },
    }
    if (!row) continue
    if ('meta' in row) meta = { ...row.meta, id: row.meta.id || meta.id }
    else records.push({ offset: line.offset, row })
  }
  const collected = collectCodexTurns(meta.id, records, cursor?.turn, cursor?.lastRequest)
  const firstSummary = cursor?.firstSummary ?? (!cursor && start > 0
    ? await readFirstSummary($, path, entry.size) : undefined) ?? collected.firstSummary
  const boundary = cursor?.scannedTo === scannedTo ? cursor.boundary : await readBoundary($, path, scannedTo)
  return {
    cursor: {
      path, size: entry.size, mtimeMs: entry.mtimeMs, boundary, scannedTo, offset, skipping, oversized, meta,
      ...(firstSummary ? { firstSummary } : {}),
      ...(collected.active ? { turn: collected.active } : {}),
      ...(collected.lastRequest ? { lastRequest: collected.lastRequest } : {}),
    },
    turns: collected.turns,
  }
}

async function readBoundary($: Host, path: string, offset: number): Promise<string> {
  const length = Math.min(offset, 1024)
  if (!length) return ''
  return (await $.fs.read(path, { as: 'bytes', offset: offset - length, length })).base64
}

async function initialOffset($: Host, path: string, size: number): Promise<number> {
  const tail = Math.max(0, size - TAIL_BYTES)
  if (!tail) return 0
  const bytes = await bytesAt($, path, tail - 1, size - tail + 1)
  const startsMidLine = bytes[0] !== 10
  const hasTurn = completeLines(bytes.subarray(1), startsMidLine).lines.some(line => {
    const row = parseCodexLine(line.text)
    return row && 'event' in row && row.event.kind === 'start'
  })
  return hasTurn ? tail : Math.max(0, size - INITIAL_BYTES)
}

type RolloutLine = { offset: number; text: string } | { offset: number; bytes: number }

async function readRolloutLines(
  $: Host, path: string, end: number, start: number, committed: number, skipping: boolean, oversized: boolean,
) {
  const lines: RolloutLine[] = []
  const line = new Uint8Array(MAX_LINE_BYTES)
  const decoder = new TextDecoder()
  let cursor = start
  for (; cursor < end;) {
    const chunk = await bytesAt($, path, cursor, Math.min(READ_BYTES, end - cursor))
    if (!chunk.length) break
    for (let part = 0; part < chunk.length;) {
      const newline = chunk.indexOf(10, part)
      const limit = newline < 0 ? chunk.length : newline
      const length = cursor + limit - committed
      if (!skipping && !oversized) {
        oversized = length > MAX_LINE_BYTES
        if (!oversized) line.set(chunk.subarray(part, limit), cursor + part - committed)
      }
      if (newline < 0) break
      if (!skipping) lines.push(oversized ? { offset: committed, bytes: length }
        : { offset: committed, text: decoder.decode(line.subarray(0, length)) })
      committed = cursor + newline + 1
      skipping = false
      oversized = false
      part = newline + 1
    }
    cursor += chunk.length
  }
  return { lines, offset: committed, scannedTo: cursor, skipping, oversized }
}

async function readIndex($: Host, path: string, ids: Set<string>, prior: CodexCheckpoint['index']) {
  if (ids.size === 0 || !(await $.fs.exists(path))) return undefined
  const stat = await $.fs.stat(path)
  const version = JSON.stringify([stat.size, stat.mtimeMs, [...ids].sort()])
  if (prior?.version === version) return prior

  const titles = new Map<string, string>()
  let skipping = false
  for (let offset = 0; offset < stat.size;) {
    const chunk = await bytesAt($, path, offset, Math.min(READ_BYTES, stat.size - offset))
    if (chunk.length === 0) break
    const lines = completeLines(chunk, skipping)
    const isLast = offset + chunk.length >= stat.size
    if (isLast && !lines.skipping && lines.consumed < chunk.length) {
      const last = new TextDecoder().decode(chunk.subarray(lines.consumed))
      lines.lines.push({ offset: lines.consumed, text: last })
    }
    for (const line of lines.lines) {
      const entry = recordOf(parseJson(line.text))
      const id = textOf(entry.id)
      const name = textOf(entry.thread_name).trim()
      if (ids.has(id) && name) titles.set(id, name)
    }
    skipping = lines.skipping || (!isLast && lines.consumed === 0)
    offset += isLast || lines.consumed === 0 ? chunk.length : lines.consumed
  }
  return { version, titles: [...titles].map(([id, name]) => ({ id, name })) }
}

export async function pollCodex($: Host, prior: CodexCheckpoint, now: number): Promise<{
  checkpoint: CodexCheckpoint
  monitor: Monitor
  changes: CodexObservation[]
}> {
  const changes: CodexObservation[] = []
  const files: CodexCursor[] = []
  let index = prior.index
  let failure: Monitor | undefined
  try {
    const home = await $.env.get('USERPROFILE') || await $.env.get('HOME')
    if (!home) throw new Error('USERPROFILE / HOME がありません')
    const root = home.replace(/[\\/]+$/u, '') + '/.codex'
    if (!(await $.fs.exists(root + '/sessions'))) throw new Error('Codex の sessions フォルダがありません')
    const cursors = new Map(prior.files.map(cursor => [cursor.path, cursor]))
    const turns = new Map<string, CodexTurn[]>()

    for (const directory of sessionFolders(root, now)) {
      if (!(await $.fs.exists(directory))) continue
      const entries = await $.fs.list(directory)
      for (const entry of entries) {
        if (entry.kind !== 'file' || entry.isLink || !/^rollout-[^\\/]+\.jsonl$/u.test(entry.name) ||
          now - entry.mtimeMs > FLOW_WINDOW) continue
        const path = directory + '/' + entry.name
        const previous = cursors.get(path)
        try {
          const rollout = await readRollout($, path, entry, previous, now)
          files.push(rollout.cursor)
          turns.set(path, rollout.turns)
        } catch (error) {
          if (previous) files.push(previous)
          failure = unavailable(error, 'Codex の記録を読めません')
        }
      }
    }
    try {
      index = await readIndex($, root + '/session_index.jsonl', new Set(files.map(file => file.meta.id)), index)
    } catch (error) {
      failure = unavailable(error, 'Codex のスレッド名を取得できません')
    }
    const titles = new Map(index?.titles.map(title => [title.id, title.name]))
    for (const file of files) {
      changes.push({
        meta: file.meta,
        label: titles.get(file.meta.id) || file.firstSummary || '',
        turns: turns.get(file.path) ?? [],
      })
    }
    return { changes, checkpoint: { files, ...(index ? { index } : {}) }, monitor: failure ?? { status: 'ok' } }
  } catch (error) {
    return { changes, checkpoint: prior, monitor: unavailable(error, 'Codex の記録を読めません') }
  }
}

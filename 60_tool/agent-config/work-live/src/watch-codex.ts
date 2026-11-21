import { basename, join } from 'node:path'
import type { CodexTurnRef, Monitor, RolloutMeta, TurnRef } from '../types/index.d.ts'
import { entries, fileStamp, inspectHead, isDirectory, readJsonl } from './jsonl.ts'
import type { JsonlCursor } from './jsonl.ts'
import { FLOW_WINDOW, parseJson, recordOf, summarize, textOf, unavailable } from './model.ts'
import { collectCodexTurns, parseCodexLine } from './parse-codex.ts'
import type { CodexLine, CodexRecord, CodexTurn } from './parse-codex.ts'

export type CodexObservation = { meta: RolloutMeta; label: string; turns: CodexTurn[] }
type TurnHead = { id: string; records: [CodexRecord, CodexRecord] }
type RolloutCursor = {
  bytes: JsonlCursor
  meta: RolloutMeta
  firstSummary?: string
  headTurn?: TurnHead
  turn?: CodexTurnRef
  lastRequest?: TurnRef
}
type Index = { cursor: JsonlCursor; titles: Map<string, string> }
export type CodexCheckpoint = { files: Map<string, RolloutCursor>; index?: Index }

export function sessionFolders(root: string, now: number): string[] {
  const day = new Date(now)
  return [0, 1].map(back => {
    const date = new Date(day.getFullYear(), day.getMonth(), day.getDate() - back)
    const month = String(date.getMonth() + 1).padStart(2, '0')
    const number = String(date.getDate()).padStart(2, '0')
    return join(root, 'sessions', String(date.getFullYear()), month, number)
  })
}

async function readRollout(path: string, stamp: NonNullable<Awaited<ReturnType<typeof fileStamp>>>,
  previous: RolloutCursor | undefined, now: number) {
  const log = await readJsonl(path, stamp, previous?.bytes, { isStart: text => {
    const row = parseCodexLine(text)
    return !!row && 'event' in row && row.event.kind === 'start'
  } })
  const prior = log.initial ? undefined : previous
  const name = basename(path, '.jsonl')
  const id = name.match(/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/iu)?.[0] ?? name.slice('rollout-'.length)
  let meta = prior?.meta ?? { id, cwd: '', originator: '', source: '', parentThreadId: '' }
  let firstSummary = prior?.firstSummary
  let headTurn = prior?.headTurn
  if (log.fromTail) {
    let start: { id: string; record: CodexRecord } | undefined
    await inspectHead(path, stamp.size, (text, offset) => {
      const row = parseCodexLine(text)
      if (!row) return false
      if ('meta' in row) meta = { ...row.meta, id: row.meta.id || id }
      else if (row.event.kind === 'start') start = { id: row.event.turnId, record: { offset, row } }
      else if (row.event.kind === 'end') start = undefined
      else if (row.event.kind === 'prompt') {
        firstSummary = summarize(row.event.prompt) || '作業'
        if (start?.id) headTurn = { id: start.id, records: [start.record, { offset, row }] }
        return true
      }
      return false
    })
  }
  const records: CodexRecord[] = []
  for (const line of log.lines) {
    const row: CodexLine | undefined = 'text' in line ? parseCodexLine(line.text, now) : {
      at: now, event: { kind: 'step', step: {
        kind: 'tool', title: '長い記録を省略', full: '（長すぎるため省略: ' + Math.ceil(line.bytes / 1024) + ' KB）',
      } },
    }
    if (!row) continue
    if ('meta' in row) meta = { ...row.meta, id: row.meta.id || id }
    else records.push({ offset: line.offset, row })
  }
  if (headTurn) {
    // A long turn can begin before the tail window. Join the two ranges using its recorded ID.
    const first = records.find(({ row }) => row.event.kind === 'start' || row.turnId)
    if (first) {
      if (first.row.event.kind !== 'start' && first.row.turnId === headTurn.id) {
        const offset = records[0]!.offset
        records.unshift(...headTurn.records.filter(record => record.offset < offset))
      }
      headTurn = undefined
    }
  }
  const collected = collectCodexTurns(meta.id, records, prior?.turn, prior?.lastRequest)
  const cursor: RolloutCursor = {
    bytes: log.cursor, meta, firstSummary: firstSummary ?? collected.firstSummary, headTurn,
    turn: collected.active, lastRequest: collected.lastRequest,
  }
  return { cursor, turns: collected.turns }
}

async function readIndex(path: string, previous: Index | undefined) {
  const stamp = await fileStamp(path)
  if (!stamp) return undefined
  let cursor = previous?.cursor
  let titles = previous?.titles ?? new Map<string, string>()
  for (;;) {
    const log = await readJsonl(path, stamp, cursor, { readLastLine: true })
    if (log.initial) titles = new Map()
    else if (log.lines.length && titles === previous?.titles) titles = new Map(titles)
    for (const line of log.lines) {
      if (!('text' in line)) continue
      const row = recordOf(parseJson(line.text))
      const id = textOf(row.id)
      const title = textOf(row.thread_name).trim()
      if (id && title) titles.set(id, title)
    }
    if (log.cursor.readTo >= stamp.size || log.cursor.readTo === cursor?.readTo) return { cursor: log.cursor, titles }
    cursor = log.cursor
  }
}

export async function pollCodex(root: string, prior: CodexCheckpoint, now: number): Promise<{
  checkpoint: CodexCheckpoint; monitor: Monitor; changes: CodexObservation[]
}> {
  const files = new Map<string, RolloutCursor>()
  const turns = new Map<string, CodexTurn[]>()
  let index = prior.index
  let failure: Monitor | undefined
  try {
    if (!(await isDirectory(join(root, 'sessions')))) throw new Error('Codex の sessions フォルダがありません')
    for (const directory of sessionFolders(root, now)) {
      for (const path of await entries(directory, 'file')) {
        if (!/^rollout-.+\.jsonl$/u.test(basename(path))) continue
        const previous = prior.files.get(path)
        try {
          const stamp = await fileStamp(path)
          if (!stamp || now - stamp.mtimeMs > FLOW_WINDOW) continue
          const rollout = await readRollout(path, stamp, previous, now)
          files.set(path, rollout.cursor)
          turns.set(path, rollout.turns)
        } catch (error) {
          if (previous) files.set(path, previous)
          failure ??= unavailable(error, 'Codex の記録を読めません')
        }
      }
    }
    try {
      index = await readIndex(join(root, 'session_index.jsonl'), index)
    } catch (error) {
      failure ??= unavailable(error, 'Codex のスレッド名を取得できません')
    }
    const changes = [...files].map(([path, file]) => ({
      meta: file.meta, label: index?.titles.get(file.meta.id) || file.firstSummary || '', turns: turns.get(path) ?? [],
    }))
    return { changes, checkpoint: { files, index }, monitor: failure ?? { status: 'ok' } }
  } catch (error) {
    return { changes: [], checkpoint: prior, monitor: unavailable(error, 'Codex の記録を読めません') }
  }
}

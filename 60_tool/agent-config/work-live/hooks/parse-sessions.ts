import { fingerprint, recordOf, textOf } from './model'
import type { StepText } from './model'
import { commandLanguage, describeCommand, describeEdit, describeMessage, describeToolKind } from './describe'

export type SessionRow = {
  id: string
  label: string
  startedBy: string
  isRunning: boolean
  isRemote: boolean
  isArchived: boolean
  cwd?: string
  startedAt?: number
  lastActiveAt?: number
}

function sessionTime(...values: unknown[]): number | undefined {
  for (const value of values) {
    const numeric = typeof value === 'number' ? value : /^\d{10,13}$/u.test(textOf(value)) ? Number(value) : undefined
    const time = numeric === undefined ? Date.parse(textOf(value)) : numeric < 1e12 ? numeric * 1000 : numeric
    if (Number.isFinite(time)) return time
  }
}

export function parseSessionList(text: string): SessionRow[] {
  const rows: unknown = JSON.parse(text)
  if (!Array.isArray(rows)) throw new Error('セッション一覧の形式が不明です')
  const sessions = new Map<string, SessionRow>()
  for (const row of rows) {
    const entry = recordOf(row)
    const id = textOf(entry.sessionId)
    if (!id) continue
    const isArchived = entry.isArchived === true
    const isRemote = !id.startsWith('local_') || entry.isRemote === true || entry.remote === true ||
      entry.isCloud === true || entry.is_remote === true || [entry.source, entry.location, entry.type]
        .some(value => value === 'remote' || value === 'cloud')
    sessions.set(id, {
      id, label: textOf(entry.title).trim(),
      startedBy: textOf(entry.startedBy),
      cwd: textOf(entry.cwd),
      startedAt: sessionTime(entry.startedAt, entry.createdAt, entry.started_at, entry.created_at),
      lastActiveAt: sessionTime(entry.lastActiveAt, entry.lastActivityAt, entry.updatedAt, entry.lastActivity,
        entry.lastActive, entry.lastUpdatedAt, entry.last_active_at, entry.last_activity_at, entry.updated_at,
        entry.startedAt, entry.createdAt, entry.started_at, entry.created_at),
      isRunning: !isArchived && entry.isRunning === true,
      isRemote, isArchived,
    })
  }
  return [...sessions.values()]
}

export function parseSessionTranscript(text: string): { tag: string; body: string }[] {
  const entries: { tag: string; body: string }[] = []
  let afterBlank = true
  let fence = 0
  for (const line of text.split(/\r?\n/u)) {
    const match = afterBlank && fence === 0 && /^\[([^\]\s][^\]]*)\](?:[ \t](.*)|$)/u.exec(line)
    const previous = entries.at(-1)
    const body = match ? match[2] ?? '' : line
    if (match) {
      if (previous) previous.body = previous.body.trimEnd()
      entries.push({ tag: match[1]!.toLowerCase(), body })
    } else if (previous) {
      previous.body += '\n' + line
    }
    const ticks = /^[ \t]*(\x60{3,})(.*)$/u.exec(body)
    if (ticks?.[1]) {
      if (!fence) fence = ticks[1].length
      else if (ticks[1].length >= fence && !ticks[2]?.trim()) fence = 0
    }
    afterBlank = !line.trim()
  }
  const last = entries.at(-1)
  if (last) last.body = last.body.trimEnd()
  return entries
}

export function sessionStep(tag: string, body: string): StepText {
  if (tag === 'assistant') return { kind: 'message', title: describeMessage(body, 'Claude のメッセージ'), full: body }
  if (tag === 'result') {
    const firstLine = body.trimStart().split('\n', 1)[0] ?? ''
    const failed = /\b(?:errors?|failed|failure|aborted|cancelled|canceled)\b|失敗|エラー|中断|取消/iu.test(firstLine)
    return { kind: failed ? 'error' : 'done', title: describeMessage(body, failed ? '作業失敗' : '作業完了'), full: body }
  }
  const namedTool = tag.replace(/^tool(?:[_ -](?:use|call|result))?(?:[\s:_]+|$)/iu, '')
  const tool = /^[\w-]+/u.exec(namedTool || body.trimStart())?.[0] ?? ''
  const toolKind = describeToolKind(tool)
  const { kind } = toolKind
  const payload = body.replace(/^\s*(?:Bash|PowerShell|exec_command|Edit|Write|NotebookEdit|MultiEdit|apply_patch)\b[\s:]+/iu, '')
  let args: Record<string, unknown> = {}
  if (payload.startsWith('{')) {
    try { args = recordOf(JSON.parse(payload)) } catch { /* Keep the original text. */ }
  }
  if (kind === 'command') {
    const command = textOf(args.command) || textOf(args.cmd) || payload
    const exit = typeof args.exit_code === 'number' ? args.exit_code : typeof args.exitCode === 'number' ? args.exitCode : undefined
    const { text, icon } = describeCommand(command, exit)
    return { kind: icon === 'error' ? 'error' : kind, title: text, icon, full: body,
      language: /PowerShell/iu.test(tool) ? 'powershell' : commandLanguage(command) }
  }
  if (kind === 'edit') {
    const path = textOf(args.file_path) || textOf(args.notebook_path) || textOf(args.path)
    const paths = Object.keys(recordOf(args.changes))
    return { kind, title: describeEdit(paths.length ? paths : [path || payload.replace(/^(["'])([^]*)\1$/u, '$2')]), full: body }
  }
  return { ...toolKind, title: describeMessage(body, tag), full: body }
}

export function newSessionEvents(text: string, previous: readonly string[]) {
  const entries = parseSessionTranscript(text)
  const keys = entries.map(({ tag, body }) => tag + '\0' + body)
  const seen = keys.map(fingerprint)
  if (!seen.length) return { seen: [...previous], events: [] }
  // The MCP supplies a rolling window. Match the overlap by position so repeated messages remain separate.
  let overlap = Math.min(previous.length, seen.length)
  let revised
  while (overlap) {
    const tail = previous.slice(-overlap)
    if (tail.slice(0, -1).every((hash, index) => seen[index] === hash)) {
      const last = tail.at(-1) ?? ''
      const index = overlap - 1
      if (seen[index] === last) break
      // A streaming assistant message can grow in place at the end of the previous window.
      const length = Number(last.split(':', 1)[0])
      const key = keys[index] ?? ''
      if (length < key.length && fingerprint(key.slice(0, length)) === last) {
        revised = entries[index]
        break
      }
    }
    overlap--
  }
  return { seen, events: entries.slice(overlap), revised }
}

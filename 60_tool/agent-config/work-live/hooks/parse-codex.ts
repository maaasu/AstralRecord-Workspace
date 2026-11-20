import type { CodexCursor, RolloutMeta, Step } from '../types'
import { basename, isNotification, MAX_PROMPT, MAX_STEP, notification, recordOf, stepOf, summarize, textOf, truncate } from './model'
import type { StepText } from './model'
import { commandLanguage, describeCommand, describeEdit, describeMessage } from './describe'

type Operation = { kind: 'command' | 'patch'; id: string; phase: 'begin' | 'end'; hasInput: boolean }

export type CodexEvent =
  | { kind: 'start'; turnId: string }
  | { kind: 'prompt'; prompt: string }
  | { kind: 'notification'; body: string }
  | { kind: 'step'; step: StepText; operation?: Operation }
  | { kind: 'end'; status: 'done' | 'error'; step: StepText }

export type CodexLine = { meta: RolloutMeta } | { at: number; event: CodexEvent }
export type CodexTurn = {
  id: string
  at: number
  prompt?: string
  steps: Step[]
  status?: 'done' | 'error'
  endedAt?: number
}

export function parseJson(line: string): unknown {
  try {
    return JSON.parse(line)
  } catch {
    return undefined
  }
}

function dateOf(value: unknown): number | undefined {
  if (typeof value === 'number' && Number.isFinite(value)) return value < 1e12 ? value * 1_000 : value
  const at = Date.parse(textOf(value))
  return Number.isFinite(at) ? at : undefined
}

function commandEvent(item: Record<string, unknown>): StepText {
  const parts = Array.isArray(item.command) ? item.command.map(textOf) : [textOf(item.command)]
  const code = item.exit_code ?? item.exitCode
  const output = textOf(item.aggregated_output) || textOf(item.output) || textOf(item.result) ||
    [textOf(item.stdout), textOf(item.stderr)].filter(Boolean).join('\n')
  const command = parts.join(' ')
  const full = operationText(command, code, output)
  const { text, icon } = describeCommand(command, typeof code === 'number' ? code : undefined)
  return {
    kind: icon === 'error' ? 'error' : 'command',
    title: text, icon,
    full, language: commandLanguage(command),
  }
}

function operationText(input: string, exit: unknown, output: string): string {
  const result = [typeof exit === 'number' ? '終了コード ' + exit : '', output].filter(Boolean).join('\n')
  return [result ? truncate(input, MAX_STEP / 2) : input, result].filter(Boolean).join('\n')
}

function editEvent(value: unknown): StepText {
  const changes = Array.isArray(value)
    ? value.map(entry => [textOf(recordOf(entry).path), entry] as const)
    : Object.entries(recordOf(value))
  const title = describeEdit(changes.map(([path]) => path))
  const files = changes.map(([path, value]) => {
    const change = recordOf(value)
    const kind = textOf(value) || textOf(change.type) || textOf(change.kind) || textOf(recordOf(change.kind).type)
    const diff = textOf(change.unified_diff) || textOf(change.diff) || textOf(change.content)
    return { header: path + (kind ? ' (' + kind + ')' : ''), diff: truncate(diff, 1_500) }
  })
  const full = [files.map(file => file.header).join('\n'), ...files.filter(file => file.diff)
    .map(file => file.header + '\n' + file.diff)].join('\n\n')
  return { kind: 'edit', title, full }
}

function itemEvent(item: Record<string, unknown>): StepText | undefined {
  switch (item.type) {
    case 'AgentMessage': {
      const full = Array.isArray(item.content)
        ? item.content.map(block => textOf(recordOf(block).text)).filter(Boolean).join('\n')
        : textOf(item.content) || textOf(item.text)
      return { kind: 'message', title: describeMessage(full, 'Codex のメッセージ'), full }
    }
    case 'CommandExecution':
      return commandEvent(item)
    case 'FileChange':
      return editEvent(item.changes)
    case 'ImageView': {
      const full = textOf(item.path)
      return { kind: 'read', title: basename(full) || '画像を確認', full }
    }
    case 'SubAgentActivity':
      return { kind: 'agent', title: 'サブエージェントの作業', full: textOf(item.agent_path) || textOf(item.agent_thread_id) }
    default:
      return undefined
  }
}

function userPrompt(content: unknown): string {
  if (!Array.isArray(content)) return ''
  return content.map(block => textOf(recordOf(block).text)).filter(text =>
    text.trim() && !/^(?:# AGENTS\.md instructions|<environment_context>|<INSTRUCTIONS>)/u.test(text.trimStart()),
  ).at(-1) ?? ''
}

export function parseCodexLine(line: string, fallbackAt = 0): CodexLine | undefined {
  const row = recordOf(parseJson(line))
  const payload = recordOf(row.payload)
  if (row.type === 'session_meta') {
    const source = recordOf(payload.source)
    const spawn = recordOf(recordOf(source.subagent).thread_spawn)
    return { meta: {
      id: textOf(payload.id), cwd: textOf(payload.cwd), originator: textOf(payload.originator),
      source: textOf(payload.source) || Object.keys(source)[0] || '',
      timestamp: dateOf(payload.timestamp) ?? dateOf(row.timestamp),
      parentThreadId: textOf(payload.parent_thread_id) || textOf(spawn.parent_thread_id),
    } }
  }
  const at = dateOf(row.timestamp) ?? fallbackAt
  if (row.type === 'response_item' && payload.type === 'message' && payload.role === 'user') {
    const prompt = userPrompt(payload.content)
    if (isNotification(prompt)) return { at, event: { kind: 'notification', body: prompt } }
    if (prompt) return { at, event: { kind: 'prompt', prompt: truncate(prompt, MAX_PROMPT) } }
  }
  if (row.type !== 'event_msg') return undefined
  let step: StepText | undefined
  let operation: Operation | undefined
  switch (payload.type) {
    case 'task_started':
      return { at: dateOf(payload.started_at) ?? at, event: { kind: 'start', turnId: textOf(payload.turn_id) } }
    case 'task_complete':
      return { at: dateOf(payload.completed_at) ?? at, event: { kind: 'end', status: 'done', step: {
        kind: 'done', title: describeMessage(textOf(payload.last_agent_message), '作業完了'), full: textOf(payload.last_agent_message),
      } } }
    case 'turn_aborted':
      return { at, event: { kind: 'end', status: 'error', step: {
        kind: 'error', title: '作業中断', full: textOf(payload.reason),
      } } }
    case 'item_started':
    case 'item_completed': {
      const item = recordOf(payload.item)
      step = itemEvent(item)
      const phase = payload.type === 'item_started' ? 'begin' : 'end'
      if (item.type === 'CommandExecution') operation = operationOf('command', phase, item, payload)
      if (item.type === 'FileChange') operation = operationOf('patch', phase, item, payload)
      break
    }
    case 'exec_command_begin':
    case 'exec_command_end':
      step = commandEvent(payload)
      operation = operationOf('command', payload.type === 'exec_command_begin' ? 'begin' : 'end', payload)
      break
    case 'patch_apply_begin':
    case 'patch_apply_end': {
      step = editEvent(payload.changes)
      const output = [textOf(payload.output) || textOf(payload.result), textOf(payload.stdout), textOf(payload.stderr)].filter(Boolean).join('\n')
      const code = payload.exit_code ?? payload.exitCode
      const failed = payload.success === false || (typeof code === 'number' && code !== 0)
      step = { ...step, ...(failed ? { kind: 'error', icon: 'error' } : {}),
        full: operationText(step.full.trimEnd(), code, output) }
      operation = operationOf('patch', payload.type === 'patch_apply_begin' ? 'begin' : 'end', payload)
      break
    }
    case 'agent_message':
      step = { kind: 'message', title: describeMessage(textOf(payload.message), 'Codex のメッセージ'), full: textOf(payload.message) }
      break
  }
  if (step) return { at, event: { kind: 'step', step, ...(operation ? { operation } : {}) } }
}

function operationOf(
  kind: Operation['kind'], phase: Operation['phase'], item: Record<string, unknown>, envelope = item,
): Operation {
  const id = textOf(item.call_id) || textOf(envelope.call_id) || textOf(item.id) ||
    textOf(item.item_id) || textOf(envelope.item_id) || textOf(envelope.id)
  const hasInput = kind === 'command' ? (Array.isArray(item.command) ? !!item.command.length : !!textOf(item.command))
    : !!(Array.isArray(item.changes) ? item.changes.length : Object.keys(recordOf(item.changes)).length)
  return { kind, phase, id, hasInput }
}

export function collectCodexTurns(
  thread: string,
  records: readonly { offset: number; row: Exclude<CodexLine, { meta: RolloutMeta }> }[],
  prior: CodexCursor['turn'],
  previousRequest?: CodexCursor['lastRequest'],
) {
  let active = prior
  let lastRequest = previousRequest
  const turns = new Map<string, CodexTurn>()
  let firstSummary: string | undefined
  function entry(ref: { id: string; at: number }) {
    let turn = turns.get(ref.id)
    if (!turn) {
      turn = { id: ref.id, at: ref.at, steps: [] }
      turns.set(turn.id, turn)
    }
    return turn
  }
  for (const { offset, row: { at, event } } of records) {
    if (event.kind === 'start') active = { id: 'codex-turn:' + thread + ':' + (event.turnId || offset), at }
    if (event.kind === 'notification') {
      if (lastRequest) entry(lastRequest).steps.push(stepOf(thread + ':' + offset, at, notification(event.body)))
      if (active && active.id !== lastRequest?.id) active = { ...active, notified: true }
      continue
    }
    if (!active) continue
    if (event.kind === 'prompt') {
      active = { ...active }
      delete active.notified
      lastRequest = active
      entry(active).prompt = event.prompt
      firstSummary ??= summarize(event.prompt) || '作業'
    }
    const target = active.notified ? lastRequest : active
    if (target && (event.kind === 'step' || event.kind === 'end')) {
      const turn = entry(target)
      const operation = event.kind === 'step' ? event.operation : undefined
      let id = thread + ':' + offset
      let content = event.step
      if (operation) {
        const pending = active.pending ?? []
        const matching = operation.phase === 'end' ? pending.findLast(item => item.kind === operation.kind &&
          (!operation.id || item.operationId === operation.id)) ??
          pending.findLast(item => item.kind === operation.kind && !item.operationId) : undefined
        const keyedId = operation.id ? turn.id + ':' + operation.kind + ':' + operation.id : id
        id = matching?.id ?? keyedId
        if (matching && !operation.hasInput) {
          content = { ...content, title: matching.step.title, language: matching.step.language,
            full: operationText(matching.step.full, undefined, content.full) }
        }
        active = { ...active, pending: operation.phase === 'begin'
          ? [...pending.filter(item => item.id !== id), { id, kind: operation.kind, operationId: operation.id,
              step: stepOf(id, at, content) }].slice(-60)
          : pending.filter(item => item.id !== id) }
      }
      const step = stepOf(id, at, content)
      const index = turn.steps.findIndex(item => item.id === id)
      if (index < 0) turn.steps.push(step)
      else turn.steps[index] = step
    }
    if (event.kind === 'end') {
      if (!active.notified) {
        entry(active).status = event.status
        entry(active).endedAt = at
      }
      active = undefined
    }
  }
  return { turns: [...turns.values()], active, lastRequest, firstSummary }
}

export function completeLines(bytes: Uint8Array, skipFirst: boolean): {
  lines: { offset: number; text: string }[]
  consumed: number
  skipping: boolean
} {
  const decoder = new TextDecoder()
  const lines = []
  let start = 0
  for (let end = bytes.indexOf(10); end !== -1; end = bytes.indexOf(10, start)) {
    if (!skipFirst) lines.push({ offset: start, text: decoder.decode(bytes.subarray(start, end)) })
    skipFirst = false
    start = end + 1
  }
  return { lines, consumed: skipFirst ? bytes.length : start, skipping: skipFirst }
}

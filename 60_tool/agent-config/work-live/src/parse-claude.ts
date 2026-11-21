import type { RequestStatus } from '../types/index.d.ts'
import { commandLanguage, describeCommand, describeEdit, describeToolKind } from './describe.ts'
import { basename, dateOf, isNotification, parseJson, recordOf, textOf } from './model.ts'
import type { StepText } from './model.ts'
import type { JsonlLine } from './jsonl.ts'

export type ClaudeRow = {
  at: number
  id: string
  parent: string
  type: string
  data: Record<string, unknown>
}

export function claudeRow(line: JsonlLine, fallbackAt: number): ClaudeRow | undefined {
  if (!('text' in line)) return { at: fallbackAt, id: 'offset:' + line.offset, parent: '', type: 'oversized',
    data: { bytes: line.bytes } }
  const data = recordOf(parseJson(line.text))
  const type = textOf(data.type)
  if (!type) return undefined
  return { data, type, at: Math.min(dateOf(data.timestamp) ?? fallbackAt, fallbackAt),
    id: textOf(data.uuid) || 'offset:' + line.offset, parent: textOf(data.parentUuid) }
}

export function blocksOf(value: unknown): Record<string, unknown>[] {
  return Array.isArray(value) ? value.map(recordOf) : []
}

export function contentText(value: unknown): string {
  return textOf(value) || blocksOf(value).filter(block => block.type === 'text').map(block => textOf(block.text))
    .filter(Boolean).join('\n')
}

const TASK_END = new Map<string, Exclude<RequestStatus, 'running'>>([
  ['completed', 'done'], ['failed', 'error'], ['killed', 'stopped'],
  ['cancelled', 'stopped'], ['canceled', 'stopped'], ['stopped', 'stopped'],
])

export function taskCompletion(body: string) {
  const task = /^\s*<task-notification>\s*([\s\S]*?)<\/task-notification>/u.exec(body)?.[1]
  if (!task) return undefined
  const toolUseId = /<tool-use-id>\s*([^<\s]+)\s*<\/tool-use-id>/u.exec(task)?.[1]
  const status = TASK_END.get(/<status>\s*([^<\s]+)\s*<\/status>/u.exec(task)?.[1] ?? '')
  const summary = /<summary>\s*([\s\S]*?)\s*<\/summary>/u.exec(task)?.[1]
  return toolUseId && status ? { toolUseId, status, summary } : undefined
}

export function isClaudePrompt(text: string): boolean {
  const body = text.trim()
  return !!body && !isNotification(body) && !/^\/[\w:-]+(?:\s|$)|^\[Request interrupted by user(?: for tool use)?\]/iu.test(body)
}

export function claudePrompt(row: ClaudeRow): string {
  const { data } = row
  if (row.type !== 'user' || data.isMeta || data.isSynthetic || data.isCompactSummary || data.isVisibleInTranscriptOnly) return ''
  const text = contentText(recordOf(data.message).content)
  return isClaudePrompt(text) ? text : ''
}

export function isClaudeStart(text: string): boolean {
  const row = claudeRow({ text, offset: 0 }, 0)
  return !!row && !!claudePrompt(row)
}

export function cloudSession(row: Record<string, unknown>): boolean {
  return textOf(row.sessionId).startsWith('session_') || row.isRemote === true || row.isCloud === true ||
    [row.source, row.location, row.kind, row.target].some(value => value === 'remote' || value === 'cloud')
}

export function describeClaudeTool(tool: string, input: Record<string, unknown>): StepText {
  const kind = describeToolKind(tool)
  if (kind.kind === 'command') {
    const command = textOf(input.command) || textOf(input.cmd)
    const { text, icon } = describeCommand(command)
    return { kind: 'command', title: text, icon, full: command,
      language: tool.toLowerCase() === 'powershell' ? 'powershell' : commandLanguage(command) }
  }
  if (kind.kind === 'agent') {
    return { ...kind, title: textOf(input.description) || 'サブエージェントに依頼',
      full: textOf(input.prompt) }
  }
  const path = textOf(input.file_path) || textOf(input.notebook_path) || textOf(input.path)
  return {
    ...kind,
    title: kind.kind === 'edit' ? describeEdit([path])
      : [tool, basename(path) || textOf(input.pattern) || textOf(input.url) || textOf(input.query)].filter(Boolean).join(' '),
    full: JSON.stringify(input, null, 2),
  }
}

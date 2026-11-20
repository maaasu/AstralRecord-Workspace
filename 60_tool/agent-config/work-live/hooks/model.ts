import type { Actor, ActorKind, IconName, LiveBoard, Monitor, RequestDetail, RequestSummary, Step, StepCount } from '../types'
import { stepIcon } from './icons'

export type StepText = Pick<Step, 'kind' | 'title' | 'full' | 'language'> & Partial<Pick<Step, 'icon'>>
export const MAX_STEP = 4_000
export const MAX_PROMPT = 20_000
export const FLOW_WINDOW = 2 * 60 * 60_000
export const emptyDetail: RequestDetail = { prompt: '', promptSource: '', steps: [] }
export const emptyBoard: LiveBoard = {
  actors: [],
  requests: [],
  selectedRequest: null,
  selectedSession: null,
  allSessionsExpanded: false,
  expandedStep: null,
  promptExpanded: false,
  olderStepsExpanded: false,
  scope: 'hour',
  monitors: { codex: { status: 'off' }, sessions: { status: 'off' } },
  placementHintShown: false,
  sequence: 0,
  selfQueue: [],
  selfTurn: null,
}

export function oneLine(value: string, length: number): string {
  const clean = value.replace(/[\p{Cc}\uD800-\uDFFF\uFFFE\uFFFF]/gu, ' ').replace(/\s+/gu, ' ').trim()
  return Array.from(clean.slice(0, length * 2)).slice(0, length).join('')
}

export function codeText(value: string): string {
  return value.replace(/\r\n?/gu, '\n').replace(/[\u0000-\u0008\u000B\u000C\u000E-\u001F\u007F-\u009F]/gu, '')
}

export function displayText(value: string): string {
  return value.replace(/[#*0-9]\uFE0F?\u20E3|[\p{Extended_Pictographic}\p{Regional_Indicator}✎✔✖●○•①-⑳㉑-㉟㊱-㊿][\uFE0E\uFE0F\u200D\p{Emoji_Modifier}]*/gu, '').trim()
}

export function truncate(value: string, limit: number): string {
  return value.length <= limit ? value : value.slice(0, limit).replace(/[\uD800-\uDBFF]$/u, '')
}

export function recordOf(value: unknown): Record<string, unknown> {
  return value !== null && typeof value === 'object' && !Array.isArray(value)
    ? value as Record<string, unknown>
    : {}
}

export function textOf(value: unknown): string {
  return typeof value === 'string' ? value : ''
}

export function basename(path: string): string {
  return path.replace(/[\\/]+$/u, '').split(/[\\/]/u).at(-1) ?? ''
}

export function fingerprint(value: string): string {
  let hash = 2166136261
  for (let index = 0; index < value.length; index++) {
    hash = Math.imul(hash ^ value.charCodeAt(index), 16777619)
  }
  return value.length + ':' + (hash >>> 0).toString(36)
}

export function summarize(prompt: string): string {
  const task = /^[ \t]*#[ \t]*タスク[ \t]*[:：][ \t]*(\S.*)$/mu.exec(prompt)?.[1]
  const line = task ?? prompt.split(/\r?\n/u).find(part => part.trim()) ?? ''
  return oneLine(line.replace(/!?\[([^\]]+)\]\([^)]+\)/gu, '$1')
    .replace(/^\s*(?:[#>*+-]+\s+|\d+[.)]\s+)/gu, '')
    .replace(/(\*\*|__|~~|\x60+)(.*?)\1/gu, '$2')
    .replace(/\*([^*\s](?:[^*]*[^*\s])?)\*/gu, '$1')
    .replace(/(^|[\s(])_([^_\s](?:[^_]*[^_\s])?)_(?=$|[\s).,、。])/gu, '$1$2'), 40)
}

export function inferVerb(prompt: string): string {
  if (/レビュー|review/iu.test(prompt)) return 'レビューを依頼'
  if (/調査|調べ|確認|読んで/u.test(prompt)) return '調査を依頼'
  if (/実装|作成|修正|追加|implement|fix/iu.test(prompt)) return '実装を依頼'
  return '作業を依頼'
}

export function actor(id: string, kind: ActorKind, label: string): Actor {
  return { id, kind, label: oneLine(label, label.length) }
}

export function namedActor(id: string, kind: ActorKind, prefix: string, name: string): Actor {
  return actor(id, kind, name.trim() ? prefix + '（' + name + '）' : prefix)
}

export function shortName(name: string): string {
  const chars = Array.from(name)
  return chars.length > 18 ? chars.slice(0, 17).join('') + '…' : name
}

export const USER = actor('user', 'user', 'あなた')
export const SELF = actor('self', 'claude', 'Claude（このセッション）')

export function isNotification(body: string): boolean {
  return /^<(?:task-notification|system-reminder|command-name|command-message|command-args|local-command-[\w-]+)(?=[\s/>]|$)/iu
    .test(body.trimStart())
}

export function notification(body: string): StepText {
  return { kind: 'tool', icon: 'bell', title: 'バックグラウンド作業の完了通知', full: body }
}

export function stepOf(id: string, at: number, content: StepText): Step {
  return { ...content, id, at, icon: stepIcon(content), title: oneLine(content.title, 120) || '作業',
    full: truncate(content.full, MAX_STEP) }
}

const countByIcon: Partial<Record<IconName, StepCount>> = {
  file: 'read', folder: 'read', search: 'search', edit: 'edit',
  terminal: 'command', git: 'command', run: 'command', web: 'web',
  agent: 'agent', delegate: 'delegate', error: 'error',
}

export function stepCountCategory(icon: IconName): StepCount | undefined {
  return countByIcon[icon]
}

export function summarizeSteps(steps: readonly Step[]): Pick<RequestSummary, 'lastStep' | 'lastStepAt' | 'stepCount' | 'counts'> {
  const counts: RequestSummary['counts'] = {}
  for (const step of steps) {
    const category = stepCountCategory(stepIcon(step))
    if (category) counts[category] = (counts[category] ?? 0) + 1
  }
  const last = steps.at(-1)
  return {
    counts, stepCount: steps.length, lastStepAt: last?.at,
    lastStep: last && { at: last.at, icon: stepIcon(last), title: oneLine(last.title, 40) },
  }
}

export function requestResult(request: RequestSummary, steps: readonly Step[]): string | undefined {
  if (request.status === 'running') return undefined
  const result = steps.findLast(step => step.at <= (request.endedAt ?? Infinity) &&
    (step.kind === 'done' || step.kind === 'message' || (step.kind === 'error' && request.status === 'error')) &&
    step.full.trim())
  return result && oneLine(result.full, 60)
}

export function unavailable(error: unknown, fallback: string): Monitor {
  const reason = error instanceof Error ? error.message : textOf(error)
  return { status: 'unavailable', reason: oneLine(reason, 100) || fallback }
}

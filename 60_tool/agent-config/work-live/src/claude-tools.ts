import { isAbsolute, resolve } from 'node:path'
import type { Actor, LiveRequest } from '../types/index.d.ts'
import { describeCommand } from './describe.ts'
import { inferVerb, isNotification, MAX_STEP, namedActor, recordOf, stepOf, summarize, textOf, truncate } from './model.ts'
import type { StepText } from './model.ts'
import { attachSession, claudeActor, codexActor, mergeObservation, observedRequest } from './observe.ts'
import { contentText, describeClaudeTool, isClaudePrompt } from './parse-claude.ts'
import { codexVerb, instructionSource, parseCodexCommand, sessionId, sessionKey, sessionMessageInput, startSessionInput } from './parse-tool.ts'
import { createRequest, editRequest, rememberActor } from './state.ts'
import type { Draft } from './state.ts'
import type { AgentRecord } from './watch-claude.ts'

type ToolContext = {
  session: string
  actor: Actor
  cwd: string
  agents: ReadonlyMap<string, AgentRecord>
  aliases: ReadonlyMap<string, string>
}
export type ToolCall = {
  requestId: string; parent?: string; step: StepText; background: boolean; sessionAction?: LiveRequest['awaitingSession']
}

export function toolRequestId(session: string, id: string) {
  return 'claude-tool:' + session + ':' + id
}

export function agentActor(session: string, id: string, name: string) {
  return namedActor('agent:' + session + ':' + id, 'subagent', 'サブエージェント', name)
}

export function attachAgent(draft: Draft, request: LiveRequest, to: Actor) {
  const previous = draft.value.actors.find(item => item.id === request.to)
  const actor = to.label === 'サブエージェント' && previous ? { ...to, label: previous.label } : to
  rememberActor(draft, actor)
  request.to = actor.id
  if (request.observation) return
  const found = draft.value.requests.find(item => item !== request && item.to === to.id &&
    item.id === item.observation && item.at >= request.at - 5_000)
  if (found) mergeObservation(draft, request, found)
}

export function beginTool(draft: Draft, context: ToolContext, parent: LiveRequest | undefined,
  call: Record<string, unknown>, at: number): ToolCall | undefined {
  const id = textOf(call.id)
  const name = textOf(call.name)
  if (!id || !name) return undefined
  const input = recordOf(call.input)
  const step = describeClaudeTool(name, input)
  const requestId = toolRequestId(context.session, id)
  const stepId = requestId + ':tool'
  if (parent) editRequest(draft, parent, { steps: [stepOf(stepId, at, step)] })
  const sessionAction = /(?:^|__)start_session$/iu.test(name) ? 'start'
    : /(?:^|__)send_message$|^SendMessage$/iu.test(name) ? 'message' : undefined
  const observed: ToolCall = { requestId, parent: parent?.id, step: { ...step, full: truncate(step.full, MAX_STEP) },
    sessionAction, background: input.run_in_background === true }
  if (observedRequest(draft, requestId)) return observed

  let request: LiveRequest | undefined
  const launch = step.kind === 'command' ? parseCodexCommand(step.full) : undefined
  if (launch && !isNotification(launch.prompt)) {
    const cwd = launch.cwd && !/[$%\x60]|^(?:\(|~(?:[\\/]|$))/u.test(launch.cwd)
      ? (isAbsolute(launch.cwd) ? launch.cwd : context.cwd ? resolve(context.cwd, launch.cwd) : launch.cwd) : context.cwd
    const prompt = launch.prompt.trim() ? launch.prompt : ''
    request = createRequest(draft, requestId, context.actor, codexActor('pending:' + requestId),
      codexVerb(launch, prompt), prompt, at)
    request.codexLaunch = { cwd, mode: launch.mode, readOnly: launch.readOnly }
    if (!prompt) {
      request.awaitingPrompt = true
      request.summary = summarize(textOf(input.description)) || 'Codex ' + launch.mode
    }
    editRequest(draft, request, { prompt, promptSource: prompt ? 'コマンド引数' : launch.file ? instructionSource(launch.file) : '',
      steps: [stepOf(stepId, at, step)] })
  } else if (sessionAction === 'start') {
    const start = startSessionInput(input)
    if (start.remote || !isClaudePrompt(start.prompt)) return observed
    request = createRequest(draft, requestId, context.actor, claudeActor('pending:' + requestId, start.title),
      '作業を依頼', start.prompt, at)
    request.summary = summarize(start.title) || request.summary
    request.awaitingSession = 'start'
    editRequest(draft, request, { prompt: start.prompt, promptSource: 'セッション開始ツール' })
  } else if (sessionAction === 'message') {
    const send = sessionMessageInput(name, input)
    if (!send || send.id.startsWith('session_')) return observed
    const target = sessionKey(send.id)
    const to = claudeActor(context.aliases.get(target) ?? target)
    if (isNotification(send.prompt)) {
      const recent = draft.value.requests.findLast(item => item.to === to.id)
      if (recent) editRequest(draft, recent, { steps: [stepOf(stepId, at,
        { kind: 'tool', icon: 'bell', title: 'バックグラウンド作業の完了通知', full: send.prompt })] })
      return observed
    }
    request = createRequest(draft, requestId, context.actor, draft.value.actors.find(actor => actor.id === to.id) ?? to,
      '追加の指示を送信', send.prompt, at)
    request.awaitingSession = 'message'
    editRequest(draft, request, { prompt: send.prompt, promptSource: 'セッション送信ツール' })
  } else if (step.kind === 'agent' && isClaudePrompt(step.full)) {
    const agent = context.agents.get(requestId)
    const description = textOf(input.description)
    const to = agentActor(context.session, agent?.id ?? 'pending:' + id, textOf(input.subagent_type) || description || agent?.name || '')
    request = createRequest(draft, requestId, context.actor, to, inferVerb(step.full), step.full, at)
    request.summary = summarize(description) || request.summary
    editRequest(draft, request, { prompt: step.full, promptSource: 'Agent ツール' })
    if (agent) attachAgent(draft, request, to)
  }
  if (request && parent) request.parentId = parent.id
  return observed
}

function resultId(output: string, info: Record<string, unknown>) {
  for (const row of [info, recordOf(info.structuredContent), recordOf(info.data)]) {
    const id = sessionId(textOf(row.sessionId) || textOf(row.session_id))
    if (id) return id
  }
  return sessionId(output)
}

export function finishTool(draft: Draft, context: ToolContext, call: ToolCall, result: Record<string, unknown>,
  info: Record<string, unknown>, at: number, now: number) {
  const output = contentText(result.content) || [textOf(info.stdout), textOf(info.stderr)].filter(Boolean).join('\n')
  const parsedExit = /^\s*(?:Exit code|終了コード)\s*:?\s*(\d+)/iu.exec(output)?.[1]
  const code = info.exitCode ?? info.exit_code
  const exit = typeof code === 'number' ? code : parsedExit === undefined ? undefined : Number(parsedExit)
  const failed = result.is_error === true || info.interrupted === true || (exit !== undefined && exit !== 0)
  const stepId = call.requestId + ':tool'
  const command = call.step.kind === 'command'
  const suffix = [exit === undefined ? '' : '終了コード ' + exit, output].filter(Boolean).join('\n')
  const full = [suffix ? truncate(call.step.full, MAX_STEP / 2) : call.step.full, suffix].filter(Boolean).join('\n')
  const step = stepOf(stepId, at, { ...call.step, full,
    title: command ? describeCommand(call.step.full, exit).text : call.step.title,
    kind: failed ? 'error' : call.step.kind, icon: failed ? 'error' : call.step.icon })
  const parent = observedRequest(draft, call.parent)
  if (parent) editRequest(draft, parent, { steps: [step] })
  const request = observedRequest(draft, call.requestId)
  if (!request) return

  if (request.codexLaunch) {
    if (request.observation) return
    editRequest(draft, request, { steps: [step] })
    if (!failed) {
      const background = call.background || textOf(info.backgroundTaskId) || /^\s*Command running in background\b/iu.test(output)
      if (!background) {
        request.codexLaunch.shellEndedAt = at
      }
      return
    }
  } else if (call.sessionAction && !failed) {
    if (request.awaitingSession === 'start') {
      const id = resultId(output, info)
      if (id.startsWith('session_')) draft.value.requests = draft.value.requests.filter(item => item !== request)
      else if (id) attachSession(draft, request, context.aliases.get(sessionKey(id)) ?? id, now)
    }
    return
  } else if (call.step.kind === 'agent') {
    const id = textOf(info.agentId) || /\bagentId\s*:\s*([\w-]+)/iu.exec(output)?.[1]
    if (id) attachAgent(draft, request, agentActor(context.session, id, ''))
    if (!failed && (info.status === 'async_launched' || call.background)) return
    if (request.status !== 'running' && request.observation && !failed) return
  }
  const steps = output ? [stepOf(request.id + ':result', at, {
    kind: failed ? 'error' : 'done', title: failed ? '呼び出しエラー' : '呼び出し結果',
    full: call.step.kind === 'agent' ? truncate(output, 2_000) : output,
  })] : []
  editRequest(draft, request, { steps, status: failed ? 'error' : 'done', endedAt: at })
}

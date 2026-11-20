import type { EngineInterface, ToolCallInput, ToolCallResult } from 'claude-code'
import { basename, inferVerb, isNotification, MAX_PROMPT, namedActor, recordOf, SELF, stepOf, summarize, textOf, truncate } from './model'
import type { StepText } from './model'
import { describeCommand, describeEdit, describeToolKind } from './describe'
import { attachSession, claudeActor, codexActor } from './observe'
import { codexVerb, instructionSource, parseCodexCommand, sessionId, sessionMessageInput, startSessionInput } from './parse-tool'
import { createRequest, editBoard, editRequest, recordNotification } from './state'
import type { Runtime } from './state'

const START = 'mcp__ccd_session__start_session'
const SEND = 'mcp__ccd_session_mgmt__send_message'

type ObservedTool = { id: string; tool: string; background: boolean; settleCodex: boolean; self?: string; step: StepText; stepId: string }

function describeTool(e: ToolCallInput): StepText {
  if (e.tool === 'Bash' || e.tool === 'PowerShell') {
    const { text, icon } = describeCommand(e.command)
    return { kind: 'command', title: text, icon, full: e.command,
      language: e.tool === 'PowerShell' ? 'powershell' : 'bash' }
  }
  if (e.tool === 'Agent') return { kind: 'agent', title: e.description, full: e.prompt }
  const args = recordOf(e)
  const path = textOf(args.file_path) || textOf(args.notebook_path) || textOf(args.path)
  const toolKind = describeToolKind(e.tool)
  const { tool, tool_use_id, agentId, requestMeta, ...input } = args
  return {
    ...toolKind, title: toolKind.kind === 'edit' ? describeEdit([path])
      : [e.tool, basename(path) || textOf(args.pattern) || textOf(args.url) || textOf(args.query)].filter(Boolean).join(' '),
    full: JSON.stringify(input, null, 2),
  }
}

async function readInstructions($: EngineInterface, file: string): Promise<string> {
  if (!file || /[$%]/u.test(file)) return ''
  try {
    const prompt = truncate(await $.fs.read(file, { as: 'text', length: MAX_PROMPT * 4 }), MAX_PROMPT)
    return prompt.trim() ? prompt : ''
  } catch {
    // If the path cannot be read, the Codex rollout can supply the prompt.
    return ''
  }
}

export async function recordToolStart(
  $: EngineInterface, runtime: Runtime, e: ToolCallInput, showSelf: boolean, at: number,
): Promise<ObservedTool | undefined> {
  const shell = e.tool === 'Bash' || e.tool === 'PowerShell' ? e : undefined
  const codex = shell && parseCodexCommand(shell.command)
  const start = e.tool === START ? startSessionInput(e) : undefined
  const send = e.tool === SEND || e.tool === 'SendMessage' ? sessionMessageInput(e.tool, e) : undefined
  const agent = e.tool === 'Agent' ? e : undefined
  if (!showSelf && !codex && !start && !send && !agent) return undefined

  const instructions = codex && await readInstructions($, codex.file)
  const cwd = codex ? codex.cwd || await $.session.cwd() : ''
  return editBoard($, runtime, async draft => {
    const step = stepOf('tool:' + e.tool_use_id, at, describeTool(e))
    const self = draft.value.requests.find(item => !e.agentId && item.id === draft.value.selfTurn?.request)
    if (showSelf && self) await editRequest(draft, self, { steps: [step] })

    const id = 'request:' + e.tool_use_id
    if (draft.value.requests.some(item => item.id === id)) return undefined
    const observed = { id, tool: e.tool, background: shell?.run_in_background === true,
      settleCodex: draft.value.monitors.codex.status === 'off', self: showSelf ? self?.id : undefined,
      step, stepId: step.id }
    if (codex) {
      const prompt = instructions || (codex.prompt.trim() ? codex.prompt : '')
      if (isNotification(prompt)) return observed
      const request = createRequest(draft, id, SELF, codexActor('pending:' + e.tool_use_id), codexVerb(codex, prompt), prompt, at)
      request.codexLaunch = { cwd, mode: codex.mode, readOnly: codex.readOnly }
      if (!prompt.trim()) {
        request.awaitingPrompt = true
        request.summary = summarize(shell?.description ?? '') || 'Codex ' + codex.mode
      }
      await editRequest(draft, request, {
        prompt, promptSource: instructions ? instructionSource(codex.file) : prompt ? 'コマンド引数' : '',
        steps: [step],
      })
    } else if (start) {
      if (!start.prompt.trim() || isNotification(start.prompt)) return observed
      const to = claudeActor('pending:' + e.tool_use_id, start.title, start.remote)
      const request = createRequest(draft, id, SELF, to, start.remote ? 'クラウドで作業を依頼' : '作業を依頼', start.prompt, at)
      request.summary = summarize(start.title) || request.summary
      request.awaitingSession = 'start'
      await editRequest(draft, request, { prompt: start.prompt, promptSource: 'セッション開始ツール' })
    } else if (send) {
      const fallback = claudeActor(send.id)
      const to = draft.value.actors.find(item => item.id === fallback.id) ?? fallback
      if (isNotification(send.prompt)) {
        await recordNotification(draft, to.id, send.prompt, at)
        return observed
      }
      if (!send.prompt.trim()) return observed
      const request = createRequest(draft, id, SELF, to, '追加の指示を送信', send.prompt, at)
      request.awaitingSession = 'message'
      await editRequest(draft, request, { prompt: send.prompt, promptSource: 'セッション送信ツール' })
    } else if (agent) {
      if (!agent.prompt.trim() || isNotification(agent.prompt)) return observed
      const to = namedActor('agent:' + e.tool_use_id, 'subagent', 'サブエージェント', agent.subagent_type || agent.description)
      const request = createRequest(draft, id, SELF, to, inferVerb(agent.prompt), agent.prompt, at)
      request.summary = summarize(agent.description) || request.summary
      await editRequest(draft, request, { prompt: agent.prompt, promptSource: 'Agent ツール' })
    }
    const delegated = draft.value.requests.find(item => item.id === id)
    if (delegated && self) delegated.parentId = self.id
    return observed
  })
}

export async function recordToolResult($: EngineInterface, runtime: Runtime, observed: ObservedTool, result: ToolCallResult, now: number) {
  const text = result.text || result.deny || ''
  const payload = recordOf(result.result)
  const exit = typeof payload.exitCode === 'number' ? payload.exitCode
    : typeof payload.exit_code === 'number' ? payload.exit_code : undefined
  const failed = result.isError === true || result.deny !== undefined || (exit !== undefined && exit !== 0)
  await editBoard($, runtime, async draft => {
    const request = draft.value.requests.find(item => item.id === observed.id)
    const self = draft.value.requests.find(item => item.id === observed.self)
    if (self || (failed && request?.codexLaunch)) {
      const command = observed.step.kind === 'command'
      const output = command ? text || [textOf(payload.stdout), textOf(payload.stderr)].filter(Boolean).join('\n') : ''
      const full = [observed.step.full, exit === undefined ? '' : '終了コード ' + exit, output].filter(Boolean).join('\n')
      const title = command ? describeCommand(observed.step.full, exit).text : observed.step.title
      const step = stepOf(observed.stepId, now, {
        ...observed.step, full, title, kind: failed ? 'error' : observed.step.kind,
        icon: failed ? 'error' : observed.step.icon,
      })
      if (self) await editRequest(draft, self, { steps: [step] })
      if (request?.codexLaunch && failed) await editRequest(draft, request, { steps: [step] })
    }
    if (!request) return
    if (request.codexLaunch) {
      if (request.codexFinished || (request.observation && !failed)) return
      if (!failed && !observed.background && !observed.settleCodex) {
        request.codexLaunch = { ...request.codexLaunch, shellEndedAt: now }
        return
      }
      if (!failed && observed.background) return
    }
    if (observed.tool === START && !failed) {
      const id = sessionId(text)
      if (id) await attachSession(draft, request, id, now)
      return
    }
    if ((observed.tool === SEND || observed.tool === 'SendMessage') && !failed) return
    const steps = text ? [stepOf(observed.id + ':result', now, {
      kind: failed ? 'error' : 'done', title: failed ? '呼び出しエラー' : '呼び出し結果',
      full: truncate(text, observed.tool === 'Agent' ? 2_000 : 4_000),
    })] : []
    await editRequest(draft, request, { steps, status: failed ? 'error' : 'done', endedAt: now })
  })
}

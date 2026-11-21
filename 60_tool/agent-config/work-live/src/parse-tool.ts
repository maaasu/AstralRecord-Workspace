import { basename, recordOf, textOf } from './model.ts'
import type { CodexLaunch } from '../types/index.d.ts'

export type CodexCommand = CodexLaunch & { file: string; prompt: string }
const CODEX = /\bcodex(?:\.cmd|\.exe)?\s+(exec|review)\b/iu

export function parseCodexCommand(command: string): CodexCommand | undefined {
  const match = CODEX.exec(command)
  if (!match) return undefined
  const before = command.slice(0, match.index)
  const rest = command.slice(match.index + match[0].length)
  const redirect = /(?:^|\s)-\s*<\s*(?:"([^"]+)"|'([^']+)'|([^\s;&|<>]+))/u.exec(rest)
  const pipe = /\b(?:echo|printf)\s+(?:"((?:\\.|[^"\\])*)"|'([^']*)')\s*\|\s*(?:&\s*)?$/su.exec(before)
  const direct = !redirect && !pipe ? quotedPrompt(rest) : ''
  const dir = /(?:^|\s)(?:-C|--cd)(?:\s+|=)(?:"([^"]+)"|'([^']+)'|([^\s;&|<>]+))/u.exec(rest)
  return {
    mode: match[1]?.toLowerCase() === 'review' ? 'review' : 'exec',
    cwd: dir?.[1] ?? dir?.[2] ?? dir?.[3] ?? '',
    readOnly: /(?:^|\s)(?:-s|--sandbox)(?:\s+|=)["']?read-only\b/u.test(rest),
    file: redirect?.[1] ?? redirect?.[2] ?? redirect?.[3] ?? '',
    prompt: redirect ? '' : pipe?.[1]?.replace(/\\(["\\])/gu, '$1') ?? pipe?.[2] ?? direct,
  }
}

function quotedPrompt(args: string): string {
  // Consume options with values before looking for a quoted positional argument.
  const values = /^(?:-[aCcimops]|--(?:ask-for-approval|local-provider|cd|config|model|sandbox|image|profile|add-dir|output-schema|output-last-message|color|enable|disable|base|commit|title))$/u
  const words = /"((?:\\["\\]|[^"])*)"|'([^']*)'|([^\s"';&|<>]+)|[;&|<>]/gu
  let skipValue = false
  for (const [word, double, single, bare] of args.matchAll(words)) {
    if (/^[;&|<>]$/u.test(word)) break
    if (skipValue) {
      skipValue = false
      continue
    }
    if (bare?.startsWith('-') && bare !== '--') {
      skipValue = values.test(bare.split('=', 1)[0] ?? '') && (!bare.includes('=') || bare.endsWith('='))
      continue
    }
    if (double !== undefined || single !== undefined) return double?.replace(/\\(["\\])/gu, '$1') ?? single ?? ''
    if (bare !== '--') break
  }
  return ''
}

export function codexVerb(command: CodexLaunch, prompt: string): string {
  if (command.mode === 'review' || /レビュー|review/iu.test(prompt)) return 'レビューを依頼'
  return command.readOnly ? '調査を依頼' : '実装を依頼'
}

export function instructionSource(file: string): string {
  return '指示書: ' + basename(file)
}

export function normalizePath(path: string): string {
  return path.replace(/\\/gu, '/').replace(/\/+$/u, '').toLowerCase()
}

export function matchesCodexLaunch(launch: CodexLaunch, calledAt: number, cwd: string, at: number): boolean {
  return !!launch.cwd && !!cwd && normalizePath(launch.cwd) === normalizePath(cwd) &&
    at >= calledAt - 5_000 && at <= calledAt + 180_000
}

export function sessionKey(id: string): string {
  return id.replace(/^local_/u, '')
}

export function sessionId(text: string): string {
  return /\b(?:local|session)_[\w-]+\b/u.exec(text)?.[0] ??
    /(?:^|(?:session(?:[_ -]?id)?["']?\s*[:=]\s*["']?)|(?:started session\s*[:=]\s*))([0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})(?=$|[^\w-])/iu.exec(text.trim())?.[1] ?? ''
}

export function startSessionInput(input: unknown) {
  const args = recordOf(input)
  return {
    title: textOf(args.title),
    prompt: [textOf(args.prompt), textOf(args.background)].filter(Boolean).join('\n\n'),
    remote: args.target === 'cloud',
  }
}

export function sessionMessageInput(tool: string, input: unknown) {
  const args = recordOf(input)
  const to = tool === 'SendMessage' ? textOf(args.to) : textOf(args.session_id) || textOf(args.sessionId)
  const id = sessionId(to)
  const prompt = textOf(args.message) || textOf(args.prompt)
  return id && prompt ? { id, prompt } : undefined
}

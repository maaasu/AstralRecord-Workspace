import type { IconName, Kind } from '../types'
import { basename, oneLine } from './model'

const wrappers = /^(?:&\s+)?(?:"[^"\r\n]*(?:pwsh|powershell)(?:\.exe)?"|'[^'\r\n]*(?:pwsh|powershell)(?:\.exe)?'|(?:pwsh|powershell)(?:\.exe)?)\s+(?:-\S+\s+)*?-(?:command|c)\s+|^(?:\/\S+\/)?(?:bash|sh|zsh)\s+-[a-z]*c\s+/iu
const encoding = /^(?:\[Console\]::OutputEncoding|\$OutputEncoding)\s*=\s*(?:\[[\w.]+\]::\w+(?:\([^)]*\))?|New-Object\s+[\w.]+(?:\([^)]*\))?|[^;\r\n]+(?=;))\s*/iu

function commands(input: string): string[] {
  const pieces: string[] = []
  let quote = ''
  let start = 0
  for (let i = 0; i < input.length; i++) {
    const char = input[i]
    if ((char === '`' && quote !== "'") || (char === '\\' && /["';&|]/u.test(input[i + 1] ?? '') && quote !== "'")) {
      i++
    } else if (quote) {
      if (char === quote) quote = ''
    } else if (char === '"' || char === "'") {
      quote = char
    } else if (char === ';' || char === '|' || char === '\n' || input.slice(i, i + 2) === '&&') {
      pieces.push(input.slice(start, i).trim())
      if (input[i + 1] === char && (char === '|' || char === '&')) i++
      start = i + 1
    }
  }
  return [...pieces, input.slice(start).trim()].filter(Boolean)
}

function meaningful(input: string): string[] {
  let command = input.trim()
  const wrapper = wrappers.exec(command)
  if (wrapper) {
    command = command.slice(wrapper[0].length).trim()
    if (/^(["'])[^]*\1$/u.test(command)) command = command.slice(1, -1)
  }
  return commands(command).map(part => part.replace(encoding, '').trim())
    .filter(part => part && !/^(?:(?:cd|Set-Location)\s+|chcp(?:\.com)?\s+65001\b)/iu.test(part))
}

function words(command: string, splitCommas = false): string[] {
  const token = splitCommas ? /(?:[^\s"',]+|"[^"]*"|'[^']*')+/gu : /(?:[^\s"']+|"[^"]*"|'[^']*')+/gu
  return (command.match(token) ?? [])
    .map(word => word.replace(/^(["'])([^]*)\1$/u, '$2'))
}

function operands(args: readonly string[], flagsWithValue: RegExp): string[] {
  const values: string[] = []
  for (let i = 0; i < args.length; i++) {
    const arg = args[i]!
    if (arg === '--') return [...values, ...args.slice(i + 1)]
    if (flagsWithValue.test(arg)) i++
    else if (!arg.startsWith('-')) values.push(arg)
  }
  return values
}

export function namedItems(paths: readonly string[]): string {
  const names = paths.map(basename).filter(Boolean)
  return (names[0] ?? '') + (names.length > 1 ? ' ほか ' + (names.length - 1) + ' 件' : '')
}

function describeFirst(command: string): { text: string; icon: IconName } {
  const line = command.replace(/^&\s+/u, '')
  const [program = '', ...args] = words(line)
  const tool = basename(program).toLowerCase().replace(/\.(?:exe|cmd|bat)$/u, '')
  const [first, second] = args
  if (/^(?:get-content|cat|type|head|tail)$/u.test(tool) || (tool === 'sed' && args.includes('-n'))) {
    const parameters = tool === 'get-content' ? words(line, true).slice(1) : args
    const paths = operands(parameters, /^(?:-Encoding|-TotalCount|-Tail|-ReadCount|-Delimiter|-Filter|-Include|-Exclude|-n|-c|--lines|--bytes)$/iu)
    const files = tool === 'sed' ? operands(args, /^-(?:e|f)$/u).slice(args.includes('-e') || args.includes('-f') ? 0 : 1) : paths
    return { text: 'ファイルを読む: ' + (namedItems(files) || '標準入力'), icon: 'file' }
  }
  if (/^(?:ls|get-childitem|dir|find)$/u.test(tool) || (tool === 'rg' && args.includes('--files'))) {
    const paths = operands(args, /^(?:-g|--glob|--iglob|-Filter|-Include|-Exclude|-Depth|--type|-t)$/iu)
    return { text: '一覧: ' + (namedItems(tool === 'find' ? paths.slice(0, 1) : paths) || '現在のフォルダ'), icon: 'folder' }
  }
  if (/^(?:rg|grep|select-string|findstr)$/u.test(tool)) {
    const patternIndex = args.findIndex(arg => /^(?:-e|--regexp|-Pattern)$/iu.test(arg))
    const pattern = patternIndex >= 0 ? args[patternIndex + 1] : args.find(arg => /^(?:--regexp=|\/c:)/iu.test(arg))
      ?.replace(/^(?:--regexp=|\/c:)/iu, '') ?? operands(args, /^(?:-g|--glob|--iglob|-Path|-LiteralPath|-Encoding|-A|-B|-C|-m|-t|--type|--exclude|--include)$/iu)[0]
    return { text: '検索: ' + (pattern ?? ''), icon: 'search' }
  }
  if (tool === 'git') {
    let i = 0
    while (args[i]?.startsWith('-')) i += /^-(?:C|c)$/u.test(args[i]!) ? 2 : 1
    return { text: [tool, ...args.slice(i, i + 4)].join(' '), icon: 'git' }
  }
  if (/^(?:python(?:3(?:\.\d+)?)?|py)$/u.test(tool)) {
    const script = args.find(arg => /\.py$/iu.test(arg))
    return { text: !args.includes('-c') && script ? 'Python: ' + basename(script) : 'Python を実行', icon: 'run' }
  }
  if (/^(?:node|npm|npx|mvnw?|gradlew?|dotnet|bun|deno|pnpm|yarn)$/u.test(tool)) {
    return { text: [tool, first && (first.startsWith('-') ? second : basename(first))].filter(Boolean).join(' '), icon: 'run' }
  }
  if (tool === 'codex' && /^(?:exec|review)$/iu.test(first ?? '')) return { text: 'Codex に依頼', icon: 'delegate' }
  return { text: command, icon: 'terminal' }
}

export function describeCommand(command: string, exitCode?: number): { text: string; icon: IconName } {
  const parts = meaningful(command)
  const summaries = (parts.length ? parts : [command]).map(describeFirst)
  const summary = summaries.find(({ icon }) => icon === 'delegate') ?? summaries[0]!
  const failed = exitCode !== undefined && exitCode !== 0
  return {
    text: (oneLine(summary.text, 60) || 'コマンド実行')
      + (parts.length > 1 ? ' ほか ' + (parts.length - 1) + ' 件' : '')
      + (failed ? '（失敗: 終了コード ' + exitCode + '）' : ''),
    icon: failed ? 'error' : summary.icon,
  }
}

export function describeToolKind(tool: string): { kind: Kind; icon?: IconName } {
  if (/^(?:edit|write|notebookedit|multiedit|apply_patch)$/iu.test(tool)) return { kind: 'edit' }
  if (/^(?:bash|powershell|exec_command)$/iu.test(tool)) return { kind: 'command' }
  if (/^read$/iu.test(tool)) return { kind: 'read' }
  if (/^grep$/iu.test(tool)) return { kind: 'read', icon: 'search' }
  if (/^glob$/iu.test(tool)) return { kind: 'read', icon: 'folder' }
  if (/^(?:webfetch|websearch|browser|chrome|mcp__[\w-]*(?:browser|chrome)[\w-]*__)/iu.test(tool)) return { kind: 'web' }
  if (/^(?:agent|task)$/iu.test(tool)) return { kind: 'agent' }
  if (/(?:^|__)(?:start_session|send_message)$|^sendmessage$/iu.test(tool)) return { kind: 'tool', icon: 'delegate' }
  return { kind: 'tool' }
}

export function commandLanguage(command: string): 'powershell' | 'bash' {
  return /\b(?:pwsh|powershell)(?:\.exe)?\b|\[Console\]::|\$OutputEncoding|\b(?:Get-Content|Get-ChildItem|Select-String|Set-Location)\b/iu
    .test(command) ? 'powershell' : 'bash'
}

export function describeEdit(paths: readonly string[]): string {
  return '編集: ' + (namedItems(paths) || 'ファイル変更')
}

export function describeMessage(body: string, fallback: string): string {
  return oneLine(body, 60) || fallback
}

import { expect, test } from 'claude-code/testing'
import { describeCommand, describeEdit, describeMessage, describeToolKind } from '../hooks/describe'
import { stepIcon } from '../hooks/icons'

test('実際の PowerShell ログの前置きを除き、コマンド件数を数える', () => {
  const logged = "[Console]::OutputEncoding = [System.Text.Encoding]::UTF8 Get-Content -Raw -Encoding UTF8 -LiteralPath 'C:/x/README.md'; Get-Content -LiteralPath 'y.md'"
  expect(describeCommand(logged).text).toBe('ファイルを読む: README.md ほか 1 件')
  expect(describeCommand("& 'C:/Program Files/PowerShell/7/pwsh.exe' -Command \"" + logged + "\"").text)
    .toBe('ファイルを読む: README.md ほか 1 件')
  expect(describeCommand('powershell -NoProfile -Command "$OutputEncoding = [Text.Encoding]::UTF8; Set-Location C:/src; git status"').text)
    .toBe('git status')
  expect(describeCommand("chcp 65001; cd 'C:/x a' && cat 'docs/a b.md' | head").text).toBe('ファイルを読む: a b.md ほか 1 件')
})

test('ツールと引数を選び、引用符内の区切り文字を保持する', () => {
  expect(describeCommand('git diff --stat; git diff --cached --stat').text).toBe('git diff --stat ほか 1 件')
  expect(describeCommand('python -c "print(1); print(2)"').text).toBe('Python を実行')
  expect(describeCommand('python C:/x/test.py', 1)).toEqual({ text: 'Python: test.py（失敗: 終了コード 1）', icon: 'error' })
  expect(describeCommand("rg -n 'a|b' src", 0).text).toBe('検索: a|b')
  expect(describeCommand('rg --files -g *.ts src/lib').text).toBe('一覧: lib')
  expect(describeCommand("sed -n '1,10p' /x/a.md /x/b.md /x/c.md").text).toBe('ファイルを読む: a.md ほか 2 件')
  expect(describeCommand("Get-Content -LiteralPath 'C:/x/a.md','C:/x/b.md','C:/x/c.md'").text)
    .toBe('ファイルを読む: a.md ほか 2 件')
  expect(describeCommand('npm run test').text).toBe('npm run')
  expect(describeCommand('codex.exe exec -s read-only').text).toBe('Codex に依頼')
  expect(describeEdit(['C:/x/a.ts', 'b.ts', 'c.ts'])).toBe('編集: a.ts ほか 2 件')
  expect(describeMessage('🦊'.repeat(63), '作業')).toBe('🦊'.repeat(60))
})

test('コマンドとセッションのツールから作業アイコンを選ぶ', () => {
  for (const [command, icon] of [
    ['cat a.txt', 'file'], ['rg pattern', 'search'], ['rg --files', 'folder'], ['git status', 'git'],
    ['python a.py', 'run'], ['node a.js', 'run'], ['npm test', 'run'], ['npx tsc', 'run'],
    ['mvn test', 'run'], ['gradle test', 'run'], ['dotnet test', 'run'], ['codex review', 'delegate'],
    ['echo "依頼" | codex exec -', 'delegate'], ['pwd', 'terminal'],
  ] as const) expect(describeCommand(command).icon).toBe(icon)
  for (const [tool, icon] of [
    ['Read', 'file'], ['Grep', 'search'], ['Glob', 'folder'], ['Edit', 'edit'], ['Write', 'edit'],
    ['Bash', 'terminal'], ['PowerShell', 'terminal'], ['WebFetch', 'web'], ['Agent', 'agent'],
    ['start_session', 'delegate'], ['mcp__ccd_session_mgmt__send_message', 'delegate'],
    ['SendMessage', 'delegate'], ['TodoWrite', 'tool'],
  ] as const) expect(stepIcon(describeToolKind(tool))).toBe(icon)
})

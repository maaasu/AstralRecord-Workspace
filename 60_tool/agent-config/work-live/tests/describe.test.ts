import assert from 'node:assert/strict'
import { test } from 'node:test'
import { describeCommand, describeEdit, describeMessage, describeToolKind } from '../src/describe.ts'
import { stepIcon } from '../src/icons.ts'

test('実際の PowerShell ログの前置きを除き、コマンド件数を数える', () => {
  const logged = "[Console]::OutputEncoding = [System.Text.Encoding]::UTF8 Get-Content -Raw -Encoding UTF8 -LiteralPath 'C:/x/README.md'; Get-Content -LiteralPath 'y.md'"
  assert.equal(describeCommand(logged).text, 'ファイルを読む: README.md ほか 1 件')
  assert.equal(describeCommand("& 'C:/Program Files/PowerShell/7/pwsh.exe' -Command \"" + logged + "\"").text, 'ファイルを読む: README.md ほか 1 件')
  assert.equal(describeCommand('powershell -NoProfile -Command "$OutputEncoding = [Text.Encoding]::UTF8; Set-Location C:/src; git status"').text, 'git status')
  assert.equal(describeCommand("chcp 65001; cd 'C:/x a' && cat 'docs/a b.md' | head").text, 'ファイルを読む: a b.md ほか 1 件')
})

test('ツールと引数を選び、引用符内の区切り文字を保持する', () => {
  assert.equal(describeCommand('git diff --stat; git diff --cached --stat').text, 'git diff --stat ほか 1 件')
  assert.equal(describeCommand('python -c "print(1); print(2)"').text, 'Python を実行')
  assert.deepEqual(describeCommand('python C:/x/test.py', 1), { text: 'Python: test.py（失敗: 終了コード 1）', icon: 'error' })
  assert.equal(describeCommand("rg -n 'a|b' src", 0).text, '検索: a|b')
  assert.equal(describeCommand('rg --files -g *.ts src/lib').text, '一覧: lib')
  assert.equal(describeCommand("sed -n '1,10p' /x/a.md /x/b.md /x/c.md").text, 'ファイルを読む: a.md ほか 2 件')
  assert.equal(describeCommand("Get-Content -LiteralPath 'C:/x/a.md','C:/x/b.md','C:/x/c.md'").text, 'ファイルを読む: a.md ほか 2 件')
  assert.equal(describeCommand('npm run test').text, 'npm run')
  assert.equal(describeCommand('codex.exe exec -s read-only').text, 'Codex に依頼')
  assert.equal(describeEdit(['C:/x/a.ts', 'b.ts', 'c.ts']), '編集: a.ts ほか 2 件')
  assert.equal(describeMessage('🦊'.repeat(63), '作業'), '🦊'.repeat(60))
})

test('コマンドとセッションのツールから作業アイコンを選ぶ', () => {
  for (const [command, icon] of [
    ['cat a.txt', 'file'], ['rg pattern', 'search'], ['rg --files', 'folder'], ['git status', 'git'],
    ['python a.py', 'run'], ['node a.js', 'run'], ['npm test', 'run'], ['npx tsc', 'run'],
    ['mvn test', 'run'], ['gradle test', 'run'], ['dotnet test', 'run'], ['codex review', 'delegate'],
    ['echo "依頼" | codex exec -', 'delegate'], ['pwd', 'terminal'],
  ] as const) assert.equal(describeCommand(command).icon, icon)
  for (const [tool, icon] of [
    ['Read', 'file'], ['Grep', 'search'], ['Glob', 'folder'], ['Edit', 'edit'], ['Write', 'edit'],
    ['Bash', 'terminal'], ['PowerShell', 'terminal'], ['WebFetch', 'web'], ['Agent', 'agent'],
    ['start_session', 'delegate'], ['mcp__ccd_session_mgmt__send_message', 'delegate'],
    ['SendMessage', 'delegate'], ['TodoWrite', 'tool'],
  ] as const) assert.equal(stepIcon(describeToolKind(tool)), icon)
})

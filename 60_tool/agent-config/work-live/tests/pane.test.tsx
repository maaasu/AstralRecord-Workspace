import { expect, mock, test } from 'claude-code/testing'
import type { On } from 'claude-code'
import { emptyDetail } from '../hooks/model'
import { readBoard } from '../hooks/state'
import { sampleBoard } from './fixtures/sample'
import type { Actor, LiveBoard, RequestDetail } from '../types'

const PANE = {
  plugin: 'work-live',
  component: 'Pane',
  requestId: 'work-live',
  props: { title: '作業ライブ', isFocused: false, bodyColumns: 65, placement: 'dock', scroll: { offset: 0, bodyRows: 20 }, view: {} },
} as const
const OPTIONS = { watchCodex: false, watchSessions: false }
const ORIGIN = { kind: 'composer' } as const

function captureState(on: On) {
  const unwrap = (value: unknown): any =>
    value && typeof value === 'object' && 'shape' in (value as object) && 'value' in (value as object)
      ? (value as { value: unknown }).value : value
  const captured: { board?: LiveBoard; details: Map<string, RequestDetail> } = { details: new Map() }
  on('state.set', { plugin: 'work-live' }, async (_, e, next) => {
    const result = await next(e)
    const input = e as unknown as { key: string; id?: unknown; value: unknown }
    if (input.key === 'board') captured.board = unwrap(input.value)
    if (input.key === 'detail') captured.details.set(JSON.stringify(input.id ?? null), unwrap(input.value))
    return result
  })
  return captured
}

function seedBoard(on: On, value: LiveBoard) {
  on('state.get', { plugin: 'work-live', key: 'board' }, async (_, e, next) => {
    const result = await next(e)
    if (!result.value || result.value.value !== undefined) return result
    return { value: { ...result.value, value: { shape: 'board-5', value } } }
  })
}

test('空のペインに待機中と監視停止を表示する', { options: OPTIONS }, async ($, on) => {
  mock.clock(on)
  for (const surface of ['desktop', 'terminal'] as const) {
    const ui = await $.ui.mount({ ...PANE, surface })
    expect(await ui.find({ type: 'Text', text: 'まだ作業はありません' })).toBeDefined()
    if (surface === 'desktop') {
      const icons = await ui.findAll({ type: 'Svg' })
      expect(icons.find(item => item.props.alt === '待機中')?.props).toMatchObject({ width: 16, height: 16 })
      expect(icons.filter(item => item.props.alt === '停止')).toHaveLength(2)
      expect((await ui.find({ key: 'tab:self' }))?.props.borderColor).toBe('#D97757')
    } else {
      expect(await ui.findAll({ type: 'Svg' })).toHaveLength(0)
      expect(await ui.find({ type: 'Text', text: '[待機中]' })).toBeDefined()
    }
    await ui.unmount()
  }
})

test('各面でフックを透過し、依頼から全文とコマンドを開閉する', { options: OPTIONS }, async ($, on) => {
  const clock = mock.clock(on, { now: 30_000 })
  let opens = 0
  const prompt = ['# タスク: 表示を実装 🦊', ...Array.from({ length: 35 }, (_, index) => '指示 ' + (index + 1))].join('\n')
  const input = { text: prompt, wait: false, origin: ORIGIN }
  const promptResult = { text: prompt, context: ['下流の応答'], origin: ORIGIN }
  const toolResult = { result: { stdout: 'preview', stderr: '', interrupted: false }, text: 'preview', ref: 42 }
  on('prompt.submit', (_, e) => {
    expect(e).toEqual(input)
    return promptResult
  })
  on('turn.start', (_, e) => ({ turnId: e.turnId }))
  on('turn.complete', (_, e) => {
    expect(e.answer).toBe('元の回答')
    return { text: '下流の完了' }
  })
  on('ui.open', () => {
    opens++
    return { value: { isPlaced: true } }
  })
  on('tool.call', { tool: 'Bash' }, (_, e) => {
    expect(e.command).toBe('preview --port 3000')
    expect(e.description).toBe('プレビューを起動')
    return toolResult
  })
  expect(await $.prompt.submit(input)).toEqual(promptResult)
  await $.turn.start({ turnId: 'turn-a', text: prompt })
  expect(await $.tool.call({
    tool: 'Bash', command: 'preview --port 3000', description: 'プレビューを起動', tool_use_id: 'run-preview',
  })).toEqual(toolResult)
  await clock.settle()
  expect(opens).toBe(0)
  for (const surface of ['desktop', 'mobile', 'vscode', 'terminal'] as const) {
    const ui = await $.ui.mount({ ...PANE, surface })
    const request = await ui.find({ type: 'Button', text: /1\. あなた が Claude.*に実装を依頼/ })
    if (!request?.key) throw new Error('依頼を表示できません')
    if (surface === 'terminal') {
      expect(await ui.find({ type: 'Text', text: /└─1\. 実装を依頼 → Claude.*\[実行中\]/u })).toBeDefined()
    } else {
      const icons = await ui.findAll({ type: 'Svg' })
      const diagram = icons.find(item => item.props.width === 608)
      expect(diagram?.props).toMatchObject({ width: 608, height: 124 })
      expect(diagram?.props.source).toContain('width="608" height="124" viewBox="0 0 608 124"')
      expect(icons.filter(item => item.props.width === 16 && item.props.isInteractive).map(item => item.props.alt))
        .toEqual(['実行中', '実行中'])
    }
    await ui.press({ key: request.key })
    expect((await ui.find({ type: 'Box', key: 'row:' + request.key }))?.children).toMatchObject([
      { type: 'Box', props: { key: 'request-summary:' + request.key } }, { type: 'Box', props: { key: 'detail:' + request.key } },
    ])
    expect(await ui.find({ type: 'Markdown', text: '# タスク: 表示を実装' })).toBeDefined()
    expect(await ui.find({ type: 'Markdown', text: '指示 19' })).toBeDefined()
    expect(await ui.find({ type: 'Markdown', text: '指示 20' })).toBeUndefined()
    await ui.press({ key: 'toggle-prompt' })
    expect(await ui.find({ type: 'Markdown', text: '指示 35' })).toBeDefined()
    await ui.press({ key: 'toggle-prompt' })
    expect(await ui.find({ type: 'Markdown', text: '指示 35' })).toBeUndefined()
    expect(await ui.find({ type: 'Button', text: 'preview --port 3000' })).toBeDefined()
    const requestRow = await ui.find({ key: 'request-summary:' + request.key })
    const stepRow = await ui.find({ key: 'step-summary:tool:run-preview' })
    if (surface === 'terminal') {
      expect(await ui.findAll({ type: 'Svg' })).toHaveLength(0)
      expect(await ui.find({ type: 'Text', text: '[実行]' })).toBeDefined()
      expect(await ui.find({ type: 'Text', text: '[実行中]・1 件' })).toBeDefined()
    } else {
      expect(requestRow?.children).toMatchObject([
        { type: 'Svg', props: { alt: '実行中' } },
        { type: 'Svg', props: { alt: '依頼元: あなた' } },
        { type: 'Button', props: { key: request.key } },
        { type: 'Text' },
      ])
      expect(stepRow?.children).toMatchObject([
        { type: 'Svg', props: { alt: '実行' } },
        { type: 'Button', props: { key: 'tool:run-preview' } },
      ])
      for (const type of ['Text', 'Button']) {
        for (const item of await ui.findAll({ type })) {
          expect(item.text + (item.props.label ?? '')).not.toMatch(/[\p{Extended_Pictographic}✎✔✖●○•①-⑳]/u)
        }
      }
    }
    await ui.press({ key: 'tool:run-preview' })
    expect((await ui.find({ type: 'Code', text: 'preview --port 3000' }))?.props)
      .toMatchObject({ language: 'bash', wrap: 'wrap' })
    await ui.press({ key: 'tool:run-preview' })
    expect(await ui.find({ type: 'Code', text: 'preview --port 3000' })).toBeUndefined()
    await ui.press({ key: request.key })
    expect(await ui.find({ type: 'Code' })).toBeUndefined()
    await ui.unmount()
  }
  const end = { turnId: 'turn-a', reason: 'answer', isAborted: false, durationMs: 321, answer: '元の回答' } as const
  expect(await $.turn.complete(end)).toEqual({ text: '下流の完了' })
  await clock.settle()
  const widget = await $.ui.mount({ ...PANE, props: { ...PANE.props, placement: 'widget' }, surface: 'desktop' })
  expect(await widget.find({ type: 'Text', text: '待機中' })).toBeDefined()
  expect((await widget.find({ type: 'Svg' }))?.props.alt).toBe('待機中')
  await widget.unmount()
})

test('クラウド開始の結果と追加指示を同じセッションに結び付ける', { options: OPTIONS }, async ($, on) => {
  const clock = mock.clock(on, { now: 30_000 })
  let opened = 0
  on('ui.open', () => {
    opened++
    return { value: { isPlaced: true } }
  })
  const started = { result: {}, text: 'Started session: session_cloud', ref: 80 }
  on('tool.call', { tool: 'mcp__ccd_session__start_session' }, () => started)
  on('tool.call', { tool: 'mcp__ccd_session_mgmt__send_message' }, () => ({ result: {} }))
  expect(await $.tool.call({
    tool: 'mcp__ccd_session__start_session', tool_use_id: 'start', target: 'cloud', title: '調査',
    prompt: '確認してください', background: '前提の説明',
  })).toEqual(started)
  await $.tool.call({
    tool: 'mcp__ccd_session_mgmt__send_message', tool_use_id: 'send', session_id: 'session_cloud', message: '追加で確認してください',
  })
  await clock.settle()
  expect(opened).toBe(2)
  const ui = await $.ui.mount({ ...PANE, surface: 'desktop' })
  expect(await ui.find({ type: 'Button', key: 'request:start', text: /Claude.* が クラウド（調査） にクラウドで作業を依頼/ })).toBeDefined()
  expect(await ui.find({ type: 'Button', key: 'request:send', text: /が クラウド（調査） に追加の指示を送信/ })).toBeDefined()
  for (const id of ['start', 'send']) {
    const row = await ui.find({ key: 'request-summary:request:' + id })
    expect(row?.children).toMatchObject([{ type: 'Svg' }, { type: 'Svg' }, { type: 'Button' }, { type: 'Text' }])
  }
  await ui.press({ key: 'request:start' })
  expect(await ui.find({ type: 'Markdown', text: '確認してください\n\n前提の説明' })).toBeDefined()
  await ui.unmount()

  const widget = await $.ui.mount({ ...PANE, props: { ...PANE.props, placement: 'widget' }, surface: 'desktop' })
  expect(await widget.findAll({ type: 'Text' })).toHaveLength(1)
  expect(await widget.find({ type: 'Text', text: /実行中: .*クラウド/ })).toBeDefined()
  expect((await widget.find({ type: 'Svg' }))?.props.alt).toBe('実行中')
  await widget.unmount()
})

test('Codex の指示書の読み取りを保留しても下流が進み、後から依頼を表示する', { options: OPTIONS }, async ($, on) => {
  const clock = mock.clock(on, { now: 30_000 })
  const command = 'codex exec -s read-only -C C:/work - < "work input.md"'
  let readPending = false
  let forwarded = false
  on('fs.read', async (_, e) => {
    expect(e.path.replace(/\\/gu, '/')).toMatch(/(^|\/)work input\.md$/u)
    readPending = true
    await clock.sleep(25)
    readPending = false
    return { value: '差分をレビュー\n結果は根拠とともに報告' }
  })
  on('ui.open', () => ({ value: { isPlaced: true } }))
  const answer = { result: { stdout: 'finished', stderr: '', interrupted: false }, text: 'finished', ref: 9 }
  on('tool.call', { tool: 'Bash' }, (_, e) => {
    expect(e.command).toBe(command)
    forwarded = true
    return answer
  })
  const dispatch = $.tool.call({ tool: 'Bash', command, tool_use_id: 'codex-instructions' })
  await clock.settle()
  expect(readPending).toBe(true)
  expect(forwarded).toBe(true)
  expect(await dispatch).toEqual(answer)
  await clock.advance(25)
  const ui = await $.ui.mount({ ...PANE, surface: 'desktop' })
  await ui.press({ key: 'request:codex-instructions' })
  expect(await ui.find({ type: 'Text', text: /Codex にレビューを依頼/ })).toBeDefined()
  expect(await ui.find({ type: 'Text', text: '指示書: work input.md' })).toBeDefined()
  expect(await ui.find({ type: 'Markdown', text: '差分をレビュー\n結果は根拠とともに報告' })).toBeDefined()
  await ui.press({ key: 'tool:codex-instructions' })
  expect(await ui.find({ type: 'Code', text: command })).toBeDefined()
  await ui.unmount()
})

test('セッションを切り替えると流れと見出しとウィジェットが変わる', { options: OPTIONS }, async ($, on) => {
  const now = 12_000_000
  mock.clock(on, { now })
  seedBoard(on, sampleBoard(now))
  const ui = await $.ui.mount({ ...PANE, surface: 'desktop' })
  const buttons = await ui.findAll({ type: 'Button' })
  expect(buttons.filter(item => item.key?.startsWith('session:')).map(item => item.key))
    .toEqual(['session:self', 'session:claude:cloud', 'session:codex:live', 'session:claude:guide',
      'session:codex:guide', 'session:all'])
  expect((await ui.find({ key: 'tab:self' }))?.props.borderColor).toBe('#D97757')
  expect(await ui.find({ key: 'flow:live-root' })).toBeDefined()
  expect(await ui.find({ key: 'flow:guide-root' })).toBeUndefined()
  expect(await ui.find({ key: 'flow:cloud-root' })).toBeUndefined()
  expect(await ui.find({ type: 'Text', text: /開始 \d\d:\d\d ・ あなたから 1 件受けて、2 件依頼/u })).toBeDefined()
  expect(await ui.find({ type: 'Text', text: '実行中・10 件' })).toBeDefined()

  await ui.press({ key: 'session:codex:live' })
  expect((await ui.find({ key: 'tab:codex:live' }))?.props.borderColor).toBe('#D97757')
  const dimmed = await ui.find({ key: 'live-review' })
  expect(dimmed?.children).toMatchObject([{ type: 'Text', props: { dimColor: true } }])
  await ui.press({ key: 'session:claude:guide' })
  expect(await ui.find({ key: 'flow:live-root' })).toBeUndefined()
  expect(await ui.find({ key: 'flow:guide-root' })).toBeDefined()
  expect(await ui.find({ type: 'Text', text: /あなたから 1 件受けて、1 件依頼/u })).toBeDefined()

  await ui.press({ key: 'session:claude:cloud' })
  await ui.unmount()
  const widget = await $.ui.mount({ ...PANE, props: { ...PANE.props, placement: 'widget' }, surface: 'desktop' })
  expect(await widget.find({ type: 'Text', text: /建築作例を調査/u })).toBeDefined()
  expect(await widget.findAll({ type: 'Text' })).toHaveLength(1)
  await widget.unmount()
  const again = await $.ui.mount({ ...PANE, surface: 'desktop' })
  await again.press({ key: 'session:all' })
  expect((await again.find({ key: 'tab:all' }))?.props.borderColor).toBe('#D97757')
  for (const id of ['live-root', 'guide-root', 'cloud-root']) expect(await again.find({ key: 'flow:' + id })).toBeDefined()
  await again.unmount()
  const terminal = await $.ui.mount({ ...PANE, surface: 'terminal' })
  expect(await terminal.find({ key: 'session:all', text: 'すべて [選択中]' })).toBeDefined()
  expect(await terminal.find({ type: 'Text', text: /いま: フロー図の配置テストを実行 （11 分）/u })).toBeDefined()
  await terminal.unmount()
})

test('このセッションに依頼がなければ新しい実行中のセッションを選び、8 件を超えるタブを展開する',
  { options: OPTIONS }, async ($, on) => {
    const now = 12_000_000
    mock.clock(on, { now })
    const sample = sampleBoard(now)
    const cloud = sample.requests.find(request => request.id === 'cloud-root')!
    const more: Actor[] = Array.from({ length: 8 }, (_, index) =>
      ({ id: 'codex:extra-' + index, kind: 'codex', label: 'Codex（過去の作業 ' + index + '）' }))
    seedBoard(on, {
      ...sample, actors: [...sample.actors.filter(actor => actor.id === cloud.from || actor.id === cloud.to), ...more],
      requests: [
        cloud,
        ...more.map((actor, index) => ({ ...cloud, id: 'extra-' + index, to: actor.id,
          status: 'done' as const, endedAt: now - 20 * 60_000 - index, at: now - 25 * 60_000 - index,
          lastStep: undefined, lastStepAt: undefined, stepCount: 0, counts: {} })),
      ],
    })
    const ui = await $.ui.mount({ ...PANE, surface: 'desktop' })
    expect((await ui.find({ key: 'tab:claude:cloud' }))?.props.borderColor).toBe('#D97757')
    expect(await ui.find({ key: 'flow:cloud-root' })).toBeDefined()
    expect(await ui.find({ key: 'session:more', text: 'ほか 2 件' })).toBeDefined()
    expect(await ui.find({ key: 'session:codex:extra-7' })).toBeUndefined()
    await ui.press({ key: 'session:more' })
    expect(await ui.find({ key: 'session:codex:extra-7' })).toBeDefined()
    await ui.press({ key: 'session:codex:extra-7' })
    expect(await ui.find({ key: 'flow:extra-7' })).toBeDefined()
    expect(await ui.find({ key: 'flow:cloud-root' })).toBeUndefined()
    await ui.unmount()
  })

test('タブを展開しても実行中の波紋は最大 8 個で、見出しの LIVE も動く', { options: OPTIONS }, async ($, on) => {
  const now = 12_000_000
  mock.clock(on, { now })
  const sample = sampleBoard(now)
  const cloud = sample.requests.find(request => request.id === 'cloud-root')!
  const user = sample.actors.find(actor => actor.id === cloud.from)!
  const workers: Actor[] = Array.from({ length: 10 }, (_, index) =>
    ({ id: 'codex:tab-' + index, kind: 'codex', label: 'Codex 作業 ' + index }))
  seedBoard(on, {
    ...sample,
    actors: [user, ...workers],
    requests: workers.map((worker, index) => ({ ...cloud, id: worker.id, to: worker.id, at: now - 10_000 - index })),
  })
  const ui = await $.ui.mount({ ...PANE, surface: 'desktop' })
  await ui.press({ key: 'session:more' })
  const icons = (await ui.findAll({ type: 'Svg' })).filter(item => item.props.width === 16 && item.props.alt === '実行中')
  const active = icons.filter(item => item.props.isInteractive)
  expect(active).toHaveLength(9)
  expect(active.every(item => String(item.props.source).includes('class="ripple ripple-next motion-only"'))).toBe(true)
  expect(icons.filter(item => !item.props.isInteractive).every(item => !String(item.props.source).includes('<style>'))).toBe(true)
  expect(await ui.find({ type: 'Text', text: 'LIVE 1 件' })).toBeDefined()
  await ui.unmount()
})

test('終了コードが 0 以外のコマンドに失敗アイコンを表示する', { options: OPTIONS }, async ($, on) => {
  const clock = mock.clock(on, { now: 30_000 })
  on('prompt.submit', (_, e) => ({ text: e.text }))
  on('turn.start', (_, e) => ({ turnId: e.turnId }))
  const failure = { result: { exitCode: 2, stdout: '', stderr: 'missing', interrupted: false } }
  on('tool.call', { tool: 'Bash' }, () => failure)
  await $.prompt.submit({ text: 'ファイルを読む', origin: ORIGIN, wait: false })
  await $.turn.start({ turnId: 'failed-read', text: 'ファイルを読む' })
  expect(await $.tool.call({ tool: 'Bash', command: 'cat missing.txt', tool_use_id: 'failed-read' })).toEqual(failure)
  await clock.settle()
  const ui = await $.ui.mount({ ...PANE, surface: 'desktop' })
  const request = (await ui.findAll({ type: 'Button' })).find(item => item.key?.startsWith('self-request:'))
  await ui.press({ key: request!.key! })
  expect((await ui.find({ key: 'step-summary:tool:failed-read' }))?.children).toMatchObject([
    { type: 'Svg', props: { alt: '失敗' } }, { type: 'Button' },
  ])
  expect(await ui.find({ key: 'tool:failed-read', text: '終了コード 2' })).toBeDefined()
  await ui.unmount()
})

test('メタ入力とスラッシュコマンドを除き、待機中に送った依頼を次のターンに対応させる', { options: OPTIONS }, async ($, on) => {
  const clock = mock.clock(on, { now: 30_000 })
  on('prompt.submit', (_, e) => ({ text: e.text }))
  on('turn.start', (_, e) => ({ turnId: e.turnId }))
  on('turn.complete', (_, e) => ({ text: e.answer }))
  on('tool.call', { tool: 'Bash' }, () => ({ result: { stdout: '/', stderr: '', interrupted: false } }))
  await $.prompt.submit({ text: 'メタ記録', origin: ORIGIN, wait: false, isMeta: true })
  await $.prompt.submit({ text: '/help', command: { name: 'help', args: '' }, origin: ORIGIN, wait: false })
  await $.prompt.submit({ text: '1 件目', origin: ORIGIN, wait: false })
  await $.turn.start({ turnId: 'first', text: '1 件目' })
  await clock.advance(1)
  await $.prompt.submit({ text: '2 件目', origin: ORIGIN, wait: true, turnId: 'first' })
  await $.tool.call({ tool: 'Bash', command: 'pwd', tool_use_id: 'first-command' })
  await $.turn.complete({ turnId: 'first', reason: 'answer', isAborted: false, durationMs: 0, answer: '' })
  await $.turn.start({ turnId: 'second', text: '2 件目' })
  await clock.advance(1)
  await $.tool.call({ tool: 'Bash', command: 'git status', tool_use_id: 'second-command' })
  await clock.settle()
  const ui = await $.ui.mount({ ...PANE, surface: 'terminal' })
  const requests = (await ui.findAll({ type: 'Button' })).filter(item => item.key?.startsWith('self-request:')).reverse()
  expect(requests).toHaveLength(2)
  const first = requests[0]?.key
  const second = requests[1]?.key
  if (!first || !second) throw new Error('依頼を表示できません')
  await ui.press({ key: first })
  expect(await ui.find({ type: 'Button', key: 'tool:first-command' })).toBeDefined()
  expect(await ui.find({ type: 'Button', key: 'tool:second-command' })).toBeUndefined()
  await ui.press({ key: second })
  expect(await ui.find({ type: 'Button', key: 'tool:first-command' })).toBeUndefined()
  expect(await ui.find({ type: 'Button', key: 'tool:second-command' })).toBeDefined()
  await ui.unmount()
})

test('表示範囲ボタンで古い流れと応答なしの依頼を切り替える', { options: OPTIONS }, async ($, on) => {
  const clock = mock.clock(on, { now: 30_000 })
  on('prompt.submit', (_, e) => ({ text: e.text }))
  on('turn.start', (_, e) => ({ turnId: e.turnId }))
  on('tool.call', { tool: 'Bash' }, () => ({ result: { stdout: '', stderr: '', interrupted: false } }))
  await $.prompt.submit({ text: '以前の調査', wait: false, origin: ORIGIN })
  await clock.settle()
  await clock.advance(61 * 60_000)
  await $.prompt.submit({ text: '最近の調査', wait: false, origin: ORIGIN })
  await $.turn.start({ turnId: 'recent', text: '最近の調査' })
  await clock.settle()
  const ui = await $.ui.mount({ ...PANE, surface: 'desktop' })
  expect((await ui.find({ key: 'scope:hour' }))?.props.variant).toBe('primary')
  expect(await ui.find({ type: 'Text', text: '最近の調査' })).toBeDefined()
  expect(await ui.find({ type: 'Text', text: '以前の調査' })).toBeUndefined()
  await ui.press({ key: 'scope:all' })
  expect((await ui.find({ key: 'scope:all' }))?.props.variant).toBe('primary')
  expect(await ui.find({ type: 'Text', text: '以前の調査' })).toBeDefined()
  await ui.press({ key: 'scope:running' })
  expect(await ui.find({ type: 'Text', text: '以前の調査' })).toBeUndefined()
  expect(await ui.find({ type: 'Text', text: 'LIVE 1 件' })).toBeDefined()
  await clock.advance(3 * 60_000)
  await ui.redraw()
  expect(await ui.find({ type: 'Text', text: '待機中' })).toBeDefined()
  expect(await ui.find({ type: 'Text', text: '最近の調査' })).toBeUndefined()
  await $.tool.call({ tool: 'Bash', command: 'git status', tool_use_id: 'wake' })
  await clock.settle()
  await ui.redraw()
  expect(await ui.find({ type: 'Text', text: '最近の調査' })).toBeDefined()
  await ui.unmount()
})

test('自動通知と空の入力を一覧から外し、長い通知は詳細で 4,000 字に収める', { options: OPTIONS }, async ($, on) => {
  const clock = mock.clock(on, { now: 30_000 })
  on('prompt.submit', (_, e) => ({ text: e.text }))
  const body = '<task-notification>\n' + '通知本文'.repeat(1_100) + '\n</task-notification>'
  for (const text of ['元の依頼', body, '   ']) {
    expect(await $.prompt.submit({ text, wait: false, origin: ORIGIN })).toEqual({ text })
  }
  await clock.settle()
  const ui = await $.ui.mount({ ...PANE, surface: 'terminal' })
  const requests = (await ui.findAll({ type: 'Button' })).filter(item => item.key?.startsWith('self-request:'))
  expect(requests).toHaveLength(1)
  await ui.press({ key: requests[0]!.key! })
  expect(await ui.find({ key: 'notice:2', text: 'バックグラウンド作業の完了通知' })).toBeDefined()
  expect(await ui.find({ type: 'Text', text: '[通知]' })).toBeDefined()
  await ui.press({ key: 'notice:2' })
  expect((await ui.find({ type: 'Code' }))?.props.source).toBe(body.slice(0, 4_000))
  await ui.unmount()
})

test('実行内容は新しい 15 件を古い順に並べ、前の操作を追加で表示する', { options: OPTIONS }, async ($, on) => {
  const clock = mock.clock(on, { now: 30_000 })
  on('prompt.submit', (_, e) => ({ text: e.text }))
  on('turn.start', (_, e) => ({ turnId: e.turnId }))
  on('tool.call', { tool: 'Bash' }, () => ({ result: { stdout: '', stderr: '', interrupted: false } }))
  await $.prompt.submit({ text: 'ファイルを読む', wait: false, origin: ORIGIN })
  await $.turn.start({ turnId: 'files', text: 'ファイルを読む' })
  for (let index = 1; index <= 17; index++) {
    await clock.advance(1000)
    await $.tool.call({ tool: 'Bash', command: 'cat file-' + index, tool_use_id: 'file-' + index })
  }
  await clock.settle()
  const ui = await $.ui.mount({ ...PANE, surface: 'desktop' })
  const requests = (await ui.findAll({ type: 'Button' })).filter(item => item.key?.startsWith('self-request:'))
  await ui.press({ key: requests[0]!.key! })
  const steps = async () => (await ui.findAll({ type: 'Button' })).filter(item => item.key?.startsWith('tool:file-'))
  expect((await steps()).map(step => step.key)).toEqual(Array.from({ length: 15 }, (_, index) => 'tool:file-' + (index + 3)))
  expect(await ui.find({ key: 'older-steps', text: '前の 2 件を表示' })).toBeDefined()
  await ui.press({ key: 'older-steps' })
  expect(await steps()).toHaveLength(17)
  for (const step of await steps()) {
    expect((await ui.find({ key: 'step-summary:' + step.key }))?.children).toMatchObject([
      { type: 'Svg', props: { alt: '読む' } }, { type: 'Button' },
    ])
  }
  await ui.press({ key: 'tool:file-1' })
  expect(await ui.find({ type: 'Code', text: 'cat file-1' })).toBeDefined()
  await ui.unmount()
})

test('詳細で推定の照合を表示し、取得した Markdown のリンクを無効にする', { options: OPTIONS }, async ($, on) => {
  const clock = mock.clock(on, { now: 30_000 })
  const value = sampleBoard(clock.now())
  const request = value.requests.find(item => item.from === 'self')!
  request.estimatedObservation = true
  value.selectedRequest = request.id
  seedBoard(on, value)
  on('state.get', { plugin: 'work-live', key: 'detail' }, async (_, e, next) => {
    const result = await next(e)
    if (!result.value || e.id !== request.id || result.value.value !== undefined) return result
    return { value: { ...result.value, value: { shape: 'detail-4', value: {
      ...emptyDetail, prompt: '[依頼](https://example.test/)',
    } } } }
  })
  const ui = await $.ui.mount({ ...PANE, surface: 'desktop' })
  expect(await ui.find({ type: 'Text', text: '（推定）' })).toBeDefined()
  expect(await ui.find({ type: 'Markdown', key: 'prompt:' + request.id, text: '依頼（\x60https://example.test/\x60）' })).toBeDefined()
  await ui.unmount()
})

test('依頼文のない Codex 起動も表示し、照合済みでも記録が未完了ならシェルの異常終了を反映する',
  { options: OPTIONS }, async ($, on) => {
    const clock = mock.clock(on, { now: 30_000 })
    on('ui.open', () => ({ value: { isPlaced: true } }))
    on('state.get', { plugin: 'work-live', key: 'board' }, async (_, e, next) => {
      const result = await next(e)
      const stored = result.value?.value as { shape: string; value: LiveBoard } | undefined
      if (!result.value || stored?.shape !== 'board-5') return result
      const requests = stored.value.requests.map(request => request.id === 'request:unknown-prompt'
        ? { ...request, observation: 'codex-turn:linked:one' } : request)
      return { value: { ...result.value, value: { ...stored, value: { ...stored.value, requests } } } }
    })
    const failure = { result: { stdout: '', stderr: 'Codex launch failed', interrupted: false, exitCode: 5 } }
    on('tool.call', { tool: 'Bash' }, () => failure)
    expect(await $.tool.call({
      tool: 'Bash', command: 'codex exec -C C:/work -', tool_use_id: 'unknown-prompt',
    })).toEqual(failure)
    await clock.settle()
    const ui = await $.ui.mount({ ...PANE, surface: 'desktop' })
    await ui.press({ key: 'request:unknown-prompt' })
    expect(await ui.find({ type: 'Text', text: '依頼文を取得できませんでした' })).toBeDefined()
    expect(await ui.find({ key: 'tool:unknown-prompt', text: '終了コード 5' })).toBeDefined()
    expect(await ui.find({ type: 'Text', text: 'エラーあり' })).toBeDefined()
    await ui.unmount()
  },
)

test('監視の処理が遅れても委任を出した時点のターンを親として記録する', { options: OPTIONS }, async ($, on) => {
  const clock = mock.clock(on, { now: 30_000 })
  on('prompt.submit', (_, e) => ({ text: e.text }))
  on('turn.start', (_, e) => ({ turnId: e.turnId }))
  on('turn.complete', (_, e) => ({ text: e.answer }))
  on('ui.open', () => ({ value: { isPlaced: true } }))
  on('tool.call', { tool: 'Bash' }, () => ({ result: {} }))
  const captured = captureState(on)
  const probe = async () => {
    const { requests, selfTurn } = captured.board!
    const parent = requests.find(request => request.summary === '実際の親')
    const child = requests.find(request => request.id === 'request:child')
    expect(parent).toBeDefined()
    expect(child?.parentId).toBe(parent?.id)
    expect(child?.parentId).not.toBe(selfTurn?.request)
    return { text: '確認完了' }
  }
  await $.prompt.submit({ text: '実際の親', origin: ORIGIN, wait: false })
  await $.turn.start({ turnId: 'parent', text: '実際の親' })
  await $.tool.call({ tool: 'Bash', command: 'codex exec -C C:/work "子の依頼"', tool_use_id: 'child' })
  await $.turn.complete({ turnId: 'parent', reason: 'answer', isAborted: false, durationMs: 0, answer: '結果' })
  await $.prompt.submit({ text: 'あとから来た依頼', origin: ORIGIN, wait: false })
  await $.turn.start({ turnId: 'later', text: 'あとから来た依頼' })
  await clock.settle()
  expect((await probe()).text).toBe('確認完了')
})

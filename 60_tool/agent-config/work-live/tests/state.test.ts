import { expect, mock, test } from 'claude-code/testing'
import type { On } from 'claude-code'
import type { IconName, LiveBoard, RequestDetail } from '../types'
import { emptyBoard, emptyDetail, SELF, stepOf, USER } from '../hooks/model'
import { createRequest, editRequest, readBoard, readDetail } from '../hooks/state'
import type { Draft } from '../hooks/state'

test('依頼の内訳と最新 step は追加と同じ ID の更新に追従する', async () => {
  const changedDetails = new Map<string, RequestDetail>()
  const draft: Draft = {
    value: { ...emptyBoard, actors: [], requests: [] }, changedDetails, opened: new Set(),
    detail: async id => changedDetails.get(id) ?? emptyDetail,
  }
  const request = createRequest(draft, 'summary', USER, SELF, '作業を依頼', '表示改善', 0)
  const icons: IconName[] = ['file', 'folder', 'search', 'edit', 'terminal', 'git', 'run', 'web', 'agent', 'delegate', 'error', 'bell']
  const steps = icons.map((icon, index) => stepOf(String(index), index + 1, {
    kind: 'tool', icon, title: '読'.repeat(50), full: String(index),
  }))
  await editRequest(draft, request, { steps: [...steps].reverse() })
  expect(request.counts).toEqual({ read: 2, search: 1, edit: 1, command: 3, web: 1, agent: 1, delegate: 1, error: 1 })
  expect(request.lastStep).toEqual({ at: 12, icon: 'bell', title: '読'.repeat(40) })
  expect(request.result).toBeUndefined()

  const failed = stepOf('4', 14, { kind: 'error', title: 'コマンド失敗', full: '失敗の詳細' })
  await editRequest(draft, request, { steps: [failed, failed] })
  expect(request.stepCount).toBe(12)
  expect(request.counts).toMatchObject({ command: 2, error: 2 })
  expect(request.lastStep).toEqual({ at: 14, icon: 'error', title: 'コマンド失敗' })

  const report = stepOf('final', 15, { kind: 'message', title: '実装を完了しました', full: '実装を完了しました\n' + '検'.repeat(80) })
  await editRequest(draft, request, { steps: [report] })
  expect(request.result).toBeUndefined()
  await editRequest(draft, request, { status: 'done', endedAt: 15 })
  expect(request.result).toBe(('実装を完了しました ' + '検'.repeat(80)).slice(0, 60))
  expect((await draft.detail(request.id)).steps.at(-1)?.full).toBe(report.full)

  const notice = stepOf('notice', 16, { kind: 'tool', icon: 'bell', title: '完了通知', full: '別作業の通知' })
  await editRequest(draft, request, { steps: [notice] })
  expect(request.result).toBe(('実装を完了しました ' + '検'.repeat(80)).slice(0, 60))
  expect(request.lastStep?.icon).toBe('bell')

  const explicit = createRequest(draft, 'direct', USER, SELF, '作業を依頼', '別の依頼', 17)
  await editRequest(draft, explicit, { status: 'done', endedAt: 18, result: '修正しました\n確認済みです' })
  expect(explicit.result).toBe('修正しました 確認済みです')
})

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

test('状態確定で新しい 30 件を残し、除外した依頼の詳細を空にする', async ($, on) => {
  const clock = mock.clock(on, { now: 1 })
  on('prompt.submit', (_, e) => ({ text: e.text }))
  const origin = { kind: 'composer' } as const
  let removedId = ''
  const captured = captureState(on)
  const detailOf = (id: string) => [...captured.details].filter(([key]) => key.includes(id)).at(-1)?.[1]
  const probe = async (args: string) => {
    const { requests } = captured.board!
    if (args === 'initial') {
      removedId = requests[0]!.id
      expect(detailOf(removedId)?.prompt).toBe('消える依頼')
    } else {
      expect(requests.map(request => request.at)).toEqual(Array.from({ length: 30 }, (_, index) => index + 2))
      expect(detailOf(removedId)).toEqual(emptyDetail)
    }
    return { text: '確認完了' }
  }
  await $.prompt.submit({ text: '消える依頼', wait: false, origin })
  await clock.settle()
  expect((await probe('initial')).text).toBe('確認完了')

  for (let at = 2; at <= 31; at++) {
    await clock.advance(1)
    await $.prompt.submit({ text: '残す依頼 ' + at, wait: false, origin })
    await clock.settle()
  }
  expect((await probe('retained')).text).toBe('確認完了')
})

import assert from 'node:assert/strict'
import { test } from 'node:test'
import type { IconName } from '../types/index.d.ts'
import { emptyDetail, stepOf, USER } from '../src/model.ts'
import { createRequest, detailOf, editRequest, newDraft, prune } from '../src/state.ts'
import { SELF } from './fixtures/sample.ts'

test('依頼の内訳と最新 step は追加と同じ ID の更新に追従する', () => {
  const draft = newDraft()
  const request = createRequest(draft, 'summary', USER, SELF, '作業を依頼', '表示改善', 0)
  const icons: IconName[] = ['file', 'folder', 'search', 'edit', 'terminal', 'git', 'run', 'web', 'agent', 'delegate', 'error', 'bell']
  const steps = icons.map((icon, index) => stepOf(String(index), index + 1, {
    kind: 'tool', icon, title: '読'.repeat(50), full: String(index),
  }))
  editRequest(draft, request, { steps: [...steps].reverse() })
  assert.deepEqual(request.counts, { read: 2, search: 1, edit: 1, command: 3, web: 1, agent: 1, delegate: 1, error: 1 })
  assert.deepEqual(request.lastStep, { at: 12, icon: 'bell', title: '読'.repeat(40) })
  assert.equal(request.result, undefined)

  const failed = stepOf('4', 14, { kind: 'error', title: 'コマンド失敗', full: '失敗の詳細' })
  editRequest(draft, request, { steps: [failed, failed] })
  assert.equal(request.stepCount, 12)
  assert.partialDeepStrictEqual(request.counts, { command: 2, error: 2 })
  assert.deepEqual(request.lastStep, { at: 14, icon: 'error', title: 'コマンド失敗' })

  const report = stepOf('final', 15, { kind: 'message', title: '実装を完了しました', full: '実装を完了しました\n' + '検'.repeat(80) })
  editRequest(draft, request, { steps: [report] })
  assert.equal(request.result, undefined)
  editRequest(draft, request, { status: 'done', endedAt: 15 })
  assert.equal(request.result, ('実装を完了しました ' + '検'.repeat(80)).slice(0, 60))
  assert.equal(detailOf(draft, request.id).steps.at(-1)?.full, report.full)

  const notice = stepOf('notice', 16, { kind: 'tool', icon: 'bell', title: '完了通知', full: '別作業の通知' })
  editRequest(draft, request, { steps: [notice] })
  assert.equal(request.result, ('実装を完了しました ' + '検'.repeat(80)).slice(0, 60))
  assert.equal(request.lastStep?.icon, 'bell')

  const explicit = createRequest(draft, 'direct', USER, SELF, '作業を依頼', '別の依頼', 17)
  editRequest(draft, explicit, { status: 'done', endedAt: 18, result: '修正しました\n確認済みです' })
  assert.equal(explicit.result, '修正しました 確認済みです')
})

test('最新の依頼 30 件、依頼文 20,000 字、実行内容 60 件・4,000 字に収める', () => {
  const draft = newDraft()
  for (let at = 1; at <= 31; at++) {
    const id = String(at)
    const request = createRequest(draft, id, USER, SELF, '作業を依頼', '依頼', at)
    editRequest(draft, request, { prompt: '依頼'.repeat(11_000) })
  }
  const request = draft.value.requests.at(-1)!
  editRequest(draft, request, {
    steps: Array.from({ length: 61 }, (_, index) => stepOf('step:' + index, index + 31, {
      kind: 'command', title: '作業', full: '内訳'.repeat(2_100),
    })),
  })
  prune(draft, 100)
  assert.deepEqual(draft.value.requests.map(item => item.at), Array.from({ length: 30 }, (_, index) => index + 2))
  assert.deepEqual(detailOf(draft, '1'), emptyDetail)
  const detail = detailOf(draft, request.id)
  assert.equal(detail.prompt.length, 20_000)
  assert.equal(detail.steps.length, 60)
  assert.equal(detail.steps[0]!.id, 'step:1')
  assert.equal(detail.steps[0]!.full.length, 4_000)
})

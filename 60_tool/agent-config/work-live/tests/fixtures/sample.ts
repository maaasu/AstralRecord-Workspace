import type { Actor, LiveBoard, LiveRequest, Step } from '../../types/index.d.ts'
import { actor, emptyBoard, inferVerb, requestResult, stepOf, summarizeSteps, USER } from '../../src/model.ts'
import type { StepText } from '../../src/model.ts'

const minute = 60_000
export const SELF = actor('self', 'claude', 'Claude（このセッション）')

export function sampleBoard(now: number): LiveBoard {
  const review: Actor = { id: 'agent:review', kind: 'subagent', label: 'サブエージェント（構成レビュー）' }
  const codex: Actor = { id: 'codex:live', kind: 'codex', label: 'Codex（作業フローの表示改善）' }
  const guide: Actor = { id: 'claude:guide', kind: 'claude', label: 'Claude（ゲームガイドの改善）' }
  const check: Actor = { id: 'codex:guide', kind: 'codex', label: 'Codex（ガイドのリンク点検）' }
  const cloud: Actor = { id: 'claude:cloud', kind: 'claude-remote', label: 'クラウド（建築作例の調査）' }
  const liveDemo: Actor = { id: 'agent:demo', kind: 'subagent', label: 'サブエージェント（差分の確認）' }
  const doneDemo: Actor = { id: 'agent:demo-done', kind: 'subagent', label: 'サブエージェント（型の確認）' }
  const errorDemo: Actor = { id: 'agent:demo-error', kind: 'subagent', label: 'サブエージェント（参照の確認）' }
  const liveSteps: StepText[] = [
    { kind: 'read', title: 'ファイルを読む: README.md', full: 'README.md' },
    { kind: 'read', title: '一覧: hooks', full: 'rg --files hooks', icon: 'folder' },
    { kind: 'read', title: '検索: buildFlow', full: 'rg buildFlow hooks', icon: 'search' },
    { kind: 'command', title: 'git status', full: 'git status', icon: 'git' },
    { kind: 'read', title: 'ファイルを読む: pane.tsx', full: 'hooks/pane.tsx' },
    { kind: 'edit', title: '編集: flow.ts', full: 'hooks/flow.ts' },
    { kind: 'edit', title: '編集: pane.tsx', full: 'hooks/pane.tsx' },
    { kind: 'command', title: 'TypeScript の型を確認', full: 'tsc --noEmit', icon: 'run' },
    { kind: 'edit', title: '編集: flow.test.ts', full: 'tests/flow.test.ts' },
    { kind: 'command', title: 'フロー図の配置テストを実行', full: '配置とラベルを検証', icon: 'run' },
  ]

  function entry(id: string, from: Actor, to: Actor, summary: string, age: number,
    status: LiveRequest['status'], steps: Step[], endedAge?: number): LiveRequest {
    const request: LiveRequest = {
      id, from: from.id, to: to.id, summary, verb: inferVerb(summary),
      at: now - age * minute, status, endedAt: endedAge === undefined ? undefined : now - endedAge * minute,
      ...summarizeSteps(steps),
    }
    return { ...request, result: requestResult(request, steps) }
  }

  return {
    ...emptyBoard,
    actors: [USER, SELF, review, codex, guide, check, cloud, liveDemo, doneDemo, errorDemo],
    requests: [
      entry('live-root', USER, SELF, '作業ライブのフロー図を改善', 13, 'running', [
        stepOf('delegate', now - 30_000, { kind: 'tool', icon: 'delegate', title: 'Codex の型検査を確認', full: '型検査の進み具合を確認' }),
      ]),
      entry('live-review', SELF, review, '既存フロー図の構成をレビュー', 12, 'done', [
        stepOf('review-result', now - 9 * minute, { kind: 'done', title: '調査完了', full: 'ノードと矢印の重なりを整理しました' }),
      ], 9),
      entry('live-codex', SELF, codex, 'ノードとセッションタブを実装', 11, 'running',
        liveSteps.map((step, index) => stepOf('live:' + index, now - (10 - index) * minute, step))),
      entry('guide-root', USER, guide, 'ゲームガイドの改善', 29, 'done', [
        stepOf('guide-result', now - 16 * minute, { kind: 'done', title: '改善完了', full: '初心者向けガイドの導線を更新しました' }),
      ], 16),
      entry('guide-check', guide, check, 'ガイド内のリンクを確認', 22, 'error', [
        stepOf('guide-error', now - 18 * minute, { kind: 'error', title: 'リンク検査失敗', full: '参照先のガイドが見つかりません' }),
      ], 18),
      entry('cloud-root', USER, cloud, '建築作例を調査', 7, 'running', [
        stepOf('cloud-search', now - 20_000, { kind: 'web', title: '石造りの橋の作例を検索', full: '石造りの橋の事例を調査' }),
      ]),
      entry('demo-start', USER, liveDemo, '差分を確認', 5_000 / minute, 'running', [
        stepOf('demo-edit', now - 500, { kind: 'edit', title: '編集: flow.ts', full: 'hooks/flow.ts' }),
      ]),
      entry('demo-done', liveDemo, doneDemo, '型を確認', 4_000 / minute, 'done', [
        stepOf('demo-result', now - 2_000, { kind: 'done', title: '診断の確認完了', full: '型の診断は 0 件でした' }),
      ], 2_000 / minute),
      entry('demo-error', liveDemo, errorDemo, '参照を確認', 3_000 / minute, 'error', [
        stepOf('demo-failed', now - 1_000, { kind: 'error', title: '参照の確認失敗', full: '参照先が見つかりません' }),
      ], 1_000 / minute),
    ],
  }
}

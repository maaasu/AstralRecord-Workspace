import { expect, test } from 'claude-code/testing'
import type { Actor, IconName, LiveRequest } from '../types'
import { buildFlow, buildFlowTree, flowRows, groupFlows, needsTimeRefresh, cachedFlow, layoutFlow, visibleFlows } from '../hooks/flow'
import { iconBody } from '../hooks/icons'
import { shortName } from '../hooks/model'
import { sampleBoard } from './fixtures/sample'
import { parseSvg } from './fixtures/svg'

const now = 12_000_000
const actors: Actor[] = [
  { id: 'user', kind: 'user', label: 'あなた' },
  { id: 'claude', kind: 'claude', label: 'Claude（このセッション）' },
  { id: 'codex', kind: 'codex', label: 'Codex <確認&検証> "次の作業"' },
  { id: 'cloud', kind: 'claude-remote', label: 'クラウド' },
  { id: 'unknown', kind: 'unknown', label: '不明な呼び出し元（codex exec）' },
]

function request(id: string, from: string, to: string, at: number, changes: Partial<LiveRequest> = {}): LiveRequest {
  return { id, from, to, verb: '作業を依頼', summary: '概要', at, endedAt: at, status: 'done', stepCount: 1, counts: {}, ...changes }
}

test('開始以降かつ終了後 5 分以内の最新の親に付け、流れ内で通し番号を振る', () => {
  const requests = [
    request('before', 'claude', 'codex', now - 520_001),
    request('root-a', 'user', 'claude', now - 520_000, { endedAt: now - 500_000 }),
    request('root-b', 'user', 'claude', now - 500_001, { endedAt: now - 400_000 }),
    request('at-boundary', 'claude', 'codex', now - 100_000),
    request('grandchild', 'codex', 'cloud', now - 99_998),
    request('after-boundary', 'claude', 'cloud', now - 100_000 + 1),
    request('unknown-root', 'unknown', 'cloud', now - 10),
  ]
  const flows = groupFlows({ actors, requests: requests.reverse(), now })
  expect(flows.map(flow => flow.root.request.id)).toEqual(['unknown-root', 'root-b', 'after-boundary', 'root-a', 'before'])
  const root = flows.find(flow => flow.root.request.id === 'root-b')!.root
  expect(flowRows(root).map(({ node, number, depth }) => [node.request.id, number, depth]))
    .toEqual([['root-b', 1, 0], ['at-boundary', 2, 1], ['grandchild', 3, 2]])
  expect(buildFlowTree(root, now)[2]).toContain('└─2. 作業を依頼 → Codex')
})

test('同じ時刻の依頼も取得順に依存せず親子にする', () => {
  const requests = [request('child', 'claude', 'codex', now), request('root', 'user', 'claude', now)]
  const flows = groupFlows({ actors, requests, now })
  expect(flows).toHaveLength(1)
  expect(flows[0]?.root.children[0]?.request.id).toBe('child')
})

test('最後の動きから 3 分で応答なしになり、新しい step で実行中に戻る', () => {
  const root = request('root', 'user', 'claude', now - 500_000)
  const child = request('child', 'claude', 'codex', now - 400_000, {
    status: 'running', endedAt: undefined, lastStepAt: now - 180_000,
  })
  const flows = () => groupFlows({ actors, requests: [root, child], now })
  const idle = flows()[0]!
  expect([idle.status, idle.running, idle.root.children[0]?.idleMinutes]).toEqual(['idle', 0, 3])
  const { source } = buildFlow(idle.root, undefined, now)
  expect(source).toMatch(/<path id="flow-\d+-[a-z0-9]+-edge-2"[^>]+stroke-dasharray="2 3"/u)
  expect(source).toContain('<g class="card-idle">')
  expect(source).toContain('class="idle-border" stroke-dasharray="2 3"')
  expect(source).toContain('animation:idle-border 8s linear infinite')
  child.lastStepAt = now - 179_999
  expect([flows()[0]?.status, flows()[0]?.running]).toEqual(['running', 1])
  child.status = 'done'
  expect(flows()[0]?.status).toBe('done')
  child.status = 'error'
  expect(flows()[0]?.status).toBe('error')
})

test('表示範囲は流れの最後の動きと実行中の依頼を使う', () => {
  const requests = [
    request('expired', 'user', 'claude', now - 7_200_001),
    request('older', 'user', 'claude', now - 3_600_001),
    request('recent', 'user', 'claude', now - 3_600_000),
    request('live', 'user', 'codex', now - 8_000_000, { status: 'running', lastStepAt: now - 10 }),
  ]
  const flows = groupFlows({ actors, requests, now })
  expect(visibleFlows(flows, now, 'running').map(flow => flow.root.request.id)).toEqual(['live'])
  expect(visibleFlows(flows, now, 'hour').map(flow => flow.root.request.id)).toEqual(['live', 'recent'])
  expect(visibleFlows(flows, now, 'all').map(flow => flow.root.request.id)).toEqual(['live', 'recent', 'older'])
})

test('SVG の寸法、エスケープ、矢印の番号、実行中の装飾を確認する', () => {
  const root = request('work', 'user', 'codex', now - 1, {
    summary: '<確認> & "次" \'続き\'\u0000', status: 'running', endedAt: undefined,
  })
  const diagram = buildFlow(groupFlows({ actors, requests: [root], now })[0]!.root, 'codex', now)
  expect([diagram.width, diagram.height]).toEqual([608, 124])
  expect(diagram.source).toContain('width="608" height="124" viewBox="0 0 608 124"')
  expect(diagram.source).toContain('width="208" height="92"')
  expect(diagram.source).toMatch(/<circle [^>]*r="9"[^>]*\/><text [^>]*fill="#FFFFFF"[^>]*>1<\/text>/u)
  expect(diagram.source).toContain('>作業を依頼</text>')
  expect(diagram.source).toContain('>LIVE</text>')
  expect(diagram.source).not.toMatch(/[①-⑳㉑-㉟㊱-㊿●✔✖○]/u)
  expect(diagram.source).toContain('scale(1)">' + iconBody('user'))
  expect(diagram.source).toContain('scale(1)">' + iconBody('codex'))
  expect(diagram.source).toContain(iconBody('running', true))
  expect(diagram.source).toContain('&lt;確認&gt; &amp; &quot;次&quot; &apos;続き&apos;')
  expect(diagram.source).toContain('<title>Codex &lt;確認&amp;検証&gt; &quot;次の作業&quot;')
  expect(diagram.source.includes('\u0000')).toBe(false)
  expect(diagram.source).toContain('class="card-halo motion-only"')
  expect(diagram.source).toContain('class="card-sheen"')
  expect(diagram.source).toContain('class="status-dot"')
  expect(diagram.source).toContain('class="edge-dash"')
  expect(diagram.source).toContain('stroke-dasharray:6 6;animation:edge-dash 1.2s linear infinite')
  expect(diagram.source).toContain('class="edge-tip"')
  expect(diagram.source.match(/<animateMotion dur="1.8s"/gu)).toHaveLength(3)
  expect(diagram.source).toContain('begin="-0.6s"')
  expect(diagram.source).toContain('begin="-1.2s"')
  expect(diagram.source).toMatch(/<mpath href="#flow-\d+-[a-z0-9]+-edge-1"\/>/u)
  expect(Array.from(shortName('🦊'.repeat(19)))).toHaveLength(18)
  expect(shortName('🦊'.repeat(19))).toBe('🦊'.repeat(17) + '…')
})

test('5 列と 6 列は拡幅し、分岐では行を追加する', () => {
  const chainActors = [...actors, ...Array.from({ length: 5 }, (_, n): Actor =>
    ({ id: 'a' + n, kind: 'codex', label: 'Codex（' + n + '）' }))]
  const chain = Array.from({ length: 5 }, (_, n) => request('r' + n, n ? 'a' + (n - 1) : 'user', 'a' + n, now - 10 + n))
  for (const [count, width] of [[3, 1344], [4, 1712], [5, 2080]] as const) {
    const root = groupFlows({ actors: chainActors, requests: chain.slice(0, count), now })[0]!.root
    const diagram = buildFlow(root, undefined, now)
    expect([diagram.width, diagram.height]).toEqual([width, 124])
  }
  const branch = request('leaf', 'a0', 'cloud', now - 1, { status: 'error' })
  const root = groupFlows({ actors: chainActors, requests: [...chain, branch], now })[0]!.root
  const diagram = buildFlow(root, undefined, now)
  expect(diagram.height).toBe(236)
  expect(diagram.source).toMatch(/<path id="flow-\d+-[a-z0-9]+-edge-6"[^>]*stroke="#EF4444"/u)
  expect(diagram.source).toContain('scale(1)">' + iconBody('cloud'))
  expect(diagram.source).toContain(iconBody('error'))
  expect(diagram.source).toContain(iconBody('done'))
  expect(diagram.source).toContain(iconBody('waiting'))
})

type Rectangle = { x: number; y: number; width: number; height: number }

function intersects(a: Rectangle, b: Rectangle): boolean {
  return a.x < b.x + b.width && a.x + a.width > b.x && a.y < b.y + b.height && a.y + a.height > b.y
}

test('子が 1〜4 個で孫がいても親が中央にあり、カードとラベルが互いに交差しない', () => {
  const extra: Actor[] = Array.from({ length: 7 }, (_, index) =>
    ({ id: 'child:' + index, kind: index === 0 ? 'subagent' : 'codex', label: '配置確認 ' + index }))
  const names = [...actors, ...extra]
  for (let count = 1; count <= 4; count++) {
    const started = now - 20_000
    const requests = [
      request('root', 'user', 'claude', started, { endedAt: now }),
      ...extra.slice(0, count).map((actor, index) => request('child:' + index, 'claude', actor.id, started + 1 + index,
        { verb: 'クラウドで作業を依頼', summary: '長い依頼概要をどこまで表示するか確認する', endedAt: now })),
      request('grandchild:1', 'child:0', 'child:4', started + 10),
      request('grandchild:2', 'child:0', 'child:5', started + 11),
      ...(count > 2 ? [request('grandchild:3', 'child:2', 'child:6', started + 12)] : []),
    ]
    const root = groupFlows({ actors: names, requests, now })[0]!.root
    const { cards, arrows, width, height, columns, rows } = layoutFlow(root)
    expect([columns, rows, width, height]).toEqual([4, count + 1, 1344, 32 + (count + 1) * 92 + count * 20])
    const diagram = buildFlow(root, undefined, now)
    expect(diagram.source).toContain('width="' + width + '" height="' + height + '"')
    const rects = [...cards.map(card => card.bounds), ...arrows.map(arrow => arrow.label)]
    rects.forEach((rect, index) => {
      expect(rects.slice(index + 1).some(other => intersects(rect, other))).toBe(false)
    })
    for (const card of cards) {
      const childBounds = card.children.map(child => child.bounds)
      if (childBounds.length) {
        expect(card.bounds.y).toBe((childBounds[0]!.y + childBounds.at(-1)!.y) / 2)
      }
      expect(card.kindX + Array.from(card.kindText).length * 12).toBeLessThanOrEqual(card.statusX)
    }
    for (const { from, to, label, bendX, summary } of arrows) {
      expect(label.x).toBeGreaterThan(bendX + 8)
      expect(label.x + label.width).toBe(to.bounds.x - 8)
      expect(label.y + label.height).toBe(to.bounds.y + 46)
      expect(Array.from(summary).length).toBeLessThanOrEqual(12)
      expect(from.bounds.x + 208).toBeLessThan(bendX)
    }
  }
})

test('同じ親から同じ相手への依頼を一本にまとめ、3 個の番号と残りの件数を表示する', () => {
  const requests = [
    request('root', 'user', 'claude', now - 20, { endedAt: now }),
    ...Array.from({ length: 5 }, (_, index) => request('repeat:' + index, 'claude', 'codex', now - 10 + index)),
  ]
  const root = groupFlows({ actors, requests, now })[0]!.root
  expect(flowRows(root)).toHaveLength(6)
  const { arrows, cards } = layoutFlow(root)
  expect(arrows).toHaveLength(2)
  expect(cards).toHaveLength(3)
  expect(arrows.find(arrow => arrow.to.actor.id === 'codex')?.numbers).toEqual(['2', '3', '4', '+2'])
  expect(buildFlow(root, undefined, now).source).toContain('>+2</text>')
})

test('サンプルのカードに作業、結果、内訳を表示し、選択と直接の相手を強調する', () => {
  const root = groupFlows({ ...sampleBoard(now), now }).find(flow => flow.root.request.id === 'live-root')!.root
  const { source } = buildFlow(root, 'codex:live', now)
  expect(source).toMatch(/<g data-actor="codex:live"><title>[^<]*<\/title>.*?width="208"[^>]*stroke-width="3"/u)
  expect(source).toContain('<g data-actor="user" opacity="0.45">')
  expect(source).toContain('<g data-actor="agent:review" opacity="0.45">')
  expect(source).toContain('<g data-actor="self">')
  expect(source).toContain('>いま:</text>')
  expect(source).toContain('フロー図の配置テスト')
  expect(source).toContain('>結果:</text>')
  expect(source).toContain('>11 分</text>')
  expect(source).toContain(iconBody('file'))
  expect(source).toContain(iconBody('done'))
  expect(buildFlowTree(root, now).join('\n')).toContain('結果: ノードと矢印の重なりを整理しました （3 分）')
  expect(buildFlowTree(root, now).join('\n')).toContain('いま: フロー図の配置テストを実行 （11 分）')
})

test('最新作業のアイコンだけを作業種別に応じて動かす', () => {
  const motion: [IconName, RegExp][] = [
    ['edit', /<g class="step-edit"/u],
    ['terminal', /<path class="step-cursor"/u],
    ['file', /<path class="step-scan motion-only"/u],
    ['search', /<animateMotion path="M-1 0[^"]+" dur="1.6s"/u],
    ['folder', /<path class="step-lid motion-only"/u],
    ['git', /<circle class="step-dot dot-last" cx="12" cy="4.5"/u],
    ['run', /<g class="step-run"/u],
    ['web', /<path class="step-meridian meridian-next"/u],
    ['message', /<g class="motion-only" fill="currentColor"/u],
    ['agent', /<g class="step-person person-next"/u],
    ['subagent', /<g class="step-person person-next"/u],
    ['delegate', /<path class="step-delegate"/u],
    ['bell', /<g class="step-other"/u],
  ]
  for (const [icon, pattern] of motion) {
    const entry = request('working', 'user', 'codex', now - 20_000, {
      status: 'running', endedAt: undefined, counts: { edit: 1 },
      lastStep: { at: now - 10_000, icon, title: 'ソースの確認' },
    })
    const { source } = buildFlow(groupFlows({ actors, requests: [entry], now })[0]!.root, undefined, now)
    expect(source).toMatch(pattern)
    expect(source).toContain('<circle class="step-dot dot-last"')
    expect(source).toContain(iconBody('edit')) // The count icon stays static.
    const userCard = source.slice(source.indexOf('<g data-actor="user"'), source.indexOf('<g data-actor="codex"'))
    expect(userCard).not.toMatch(/class=|<animate/u)
  }
})

test('開始、最新作業、完了、失敗の演出はそれぞれの発生から 6 秒以内に描く', () => {
  const requests = [
    request('running', 'user', 'claude', now - 6_000, {
      status: 'running', endedAt: undefined, lastStepAt: now - 6_000,
      lastStep: { at: now - 6_000, icon: 'git', title: 'ブランチの確認' }, counts: { command: 2, edit: 1 },
    }),
    request('completed', 'user', 'codex', now - 90_000, { endedAt: now - 6_000 }),
    request('failed', 'user', 'cloud', now - 60_000, { endedAt: now - 6_000, status: 'error' }),
  ]
  function diagrams(at: number): string {
    return groupFlows({ actors, requests, now: at }).map(({ root }) => buildFlow(root, undefined, at).source).join('')
  }
  const fresh = diagrams(now)
  const stale = diagrams(now + 1)
  for (const pattern of [
    /class="card-enter"/u, /class="edge-draw motion-only"/u, /class="edge-badges"/u,
    /class="step-enter"/u, /class="count-flash">2<\/text>/u,
    /class="done-border motion-only"/u, /class="done-pop"/u, /keyPoints="1;0"/u,
    /class="card-shake"/u, /class="error-flash motion-only"/u,
  ]) {
    expect(fresh).toMatch(pattern)
    expect(stale).not.toMatch(pattern)
  }
  expect(fresh.match(/class="done-pop"/gu)).toHaveLength(2)
  expect(fresh.match(/class="error-flash motion-only"/gu)).toHaveLength(2)
  expect(fresh).not.toContain('class="count-flash">1</text>')
  expect(diagrams(now - 6_001)).not.toMatch(/class="(?:done-pop|card-shake)"/u)
})

test('動きの軽減を適用でき、同じ依頼の SVG は安定し、別の依頼との参照は独立する', () => {
  const entry = request('xml', 'user', 'codex', now, {
    status: 'running', endedAt: undefined, summary: '<script onload="bad()"> & \u0000',
    lastStep: { at: now, icon: 'search', title: '検索する' },
  })
  const root = groupFlows({ actors, requests: [entry], now })[0]!.root
  const allIds = new Set<string>()
  expect(buildFlow(root, undefined, now)).toEqual(buildFlow(root, undefined, now))
  for (const id of ['xml', '別の依頼']) {
    const { source } = buildFlow({ ...root, request: { ...entry, id } }, undefined, now)
    expect(source).toContain('@media (prefers-reduced-motion: reduce)')
    expect(source).toContain('animation:none!important')
    expect(source).toContain('.smil,.motion-only{display:none}.still{display:inline}')
    expect(source).not.toMatch(/<script|\son[a-z]+="/iu)
    const { elements } = parseSvg(source)
    const ids = new Set<string>()
    for (const { name, attributes } of elements) {
      expect(name).not.toBe('script')
      const id = attributes.get('id')
      if (id) {
        expect(allIds.has(id)).toBe(false)
        expect(id).toMatch(/^flow-\d+-/u)
        allIds.add(id)
        ids.add(id)
      }
    }
    expect(ids.size).toBeGreaterThan(3)
    for (const { attributes } of elements) {
      for (const [name, value] of attributes) {
        expect(name).not.toMatch(/^on/iu)
        const reference = name === 'href' ? value.slice(1) : /^url\(#(.+)\)$/u.exec(value)?.[1]
        if (reference) expect(ids.has(reference)).toBe(true)
      }
    }
  }
})

test('実行中の矢印が 6 本を超えると古いものから簡略化し、SVG の文字数上限を守る', () => {
  const children: Actor[] = Array.from({ length: 40 }, (_, index) => (
    { id: 'worker-' + index, kind: 'codex', label: '作業担当 ' + index }
  ))
  const requests = [
    request('root', 'user', 'claude', now - 10_000, { status: 'running', endedAt: undefined }),
    ...children.map((actor, index) => request(actor.id, 'claude', actor.id, now - 7_000 + index, {
      status: 'running', endedAt: undefined,
      lastStep: { at: now - 1, icon: 'edit', title: '変更を確認' }, counts: { edit: 1 },
    })),
  ]
  const root = groupFlows({ actors: [...actors, ...children], requests, now })[0]!.root
  const { source } = buildFlow(root, undefined, now)
  const { elements } = parseSvg(source)
  expect(source.length).toBeLessThanOrEqual(131_072)
  expect(elements.filter(({ name }) => name === 'animateMotion')).toHaveLength(18)
  expect(elements.filter(({ attributes }) => attributes.get('class') === 'edge-dash')).toHaveLength(41)
  expect(elements.filter(({ attributes }) => attributes.get('class') === 'step-enter')).toHaveLength(40)
  const animatedEdges = new Set(elements.filter(({ name }) => name === 'mpath')
    .map(({ attributes }) => Number(attributes.get('href')!.split('-').at(-1))))
  expect([...animatedEdges]).toEqual([36, 37, 38, 39, 40, 41])
})

test('現在のターンの明示的な親を優先し、完了した依頼の時刻更新を止める', () => {
  const root = request('actual-parent', 'user', 'self', now - 30_000)
  const competing = request('competing', 'user', 'self', now - 20_000, { endedAt: now - 15_000 })
  const child = request('explicit-child', 'self', 'codex', now - 10_000, { parentId: root.id })
  const flows = groupFlows({ actors, requests: [root, competing, child], now })
  expect(flows.find(flow => flow.root.request.id === root.id)?.root.children[0]?.request.id).toBe(child.id)
  expect(flows.find(flow => flow.root.request.id === competing.id)?.root.children).toHaveLength(0)
  expect(needsTimeRefresh([root, competing, child, { ...child, status: 'stopped' }], now)).toBe(false)
  expect(needsTimeRefresh([{ ...child, status: 'running' }], now)).toBe(true)
  expect(needsTimeRefresh([{ ...child, status: 'running' }], child.at + 180_000)).toBe(false)
  expect(cachedFlow(flows[0]!.root, undefined, now + 60_000)).toBe(cachedFlow(flows[0]!.root, undefined, now))
})

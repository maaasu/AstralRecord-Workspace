import assert from 'node:assert/strict'
import { test } from 'node:test'
import type { Actor, IconName, LiveRequest } from '../types/index.d.ts'
import { disableReplayedMotion } from '../public/app.js'
import { buildFlow, buildFlowTree, flowRows, groupFlows, needsTimeRefresh, cachedFlow, layoutFlow, visibleFlows } from '../src/flow.ts'
import { iconBody } from '../src/icons.ts'
import { shortName } from '../src/model.ts'
import { sampleBoard } from './fixtures/sample.ts'
import { parseSvg } from './fixtures/svg.ts'

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
  assert.deepEqual(flows.map(flow => flow.root.request.id), ['unknown-root', 'root-b', 'after-boundary', 'root-a', 'before'])
  const root = flows.find(flow => flow.root.request.id === 'root-b')!.root
  assert.deepEqual(flowRows(root).map(({ node, number, depth }) => [node.request.id, number, depth]), [['root-b', 1, 0], ['at-boundary', 2, 1], ['grandchild', 3, 2]])
  assert.ok((buildFlowTree(root, now)[2])?.includes('└─2. 作業を依頼 → Codex'))
})

test('同じ時刻の依頼も取得順に依存せず親子にする', () => {
  const requests = [request('child', 'claude', 'codex', now), request('root', 'user', 'claude', now)]
  const flows = groupFlows({ actors, requests, now })
  assert.equal((flows)?.length, 1)
  assert.equal(flows[0]?.root.children[0]?.request.id, 'child')
})

test('最後の動きから 3 分で応答なしになり、新しい step で実行中に戻る', () => {
  const root = request('root', 'user', 'claude', now - 500_000)
  const child = request('child', 'claude', 'codex', now - 400_000, {
    status: 'running', endedAt: undefined, lastStepAt: now - 180_000,
  })
  const flows = () => groupFlows({ actors, requests: [root, child], now })
  const idle = flows()[0]!
  assert.deepEqual([idle.status, idle.running, idle.root.children[0]?.idleMinutes], ['idle', 0, 3])
  const { source } = buildFlow(idle.root, undefined, now)
  assert.match(source, /<path id="flow-\d+-[a-z0-9]+-edge-2"[^>]+stroke-dasharray="2 3"/u)
  assert.ok((source)?.includes('<g class="card-idle">'))
  assert.ok((source)?.includes('class="idle-border" stroke-dasharray="2 3"'))
  assert.ok((source)?.includes('animation:idle-border 8s linear infinite'))
  child.lastStepAt = now - 179_999
  assert.deepEqual([flows()[0]?.status, flows()[0]?.running], ['running', 1])
  child.status = 'done'
  assert.equal(flows()[0]?.status, 'done')
  child.status = 'error'
  assert.equal(flows()[0]?.status, 'error')
})

test('表示範囲は流れの最後の動きと実行中の依頼を使う', () => {
  const requests = [
    request('expired', 'user', 'claude', now - 7_200_001),
    request('older', 'user', 'claude', now - 3_600_001),
    request('recent', 'user', 'claude', now - 3_600_000),
    request('live', 'user', 'codex', now - 8_000_000, { status: 'running', lastStepAt: now - 10 }),
  ]
  const flows = groupFlows({ actors, requests, now })
  assert.deepEqual(visibleFlows(flows, now, 'running').map(flow => flow.root.request.id), ['live'])
  assert.deepEqual(visibleFlows(flows, now, 'hour').map(flow => flow.root.request.id), ['live', 'recent'])
  assert.deepEqual(visibleFlows(flows, now, 'all').map(flow => flow.root.request.id), ['live', 'recent', 'older'])
})

test('SVG の寸法、エスケープ、矢印の番号、実行中の装飾を確認する', () => {
  const root = request('work', 'user', 'codex', now - 1, {
    summary: '<確認> & "次" \'続き\'\u0000', status: 'running', endedAt: undefined,
  })
  const diagram = buildFlow(groupFlows({ actors, requests: [root], now })[0]!.root, 'codex', now)
  assert.deepEqual([diagram.width, diagram.height], [608, 124])
  assert.ok((diagram.source)?.includes('width="608" height="124" viewBox="0 0 608 124"'))
  assert.ok((diagram.source)?.includes('width="208" height="92"'))
  assert.match(diagram.source, /<circle [^>]*r="9"[^>]*\/><text [^>]*fill="#FFFFFF"[^>]*>1<\/text>/u)
  assert.ok((diagram.source)?.includes('>作業を依頼</text>'))
  assert.ok((diagram.source)?.includes('>LIVE</text>'))
  assert.doesNotMatch(diagram.source, /[①-⑳㉑-㉟㊱-㊿●✔✖○]/u)
  assert.ok((diagram.source)?.includes('scale(1)">' + iconBody('user')))
  assert.ok((diagram.source)?.includes('scale(1)">' + iconBody('codex')))
  assert.ok((diagram.source)?.includes(iconBody('running', true)))
  assert.ok((diagram.source)?.includes('&lt;確認&gt; &amp; &quot;次&quot; &apos;続き&apos;'))
  assert.ok((diagram.source)?.includes('<title>Codex &lt;確認&amp;検証&gt; &quot;次の作業&quot;'))
  assert.equal(diagram.source.includes('\u0000'), false)
  assert.ok((diagram.source)?.includes('class="card-halo motion-only"'))
  assert.ok((diagram.source)?.includes('class="card-sheen"'))
  assert.ok((diagram.source)?.includes('class="status-dot"'))
  assert.ok((diagram.source)?.includes('class="edge-dash"'))
  assert.ok((diagram.source)?.includes('stroke-dasharray:6 6;animation:edge-dash 1.2s linear infinite'))
  assert.ok((diagram.source)?.includes('class="edge-tip"'))
  assert.equal((diagram.source.match(/<animateMotion dur="1.8s"/gu))?.length, 3)
  assert.ok((diagram.source)?.includes('begin="-0.6s"'))
  assert.ok((diagram.source)?.includes('begin="-1.2s"'))
  assert.match(diagram.source, /<mpath href="#flow-\d+-[a-z0-9]+-edge-1"\/>/u)
  assert.equal((Array.from(shortName('🦊'.repeat(19))))?.length, 18)
  assert.equal(shortName('🦊'.repeat(19)), '🦊'.repeat(17) + '…')
})

test('5 列と 6 列は拡幅し、分岐では行を追加する', () => {
  const chainActors = [...actors, ...Array.from({ length: 5 }, (_, n): Actor =>
    ({ id: 'a' + n, kind: 'codex', label: 'Codex（' + n + '）' }))]
  const chain = Array.from({ length: 5 }, (_, n) => request('r' + n, n ? 'a' + (n - 1) : 'user', 'a' + n, now - 10 + n))
  for (const [count, width] of [[3, 1344], [4, 1712], [5, 2080]] as const) {
    const root = groupFlows({ actors: chainActors, requests: chain.slice(0, count), now })[0]!.root
    const diagram = buildFlow(root, undefined, now)
    assert.deepEqual([diagram.width, diagram.height], [width, 124])
  }
  const branch = request('leaf', 'a0', 'cloud', now - 1, { status: 'error' })
  const root = groupFlows({ actors: chainActors, requests: [...chain, branch], now })[0]!.root
  const diagram = buildFlow(root, undefined, now)
  assert.equal(diagram.height, 236)
  assert.match(diagram.source, /<path id="flow-\d+-[a-z0-9]+-edge-6"[^>]*stroke="#EF4444"/u)
  assert.ok((diagram.source)?.includes('scale(1)">' + iconBody('cloud')))
  assert.ok((diagram.source)?.includes(iconBody('error')))
  assert.ok((diagram.source)?.includes(iconBody('done')))
  assert.ok((diagram.source)?.includes(iconBody('waiting')))
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
    assert.deepEqual([columns, rows, width, height], [4, count + 1, 1344, 32 + (count + 1) * 92 + count * 20])
    const diagram = buildFlow(root, undefined, now)
    assert.ok((diagram.source)?.includes('width="' + width + '" height="' + height + '"'))
    const rects = [...cards.map(card => card.bounds), ...arrows.map(arrow => arrow.label)]
    rects.forEach((rect, index) => {
      assert.equal(rects.slice(index + 1).some(other => intersects(rect, other)), false)
    })
    for (const card of cards) {
      const childBounds = card.children.map(child => child.bounds)
      if (childBounds.length) {
        assert.equal(card.bounds.y, (childBounds[0]!.y + childBounds.at(-1)!.y) / 2)
      }
      assert.ok((card.kindX + Array.from(card.kindText).length * 12) <= (card.statusX))
    }
    for (const { from, to, label, bendX, summary } of arrows) {
      assert.ok((label.x) > (bendX + 8))
      assert.equal(label.x + label.width, to.bounds.x - 8)
      assert.equal(label.y + label.height, to.bounds.y + 46)
      assert.ok((Array.from(summary).length) <= (12))
      assert.ok((from.bounds.x + 208) < (bendX))
    }
  }
})

test('同じ親から同じ相手への依頼を一本にまとめ、3 個の番号と残りの件数を表示する', () => {
  const requests = [
    request('root', 'user', 'claude', now - 20, { endedAt: now }),
    ...Array.from({ length: 5 }, (_, index) => request('repeat:' + index, 'claude', 'codex', now - 10 + index)),
  ]
  const root = groupFlows({ actors, requests, now })[0]!.root
  assert.equal((flowRows(root))?.length, 6)
  const { arrows, cards } = layoutFlow(root)
  assert.equal((arrows)?.length, 2)
  assert.equal((cards)?.length, 3)
  assert.deepEqual(arrows.find(arrow => arrow.to.actor.id === 'codex')?.numbers, ['2', '3', '4', '+2'])
  assert.ok((buildFlow(root, undefined, now).source)?.includes('>+2</text>'))
})

test('サンプルのカードに作業、結果、内訳を表示し、選択と直接の相手を強調する', () => {
  const root = groupFlows({ ...sampleBoard(now), now }).find(flow => flow.root.request.id === 'live-root')!.root
  const { source } = buildFlow(root, 'codex:live', now)
  assert.match(source, /<g data-actor="codex:live"><title>[^<]*<\/title>.*?width="208"[^>]*stroke-width="3"/u)
  assert.ok((source)?.includes('<g data-actor="user" opacity="0.45">'))
  assert.ok((source)?.includes('<g data-actor="agent:review" opacity="0.45">'))
  assert.ok((source)?.includes('<g data-actor="self">'))
  assert.ok((source)?.includes('>いま:</text>'))
  assert.ok((source)?.includes('フロー図の配置テスト'))
  assert.ok((source)?.includes('>結果:</text>'))
  assert.ok((source)?.includes('>11 分</text>'))
  assert.ok((source)?.includes(iconBody('file')))
  assert.ok((source)?.includes(iconBody('done')))
  assert.ok((buildFlowTree(root, now).join('\n'))?.includes('結果: ノードと矢印の重なりを整理しました （3 分）'))
  assert.ok((buildFlowTree(root, now).join('\n'))?.includes('いま: フロー図の配置テストを実行 （11 分）'))
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
    assert.match(source, pattern)
    assert.ok((source)?.includes('<circle class="step-dot dot-last"'))
    assert.ok((source)?.includes(iconBody('edit'))) // The count icon stays static.
    const userCard = source.slice(source.indexOf('<g data-actor="user"'), source.indexOf('<g data-actor="codex"'))
    assert.doesNotMatch(userCard, /class=|<animate/u)
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
    assert.match(fresh, pattern)
    assert.doesNotMatch(stale, pattern)
  }
  assert.equal((fresh.match(/class="done-pop"/gu))?.length, 2)
  assert.equal((fresh.match(/class="error-flash motion-only"/gu))?.length, 2)
  assert.ok(!(fresh)?.includes('class="count-flash">1</text>'))
  assert.doesNotMatch(diagrams(now - 6_001), /class="(?:done-pop|card-shake)"/u)
})

test('動きの軽減を適用でき、同じ依頼の SVG は安定し、別の依頼との参照は独立する', () => {
  const entry = request('xml', 'user', 'codex', now, {
    status: 'running', endedAt: undefined, summary: '<script onload="bad()"> & \u0000',
    lastStep: { at: now, icon: 'search', title: '検索する' },
  })
  const root = groupFlows({ actors, requests: [entry], now })[0]!.root
  const allIds = new Set<string>()
  assert.deepEqual(buildFlow(root, undefined, now), buildFlow(root, undefined, now))
  for (const id of ['xml', '別の依頼']) {
    const { source } = buildFlow({ ...root, request: { ...entry, id } }, undefined, now)
    assert.ok((source)?.includes('@media (prefers-reduced-motion: reduce)'))
    assert.ok((source)?.includes('animation:none!important'))
    assert.ok((source)?.includes('.smil,.motion-only{display:none}.still{display:inline}'))
    assert.doesNotMatch(source, /<script|\son[a-z]+="/iu)
    const { elements } = parseSvg(source)
    const ids = new Set<string>()
    for (const { name, attributes } of elements) {
      assert.notEqual(name, 'script')
      const id = attributes.get('id')
      if (id) {
        assert.equal(allIds.has(id), false)
        assert.match(id, /^flow-\d+-/u)
        allIds.add(id)
        ids.add(id)
      }
    }
    assert.ok((ids.size) > (3))
    for (const { attributes } of elements) {
      for (const [name, value] of attributes) {
        assert.doesNotMatch(name, /^on/iu)
        const reference = name === 'href' ? value.slice(1) : /^url\(#(.+)\)$/u.exec(value)?.[1]
        if (reference) assert.equal(ids.has(reference), true)
      }
    }
  }
})

test('SVG を更新しても開始・完了の演出を再生し直さず、新しい更新だけを動かす', () => {
  function effect(key: string, className: string) {
    return {
      className, removed: false,
      getAttribute: () => key,
      classList: { contains: (name: string) => name === className },
      removeAttribute() { this.className = '' },
      remove() { this.removed = true },
    }
  }
  const seen = new Map<string, number>()
  const first = [effect('start', 'card-enter'), effect('finish', 'done-pop'), effect('finish', 'smil')]
  disableReplayedMotion({ querySelectorAll: () => first }, seen, 100)
  assert.equal(first[0]!.className, 'card-enter')
  assert.equal(first[2]!.removed, false)
  const second = [effect('start', 'card-enter'), effect('finish', 'smil'), effect('new-step', 'step-enter')]
  disableReplayedMotion({ querySelectorAll: () => second }, seen, 2_100)
  assert.equal(second[0]!.className, '')
  assert.equal(second[1]!.removed, true)
  assert.equal(second[2]!.className, 'step-enter')
})

test('SVG 属性の主体 ID とテキストの名前、動詞、要約、結果をすべてエスケープする', () => {
  const unsafe = '"><script>&悪意</script>'
  const from = { ...actors[0]!, id: unsafe, label: unsafe }
  const to = { ...actors[1]!, label: unsafe }
  const entry = request(unsafe, from.id, to.id, now, { verb: unsafe, summary: unsafe, result: unsafe })
  const root = groupFlows({ actors: [from, to], requests: [entry], now })[0]!.root
  const source = buildFlow(root, undefined, now).source
  const escaped = '&quot;&gt;&lt;script&gt;&amp;悪意&lt;/script&gt;'
  assert.ok(source.includes('data-actor="' + escaped + '"'))
  assert.ok(source.includes('<title>' + escaped))
  assert.ok(source.includes(' に' + escaped + '（' + escaped + '）'))
  assert.doesNotMatch(source, /<script|\son[a-z]+="/iu)
  assert.ok(parseSvg(source).elements.every(({ name }) => name !== 'script'))
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
  assert.ok((source.length) <= (131_072))
  assert.equal((elements.filter(({ name }) => name === 'animateMotion'))?.length, 18)
  assert.equal((elements.filter(({ attributes }) => attributes.get('class') === 'edge-dash'))?.length, 41)
  assert.equal((elements.filter(({ attributes }) => attributes.get('class') === 'step-enter'))?.length, 40)
  const animatedEdges = new Set(elements.filter(({ name }) => name === 'mpath')
    .map(({ attributes }) => Number(attributes.get('href')!.split('-').at(-1))))
  assert.deepEqual([...animatedEdges], [36, 37, 38, 39, 40, 41])
})

test('現在のターンの明示的な親を優先し、完了した依頼の時刻更新を止める', () => {
  const root = request('actual-parent', 'user', 'self', now - 30_000)
  const competing = request('competing', 'user', 'self', now - 20_000, { endedAt: now - 15_000 })
  const child = request('explicit-child', 'self', 'codex', now - 10_000, { parentId: root.id })
  const flows = groupFlows({ actors, requests: [root, competing, child], now })
  assert.equal(flows.find(flow => flow.root.request.id === root.id)?.root.children[0]?.request.id, child.id)
  assert.equal((flows.find(flow => flow.root.request.id === competing.id)?.root.children)?.length, 0)
  assert.equal(needsTimeRefresh([root, competing, child, { ...child, status: 'stopped' }], now), false)
  assert.equal(needsTimeRefresh([{ ...child, status: 'running' }], now), true)
  assert.equal(needsTimeRefresh([{ ...child, status: 'running' }], child.at + 180_000), false)
  assert.equal(cachedFlow(flows[0]!.root, undefined, now + 60_000), cachedFlow(flows[0]!.root, undefined, now))
})

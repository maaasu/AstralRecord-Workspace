import { describe, expect, it } from 'vitest'
import {
  addNode, addShopPurchases, applySuggestions, canConnect, freeSlot, planSuggestions, suggestFor, connect, edgePorts, gameFlowOutline,
  gameFlowPorts, nodeKind, parseGameFlow, refKind, removeNodes, serializeGameFlow, uniqueNodeId, visibleFlow, type GameFlowContext,
} from './gameFlow'

const source = {
  schemaVersion: 1, type: 'GAME_FLOW', id: 'sample', name: 'サンプル', classes: ['class:swordsman', 'class:hunter'],
  nodes: [
    { id: 'steppe', ref: 'dungeon:eriva_windscar_steppe', x: 0, y: 0 },
    { id: 'shop', ref: 'sh:weapon_craftsman_shop', note: '交換', x: 300, y: 0 },
    { id: 'sword', ref: 'item:20a00073', classes: ['class:swordsman'], x: 600, y: 0 },
    { id: 'bow', ref: 'item:20a00077', classes: ['class:hunter'], x: 600, y: 120 },
    { id: 'memo', label: 'Lv15', x: 0, y: 200, color: 'keep' },
  ],
  edges: [
    { from: 'steppe', to: 'shop', label: 'クリア報酬' },
    { from: 'shop', to: 'sword', label: '購入' },
    { from: 'shop', to: 'bow', label: '購入' },
  ],
}

const prefixes = { dungeon: 'dungeon', shop: 'shop', sh: 'shop', item: 'item', class: 'class' }

describe('game flow', () => {
  it('round-trips and keeps unknown keys', () => {
    const flow = parseGameFlow(source)
    expect(serializeGameFlow(flow)).toEqual(source)
  })

  it('keeps legacy notes and their attached edges alongside new actions and port metadata', () => {
    const persisted = {
      ...source,
      nodes: [...source.nodes, { id: 'action', role: 'action', label: '装備する', x: 600, y: 260 }],
      edges: [...source.edges, { from: 'memo', to: 'shop' }, { from: 'bow', to: 'action', fromPort: 'bottom', toPort: 'right' }],
    }
    const flow = parseGameFlow(persisted)
    expect(nodeKind(flow.nodes[4])).toBe('note')
    expect(nodeKind(flow.nodes[5])).toBe('action')
    expect(serializeGameFlow(flow)).toEqual(persisted)
  })

  it('uses the resolved mob path to distinguish NPCs', () => {
    const node = { ref: 'm:general_goods_merchant' }
    const prefixes = { m: 'mob' }
    expect(nodeKind(node, prefixes, '40.features.mob/npc/v1.general_goods_merchant.yml')).toBe('npc')
    expect(nodeKind(node, prefixes, '40.features.mob/enemy/v1.general_goods_merchant.yml')).toBe('mob')
  })

  it.each(gameFlowPorts)('keeps %s as the chosen start and end in opposing connections after a move', port => {
    const initial = parseGameFlow(source)
    const outgoing = connect(initial, 'sword', 'steppe', undefined, [], { fromPort: port, toPort: 'top' })
    const connected = connect(outgoing, 'steppe', 'sword', undefined, [], { fromPort: 'bottom', toPort: port })
    const [start, end] = parseGameFlow(serializeGameFlow(connected)).edges.slice(-2)
    expect(edgePorts(start, { x: 4, y: 400 }, { x: 0, y: 0 })).toEqual({ fromPort: port, toPort: 'top' })
    expect(edgePorts(end, { x: 0, y: 0 }, { x: 4, y: 400 })).toEqual({ fromPort: 'bottom', toPort: port })
  })

  it.each([
    [320, 0, 'right', 'left'],
    [-320, 0, 'left', 'right'],
    [0, -180, 'top', 'bottom'],
    [0, 180, 'bottom', 'top'],
  ])('chooses opposing ports for a legacy edge to (%i, %i)', (x, y, fromPort, toPort) => {
    expect(edgePorts({}, { x: 0, y: 0 }, { x, y })).toEqual({ fromPort, toPort })
  })

  it('omits empty optional values on save', () => {
    const flow = parseGameFlow(source)
    flow.nodes[1] = { ...flow.nodes[1], note: '  ', classes: [] }
    const saved = serializeGameFlow(flow) as { nodes: Record<string, unknown>[] }
    expect(saved.nodes[1]).toEqual({ id: 'shop', ref: 'sh:weapon_craftsman_shop', x: 300, y: 0 })
  })

  it('resolves kinds through reference aliases', () => {
    expect(refKind('sh:weapon_craftsman_shop', prefixes)).toBe('shop')
    expect(refKind(undefined, prefixes)).toBe('note')
  })

  it('filters nodes and edges by class', () => {
    const flow = parseGameFlow(source)
    const hunter = visibleFlow(flow, 'class:hunter')
    expect([...hunter.nodes]).toEqual(['steppe', 'shop', 'bow', 'memo'])
    expect([...hunter.edges]).toEqual([0, 2])
    expect(visibleFlow(flow, null).edges.size).toBe(3)
  })

  it('creates unique node ids and rejects invalid connections', () => {
    const flow = parseGameFlow(source)
    expect(uniqueNodeId(flow, 'sword')).toBe('sword_2')
    expect(uniqueNodeId(flow, '20a00080')).toBe('20a00080')
    expect(canConnect(flow, 'shop', 'sword')).toBe(false)
    expect(canConnect(flow, 'shop', 'shop')).toBe(false)
    expect(connect(flow, 'sword', 'steppe', '挑戦').edges.at(-1)).toMatchObject({ from: 'sword', to: 'steppe', label: '挑戦' })
  })

  it('removes edges attached to removed nodes', () => {
    const flow = removeNodes(parseGameFlow(source), new Set(['shop']))
    expect(flow.nodes.map(node => node.id)).not.toContain('shop')
    expect(flow.edges).toEqual([])
  })

  it('adds shop purchases with class suggestions and reuses existing item nodes', () => {
    const context: GameFlowContext = { shops: [], itemClasses: { '20a00080': ['mage'] } }
    const flow = addShopPurchases(parseGameFlow(source), 'shop', ['20a00073', '20a00080'], context)
    const staff = flow.nodes.find(node => node.ref === 'item:20a00080')!
    expect(staff.classes).toEqual(['class:mage'])
    expect(staff.x).toBe(600)
    expect(flow.nodes.filter(node => node.ref === 'item:20a00073')).toHaveLength(1)
    expect(flow.edges.filter(edge => edge.from === 'shop').map(edge => edge.to)).toEqual(['sword', 'bow', staff.id])
  })

  it('reuses item nodes written with a reference alias', () => {
    const flow = parseGameFlow({ ...source, nodes: [...source.nodes, { id: 'staff', ref: 'im:20a00080', x: 0, y: 0 }] })
    const next = addShopPurchases(flow, 'shop', ['20a00080'], null, { ...prefixes, im: 'item' })
    expect(next.nodes.filter(node => node.ref?.endsWith('20a00080'))).toHaveLength(1)
    expect(next.edges.at(-1)).toMatchObject({ from: 'shop', to: 'staff' })
  })

  it('keeps scalar text values and lays out nodes without coordinates', () => {
    const flow = parseGameFlow({ id: 'x', name: 'x', nodes: [
      { id: 'a', label: 2024 }, { id: 'b', label: 'b' }, { id: 'c', label: 'c', x: 10, y: 20 },
    ], edges: [] })
    expect(flow.nodes.map(node => [node.label, node.x, node.y])).toEqual([['2024', 0, 0], ['b', 300, 0], ['c', 10, 20]])
  })

  it('adds a note node', () => {
    const { flow, id } = addNode(parseGameFlow(source), { label: '転職', x: 1, y: 2 })
    expect(id).toBe('node')
    expect(flow.nodes.at(-1)).toMatchObject({ id: 'node', label: '転職', classes: [] })
  })

  it('writes a resolved outline for one class', () => {
    const names: Record<string, string> = {
      'dungeon:eriva_windscar_steppe': '風切りの草原', 'sh:weapon_craftsman_shop': '武器職人',
      'item:20a00073': 'エリヴァソード', 'item:20a00077': 'エリヴァショートボウ', 'class:hunter': 'ハンター', 'class:swordsman': 'ソードマン',
    }
    const text = gameFlowOutline(parseGameFlow(source), ref => names[ref], { prefixes, classRef: 'class:hunter' })
    expect(text).toContain('職業フィルタ: ハンター')
    expect(text).toContain('- shop: [ショップ] 武器職人 (sh:weapon_craftsman_shop) — 交換')
    expect(text).toContain('- 武器職人 → エリヴァショートボウ（購入）')
    expect(text).not.toContain('エリヴァソード')
  })
})

describe('game flow suggestions', () => {
  const context: GameFlowContext = {
    shops: [], itemClasses: { bow: ['hunter'] },
    links: [
      { from: 'world:field', to: 'dungeon:cave', label: '入場' },
      { from: 'dungeon:cave', to: 'mob:boar', label: '出現' },
      { from: 'dungeon:cave', to: 'mob:king', label: 'ボス' },
      { from: 'mob:boar', to: 'item:pelt', label: 'ドロップ' },
      { from: 'mob:boar', to: 'item:bow', label: 'ドロップ' },
      { from: 'item:pelt', to: 'shop:weapon', label: '素材' },
    ],
  }
  const flow = parseGameFlow({ id: 'f', name: 'f', nodes: [
    { id: 'cave', ref: 'dn:cave', x: 0, y: 0 },
    { id: 'boar', ref: 'mob:boar', x: 300, y: 0 },
  ], edges: [{ from: 'cave', to: 'boar', label: '出現' }] })
  const aliases = { ...prefixes, dn: 'dungeon', mob: 'mob', world: 'world' }

  it('suggests next and previous masters and skips existing connections', () => {
    const result = suggestFor(flow, 'cave', context, aliases)
    expect(result.next.map(entry => entry.ref)).toEqual(['mob:king'])
    expect(result.previous).toEqual([{ ref: 'world:field', label: '入場', direction: 'previous', existingNodeId: undefined }])
    expect(suggestFor(flow, 'boar', context, aliases).next.map(entry => `${entry.ref} ${entry.label}`)).toEqual(['item:pelt ドロップ', 'item:bow ドロップ'])
  })

  it('places suggestions beside the node without overlap and links existing nodes', () => {
    const withBow = { ...flow, nodes: [...flow.nodes, { id: 'bow', ref: 'item:bow', classes: [], x: 900, y: 500, rest: {} }] }
    const { next } = suggestFor(withBow, 'boar', context, aliases)
    expect(next.find(entry => entry.ref === 'item:bow')?.existingNodeId).toBe('bow')
    const applied = applySuggestions(withBow, 'boar', next, context)
    const pelt = applied.nodes.find(node => node.ref === 'item:pelt')!
    expect([pelt.x, pelt.y]).toEqual([600, 0])
    expect(applied.nodes).toHaveLength(4)
    expect(applied.edges.slice(1).map(edge => `${edge.from}->${edge.to} ${edge.label}`)).toEqual(['boar->pelt ドロップ', 'boar->bow ドロップ'])
  })

  it('places previous suggestions on the left and gives items their equipment classes', () => {
    const applied = applySuggestions(flow, 'cave', suggestFor(flow, 'cave', context, aliases).previous, context, ['class:mage'])
    const node = applied.nodes.at(-1)!
    const edge = applied.edges.at(-1)!
    expect(node).toMatchObject({ ref: 'world:field', x: -300, y: 0, classes: ['class:mage'] })
    expect(edge).toMatchObject({ from: 'field', to: 'cave', label: '入場' })
    expect(edge).not.toHaveProperty('fromPort')
    expect(edge).not.toHaveProperty('toPort')
    expect(edgePorts(edge, node, applied.nodes[0])).toEqual({ fromPort: 'right', toPort: 'left' })
    const bow = applySuggestions(flow, 'boar', [{ ref: 'item:bow', label: 'ドロップ', direction: 'next' }], context, ['class:mage'])
    expect(bow.nodes.at(-1)?.classes).toEqual(['class:hunter'])
  })

  it('finds a free slot by alternating below and above', () => {
    const crowded = parseGameFlow({ id: 'f', name: 'f', nodes: [{ id: 'a', label: 'a', x: 0, y: 0 }, { id: 'b', label: 'b', x: 0, y: 110 }], edges: [] })
    expect(freeSlot(crowded, 0, 0)).toEqual({ x: 0, y: -140 })
  })
})

describe('suggestion placement plan', () => {
  const flow = parseGameFlow({ id: 'f', name: 'f', nodes: [{ id: 'sword', ref: 'item:sword', x: 0, y: 0 }], edges: [] })

  it('uses one node when the same master is suggested in both directions', () => {
    const plan = planSuggestions(flow, 'sword', [
      { ref: 'shop:weapon', label: '素材', direction: 'next' },
      { ref: 'shop:weapon', label: '購入', direction: 'previous' },
    ], null)
    expect(plan.flow.nodes.map(node => node.id)).toEqual(['sword', 'weapon'])
    expect(plan.placements.map(entry => [entry.nodeId, entry.added])).toEqual([['weapon', true], ['weapon', false]])
    expect(plan.flow.edges.map(edge => `${edge.from}->${edge.to}`)).toEqual(['sword->weapon', 'weapon->sword'])
  })

  it('places a single suggestion at its previewed position', () => {
    const placed = applySuggestions(flow, 'sword', [{ ref: 'mob:boar', label: '素材', direction: 'next', position: { x: 300, y: -110 } }], null)
    expect(placed.nodes.at(-1)).toMatchObject({ x: 300, y: -110 })
  })
})

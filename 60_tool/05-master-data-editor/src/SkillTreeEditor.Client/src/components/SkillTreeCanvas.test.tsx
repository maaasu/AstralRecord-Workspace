import { StrictMode, useCallback, useState, type ComponentProps } from 'react'
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { DockWorkspace } from '../docking/DockWorkspace'
import { DockPanelContent, useDocking } from '../docking/dockPanel'
import { routeEdge, SkillTreeCanvas } from './SkillTreeCanvas'
import type { NodeMaster, StructureDocument } from '../types/editor'
import * as nodePresentation from '../data/nodeEffectPresentation'
import * as mapLayout from '../state/skillTreeMapLayout'
import { useHistory } from '../state/history'

beforeEach(() => {
  // 地図のノードは明示した寸法で描画するため、JSDOMでも実ブラウザと同じ寸法を返す。
  for (const [property, dimension] of [['offsetWidth', 'width'], ['offsetHeight', 'height']] as const) {
    const original = Object.getOwnPropertyDescriptor(HTMLElement.prototype, property)!.get!
    vi.spyOn(HTMLElement.prototype, property, 'get').mockImplementation(function (this: HTMLElement) {
      return this.classList.contains('react-flow__node') ? parseFloat(this.style[dimension]) : original.call(this)
    })
  }
})
afterEach(() => {
  localStorage.removeItem('astralrecord.skilltree-editor.grid')
  vi.restoreAllMocks()
})

function nodeCenter(node: HTMLElement) {
  const [, left, top] = node.style.transform.match(/translate\((-?[\d.]+)px,\s*(-?[\d.]+)px\)/)!
  return {
    x: Number(left) + parseFloat(node.style.width) / 2,
    z: Number(top) + parseFloat(node.style.height) / 2,
  }
}

function dragEvent(type: string, clientX: number, clientY: number, buttons: number) {
  const event = new MouseEvent(type, { bubbles: true, cancelable: true, button: 0, buttons, clientX, clientY })
  // JSDOMのWindow変換を避け、d3-dragが参照するviewを明示する。
  Object.defineProperty(event, 'view', { value: window })
  return event
}

// d3はドラッグとして終了した場合、次のイベントループまでクリックを抑止する。
const afterDrag = () => act(() => new Promise<void>(resolve => setTimeout(resolve, 0)))

const structure: StructureDocument = {
  $schema: '../schemas/structure.v1.schema.json',
  schemaVersion: 1,
  structureId: 'main',
  name: 'Main',
  rootNodeId: '1000',
  nodes: [
    { nodeId: '1000', x: 0, y: 0, z: 0 },
    { nodeId: '1001', x: 6, y: 0, z: 0 },
  ],
  edges: [{ sourceNodeId: '1000', targetNodeId: '1001' }],
}

const masters: NodeMaster[] = [
  {
    $schema: '../schemas/node.v1.schema.json', schemaVersion: 1, nodeId: '1000', name: 'Root node',
    icon: 'NETHER_STAR', tags: ['root'], pointType: 'PP', pointCost: 0, effects: [],
  },
  {
    $schema: '../schemas/node.v1.schema.json', schemaVersion: 1, nodeId: '1001', name: 'Second node',
    icon: 'BOOK', tags: [], pointType: 'PP', pointCost: 1, effects: [],
  },
]

function CanvasWithHistory({ initial, nodeSize }: { initial: StructureDocument; nodeSize: number }) {
  const history = useHistory(initial)
  const [filtered, setFiltered] = useState(false)
  return <div style={{ width: 800, height: 600 }}>
    <button onClick={history.undo} disabled={!history.canUndo}>Undo</button>
    <button onClick={history.redo} disabled={!history.canRedo}>Redo</button>
    <button onClick={() => setFiltered(true)}>表示対象を変更</button>
    <output aria-label="確定座標">{JSON.stringify(history.present.nodes)}</output>
    <SkillTreeCanvas structure={history.present} masters={masters} nodeSize={nodeSize}
      onRecord={history.record} onReplace={history.replace} onBeginTransaction={history.beginTransaction}
      onCommitTransaction={history.commitTransaction} onSelectedNode={() => {}} onEditMaster={() => {}} onNotify={() => {}}
      visibleNodeIds={filtered ? new Set(['1000']) : null} />
  </div>
}

function canvasViewport(node: Element) {
  const viewport = node.closest<HTMLElement>('.react-flow__viewport')!
  const [, x, y, zoom] = viewport.style.transform.match(/translate\(([^,]+)px,\s*([^)]+)px\) scale\(([^)]+)\)/)!
  return { x: Number(x), y: Number(y), zoom: Number(zoom) }
}

function savedPositions() {
  return JSON.parse(screen.getByLabelText('確定座標').textContent!) as StructureDocument['nodes']
}

describe('SkillTreeCanvas', () => {
  it('複数ドラッグで1ブロックの差分を両方に適用し、Undoで元のX/Zへ戻す', async () => {
    const initial = { ...structure, nodes: [structure.nodes[0], { ...structure.nodes[1], x: 6.1 }] }
    render(<CanvasWithHistory initial={initial} nodeSize={32} />)
    const first = await screen.findByTestId('rf__node-1000')
    const second = screen.getByTestId('rf__node-1001')
    fireEvent.click(first)
    fireEvent.keyDown(window, { key: 'Control', code: 'ControlLeft', ctrlKey: true })
    fireEvent.click(second, { ctrlKey: true })
    fireEvent.keyUp(window, { key: 'Control', code: 'ControlLeft' })
    await waitFor(() => expect(second).toHaveClass('selected'))
    expect(first).toHaveClass('selected')
    const { x, y, zoom } = canvasViewport(first)
    const endX = x + mapLayout.BLOCK_SCALE * zoom
    fireEvent(first.querySelector('.skill-node')!, dragEvent('mousedown', x, y, 1))
    fireEvent(window, dragEvent('mousemove', endX, y, 1))
    fireEvent(window, dragEvent('mouseup', endX, y, 0))
    expect(savedPositions()).toEqual([{ ...initial.nodes[0], x: 1 }, { ...initial.nodes[1], x: 7.1 }])
    await afterDrag()
    fireEvent.click(screen.getByRole('button', { name: 'Undo' }))
    expect(savedPositions()).toEqual(initial.nodes)
    expect(nodeCenter(first)).toEqual({ x: 0, z: 0 })
    expect(nodeCenter(second)).toEqual({ x: 6.1 * mapLayout.BLOCK_SCALE, z: 0 })
  })

  it('密集したノードも端で接続し、中央から移動できる。接続の履歴では間隔を再計算しない', async () => {
    const measureSpacing = vi.spyOn(mapLayout, 'measureNodeSpacing')
    const measureLabels = vi.spyOn(mapLayout, 'measureLabelZoom')
    const initial = { ...structure, edges: [], nodes: [structure.nodes[0], { ...structure.nodes[1], x: 0.1 }] }
    render(<CanvasWithHistory initial={initial} nodeSize={32} />)
    const first = await screen.findByTestId('rf__node-1000')
    const second = screen.getByTestId('rf__node-1001')
    const right = first.querySelector<HTMLElement>('[data-handleid="right"]')!
    const left = second.querySelector<HTMLElement>('[data-handleid="left"]')!
    const frameSize = parseFloat(first.querySelector<HTMLElement>('.skill-node')!.style.getPropertyValue('--skill-node-size'))
    expect(parseFloat(right.style.width)).toBeCloseTo(frameSize * 0.2)
    expect(right.style.minWidth).toBe(right.style.width)
    expect(right.style.minHeight).toBe(right.style.height)
    expect(right).toHaveClass('nodrag', 'connectable')

    const hitTest = Object.getOwnPropertyDescriptor(document, 'elementFromPoint')
    Object.defineProperty(document, 'elementFromPoint', { configurable: true, value: () => left })
    const { x, y, zoom } = canvasViewport(first)
    try {
      fireEvent(right, dragEvent('mousedown', x + frameSize * zoom / 2, y, 1))
      fireEvent(document, dragEvent('mousemove', x + (3.6 - frameSize / 2) * zoom, y, 1))
      fireEvent(document, dragEvent('mouseup', x + (3.6 - frameSize / 2) * zoom, y, 0))
    } finally {
      if (hitTest) Object.defineProperty(document, 'elementFromPoint', hitTest)
      else Reflect.deleteProperty(document, 'elementFromPoint')
    }
    const edgeId = 'rf__edge-edge:1000:1001'
    expect(await screen.findByTestId(edgeId)).toBeInTheDocument()
    expect(savedPositions()).toEqual(initial.nodes)
    fireEvent.click(screen.getByRole('button', { name: 'Undo' }))
    expect(screen.queryByTestId(edgeId)).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Redo' }))
    expect(await screen.findByTestId(edgeId)).toBeInTheDocument()
    expect(measureSpacing).toHaveBeenCalledTimes(1)
    expect(measureLabels).toHaveBeenCalledTimes(1)

    const endY = y + mapLayout.BLOCK_SCALE * zoom
    fireEvent(first.querySelector('.skill-node')!, dragEvent('mousedown', x, y, 1))
    fireEvent(window, dragEvent('mousemove', x, endY, 1))
    fireEvent(window, dragEvent('mouseup', x, endY, 0))
    expect(savedPositions()).toEqual([{ ...initial.nodes[0], z: 1 }, initial.nodes[1]])
    expect(measureSpacing).toHaveBeenCalledTimes(2)
    expect(measureLabels).toHaveBeenCalledTimes(2)
    await afterDrag()
    fireEvent.click(screen.getByRole('button', { name: '表示対象を変更' }))
    expect(screen.queryByTestId('rf__node-1001')).not.toBeInTheDocument()
    expect(measureSpacing).toHaveBeenCalledTimes(3)
    expect(measureLabels).toHaveBeenCalledTimes(3)
  })

  it('reuses prepared connections across six edge-only selections in a 100-node tree', async () => {
    const sourcesRead = vi.fn((index: number) => String(2000 + index))
    const manyMasters = Array.from({ length: 100 }, (_, index) => ({ ...masters[1], nodeId: String(2000 + index) }))
    const manyNodes = { ...structure, rootNodeId: '2000', nodes: manyMasters.map((master, index) => ({ nodeId: master.nodeId, x: index % 10 * 6, y: 0, z: Math.floor(index / 10) * 6 })),
      edges: manyMasters.slice(1).map((master, index) => ({ get sourceNodeId() { return sourcesRead(index) }, targetNodeId: master.nodeId })) }
    const onRecord = vi.fn(), onReplace = vi.fn()
    render(<div style={{ width: 800, height: 600 }}><SkillTreeCanvas structure={manyNodes} masters={manyMasters}
      onRecord={onRecord} onReplace={onReplace} onSelectedNode={vi.fn()} onBeginTransaction={vi.fn()} onCommitTransaction={vi.fn()} onEditMaster={vi.fn()} onNotify={vi.fn()} /></div>)
    await screen.findByTestId('rf__edge-edge:2098:2099')
    expect(sourcesRead).toHaveBeenCalled()
    sourcesRead.mockClear()
    for (const index of [0, 17, 38, 55, 79, 98]) {
      const edge = screen.getByTestId(`rf__edge-edge:${2000 + index}:${2001 + index}`)
      const path = edge.querySelector('path')?.getAttribute('d')
      fireEvent.click(edge)
      await waitFor(() => expect(edge).toHaveClass('selected'))
      expect(edge.querySelector('path')).toHaveAttribute('d', path)
    }
    expect(screen.getByTestId('rf__edge-edge:2000:2001')).not.toHaveClass('selected')
    expect(onRecord).not.toHaveBeenCalled()
    expect(onReplace).not.toHaveBeenCalled()
    expect(sourcesRead).toHaveBeenCalledTimes(0)
  })

  it('reuses node descriptions across six selection changes in a 100-node tree', async () => {
    const effects = vi.spyOn(nodePresentation, 'describeNodeEffects')
    const tooltip = vi.spyOn(nodePresentation, 'nodeTooltip')
    const manyMasters = Array.from({ length: 100 }, (_, index) => ({ ...masters[1], nodeId: String(2000 + index), name: `ノード${index}`,
      pointType: 'CP', unlockCondition: { classId: 'class-99' }, effects: [{ type: 'skill', skillId: 'skill-99' }] }))
    const manyNodes = { ...structure, rootNodeId: '2000', nodes: manyMasters.map((master, index) => ({ nodeId: master.nodeId, x: index % 10 * 6, y: 0, z: Math.floor(index / 10) * 6 })), edges: [] }
    const skillMasters = Array.from({ length: 100 }, (_, index) => ({ id: `skill-${index}`, name: `&6スキル${index}`, type: 'ACTIVE' }))
    const classMasters = Array.from({ length: 100 }, (_, index) => ({ id: `class-${index}`, name: `&b職業${index}`, parentClassIds: [] }))
    const onRecord = vi.fn(), onReplace = vi.fn(), onSelectedNode = vi.fn()
    render(<div style={{ width: 800, height: 600 }}><SkillTreeCanvas structure={manyNodes} masters={manyMasters} skillMasters={skillMasters} classMasters={classMasters}
      onRecord={onRecord} onReplace={onReplace} onSelectedNode={onSelectedNode} onBeginTransaction={vi.fn()} onCommitTransaction={vi.fn()} onEditMaster={vi.fn()} onNotify={vi.fn()} /></div>)
    expect(await screen.findByTestId('rf__node-2099')).toHaveTextContent('1 CP')
    expect(effects).toHaveBeenCalledTimes(100)
    expect(tooltip).toHaveBeenCalledTimes(100)
    effects.mockClear(); tooltip.mockClear()
    for (const index of [0, 17, 38, 55, 79, 99]) {
      const node = screen.getByTestId(`rf__node-${2000 + index}`)
      fireEvent.click(node)
      await waitFor(() => expect(node).toHaveClass('selected'))
      expect(node.querySelector('.skill-node')).toHaveAttribute('title', expect.stringContaining('スキル99'))
      expect(onSelectedNode).toHaveBeenLastCalledWith(String(2000 + index))
    }
    expect(effects).toHaveBeenCalledTimes(0)
    expect(tooltip).toHaveBeenCalledTimes(0)
    expect(screen.getByTestId('rf__node-2000')).not.toHaveClass('selected')
    expect(onRecord).not.toHaveBeenCalled()
    expect(onReplace).not.toHaveBeenCalled()
  })

  it('refreshes connection paths for positions, visibility and topology while keeping edge selection', async () => {
    let props: ComponentProps<typeof SkillTreeCanvas> = { structure, masters, onRecord: vi.fn(), onReplace: vi.fn(),
      onSelectedNode: vi.fn(), onBeginTransaction: vi.fn(), onCommitTransaction: vi.fn(), onEditMaster: vi.fn(), onNotify: vi.fn() }
    const view = render(<StrictMode><div style={{ width: 800, height: 600 }}><SkillTreeCanvas {...props} /></div></StrictMode>)
    const update = (patch: Partial<typeof props>) => {
      props = { ...props, ...patch }
      view.rerender(<StrictMode><div style={{ width: 800, height: 600 }}><SkillTreeCanvas {...props} /></div></StrictMode>)
    }
    const edge = () => screen.getByTestId('rf__edge-edge:1000:1001')
    const path = () => edge().querySelector('path')?.getAttribute('d')
    await screen.findByTestId('rf__edge-edge:1000:1001')
    const horizontalPath = path()
    expect(horizontalPath).toMatch(/^M/)
    fireEvent.click(edge())
    await waitFor(() => expect(edge()).toHaveClass('selected'))
    update({ structure: { ...structure, nodes: structure.nodes.map(placement => placement.nodeId === '1001' ? { ...placement, x: 0, z: 6 } : placement) } })
    await waitFor(() => expect(path()).not.toBe(horizontalPath))
    const verticalPath = path()
    expect(edge()).toHaveClass('selected')
    update({ visibleNodeIds: new Set(['1000']) })
    await waitFor(() => expect(screen.queryByTestId('rf__edge-edge:1000:1001')).not.toBeInTheDocument())
    update({ visibleNodeIds: null })
    await waitFor(() => expect(path()).toBe(verticalPath))
    expect(edge()).toHaveClass('selected')
    update({ structure: { ...structure, edges: [] } })
    await waitFor(() => expect(screen.queryByTestId('rf__edge-edge:1000:1001')).not.toBeInTheDocument())
    update({ structure })
    await waitFor(() => expect(path()).toBe(horizontalPath))
    update({ structure: { ...structure, nodes: [...structure.nodes, { nodeId: '1002', x: -6, y: 0, z: 0 }], edges: [{ sourceNodeId: '1000', targetNodeId: '1002' }] } })
    expect(await screen.findByTestId('rf__edge-edge:1000:1002')).toBeInTheDocument()
    expect(screen.getByTestId('rf__node-1002')).toHaveTextContent('Unknown 1002')
    expect(screen.queryByTestId('rf__edge-edge:1000:1001')).not.toBeInTheDocument()
    expect(props.onRecord).not.toHaveBeenCalled()
    expect(props.onReplace).not.toHaveBeenCalled()
  })

  it('updates connection paths during a live drag before committing the structure', async () => {
    const onReplace = vi.fn(), onCommit = vi.fn(), onBegin = vi.fn()
    render(<div style={{ width: 800, height: 600 }}><SkillTreeCanvas structure={structure} masters={masters}
      onRecord={vi.fn()} onReplace={onReplace} onSelectedNode={vi.fn()} onBeginTransaction={onBegin} onCommitTransaction={onCommit} onEditMaster={vi.fn()} onNotify={vi.fn()} /></div>)
    const edge = await screen.findByTestId('rf__edge-edge:1000:1001')
    const originalPath = edge.querySelector('path')?.getAttribute('d')
    const node = screen.getByTestId('rf__node-1001')
    const dragWindow = node.ownerDocument.defaultView!
    fireEvent(node, dragEvent('mousedown', 400, 100, 1))
    fireEvent(dragWindow, dragEvent('mousemove', 410, 110, 1))
    fireEvent(dragWindow, dragEvent('mousemove', 100, 500, 1))
    try {
      expect(onBegin).toHaveBeenCalledOnce()
      await waitFor(() => expect(screen.getByTestId('rf__edge-edge:1000:1001').querySelector('path')?.getAttribute('d')).not.toBe(originalPath))
      expect(onReplace).not.toHaveBeenCalled()
      expect(onCommit).not.toHaveBeenCalled()
    } finally {
      fireEvent(dragWindow, dragEvent('mouseup', 100, 500, 0))
    }
    await waitFor(() => expect(onCommit).toHaveBeenCalledOnce())
    expect(onReplace).toHaveBeenCalledOnce()
    expect(onReplace.mock.calls[0][0].nodes[1]).not.toEqual(structure.nodes[1])
    expect(onReplace.mock.calls[0][0].edges).toEqual(structure.edges)
  })

  it('refreshes descriptions and positions when master, catalog, structure or display inputs change', async () => {
    let props: ComponentProps<typeof SkillTreeCanvas> = {
      structure,
      masters: masters.map(master => master.nodeId === '1001' ? { ...master, pointType: 'CP', unlockCondition: { classId: 'adventurer' }, effects: [{ type: 'skill', skillId: 'flame' }] } : master),
      skillMasters: [{ id: 'flame', name: '&6火球', type: 'ACTIVE' }],
      classMasters: [{ id: 'adventurer', name: '&b冒険者', parentClassIds: [] }],
      onRecord: vi.fn(), onReplace: vi.fn(), onSelectedNode: vi.fn(), onBeginTransaction: vi.fn(), onCommitTransaction: vi.fn(), onEditMaster: vi.fn(), onNotify: vi.fn(),
    }
    const view = render(<div style={{ width: 800, height: 600 }}><SkillTreeCanvas {...props} /></div>)
    const update = (patch: Partial<typeof props>) => {
      props = { ...props, ...patch }
      view.rerender(<div style={{ width: 800, height: 600 }}><SkillTreeCanvas {...props} /></div>)
    }
    const node = () => screen.getByTestId('rf__node-1001')
    fireEvent.click(await screen.findByTestId('rf__node-1001'))
    await waitFor(() => expect(node()).toHaveClass('selected'))
    expect(node().querySelector('.skill-node')).toHaveAttribute('title', expect.stringContaining('・火球'))

    update({ skillMasters: [{ id: 'flame', name: '&6大火球', type: 'ACTIVE' }] })
    await waitFor(() => expect(node().querySelector('.skill-node')).toHaveAttribute('title', expect.stringContaining('・大火球')))
    update({ classMasters: [{ id: 'adventurer', name: '&b熟練冒険者', parentClassIds: [] }] })
    await waitFor(() => expect(node().querySelector('.skill-node')).toHaveAttribute('title', expect.stringContaining('CP[熟練冒険者] 1')))
    update({ masters: props.masters.map(master => master.nodeId === '1001' ? { ...master, name: '&b強化ノード', icon: 'DIAMOND', pointCost: 2, effects: [], tags: ['root'] } : master) })
    await waitFor(() => expect(node()).toHaveTextContent('強化ノード'))
    expect(node()).toHaveTextContent('2 CP')
    expect(node().querySelector('.skill-node')).not.toHaveAttribute('title', expect.stringContaining('大火球'))
    expect(node().querySelector('.skill-node')).toHaveAttribute('title', expect.stringContaining('ルート／根（root）'))
    expect(node()).toHaveClass('selected')

    update({ structure: { ...structure, rootNodeId: '1001', nodes: structure.nodes.map(placement => placement.nodeId === '1001' ? { ...placement, x: -6, y: 12, z: 9 } : placement) } })
    await waitFor(() => expect(nodeCenter(node())).toEqual({ x: -216, z: 324 }))
    fireEvent.mouseEnter(node())
    expect(screen.getByLabelText('ノードの要約')).toHaveTextContent('Y 12')
    expect(node().querySelector('.skill-node')).toHaveClass('root')
    expect(screen.getByTestId('rf__node-1000').querySelector('.skill-node')).not.toHaveClass('root')
    update({ structure })
    await waitFor(() => expect(nodeCenter(node())).toEqual({ x: 216, z: 0 }))
    expect(screen.getByLabelText('ノードの要約')).toHaveTextContent('Y 0')
    fireEvent.mouseLeave(node())
    expect(node().querySelector('.skill-node')).not.toHaveClass('root')

    update({ nodeSize: 96, iconRevision: 3 })
    await waitFor(() => expect(node().querySelector('.skill-node')).toHaveStyle('--skill-node-size: 96px'))
    expect(screen.getByTitle('DIAMOND').querySelector('img')).toHaveAttribute('src', '/api/minecraft-icons/DIAMOND?revision=3')
    update({ visibleNodeIds: new Set(['1000']) })
    await waitFor(() => expect(screen.queryByTestId('rf__node-1001')).not.toBeInTheDocument())
    expect(screen.queryByTestId('rf__edge-edge:1000:1001')).not.toBeInTheDocument()
    update({ visibleNodeIds: null })
    expect(await screen.findByTestId('rf__node-1001')).toHaveTextContent('強化ノード')
    update({ masters: [masters[0]] })
    await waitFor(() => expect(node()).toHaveTextContent('Unknown 1001'))
    expect(node().querySelector('.skill-node')).toHaveAttribute('title', 'Unknown 1001')
    expect(props.onRecord).not.toHaveBeenCalled()
    expect(props.onReplace).not.toHaveBeenCalled()
  })

  it('routes edges through the nearest matching handles', () => {
    const placements = new Map([
      ['1000', { x: 0, z: 0 }],
      ['1001', { x: 0, z: 6 }],
      ['1002', { x: -6, z: 0 }],
    ])

    expect(routeEdge('1000', '1001', placements)).toEqual({
      source: '1000', target: '1001', sourceHandle: 'bottom', targetHandle: 'top',
    })
    expect(routeEdge('1000', '1002', placements)).toEqual({
      source: '1002', target: '1000', sourceHandle: 'right', targetHandle: 'left',
    })
  })

  it('shows cost in the map label and the class, kind, effects and conditions in the hover summary', async () => {
    const classMasters = [{ id: 'adventurer', name: '&6冒険者', parentClassIds: [] }]
    const classMastersNodes = masters.map((master) => master.nodeId === '1001'
      ? { ...master, name: '&aABCDEFGHIJKLM', tags: ['notable'], pointType: 'CP', pointCost: 1,
          unlockCondition: { classId: 'adventurer', playerLevel: 8 },
          effects: [{ type: 'status', status: 'MAX_HEALTH', modifierType: 'FLAT', value: 10 }] }
      : master)

    render(
      <div style={{ width: 800, height: 600 }}>
        <SkillTreeCanvas
          structure={structure}
          masters={classMastersNodes}
          classMasters={classMasters}
          onRecord={vi.fn()}
          onReplace={vi.fn()}
          onBeginTransaction={vi.fn()}
          onCommitTransaction={vi.fn()}
          onSelectedNode={vi.fn()}
          onEditMaster={vi.fn()}
          onNotify={vi.fn()}
        />
      </div>,
    )

    const second = await screen.findByTestId('rf__node-1001')
    const root = screen.getByTestId('rf__node-1000')
    expect(root.querySelector('.skill-node')).toHaveAttribute('data-point-type', 'PP')
    expect(root.querySelector('.skill-node')).toHaveAttribute('data-node-kind', 'root')
    expect(second.querySelector('.skill-node')).toHaveAttribute('data-point-type', 'CP')
    expect(second.querySelector('.skill-node')).toHaveAttribute('data-node-kind', 'notable')
    expect(within(second).getByText('ABCDEFGHIJK…')).toHaveStyle({ color: '#55ff55' })
    expect(second).toHaveTextContent('1 CP')
    fireEvent.mouseEnter(second)
    const summary = screen.getByLabelText('ノードの要約')
    expect(summary).toHaveTextContent('#1001 · 冒険者 · 主要ノード')
    expect(summary).toHaveTextContent('プレイヤーLv. 8 以上')
    expect(summary).toHaveTextContent('接続 1件 · Root node')
    expect(within(summary).getByText('最大HP')).toHaveAttribute('data-stat-category', 'resource')
    expect(root).toHaveClass('tree-neighbor')
    expect(screen.getByTestId('rf__edge-edge:1000:1001')).toHaveClass('connected')
    fireEvent.mouseLeave(second)
    expect(summary).not.toBeInTheDocument()
  })

  it('renders and survives a parent selection rerender without a React Flow update loop', async () => {
    const onRecord = vi.fn()
    const onReplace = vi.fn()
    const onBeginTransaction = vi.fn()
    const onCommitTransaction = vi.fn()

    function Harness() {
      const [selected, setSelected] = useState<string | null>(null)
      const [revision, setRevision] = useState(0)
      const selectNode = useCallback((nodeId: string | null) => setSelected(nodeId), [])
      return (
        <div style={{ width: 800, height: 600 }}>
          <output data-testid="selected-node">{selected ?? 'none'}</output>
          <output data-testid="parent-revision">{revision}</output>
          <button type="button" onClick={() => setRevision((value) => value + 1)}>rerender parent</button>
          <SkillTreeCanvas
            structure={structure}
            masters={masters}
            onRecord={onRecord}
            onReplace={onReplace}
            onBeginTransaction={onBeginTransaction}
            onCommitTransaction={onCommitTransaction}
            onSelectedNode={selectNode}
            onEditMaster={vi.fn()}
            onNotify={vi.fn()}
          />
        </div>
      )
    }

    render(<StrictMode><Harness /></StrictMode>)
    expect(await screen.findByText('Root node')).toBeInTheDocument()
    expect(screen.getByTitle('NETHER_STAR').querySelector('img')).toHaveAttribute(
      'src',
      '/api/minecraft-icons/NETHER_STAR?revision=0',
    )

    fireEvent.click(screen.getByRole('button', { name: 'rerender parent' }))

    expect(screen.getByTestId('parent-revision')).toHaveTextContent('1')
    expect(screen.getByTestId('selected-node')).toHaveTextContent('none')
    expect(screen.getByText('Root node')).toBeInTheDocument()
    expect(screen.getByText('Second node')).toBeInTheDocument()
    expect(screen.getByTestId('rf__node-1000').querySelector('.skill-node')).toHaveAttribute(
      'title',
      expect.stringContaining('ルート／根（root）'),
    )
  })

  it('keeps both nodes selected after Ctrl-clicking a second node', async () => {
    render(
      <div style={{ width: 800, height: 600 }}>
        <SkillTreeCanvas
          structure={structure}
          masters={masters}
          onRecord={vi.fn()}
          onReplace={vi.fn()}
          onBeginTransaction={vi.fn()}
          onCommitTransaction={vi.fn()}
          onSelectedNode={vi.fn()}
          onEditMaster={vi.fn()}
          onNotify={vi.fn()}
        />
      </div>,
    )

    const rootNode = await screen.findByTestId('rf__node-1000')
    const secondNode = screen.getByTestId('rf__node-1001')

    fireEvent.click(rootNode)
    await waitFor(() => expect(rootNode).toHaveClass('selected'))

    fireEvent.keyDown(window, { key: 'Control', code: 'ControlLeft', ctrlKey: true })
    fireEvent.click(secondNode, { ctrlKey: true })
    fireEvent.keyUp(window, { key: 'Control', code: 'ControlLeft' })

    await waitFor(() => {
      expect(rootNode).toHaveClass('selected')
      expect(secondNode).toHaveClass('selected')
    })
  })

  it('deletes the selected edge with the Delete key', async () => {
    const onRecord = vi.fn()
    render(
      <div style={{ width: 800, height: 600 }}>
        <SkillTreeCanvas
          structure={structure}
          masters={masters}
          onRecord={onRecord}
          onReplace={vi.fn()}
          onBeginTransaction={vi.fn()}
          onCommitTransaction={vi.fn()}
          onSelectedNode={vi.fn()}
          onEditMaster={vi.fn()}
          onNotify={vi.fn()}
        />
      </div>,
    )

    const edge = await screen.findByTestId('rf__edge-edge:1000:1001')
    fireEvent.click(edge)
    await waitFor(() => expect(screen.getByTestId('rf__edge-edge:1000:1001')).toHaveClass('selected'))

    fireEvent.keyDown(edge, { key: 'Delete', code: 'Delete' })

    await waitFor(() => expect(onRecord).toHaveBeenCalledWith({
      ...structure,
      edges: [],
    }))
  })

  it('hides the docked canvas and keeps its selection when switching tabs', async () => {
    const onRecord = vi.fn()
    function Views() {
      const dock = useDocking()!
      return <>
        <DockPanelContent id="master"><p>マスター編集画面</p></DockPanelContent>
        {dock.visited.has('skilltree') && <DockPanelContent id="skilltree">
          <div style={{ width: 800, height: 600 }}>
            <SkillTreeCanvas structure={structure} masters={masters} onRecord={onRecord} onReplace={vi.fn()}
              onBeginTransaction={vi.fn()} onCommitTransaction={vi.fn()} onSelectedNode={vi.fn()}
              onEditMaster={vi.fn()} onNotify={vi.fn()} />
          </div>
        </DockPanelContent>}
        <button type="button" onClick={() => dock.show('skilltree')}>スキルツリーを開く</button>
      </>
    }
    render(<DockWorkspace><Views /></DockWorkspace>)
    fireEvent.click(screen.getByRole('button', { name: 'スキルツリーを開く' }))
    const node = await screen.findByTestId('rf__node-1001')
    fireEvent.click(node)
    await waitFor(() => expect(node).toHaveClass('selected'))
    expect(node).toHaveStyle({ visibility: 'visible' })
    const viewport = node.closest('.react-flow__viewport') as HTMLElement
    const transform = viewport.style.transform

    const masterTab = screen.getByRole('tab', { name: 'マスターデータ' })
    fireEvent.pointerDown(masterTab, { button: 0 })
    await waitFor(() => expect(screen.getByText('マスター編集画面')).toBeVisible())
    expect(node.closest('.dock-panel-slot')).toHaveAttribute('hidden')
    expect(node).toBeInTheDocument()
    await act(async () => { fireEvent.keyDown(masterTab, { key: 'Delete', code: 'Delete' }) })
    expect(masterTab).not.toBeInTheDocument()
    await waitFor(() => expect(node).toBeVisible())
    expect(screen.getByTestId('rf__node-1001')).toBe(node)
    expect(node).toHaveClass('selected')
    expect(viewport.style.transform).toBe(transform)
    expect(onRecord).not.toHaveBeenCalled()
    fireEvent.keyDown(node, { key: 'Delete', code: 'Delete' })
    await waitFor(() => expect(onRecord).toHaveBeenCalledWith({ ...structure, nodes: [structure.nodes[0]], edges: [] }))
  })

  it('opens node operations on right click and removes all connections for the node', async () => {
    const onRecord = vi.fn()
    const onEditMaster = vi.fn()
    render(
      <div style={{ width: 800, height: 600 }}>
        <SkillTreeCanvas
          structure={structure}
          masters={masters}
          onRecord={onRecord}
          onReplace={vi.fn()}
          onBeginTransaction={vi.fn()}
          onCommitTransaction={vi.fn()}
          onSelectedNode={vi.fn()}
          onEditMaster={onEditMaster}
          onNotify={vi.fn()}
        />
      </div>,
    )

    fireEvent.contextMenu(await screen.findByTestId('rf__node-1000'), { clientX: 120, clientY: 100 })

    expect(screen.getByRole('menu', { name: 'ノード #1000 の操作' })).toBeInTheDocument()
    fireEvent.click(screen.getByRole('menuitem', { name: 'マスター定義を編集' }))
    expect(onEditMaster).toHaveBeenCalledWith(masters[0])

    fireEvent.contextMenu(screen.getByTestId('rf__node-1000'), { clientX: 120, clientY: 100 })
    fireEvent.click(screen.getByRole('menuitem', { name: 'このノードの接続をすべて削除' }))
    expect(onRecord).toHaveBeenCalledWith({ ...structure, edges: [] })
  })

  describe('large structures', () => {
    // 20 × 20 の格子。起点は左上で、初期表示では周辺の数ノードしか画面に入らない。
    const largeMasters = Array.from({ length: 400 }, (_, index) => ({ ...masters[1], nodeId: String(3000 + index), name: `大規模${index}` }))
    const largeStructure: StructureDocument = {
      ...structure,
      rootNodeId: '3000',
      nodes: largeMasters.map((master, index) => ({ nodeId: master.nodeId, x: index % 20 * 6, y: 0, z: Math.floor(index / 20) * 6 })),
      edges: largeMasters.slice(1).map((master, index) => ({ sourceNodeId: String(3000 + index), targetNodeId: master.nodeId })),
    }
    const renderLarge = (patch: Partial<ComponentProps<typeof SkillTreeCanvas>> = {}) => {
      let props: ComponentProps<typeof SkillTreeCanvas> = { structure: largeStructure, masters: largeMasters, onRecord: vi.fn(), onReplace: vi.fn(),
        onSelectedNode: vi.fn(), onBeginTransaction: vi.fn(), onCommitTransaction: vi.fn(), onEditMaster: vi.fn(), onNotify: vi.fn(), ...patch }
      const view = render(<div style={{ width: 800, height: 600 }}><SkillTreeCanvas {...props} /></div>)
      return {
        props,
        update: (next: Partial<typeof props>) => {
          props = { ...props, ...next }
          view.rerender(<div style={{ width: 800, height: 600 }}><SkillTreeCanvas {...props} /></div>)
        },
      }
    }
    const renderedNodes = () => document.querySelectorAll('.react-flow__node').length

    it('opens around the root and renders only the nodes in view', async () => {
      const effects = vi.spyOn(nodePresentation, 'describeNodeEffects')
      renderLarge()
      expect(await screen.findByTestId('rf__node-3000')).toBeInTheDocument()
      await waitFor(() => expect(renderedNodes()).toBeLessThan(60))
      expect(screen.queryByTestId('rf__node-3399')).not.toBeInTheDocument()
      // 画面外のノードも説明文は用意済みで、表示したときに作り直さない。
      expect(effects).toHaveBeenCalledTimes(400)
      // 画面外へ伸びる接続も、計測前の寸法から引ける。
      expect(await screen.findByTestId('rf__edge-edge:3000:3001')).toBeInTheDocument()
    })

    it('moves the view to a requested node, selects it and reports nodes hidden by the filter', { timeout: 15000 }, async () => {
      const onSelectedNode = vi.fn(), onNotify = vi.fn()
      const { update } = renderLarge({ onSelectedNode, onNotify })
      await screen.findByTestId('rf__node-3000')
      await waitFor(() => expect(screen.queryByTestId('rf__node-3399')).not.toBeInTheDocument())

      update({ focusRequest: { nodeId: '3399' } })
      const target = await screen.findByTestId('rf__node-3399')
      await waitFor(() => expect(target).toHaveClass('selected'))
      expect(onSelectedNode).toHaveBeenLastCalledWith('3399')
      await waitFor(() => expect(screen.queryByTestId('rf__node-3000')).not.toBeInTheDocument())

      // 同じ依頼は再描画されても繰り返さない。
      fireEvent.click(screen.getByRole('button', { name: '起点へ' }))
      expect(await screen.findByTestId('rf__node-3000')).toBeInTheDocument()
      update({ iconRevision: 1 })
      expect(screen.getByTestId('rf__node-3000')).toBeInTheDocument()

      update({ visibleNodeIds: new Set(['3000']), focusRequest: { nodeId: '3399' } })
      await waitFor(() => expect(onNotify).toHaveBeenCalledWith('ノード #3399 は現在の表示条件ではキャンバスに表示されていません。'))
    })

    it('keeps unchanged nodes mounted when one placement moves', async () => {
      const { update } = renderLarge()
      const root = await screen.findByTestId('rf__node-3000')
      const neighbour = await screen.findByTestId('rf__node-3001')
      const icon = neighbour.querySelector('img')
      update({ structure: { ...largeStructure, nodes: largeStructure.nodes.map(placement => placement.nodeId === '3000' ? { ...placement, x: 1, z: 1 } : { ...placement }) } })
      await waitFor(() => expect(nodeCenter(root)).toEqual({ x: 36, z: 36 }))
      expect(screen.getByTestId('rf__node-3001')).toBe(neighbour)
      expect(neighbour.querySelector('img')).toBe(icon)
    })

    it('fits the remaining nodes when a simulation hides all nodes in the viewport', async () => {
      const { update } = renderLarge()
      await screen.findByTestId('rf__node-3000')
      expect(screen.queryByTestId('rf__node-3399')).not.toBeInTheDocument()

      update({ visibleNodeIds: new Set(['3399']) })
      await waitFor(() => expect(screen.getByRole('button', { name: '起点へ' })).toBeDisabled())
      await waitFor(() => expect(screen.getByLabelText('現在の表示倍率')).toHaveTextContent(/250%/))
      expect(screen.getByTestId('rf__node-3399')).toBeInTheDocument()
    })
  })

  it('fits distant nodes below 10% and keeps the initial selection', async () => {
    const distant = { ...structure, nodes: [structure.nodes[0], { ...structure.nodes[1], x: 300 }] }
    render(<div style={{ width: 800, height: 600 }}><SkillTreeCanvas structure={distant} masters={masters}
      selectedNodeId="1001" onRecord={vi.fn()} onReplace={vi.fn()} onSelectedNode={vi.fn()} onBeginTransaction={vi.fn()}
      onCommitTransaction={vi.fn()} onEditMaster={vi.fn()} onNotify={vi.fn()} /></div>)
    const node = await screen.findByTestId('rf__node-1001')
    expect(node).toHaveClass('selected')
    expect(screen.getByRole('button', { name: '選択へ' })).toBeEnabled()
    fireEvent.click(screen.getByRole('button', { name: '全体表示' }))
    await waitFor(() => expect(Number.parseFloat(screen.getByLabelText('現在の表示倍率').textContent)).toBeLessThan(10))
  })

  it('keeps the selected placement and its connections when deletion is declined', async () => {
    const onRecord = vi.fn().mockReturnValue(false)
    render(<div style={{ width: 800, height: 600 }}><SkillTreeCanvas structure={structure} masters={masters}
      onRecord={onRecord} onReplace={vi.fn()} onSelectedNode={vi.fn()} onBeginTransaction={vi.fn()}
      onCommitTransaction={vi.fn()} onEditMaster={vi.fn()} onNotify={vi.fn()} /></div>)
    const node = await screen.findByTestId('rf__node-1001')
    fireEvent.click(node)
    await waitFor(() => expect(node).toHaveClass('selected'))
    for (const key of ['Delete', 'Backspace']) {
      for (const option of ['ctrlKey', 'metaKey', 'altKey', 'shiftKey', 'isComposing', 'repeat']) {
        fireEvent.keyDown(node, { key, [option]: true })
        expect(onRecord).not.toHaveBeenCalled()
      }
    }
    fireEvent.keyDown(node, { key: 'Delete', code: 'Delete' })
    await waitFor(() => expect(onRecord).toHaveBeenCalledOnce())
    expect(onRecord).toHaveBeenCalledWith({ ...structure, nodes: [structure.nodes[0]], edges: [] })
    expect(node).toHaveClass('selected')
    expect(screen.getByTestId('rf__edge-edge:1000:1001')).toBeInTheDocument()
  })

  it('offers view controls and opens the master editor on double click', async () => {
    const onEditMaster = vi.fn()
    render(<div style={{ width: 800, height: 600 }}><SkillTreeCanvas structure={structure} masters={masters}
      onRecord={vi.fn()} onReplace={vi.fn()} onSelectedNode={vi.fn()} onBeginTransaction={vi.fn()} onCommitTransaction={vi.fn()} onEditMaster={onEditMaster} onNotify={vi.fn()} /></div>)
    const node = await screen.findByTestId('rf__node-1001')
    expect(screen.getByRole('button', { name: '選択へ' })).toBeDisabled()
    for (const name of ['縮小', '拡大', '起点へ', '全体表示']) expect(screen.getByRole('button', { name })).toBeEnabled()
    expect(screen.getByLabelText('現在の表示倍率')).toHaveTextContent(/^\d+%$/)

    fireEvent.click(node)
    await waitFor(() => expect(screen.getByRole('button', { name: '選択へ' })).toBeEnabled())
    // 選択したノードにつながる接続を強調する。
    expect(screen.getByTestId('rf__edge-edge:1000:1001')).toHaveClass('connected')
    fireEvent.doubleClick(node)
    expect(onEditMaster).toHaveBeenCalledWith(masters[1])
  })

  it('applies the selected visual node size', async () => {
    render(
      <div style={{ width: 800, height: 600 }}>
        <SkillTreeCanvas
          structure={structure}
          masters={masters}
          nodeSize={40}
          onRecord={vi.fn()}
          onReplace={vi.fn()}
          onBeginTransaction={vi.fn()}
          onCommitTransaction={vi.fn()}
          onSelectedNode={vi.fn()}
          onEditMaster={vi.fn()}
          onNotify={vi.fn()}
        />
      </div>,
    )

    const rootNode = await screen.findByTestId('rf__node-1000')
    expect(rootNode.querySelector('.skill-node')).toHaveStyle('--skill-node-size: 40px')
  })

  it('keeps the block grid preference and reports pointer coordinates relative to the map origin', async () => {
    const props = { structure, masters, onRecord: vi.fn(), onReplace: vi.fn(), onSelectedNode: vi.fn(),
      onBeginTransaction: vi.fn(), onCommitTransaction: vi.fn(), onEditMaster: vi.fn(), onNotify: vi.fn() }
    const view = render(<SkillTreeCanvas {...props} />)
    const canvas = await screen.findByTestId('rf__wrapper')
    const grid = () => view.container.querySelector('.tree-map-grid')
    const toggle = () => screen.getByRole('button', { name: 'グリッド' })
    const coordinates = screen.getByLabelText('ポインターのブロック座標')
    expect(toggle()).toHaveAttribute('aria-pressed', 'true')
    expect(grid()).toBeInTheDocument()
    const transform = canvas.querySelector<HTMLElement>('.react-flow__viewport')!.style.transform
    const [, x, y, zoom] = transform.match(/translate\((-?[\d.]+)px,\s*(-?[\d.]+)px\) scale\(([\d.]+)\)/)!
    fireEvent.pointerMove(canvas, { clientX: Number(x) + 12.3 * 36 * Number(zoom), clientY: Number(y) - 4.5 * 36 * Number(zoom) })
    expect(coordinates).toHaveTextContent('X 12.3 / Z -4.5')
    expect(grid()).toHaveStyle('--tree-axis-x: ' + x + 'px')
    fireEvent.click(toggle())
    expect(toggle()).toHaveAttribute('aria-pressed', 'false')
    expect(grid()).not.toBeInTheDocument()
    view.unmount()
    render(<SkillTreeCanvas {...props} />)
    expect(toggle()).toHaveAttribute('aria-pressed', 'false')
  })

  it('marks gateways, keystones and search matches without hiding non-matching placements', async () => {
    const additional = { ...masters[1], nodeId: '1002', tags: ['keystone'] }
    const view = render(<SkillTreeCanvas
      structure={{ ...structure, nodes: [...structure.nodes, { nodeId: '1002', x: 12, y: 0, z: 0 }] }}
      masters={[masters[0], { ...masters[1], tags: ['gateway'] }, additional]}
      matchingNodeIds={new Set(['1002'])} onRecord={vi.fn()} onReplace={vi.fn()} onSelectedNode={vi.fn()}
      onBeginTransaction={vi.fn()} onCommitTransaction={vi.fn()} onEditMaster={vi.fn()} onNotify={vi.fn()} />)
    const gateway = await screen.findByTestId('rf__node-1001')
    const keystone = screen.getByTestId('rf__node-1002')
    expect(gateway).toHaveClass('tree-dim')
    expect(gateway.querySelector('.skill-node')).toHaveAttribute('data-node-kind', 'gateway')
    expect(keystone).toHaveClass('tree-match')
    expect(keystone.querySelector('.skill-node')).toHaveAttribute('data-node-kind', 'keystone')
    expect(keystone.querySelector('.skill-node')).toHaveAttribute('data-keystone', 'true')
    expect(view.container.querySelector('.tree-mini-dot[data-node-kind="keystone"]')).toBeInTheDocument()
  })
})

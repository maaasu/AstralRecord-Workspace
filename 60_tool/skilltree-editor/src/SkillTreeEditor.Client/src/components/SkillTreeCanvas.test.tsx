import { StrictMode, useCallback, useState, type ComponentProps } from 'react'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { routeEdge, SkillTreeCanvas } from './SkillTreeCanvas'
import type { NodeMaster, StructureDocument } from '../types/editor'
import * as nodePresentation from '../data/nodeEffectPresentation'

afterEach(() => vi.restoreAllMocks())

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

describe('SkillTreeCanvas', () => {
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
    expect(await screen.findByTestId('rf__node-2099')).toHaveTextContent('CP[職業99] 1')
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
    await waitFor(() => expect(node()).toHaveTextContent('CP[熟練冒険者] 1'))
    update({ masters: props.masters.map(master => master.nodeId === '1001' ? { ...master, name: '&b強化ノード', icon: 'DIAMOND', pointCost: 2, effects: [], tags: ['root'] } : master) })
    await waitFor(() => expect(node()).toHaveTextContent('強化ノード'))
    expect(node()).toHaveTextContent('CP[熟練冒険者] 2')
    expect(node().querySelector('.skill-node')).not.toHaveAttribute('title', expect.stringContaining('大火球'))
    expect(node().querySelector('.skill-node')).toHaveAttribute('title', expect.stringContaining('ルート／根（root）'))
    expect(node()).toHaveClass('selected')

    update({ structure: { ...structure, rootNodeId: '1001', nodes: structure.nodes.map(placement => placement.nodeId === '1001' ? { ...placement, x: -6, y: 12, z: 9 } : placement) } })
    await waitFor(() => expect(node().style.transform).toMatch(/translate\(-216px,\s*324px\)/))
    expect(node()).toHaveTextContent('Y 12')
    expect(node().querySelector('.skill-node')).toHaveClass('root')
    expect(screen.getByTestId('rf__node-1000').querySelector('.skill-node')).not.toHaveClass('root')
    update({ structure })
    await waitFor(() => expect(node().style.transform).toMatch(/translate\(216px,\s*0px\)/))
    expect(node()).toHaveTextContent('Y 0')
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

  it('shows the unlock class in the canvas cost label', async () => {
    const classMasters = [{ id: 'adventurer', name: '&6冒険者', parentClassIds: [] }]
    const classMastersNodes = masters.map((master) => master.nodeId === '1001'
      ? { ...master, pointType: 'CP', pointCost: 1, unlockCondition: { classId: 'adventurer' } }
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

    expect(await screen.findByTestId('rf__node-1001')).toHaveTextContent('CP[冒険者] 1')
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

    fireEvent.keyDown(document, { key: 'Delete', code: 'Delete' })
    fireEvent.keyUp(document, { key: 'Delete', code: 'Delete' })

    await waitFor(() => expect(onRecord).toHaveBeenCalledWith({
      ...structure,
      edges: [],
    }))
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
})

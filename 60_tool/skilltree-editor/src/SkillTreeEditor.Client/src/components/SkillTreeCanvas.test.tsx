import { StrictMode, useCallback, useState } from 'react'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { SkillTreeCanvas } from './SkillTreeCanvas'
import type { NodeMaster, StructureDocument } from '../types/editor'

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
    icon: 'NETHER_STAR', lore: [], tags: ['root'], pointType: 'PP', pointCost: 0, effects: [],
  },
  {
    $schema: '../schemas/node.v1.schema.json', schemaVersion: 1, nodeId: '1001', name: 'Second node',
    icon: 'BOOK', lore: [], tags: [], pointType: 'PP', pointCost: 1, effects: [],
  },
]

describe('SkillTreeCanvas', () => {
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
          />
        </div>
      )
    }

    render(<StrictMode><Harness /></StrictMode>)
    expect(await screen.findByText('Root node')).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'rerender parent' }))

    expect(screen.getByTestId('parent-revision')).toHaveTextContent('1')
    expect(screen.getByTestId('selected-node')).toHaveTextContent('none')
    expect(screen.getByText('Root node')).toBeInTheDocument()
    expect(screen.getByText('Second node')).toBeInTheDocument()
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
        />
      </div>,
    )

    const edge = await screen.findByTestId('rf__edge-edge:1000:1001')
    fireEvent.click(edge)
    await waitFor(() => expect(edge).toHaveClass('selected'))

    fireEvent.keyDown(document, { key: 'Delete', code: 'Delete' })
    fireEvent.keyUp(document, { key: 'Delete', code: 'Delete' })

    await waitFor(() => expect(onRecord).toHaveBeenCalledWith({
      ...structure,
      edges: [],
    }))
  })
})

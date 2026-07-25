import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import type { NodeMaster, StructureDocument } from '../types/editor'
import { PlacementInspector } from './PlacementInspector'

const master: NodeMaster = {
  $schema: '../schemas/node.v1.schema.json',
  schemaVersion: 1,
  nodeId: '1012',
  name: '&d旅立ちの記録',
  icon: 'NETHER_STAR',
  lore: ['最初の一歩'],
  tags: ['root'],
  pointType: 'PP',
  pointCost: 1,
  effects: [{ type: 'skill', skillId: 'starter' }],
}

const structure: StructureDocument = {
  $schema: '../schemas/structure.v1.schema.json',
  schemaVersion: 1,
  structureId: 'starter',
  name: 'Starter',
  rootNodeId: '1012',
  nodes: [{ nodeId: '1012', x: 0, y: 64, z: 0 }],
  edges: [],
}

describe('PlacementInspector', () => {
  it('edits selected placement coordinates and master fields inline', async () => {
    const onChange = vi.fn()
    const onSaveMaster = vi.fn(async (node: NodeMaster) => node)

    render(
      <PlacementInspector
        nodeId="1012"
        structure={structure}
        master={master}
        saving={false}
        iconRevision={0}
        onChange={onChange}
        onSaveMaster={onSaveMaster}
        onEditMaster={vi.fn()}
        onRetryIcons={vi.fn()}
      />,
    )

    expect(screen.getByRole('heading', { name: '旅立ちの記録' })).toBeInTheDocument()
    fireEvent.change(screen.getByLabelText('X'), { target: { value: '8' } })
    expect(onChange).toHaveBeenCalledWith({
      ...structure,
      nodes: [{ nodeId: '1012', x: 8, y: 64, z: 0 }],
    })

    fireEvent.change(screen.getByLabelText('名前'), { target: { value: '&b新しい名前' } })
    fireEvent.change(screen.getByLabelText('Minecraft Material'), { target: { value: 'diamond_sword' } })
    fireEvent.click(screen.getByRole('button', { name: 'マスター定義を保存' }))

    await waitFor(() => expect(onSaveMaster).toHaveBeenCalledWith({
      ...master,
      name: '&b新しい名前',
      icon: 'DIAMOND_SWORD',
    }))
  })
})

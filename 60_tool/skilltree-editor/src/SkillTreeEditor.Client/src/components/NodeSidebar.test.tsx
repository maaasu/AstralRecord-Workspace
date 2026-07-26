import { render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import type { NodeMaster } from '../types/editor'
import { NodeSidebar } from './NodeSidebar'

const node: NodeMaster = {
  $schema: '../schemas/node.v1.schema.json',
  schemaVersion: 1,
  nodeId: '1000',
  name: '始まりのノード',
  icon: 'NETHER_STAR',
  lore: [],
  tags: ['root'],
  pointType: 'PP',
  pointCost: 0,
  effects: [],
}

describe('NodeSidebar', () => {
  it('shows and searches shared tags by their Japanese definitions', () => {
    const props = {
      nodes: [node],
      placedIds: new Set<string>(),
      selectedTag: '',
      onQueryChange: vi.fn(),
      onTagChange: vi.fn(),
      onEdit: vi.fn(),
      onCreate: vi.fn(),
      skillMasters: [],
    }
    const { rerender } = render(<NodeSidebar {...props} query="" />)

    expect(screen.getByText('ルート／根')).toHaveAttribute('title', expect.stringContaining('root'))
    expect(screen.getByRole('option', { name: 'ルート／根（root）' })).toHaveValue('root')

    rerender(<NodeSidebar {...props} query="スキルツリーの開始点" />)
    expect(screen.getByText('始まりのノード')).toBeInTheDocument()
  })
})

import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { editorApi } from '../api/editorApi'
import type { NodeMaster, StructureDocument } from '../types/editor'
import { BLOCK_SCALE } from '../state/skillTreeMapLayout'
import { SkillTreeWorkspace } from './SkillTreeWorkspace'

const common: NodeMaster = { $schema: '', schemaVersion: 1, nodeId: '1000', name: '旅の始まり',
  icon: 'APPLE', tags: ['root'], pointType: 'PP', pointCost: 1, effects: [] }
const overlaid: NodeMaster = { ...common, nodeId: '1001', name: '旅の展望台' }
const distant: NodeMaster = { ...common, nodeId: '1002', name: '魔法の道標', pointType: 'CP', tags: ['gateway'],
  unlockCondition: { classId: 'mage' } }
const structure: StructureDocument = { $schema: '', schemaVersion: 1, structureId: 'main', name: '旅の地図', rootNodeId: common.nodeId,
  nodes: [
    { nodeId: common.nodeId, x: 0, y: 0, z: 0 },
    { nodeId: overlaid.nodeId, x: 0, y: 4, z: 0 },
    { nodeId: distant.nodeId, x: 120, y: 0, z: -20 },
  ],
  edges: [
    { sourceNodeId: common.nodeId, targetNodeId: overlaid.nodeId },
    { sourceNodeId: common.nodeId, targetNodeId: distant.nodeId },
  ],
}

beforeEach(() => {
  vi.spyOn(editorApi, 'listNodes').mockResolvedValue([common, overlaid, distant].map(content => ({
    content, revision: 'node-version', fileName: `${content.nodeId}.json`,
  })))
  vi.spyOn(editorApi, 'listStructures').mockResolvedValue([{ content: structure, revision: 'tree-version', fileName: 'main.json' }])
  vi.spyOn(editorApi, 'listSchemas').mockResolvedValue([])
  vi.spyOn(editorApi, 'listClasses').mockResolvedValue([
    { id: 'adventurer', name: '冒険者', parentClassIds: [] },
    { id: 'mage', name: '魔術師', parentClassIds: ['adventurer'] },
  ])
  vi.spyOn(editorApi, 'listSkills').mockResolvedValue([])
  vi.spyOn(editorApi, 'metadata').mockResolvedValue({} as Awaited<ReturnType<typeof editorApi.metadata>>)
  vi.spyOn(editorApi, 'getSettings').mockResolvedValue({ revision: 'settings-version', content: {
    structureId: 'main', worldName: 'world', centerX: 0, centerY: 0, centerZ: 0,
  } })
  for (const [property, dimension] of [['offsetWidth', 'width'], ['offsetHeight', 'height']] as const) {
    const original = Object.getOwnPropertyDescriptor(HTMLElement.prototype, property)!.get!
    vi.spyOn(HTMLElement.prototype, property, 'get').mockImplementation(function (this: HTMLElement) {
      return this.classList.contains('react-flow__node') ? parseFloat(this.style[dimension]) : original.call(this)
    })
  }
})

afterEach(() => {
  localStorage.removeItem('astralrecord.skilltree-editor.node-size')
  vi.restoreAllMocks()
})

const canvasNode = (node: NodeMaster) => screen.getByTestId(`rf__node-${node.nodeId}`)
const detail = () => within(screen.getByRole('region', { name: 'ノード詳細' }))

describe('スキルツリーの一覧・地図・詳細の連携', () => {
  it('重なりのバッジと相手の名前を表示し、一覧と接続先のチップから高さ違いのノードを選べる', async () => {
    render(<SkillTreeWorkspace active onDirtyChange={vi.fn()} />)
    fireEvent.click(await screen.findByRole('button', { name: '旅の展望台をキャンバスで表示' }))
    await waitFor(() => expect(canvasNode(overlaid)).toHaveClass('selected'))
    expect(detail().getByLabelText('Y')).toHaveValue('4')
    for (const node of [common, overlaid]) {
      expect(within(canvasNode(node)).getByText('重なり 2')).toBeInTheDocument()
    }
    fireEvent.mouseEnter(canvasNode(overlaid))
    const summary = within(await screen.findByLabelText('ノードの要約'))
    expect(summary.getByText('旅の始まり').closest('p')).toHaveTextContent('重なり 2 · 旅の始まり')
    fireEvent.click(detail().getByRole('button', { name: '旅の始まり 1 PP' }))
    await waitFor(() => expect(canvasNode(common)).toHaveClass('selected'))
    expect(canvasNode(overlaid)).not.toHaveClass('selected')
    expect(detail().getByLabelText('Y')).toHaveValue('0')
  })

  it('地図を空白へパンした後、シミュレーションの範囲外にある接続先を中央へ表示する', async () => {
    const { container } = render(<SkillTreeWorkspace active onDirtyChange={vi.fn()} />)
    await screen.findByTestId('rf__node-1000')
    const simulation = screen.getByLabelText('ゲーム内表示シミュレーション')
    fireEvent.click(simulation)
    expect(simulation).toBeChecked()
    expect(screen.queryByTestId('rf__node-1002')).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '旅の始まりをキャンバスで表示' }))
    const viewport = container.querySelector<HTMLElement>('.react-flow__viewport')!
    const transform = () => {
      const [, x, y, zoom] = viewport.style.transform.match(/translate\(([^,]+)px,\s*([^)]+)px\) scale\(([^)]+)\)/)!
      return { x: Number(x), y: Number(y), zoom: Number(zoom) }
    }
    await waitFor(() => expect(transform().x).toBeCloseTo(400))
    const panEvent = (type: string, clientX: number, buttons: number) => {
      const event = new MouseEvent(type, { bubbles: true, cancelable: true, clientX, clientY: 300, button: 0, buttons })
      Object.defineProperty(event, 'view', { value: window })
      return event
    }
    fireEvent(container.querySelector('.react-flow__pane')!, panEvent('mousedown', 700, 1))
    fireEvent(window, panEvent('mousemove', 100, 1))
    fireEvent(window, panEvent('mouseup', 100, 0))
    await waitFor(() => expect(transform().x).toBeLessThan(-100))
    fireEvent.click(detail().getByRole('button', { name: '魔法の道標 1 CP' }))
    expect(simulation).not.toBeChecked()
    await waitFor(() => {
      expect(canvasNode(distant)).toHaveClass('selected')
      const { x, y, zoom } = transform()
      expect(x + 120 * BLOCK_SCALE * zoom).toBeCloseTo(400)
      expect(y - 20 * BLOCK_SCALE * zoom).toBeCloseTo(300)
    })
  })

  it('左一覧を閉じている間に起点が変わっても、検索・タグ・種類の一致を地図へ反映する', async () => {
    render(<SkillTreeWorkspace active onDirtyChange={vi.fn()} />)
    await screen.findByTestId('rf__node-1001')
    fireEvent.change(screen.getByRole('searchbox', { name: 'ノードを検索' }), { target: { value: '旅' } })
    fireEvent.change(screen.getByRole('combobox', { name: 'ノードのタグ' }), { target: { value: 'root' } })
    fireEvent.change(screen.getByRole('combobox', { name: 'ノードの種類' }), { target: { value: 'small' } })
    expect(canvasNode(overlaid)).toHaveClass('tree-match')
    expect(canvasNode(common)).toHaveClass('tree-dim')
    fireEvent.click(screen.getByRole('button', { name: '旅の展望台をキャンバスで表示' }))
    await waitFor(() => expect(canvasNode(overlaid)).toHaveClass('selected'))
    const toggle = within(screen.getByRole('group', { name: '表示パネル' })).getByRole('button', { name: '一覧' })
    fireEvent.click(toggle)
    expect(screen.queryByRole('searchbox', { name: 'ノードを検索' })).not.toBeInTheDocument()
    fireEvent.click(detail().getByRole('button', { name: '起点に設定' }))
    expect(canvasNode(common)).toHaveClass('tree-match')
    expect(canvasNode(overlaid)).toHaveClass('tree-dim')
    expect(canvasNode(distant)).toHaveClass('tree-dim')
    fireEvent.click(toggle)
    expect(screen.getByRole('button', { name: '旅の始まりをキャンバスで表示' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '旅の展望台をキャンバスで表示' })).not.toBeInTheDocument()
  })
})

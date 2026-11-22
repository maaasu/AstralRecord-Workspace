import { useState } from 'react'
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { editorApi } from '../api/editorApi'
import { handleAndroidDialogBack } from '../androidEditor'
import type { JsonValue, NodeMaster, StructureDocument } from '../types/editor'
import { ProgressionSkillTreePicker } from './ProgressionSkillTreePicker'
import { MasterDataDetails } from './MasterDataNavigation'

const nodes: NodeMaster[] = [
  { $schema: '', schemaVersion: 1, nodeId: '1', name: '冒険者の生命', icon: 'APPLE', tags: [], pointType: 'PP', pointCost: 1, effects: [], unlockCondition: { classId: 'adventurer' } },
  { $schema: '', schemaVersion: 1, nodeId: '2', name: '上級の剣術', icon: 'IRON_SWORD', tags: [], pointType: 'CP', pointCost: 1, effects: [], unlockCondition: { classId: 'sword', playerLevel: 20 } },
  { $schema: '', schemaVersion: 1, nodeId: '3', name: '剣の技', icon: 'IRON_SWORD', tags: [], pointType: 'CP', pointCost: 1, effects: [], unlockCondition: { classId: 'sword' } },
  { $schema: '', schemaVersion: 1, nodeId: '4', name: '魔術の技', icon: 'BLAZE_ROD', tags: [], pointType: 'CP', pointCost: 1, effects: [], unlockCondition: { classId: 'mage' } },
]
const structure: StructureDocument = { $schema: '', schemaVersion: 1, structureId: 'game', name: 'ゲームのツリー', rootNodeId: '1',
  nodes: nodes.map((node, index) => ({ nodeId: node.nodeId, x: index * 3, y: 0, z: 0 })),
  edges: [{ sourceNodeId: '1', targetNodeId: '3' }] }
const classes = [{ id: 'adventurer', name: '冒険者', parentClassIds: [] }, { id: 'sword', name: '剣士', parentClassIds: ['adventurer'] }, { id: 'mage', name: '魔術師', parentClassIds: ['adventurer'] }]
const context = { classId: 'sword', playerLevel: 10, classLevel: 5 }
const apply = () => screen.getByRole('button', { name: '選択を反映' })

beforeEach(() => {
  vi.spyOn(editorApi, 'listNodes').mockResolvedValue(nodes.map(content => ({ content, revision: 'n', fileName: content.nodeId + '.json' })))
  vi.spyOn(editorApi, 'listStructures').mockResolvedValue([{ content: structure, revision: 's', fileName: 'game.json' }])
  vi.spyOn(editorApi, 'listClasses').mockResolvedValue(classes)
  vi.spyOn(editorApi, 'listSkills').mockResolvedValue([])
  vi.spyOn(editorApi, 'getSettings').mockResolvedValue({ revision: 'c', content: { structureId: 'game', worldName: 'world', centerX: 0, centerY: 0, centerZ: 0 } })
})
afterEach(() => {
  window.history.replaceState(null, '', '/')
  vi.restoreAllMocks()
})

describe('progression skill-tree selection', () => {
  it('displays Android selections in the picker and dismisses without applying on Back', async () => {
    window.history.replaceState(null, '', '/?mobile=android')
    const onApply = vi.fn(), onClose = vi.fn()
    render(<ProgressionSkillTreePicker context={context} values={[]} onApply={onApply} onClose={onClose} />)
    const node = await screen.findByRole('checkbox', { name: '剣の技' })
    fireEvent.click(node)
    expect(node).toHaveAttribute('aria-checked', 'true')
    expect(screen.getByRole('heading', { name: '習得ノード 1件' })).toBeVisible()
    expect(screen.getByRole('button', { name: '剣の技を習得から外す' })).toBeVisible()

    act(() => expect(handleAndroidDialogBack()).toBe(true))
    expect(onClose).toHaveBeenCalledOnce()
    expect(onApply).not.toHaveBeenCalled()
  })

  it('keeps pan and pinch gestures from toggling a node and applies a tap explicitly on Android', async () => {
    window.history.replaceState(null, '', '/?mobile=android')
    const onApply = vi.fn()
    render(<ProgressionSkillTreePicker context={context} values={[]} onApply={onApply} onClose={vi.fn()} />)
    const node = await screen.findByRole('checkbox', { name: '剣の技' })
    const point = { identifier: 1, clientX: 30, clientY: 50 }
    const secondPoint = { identifier: 2, clientX: 90, clientY: 70 }
    fireEvent.touchStart(node, { touches: [point, secondPoint] })
    fireEvent.click(node)
    expect(node).toHaveAttribute('aria-checked', 'false')
    fireEvent.touchStart(node, { touches: [point] })
    fireEvent.touchMove(node, { touches: [{ ...point, clientX: 70 }] })
    fireEvent.click(node)
    expect(node).toHaveAttribute('aria-checked', 'false')
    fireEvent.touchStart(node, { touches: [point] })
    fireEvent.touchEnd(node, { touches: [], changedTouches: [point] })
    fireEvent.click(node)
    expect(node).toHaveAttribute('aria-checked', 'true')
    expect(screen.getByRole('heading', { name: '習得ノード 1件' })).toBeVisible()
    expect(onApply).not.toHaveBeenCalled()
    fireEvent.click(apply())
    expect(onApply).toHaveBeenCalledExactlyOnceWith([3])
  })

  it('uses the actual configured positions and class ancestors, keeps out-of-range nodes and applies once', async () => {
    const onApply = vi.fn()
    render(<ProgressionSkillTreePicker context={context} values={[1, 2, 999]} onApply={onApply} onClose={vi.fn()} />)
    const learned = await screen.findByRole('checkbox', { name: '冒険者の生命' })
    expect(learned).toHaveAttribute('aria-checked', 'true')
    expect(learned).toHaveClass('learned')
    expect(learned).toHaveAttribute('data-point-type', 'PP')
    expect(learned.querySelector('.learned-node-mark')).toBeInTheDocument()
    const handles = learned.querySelectorAll('.react-flow__handle')
    expect(handles).toHaveLength(4)
    for (const handle of handles) {
      expect(handle).not.toHaveClass('connectable')
      expect(handle).not.toHaveClass('connectionindicator')
    }
    expect(screen.queryByRole('checkbox', { name: '上級の剣術' })).not.toBeInTheDocument()
    expect(screen.queryByRole('checkbox', { name: '魔術の技' })).not.toBeInTheDocument()
    expect(screen.getByTestId('rf__edge-edge:1:3').querySelector('.react-flow__edge-path')).toHaveAttribute('d', 'M 36,0 L 180,0')
    expect(screen.getByText('この職業・レベルの表示範囲外')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '未登録のノード（999）を習得から外す' })).toBeEnabled()
    fireEvent.click(screen.getByRole('checkbox', { name: '剣の技' }))
    expect(screen.getByRole('checkbox', { name: '剣の技' })).toHaveAttribute('aria-checked', 'true')
    fireEvent.keyDown(learned, { key: ' ' })
    expect(learned).toHaveAttribute('aria-checked', 'false')
    fireEvent.keyDown(learned, { key: 'Enter' })
    expect(learned).toHaveAttribute('aria-checked', 'true')
    fireEvent.keyDown(learned, { key: 'Enter' })
    expect(learned).toHaveAttribute('aria-checked', 'false')
    expect(onApply).not.toHaveBeenCalled()
    fireEvent.click(apply())
    expect(onApply).toHaveBeenCalledExactlyOnceWith([2, 999, 3])
  })

  it('cancels local choices and allows an unavailable saved node to be removed by its name', async () => {
    const onApply = vi.fn(), onClose = vi.fn()
    render(<ProgressionSkillTreePicker context={context} values={[2, 999]} onApply={onApply} onClose={onClose} />)
    await screen.findByRole('checkbox', { name: '剣の技' })
    fireEvent.click(screen.getByRole('button', { name: '上級の剣術を習得から外す' }))
    expect(screen.queryByText('この職業・レベルの表示範囲外')).not.toBeInTheDocument()
    fireEvent.keyDown(screen.getByRole('checkbox', { name: '剣の技' }), { key: 'Enter' })
    fireEvent.click(screen.getByRole('button', { name: 'キャンセル' }))
    expect(onClose).toHaveBeenCalledOnce()
    expect(onApply).not.toHaveBeenCalled()
  })

  it('retries failed loading and keeps selection disabled while its data is unavailable', async () => {
    vi.mocked(editorApi.listNodes).mockRejectedValueOnce(new Error('ノードの接続失敗'))
    render(<ProgressionSkillTreePicker context={context} values={[999]} onApply={vi.fn()} onClose={vi.fn()} />)
    expect(apply()).toBeDisabled()
    expect(await screen.findByRole('alert')).toHaveTextContent('ノードの接続失敗')
    fireEvent.click(screen.getByRole('button', { name: '再試行' }))
    await screen.findByRole('checkbox', { name: '剣の技' })
    expect(apply()).toBeEnabled()
    expect(editorApi.listNodes).toHaveBeenCalledTimes(2)
  })

  it('filters a changed class and player level without removing saved choices', async () => {
    const onApply = vi.fn()
    const view = render(<ProgressionSkillTreePicker context={context} values={[1, 3]} onApply={onApply} onClose={vi.fn()} />)
    await screen.findByRole('checkbox', { name: '剣の技' })
    view.rerender(<ProgressionSkillTreePicker context={{ classId: 'mage', playerLevel: 30, classLevel: 10 }} values={[1, 3]} onApply={onApply} onClose={vi.fn()} />)
    expect(screen.queryByRole('checkbox', { name: '剣の技' })).not.toBeInTheDocument()
    expect(screen.getByRole('checkbox', { name: '魔術の技' })).toBeInTheDocument()
    fireEvent.click(apply())
    expect(onApply).toHaveBeenCalledWith([1, 3])
  })

  it('opens the nearest class branch when the selected class inherits its tree', async () => {
    vi.mocked(editorApi.listClasses).mockResolvedValue([...classes, { id: 'veteran', name: '熟練剣士', parentClassIds: ['sword'] }])
    render(<ProgressionSkillTreePicker context={{ classId: 'veteran', playerLevel: 10, classLevel: 3 }} values={[1, 3]} onApply={vi.fn()} onClose={vi.fn()} />)
    await screen.findByRole('checkbox', { name: '剣の技' })
    await waitFor(() => expect(screen.getByTestId('rf__node-3')).toHaveClass('selected'))
    expect(screen.getByRole('checkbox', { name: '剣の技' })).toHaveAttribute('aria-checked', 'true')
  })

  it('opens lazily from a preset checkpoint and changes its node array without changing another checkpoint', async () => {
    const original: JsonValue = { classId: 'sword', levels: [{ level: 1, nodes: [1] }, { level: 25, classLevel: 7, nodes: [1] }] }
    function Form() {
      const [value, setValue] = useState(original)
      return <><MasterDataDetails value={value} path="/levels/1/nodes" fields={[]} references={[]} schema={{}} disabled={false} navigationDisabled={false}
        onSelect={vi.fn()} onFocus={vi.fn()} onInvalid={vi.fn()} onChange={setValue}
        visualReferences={{ documentPath: '90.shared.progression_preset/v1.sword.yml', files: new Map(), openFile: vi.fn() }} />
        <output data-testid="draft">{JSON.stringify(value)}</output></>
    }
    render(<Form />)
    expect(editorApi.listNodes).not.toHaveBeenCalled()
    fireEvent.click(screen.getByRole('button', { name: 'スキルツリーで選択' }))
    const dialog = await screen.findByRole('dialog', { name: 'スキルツリーから習得ノードを選択' })
    expect(dialog.closest('.master-node-editor')).toBeNull()
    expect(within(dialog).getByText(/プレイヤーLv.25 · 職業Lv.7/)).toBeInTheDocument()
    fireEvent.click(await screen.findByRole('checkbox', { name: '上級の剣術' }))
    fireEvent.click(apply())
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    expect(JSON.parse(screen.getByTestId('draft').textContent!)).toEqual({ classId: 'sword', levels: [{ level: 1, nodes: [1] }, { level: 25, classLevel: 7, nodes: [1, 2] }] })
  })
})

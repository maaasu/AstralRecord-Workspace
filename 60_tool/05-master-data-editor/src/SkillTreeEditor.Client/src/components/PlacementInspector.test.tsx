import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { useState } from 'react'
import { describe, expect, it, vi } from 'vitest'
import type { NodeMaster, StructureDocument } from '../types/editor'
import { PlacementInspector } from './PlacementInspector'

const master: NodeMaster = {
  $schema: '../schemas/node.v1.schema.json',
  schemaVersion: 1,
  nodeId: '1012',
  name: '&d旅立ちの記録',
  icon: 'NETHER_STAR',
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
  it('マスター保存待機中は実入力をdisabledにして追加入力を受け付けない', async () => {
    let finishSave!: (value: NodeMaster) => void
    const onSaveMaster = vi.fn(() => new Promise<NodeMaster>((resolve) => { finishSave = resolve }))
    render(<PlacementInspector nodeId="1012" structure={structure} master={master} saving={false} iconRevision={0}
      onChange={vi.fn()} onSaveMaster={onSaveMaster} onEditMaster={vi.fn()} onRetryIcons={vi.fn()} />)
    fireEvent.change(screen.getByLabelText('名前'), { target: { value: '保存する名前' } })
    fireEvent.click(screen.getByRole('button', { name: 'マスター定義を保存' }))
    await waitFor(() => expect(onSaveMaster).toHaveBeenCalledOnce())
    for (const label of ['名前', 'X', 'Y', 'Z', 'コスト', 'ポイント種別', 'タグを追加']) {
      expect((screen.getByLabelText(label) as HTMLInputElement).disabled).toBe(true)
    }
    fireEvent.change(screen.getByLabelText('名前'), { target: { value: '保存待ち中の入力' } })
    expect(screen.getByLabelText('名前')).toHaveValue('保存する名前')
    await act(async () => finishSave({ ...master, name: '保存する名前' }))
    expect(screen.getByLabelText('名前')).toHaveValue('保存する名前')
    expect((screen.getByLabelText('名前') as HTMLInputElement).disabled).toBe(false)
  })

  it.each([['X', ''], ['X', '-'], ['X', '1.25'], ['X', '2147483648'], ['コスト', ''], ['コスト', '1.5'], ['コスト', '-1'], ['コスト', '2147483648']])('不正な%s=%sを保持し、日本語エラーと保存抑止を表示する', (label, value) => {
    const onChange = vi.fn()
    const onDirtyChange = vi.fn()
    render(<PlacementInspector nodeId="1012" structure={structure} master={master} saving={false} iconRevision={0}
      onChange={onChange} onSaveMaster={vi.fn()} onEditMaster={vi.fn()} onRetryIcons={vi.fn()} onDirtyChange={onDirtyChange} />)
    fireEvent.change(screen.getByLabelText('名前'), { target: { value: '編集中' } })
    const input = screen.getByLabelText(label)
    fireEvent.change(input, { target: { value } })
    fireEvent.blur(input)
    expect(input).toHaveValue(value)
    expect(screen.getByRole('alert')).toHaveTextContent(label === 'コスト' ? '整数' : '0.1単位')
    expect(screen.getByRole('button', { name: 'マスター定義を保存' })).toBeDisabled()
    expect(onChange).not.toHaveBeenCalled()
    expect(onDirtyChange).toHaveBeenLastCalledWith(true)
  })
  it('shows the unlock class in the cost summary', () => {
    render(
      <PlacementInspector
        nodeId="1012"
        structure={structure}
        master={{
          ...master,
          pointType: 'CP',
          pointCost: 1,
          unlockCondition: { classId: 'adventurer' },
        }}
        classMasters={[{ id: 'adventurer', name: '&6冒険者', parentClassIds: [] }]}
        saving={false}
        iconRevision={0}
        onChange={vi.fn()}
        onSaveMaster={vi.fn(async (node: NodeMaster) => node)}
        onEditMaster={vi.fn()}
        onRetryIcons={vi.fn()}
      />,
    )

    expect(screen.getByText('CP[冒険者] 1')).toBeInTheDocument()
  })

  it('edits selected placement coordinates and master fields inline', async () => {
    const onChange = vi.fn()
    const onSaveMaster = vi.fn(async (node: NodeMaster) => node)

    function Harness() {
      const [currentStructure, setCurrentStructure] = useState(structure)
      return (
      <PlacementInspector
        nodeId="1012"
        structure={currentStructure}
        master={master}
        saving={false}
        iconRevision={0}
        materialSuggestions={['NETHER_STAR', 'DIAMOND_SWORD']}
        tagSuggestions={['root', 'combat']}
        onChange={(value) => { onChange(value); setCurrentStructure(value) }}
        onSaveMaster={onSaveMaster}
        onEditMaster={vi.fn()}
        onRetryIcons={vi.fn()}
      />
      )
    }
    render(<Harness />)

    expect(screen.getByRole('heading', { name: '旅立ちの記録' })).toBeInTheDocument()
    expect(screen.getByLabelText('Minecraft Material')).toHaveAttribute('list')
    expect(screen.getByLabelText('X')).toHaveAttribute('inputmode', 'decimal')
    expect(screen.getByRole('button', { name: 'ルート／根を削除' })).toBeInTheDocument()
    fireEvent.change(screen.getByLabelText('タグを追加'), { target: { value: 'primary' } })
    fireEvent.change(screen.getByLabelText('X'), { target: { value: '8' } })
    fireEvent.blur(screen.getByLabelText('X'))
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
      tags: ['root', 'primary'],
    }))
  })

  it('preserves intermediate decimal input and commits the completed tenth-block value', () => {
    const onChange = vi.fn()

    render(
      <PlacementInspector
        nodeId="1012"
        structure={structure}
        master={master}
        saving={false}
        iconRevision={0}
        onChange={onChange}
        onSaveMaster={vi.fn(async (node: NodeMaster) => node)}
        onEditMaster={vi.fn()}
        onRetryIcons={vi.fn()}
      />,
    )

    const x = screen.getByLabelText('X')
    fireEvent.change(x, { target: { value: '1' } })
    fireEvent.change(x, { target: { value: '1.' } })
    expect(x).toHaveValue('1.')
    expect(onChange).not.toHaveBeenCalled()

    fireEvent.change(x, { target: { value: '1.2' } })
    fireEvent.blur(x)
    expect(onChange).toHaveBeenCalledWith({
      ...structure,
      nodes: [{ nodeId: '1012', x: 1.2, y: 64, z: 0 }],
    })
  })

  it('別の座標更新でも入力途中の数値を保持する', () => {
    const props = { nodeId: '1012', master, saving: false, iconRevision: 0, onChange: vi.fn(), onSaveMaster: vi.fn(), onEditMaster: vi.fn(), onRetryIcons: vi.fn() }
    const { rerender } = render(<PlacementInspector {...props} structure={structure} />)
    fireEvent.change(screen.getByLabelText('X'), { target: { value: '-' } })
    rerender(<PlacementInspector {...props} structure={{ ...structure, nodes: [{ ...structure.nodes[0], y: 65 }] }} />)
    expect(screen.getByLabelText('X')).toHaveValue('-')
    expect(screen.getByLabelText('Y')).toHaveValue('65')
  })

  it('selects the target class for a CP node', async () => {
    const onSaveMaster = vi.fn(async (node: NodeMaster) => node)

    render(
      <PlacementInspector
        nodeId="1012"
        structure={structure}
        master={{ ...master, pointType: 'CP' }}
        classMasters={[
          { id: 'adventurer', name: '&6冒険者', parentClassIds: [] },
          { id: 'mage', name: '&b魔法使い', parentClassIds: ['adventurer'] },
        ]}
        saving={false}
        iconRevision={0}
        onChange={vi.fn()}
        onSaveMaster={onSaveMaster}
        onEditMaster={vi.fn()}
        onRetryIcons={vi.fn()}
      />,
    )

    fireEvent.change(screen.getByLabelText('対象クラス'), { target: { value: 'mage' } })
    fireEvent.click(screen.getByRole('button', { name: 'マスター定義を保存' }))

    await waitFor(() => expect(onSaveMaster).toHaveBeenCalledWith({
      ...master,
      pointType: 'CP',
      unlockCondition: { classId: 'mage' },
    }))
  })

  it('does not offer a node description field in the inline editor', async () => {
    const onSaveMaster = vi.fn(async (node: NodeMaster) => node)

    render(
      <PlacementInspector
        nodeId="1012"
        structure={structure}
        master={master}
        saving={false}
        iconRevision={0}
        onChange={vi.fn()}
        onSaveMaster={onSaveMaster}
        onEditMaster={vi.fn()}
        onRetryIcons={vi.fn()}
      />,
    )

    expect(screen.queryByLabelText('Lore')).toBeNull()
    fireEvent.change(screen.getByLabelText('名前'), { target: { value: '&d旅立ちの記録（更新）' } })
    fireEvent.click(screen.getByRole('button', { name: 'マスター定義を保存' }))

    await waitFor(() => expect(onSaveMaster).toHaveBeenCalledTimes(1))
    expect(onSaveMaster.mock.calls[0][0]).not.toHaveProperty('lore')
  })
})

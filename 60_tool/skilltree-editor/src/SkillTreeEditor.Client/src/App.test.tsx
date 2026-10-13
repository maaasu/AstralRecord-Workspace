import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import App from './App'
import { ApiError, editorApi } from './api/editorApi'
import type { NodeMaster, StructureDocument } from './types/editor'

vi.mock('./api/editorApi', async (original) => ({
  ...await original<typeof import('./api/editorApi')>(),
  editorApi: Object.fromEntries(['metadata', 'listClasses', 'listSkills', 'listNodes', 'listStructures', 'listSchemas', 'getSchema', 'getSettings', 'saveNode', 'saveStructure', 'validateStructure'].map((name) => [name, vi.fn()])),
}))
vi.mock('./components/MasterDataEditor', () => ({ MasterDataEditor: () => null }))
vi.mock('./components/NodeSidebar', () => ({ NodeSidebar: ({ nodes, onEdit }: { nodes: NodeMaster[]; onEdit: (node: NodeMaster) => void }) => <button onClick={() => onEdit(nodes[0])}>ノードを編集</button> }))
vi.mock('./components/NodeEditor', () => ({ NodeEditor: ({ node, onSave, errorMessage }: { node: NodeMaster; onSave: (node: NodeMaster) => void; errorMessage?: string }) => <div role="dialog"><button onClick={() => onSave({ ...node, name: '下書き' })}>編集中ノードを保存</button>{errorMessage && <p role="alert">{errorMessage}</p>}</div> }))
vi.mock('./components/PlacementInspector', () => ({ PlacementInspector: () => null }))
vi.mock('./components/SkillTreeCanvas', () => ({ SkillTreeCanvas: ({ structure, onRecord }: { structure: StructureDocument; onRecord: (structure: StructureDocument) => void }) => <><output aria-label="構造名">{structure.name}</output><button onClick={() => onRecord({ ...structure, name: structure.name + '変更' })}>構造を編集</button></> }))

const node: NodeMaster = { $schema: 'node.schema.json', schemaVersion: 1, nodeId: '1000', name: 'original', icon: 'STONE', tags: [], pointType: 'CP', pointCost: 1, effects: [] }
const structure: StructureDocument = { $schema: 'structure.schema.json', schemaVersion: 1, structureId: 'main', name: 'main', rootNodeId: '1000', nodes: [{ nodeId: '1000', x: 0, y: 0, z: 0 }], edges: [] }

beforeEach(() => {
  vi.resetAllMocks()
  vi.mocked(editorApi.listNodes).mockResolvedValue([{ fileName: '1000.json', content: node, revision: 'node-before' }])
  vi.mocked(editorApi.listStructures).mockResolvedValue([{ fileName: 'main.json', content: structure, revision: 'structure-before' }])
  vi.mocked(editorApi.listSchemas).mockResolvedValue([])
  vi.mocked(editorApi.listClasses).mockResolvedValue([])
  vi.mocked(editorApi.listSkills).mockResolvedValue([])
  vi.mocked(editorApi.metadata).mockResolvedValue({} as Awaited<ReturnType<typeof editorApi.metadata>>)
  vi.mocked(editorApi.getSettings).mockResolvedValue({ content: { worldName: 'world', structureId: 'main', centerX: 0, centerY: 0, centerZ: 0 }, revision: 'settings-before' })
  vi.mocked(editorApi.validateStructure).mockResolvedValue({ isValid: true, issues: [] })
})

async function openTree() {
  render(<App />)
  fireEvent.click(screen.getByRole('button', { name: 'スキルツリー' }))
  await screen.findByRole('button', { name: 'ノードを編集' })
}

describe('画面の保存元の版', () => {
  it('検証と保存の待機中は構造の変更・切替・Undoを止める', async () => {
    let finishValidation!: (value: Awaited<ReturnType<typeof editorApi.validateStructure>>) => void
    let finishSave!: (value: Awaited<ReturnType<typeof editorApi.saveStructure>>) => void
    vi.mocked(editorApi.validateStructure).mockReturnValue(new Promise((resolve) => { finishValidation = resolve }))
    vi.mocked(editorApi.saveStructure).mockReturnValue(new Promise((resolve) => { finishSave = resolve }))
    await openTree()
    fireEvent.click(screen.getByRole('button', { name: '構造を編集' }))
    fireEvent.click(screen.getByRole('button', { name: '構造を保存' }))
    const assertLocked = () => {
      expect(screen.getByRole('combobox', { name: '構造' })).toBeDisabled()
      expect(screen.getByTitle('Undo (Ctrl+Z)')).toBeDisabled()
      expect(screen.getByRole('button', { name: '補助自動配置' })).toBeDisabled()
      fireEvent.click(screen.getByRole('button', { name: '構造を編集' }))
      fireEvent.keyDown(window, { key: 'z', ctrlKey: true })
      expect(screen.getByLabelText('構造名')).toHaveTextContent('main変更')
    }
    assertLocked()
    await act(async () => finishValidation({ isValid: true, issues: [] }))
    await waitFor(() => expect(editorApi.saveStructure).toHaveBeenCalledOnce())
    assertLocked()
    await act(async () => finishSave({ content: { ...structure, name: 'main変更' }, revision: 'after' }))
    expect(screen.getByRole('combobox', { name: '構造' })).toBeEnabled()
    expect(screen.getByLabelText('構造名')).toHaveTextContent('main変更')
  })
  it('ノードの読込時の版を送り、成功後の再編集は新しい版を送る', async () => {
    vi.mocked(editorApi.saveNode).mockResolvedValue({ content: { ...node, name: '下書き' }, revision: 'node-after' })
    await openTree()
    fireEvent.click(screen.getByRole('button', { name: 'ノードを編集' }))
    fireEvent.click(await screen.findByRole('button', { name: '編集中ノードを保存' }))
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    expect(editorApi.saveNode).toHaveBeenLastCalledWith(expect.objectContaining({ name: '下書き' }), 'node-before')
    fireEvent.click(screen.getByRole('button', { name: 'ノードを編集' }))
    fireEvent.click(await screen.findByRole('button', { name: '編集中ノードを保存' }))
    await waitFor(() => expect(editorApi.saveNode).toHaveBeenLastCalledWith(expect.objectContaining({ name: '下書き' }), 'node-after'))
  })

  it('構造の競合後も未保存の構造と元の版を保持して再試行する', async () => {
    vi.mocked(editorApi.saveStructure).mockRejectedValue(new ApiError('ファイルが変更されています。', 409))
    await openTree()
    fireEvent.click(screen.getByRole('button', { name: '構造を編集' }))
    fireEvent.click(screen.getByRole('button', { name: '構造を保存' }))
    await screen.findByText('ファイルが変更されています。')
    expect(editorApi.saveStructure).toHaveBeenLastCalledWith(expect.objectContaining({ name: 'main変更' }), 'structure-before')
    expect(screen.getByRole('button', { name: '構造を保存' })).toBeEnabled()
    fireEvent.click(screen.getByRole('button', { name: '構造を保存' }))
    await waitFor(() => expect(editorApi.saveStructure).toHaveBeenCalledTimes(2))
    expect(editorApi.saveStructure).toHaveBeenLastCalledWith(expect.objectContaining({ name: 'main変更' }), 'structure-before')
  })
})

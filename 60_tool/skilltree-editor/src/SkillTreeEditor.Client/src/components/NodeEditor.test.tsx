import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import type { LoadedSchema } from '../types/editor'
import { NodeEditor } from './NodeEditor'

const schemas: LoadedSchema[] = [{ summary: { fileName: 'node.v1.schema.json', entityKind: 'node', isDefault: true },
  content: { type: 'object', properties: { icon: { type: 'string' }, name: { type: 'string' } } } }]

describe('NodeEditor material edit protection', () => {
  it('未保存原稿のあるEscapeを確認し、キャンセル後も原稿を保持する', () => {
    const onCancel = vi.fn()
    const onDirtyChange = vi.fn()
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(false)
    try {
      render(<NodeEditor node={{ $schema: '../schemas/node.v1.schema.json', nodeId: '1000', name: 'ノード' }} schemas={schemas} isNew={false} saving={false} onSave={vi.fn()} onCancel={onCancel} onDirtyChange={onDirtyChange} />)
      fireEvent.click(screen.getByRole('button', { name: '原稿 JSON' }))
      fireEvent.change(screen.getByRole('textbox', { name: 'ノードJSON' }), { target: { value: '{"nodeId":"1000","name":"編集中"}' } })
      fireEvent.keyDown(screen.getByRole('textbox', { name: 'ノードJSON' }), { key: 'Escape' })
      expect(confirm).toHaveBeenCalledWith('未保存のノード変更を破棄して閉じますか？')
      expect(onCancel).not.toHaveBeenCalled()
      expect(screen.getByRole('textbox', { name: 'ノードJSON' })).toHaveValue('{"nodeId":"1000","name":"編集中"}')
      expect(onDirtyChange).toHaveBeenLastCalledWith(true)
      confirm.mockReturnValue(true)
      fireEvent.click(screen.getByRole('button', { name: 'キャンセル' }))
      expect(onCancel).toHaveBeenCalledOnce()
    } finally { confirm.mockRestore() }
  })
  it('disables the material chooser and text changes while saving a node', () => {
    render(<NodeEditor node={{ $schema: '../schemas/node.v1.schema.json', nodeId: '1000', icon: 'BOOK', name: 'ノード' }}
      schemas={schemas} isNew={false} saving onSave={vi.fn()} onCancel={vi.fn()} />)
    expect(screen.getByRole('button', { name: '画像一覧から選択' })).toBeDisabled()
    expect(screen.getByRole('combobox', { name: 'Minecraft Material' })).toBeDisabled()
    expect(screen.getByRole('button', { name: /^閉じる$/ })).toBeDisabled()
  })
})

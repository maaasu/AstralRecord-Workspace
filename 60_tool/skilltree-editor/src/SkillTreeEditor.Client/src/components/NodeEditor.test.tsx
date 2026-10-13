import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import type { LoadedSchema } from '../types/editor'
import { NodeEditor } from './NodeEditor'

const schemas: LoadedSchema[] = [{ summary: { fileName: 'node.v1.schema.json', entityKind: 'node', isDefault: true },
  content: { type: 'object', properties: { icon: { type: 'string' }, name: { type: 'string' } } } }]

describe('NodeEditor material edit protection', () => {
  it.each([-1, null])('blocks an initially invalid numeric value %s', (pointCost) => {
    const numericSchemas: LoadedSchema[] = [{ summary: { fileName: 'node.v1.schema.json', entityKind: 'node', isDefault: true }, content: { type: 'object', properties: { pointCost: { type: 'integer', minimum: 0 } } } }]
    render(<NodeEditor node={{ $schema: '../schemas/node.v1.schema.json', nodeId: '1000', pointCost }} schemas={numericSchemas} isNew={false} saving={false} onSave={vi.fn()} onCancel={vi.fn()} />)
    expect(screen.getByRole('textbox', { name: '/pointCost' })).toHaveAttribute('aria-invalid', 'true')
    expect(screen.getByRole('button', { name: 'ノードを保存' })).toBeDisabled()
    expect(screen.getByRole('button', { name: '原稿 JSON' })).toBeDisabled()
  })
  it('blocks saving and mode switching while a numeric field is incomplete', () => {
    const numericSchemas: LoadedSchema[] = [{ summary: { fileName: 'node.v1.schema.json', entityKind: 'node', isDefault: true }, content: { type: 'object', properties: { pointCost: { type: 'integer', minimum: 0 } } } }]
    const onSave = vi.fn()
    const onDirtyChange = vi.fn()
    render(<NodeEditor node={{ $schema: '../schemas/node.v1.schema.json', nodeId: '1000', pointCost: 1 }} schemas={numericSchemas} isNew={false} saving={false} onSave={onSave} onCancel={vi.fn()} onDirtyChange={onDirtyChange} />)
    fireEvent.change(screen.getByRole('textbox', { name: '/pointCost' }), { target: { value: '-' } })
    expect(screen.getByRole('button', { name: 'ノードを保存' })).toBeDisabled()
    expect(screen.getByRole('button', { name: '原稿 JSON' })).toBeDisabled()
    expect(onDirtyChange).toHaveBeenLastCalledWith(true)
    expect(onSave).not.toHaveBeenCalled()
    fireEvent.change(screen.getByRole('textbox', { name: '/pointCost' }), { target: { value: '2' } })
    expect(screen.getByRole('button', { name: 'ノードを保存' })).not.toBeDisabled()
    expect(screen.getByRole('button', { name: '原稿 JSON' })).not.toBeDisabled()
  })
  it('keeps malformed JSON in raw mode until it is repaired', () => {
    render(<NodeEditor node={{ $schema: '../schemas/node.v1.schema.json', nodeId: '1000', name: 'ノード' }} schemas={schemas} isNew={false} saving={false} onSave={vi.fn()} onCancel={vi.fn()} />)
    fireEvent.click(screen.getByRole('button', { name: '原稿 JSON' }))
    const raw = screen.getByRole('textbox', { name: 'ノードJSON' })
    fireEvent.change(raw, { target: { value: '{broken' } })
    expect(screen.getByRole('button', { name: 'スキーマフォーム' })).toBeDisabled()
    expect(raw).toHaveValue('{broken')
    fireEvent.change(raw, { target: { value: '{"nodeId":"1000","name":"修復済み"}' } })
    expect(screen.getByRole('button', { name: 'スキーマフォーム' })).not.toBeDisabled()
  })
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

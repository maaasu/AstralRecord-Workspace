import { render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import type { LoadedSchema } from '../types/editor'
import { NodeEditor } from './NodeEditor'

const schemas: LoadedSchema[] = [{ summary: { fileName: 'node.v1.schema.json', entityKind: 'node', isDefault: true },
  content: { type: 'object', properties: { icon: { type: 'string' }, name: { type: 'string' } } } }]

describe('NodeEditor material edit protection', () => {
  it('disables the material chooser and text changes while saving a node', () => {
    render(<NodeEditor node={{ $schema: '../schemas/node.v1.schema.json', nodeId: '1000', icon: 'BOOK', name: 'ノード' }}
      schemas={schemas} isNew={false} saving onSave={vi.fn()} onCancel={vi.fn()} />)
    expect(screen.getByRole('button', { name: '画像一覧から選択' })).toBeDisabled()
    expect(screen.getByRole('combobox', { name: 'Minecraft Material' })).toBeDisabled()
    expect(screen.getByRole('button', { name: /^閉じる$/ })).toBeDisabled()
  })
})

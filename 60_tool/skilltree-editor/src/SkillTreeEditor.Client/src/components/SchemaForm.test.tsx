import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { defaultFromSchema, SchemaForm } from './SchemaForm'
import type { JsonObject } from '../types/editor'
import nodeSchema from '../../../../../../40_filebase/35.features.skilltree/schemas/node.v1.schema.json'

describe('defaultFromSchema', () => {
  it('uses the first enum value even when type is omitted', () => {
    expect(defaultFromSchema({ enum: ['CP', 'PP'] })).toBe('CP')
    expect(defaultFromSchema({ enum: ['FLAT', 'SCALAR'] })).toBe('FLAT')
  })

  it('resolves local refs while creating nested defaults', () => {
    const schema: JsonObject = {
      type: 'object',
      properties: {
        effect: { $ref: '#/$defs/effect' },
      },
      $defs: {
        effect: {
          type: 'object',
          properties: {
            type: { const: 'skill' },
            skillId: { type: 'string' },
          },
        },
      },
    }
    expect(defaultFromSchema(schema)).toEqual({ effect: { type: 'skill', skillId: '' } })
  })

  it('offers path-based suggestions without restricting free text', () => {
    render(
      <SchemaForm
        schema={{ type: 'string' }}
        value="BOOK"
        path="/icon"
        suggestionsByPath={{ '/icon': ['BOOK', 'NETHER_STAR'] }}
        onChange={vi.fn()}
      />,
    )

    expect(screen.getByRole('combobox')).toHaveAttribute('list')
    expect(document.querySelector('option[value="NETHER_STAR"]')).toBeInTheDocument()
  })

  it('shows Japanese labels while preserving the selected status id', () => {
    const onChange = vi.fn()
    render(
      <SchemaForm
        schema={{ type: 'string' }}
        value="MAX_HEALTH"
        path="/effects/0/status"
        suggestionsByPath={{
          '/effects/*/status': [
            { value: 'MAX_HEALTH', label: '最大HP（MAX_HEALTH）' },
            { value: 'STRENGTH', label: '筋力（STRENGTH）' },
          ],
        }}
        onChange={onChange}
      />,
    )

    const select = screen.getByRole('combobox')
    expect(screen.getByRole('option', { name: '最大HP（MAX_HEALTH）' })).toHaveValue('MAX_HEALTH')
    fireEvent.change(select, { target: { value: 'STRENGTH' } })
    expect(onChange).toHaveBeenCalledWith('STRENGTH')
  })

  it('uses Japanese node field labels and changes referenced effects without retaining incompatible fields', () => {
    const onChange = vi.fn()
    render(<SchemaForm schema={nodeSchema.properties.effects.items} rootSchema={nodeSchema}
      value={{ type: 'status', status: 'MAX_HEALTH', modifierType: 'FLAT', value: 25, custom: '保持' }} onChange={onChange} />)
    expect(screen.getByText('ステータス', { selector: '.schema-field > span' })).toBeInTheDocument()
    expect(screen.getByText('補正の種類', { selector: '.schema-field > span' })).toBeInTheDocument()
    fireEvent.change(screen.getByRole('combobox', { name: '種類' }), { target: { value: '0' } })
    expect(onChange).toHaveBeenLastCalledWith({ type: 'skill', skillId: '', custom: '保持' })
  })
})

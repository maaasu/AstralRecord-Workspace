import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { defaultFromSchema, SchemaForm } from './SchemaForm'
import type { JsonObject } from '../types/editor'

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
})

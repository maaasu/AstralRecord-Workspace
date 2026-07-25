import { render, screen } from '@testing-library/react'
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
})

import { fireEvent, render, screen } from '@testing-library/react'
import { useCallback, useState } from 'react'
import { describe, expect, it, vi } from 'vitest'
import { defaultFromSchema, SchemaForm } from './SchemaForm'
import type { JsonObject, JsonValue } from '../types/editor'
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
    fireEvent.change(screen.getByRole('combobox', { name: '/ 定義の種類' }), { target: { value: '0' } })
    expect(onChange).toHaveBeenLastCalledWith({ type: 'skill', skillId: '', custom: '保持' })
  })
})

describe('schema number drafts', () => {
  it.each(['', '-', '1e', 'Infinity'])('keeps unfinished or nonfinite text %j without changing the saved number', (text) => {
    const onChange = vi.fn(); const onInvalid = vi.fn()
    const { container } = render(<SchemaForm schema={{ type: 'number' }} value={5} path="/value" onChange={onChange} onInvalid={onInvalid} />)
    const input = container.querySelector('input')!
    fireEvent.change(input, { target: { value: text } })
    expect(input.value).toBe(text)
    expect(onChange).not.toHaveBeenCalled()
    expect(onInvalid).toHaveBeenLastCalledWith('/value', expect.stringMatching(/数値/))
  })

  it('rejects fractions for integer fields instead of silently truncating them', () => {
    const onChange = vi.fn(); const onInvalid = vi.fn()
    const { container } = render(<SchemaForm schema={{ type: 'integer', minimum: 0, maximum: 10 }} value={5} path="/count" onChange={onChange} onInvalid={onInvalid} />)
    const input = container.querySelector('input')!
    fireEvent.change(input, { target: { value: '1.5' } })
    expect(input.value).toBe('1.5')
    expect(onChange).not.toHaveBeenCalled()
    expect(onInvalid).toHaveBeenLastCalledWith('/count', expect.stringMatching(/整数/))
    fireEvent.change(input, { target: { value: '11' } })
    expect(onChange).not.toHaveBeenCalled()
    expect(onInvalid).toHaveBeenLastCalledWith('/count', expect.stringMatching(/10/))
    fireEvent.change(input, { target: { value: '10' } })
    expect(onChange).toHaveBeenLastCalledWith(10)
    expect(onInvalid).toHaveBeenLastCalledWith('/count', null)
  })

  it('preserves decimal input text while updating valid numeric values', () => {
    function EditableNumber() {
      const [value, setValue] = useState<JsonValue>(5)
      return <SchemaForm schema={{ type: 'number' }} value={value} onChange={setValue} />
    }
    const { container } = render(<EditableNumber />)
    const input = container.querySelector('input')!
    fireEvent.change(input, { target: { value: '1.' } })
    expect(input.value).toBe('1.')
    fireEvent.change(input, { target: { value: '1.25' } })
    expect(input.value).toBe('1.25')
  })

  it('locks structural operations and retains invalid text while other scalar fields are edited', () => {
    function EditableNode() {
      const [value, setValue] = useState<JsonValue>({ name: '元', effects: [{ type: 'status', status: 'MAX_HEALTH', modifierType: 'FLAT', value: 5 }] })
      const [invalid, setInvalid] = useState<Record<string, string>>({})
      const onInvalid = useCallback((path: string, message: string | null) => setInvalid(current => {
        if (current[path] === message || !message && !Object.hasOwn(current, path)) return current
        const next = { ...current }
        if (message) next[path] = message
        else delete next[path]
        return next
      }), [])
      return <><SchemaForm schema={{ type: 'object', properties: { name: { type: 'string' }, effects: nodeSchema.properties.effects } }} rootSchema={nodeSchema}
        value={value} onChange={setValue} onInvalid={onInvalid} navigationDisabled={Object.keys(invalid).length > 0} /><output data-testid="node">{JSON.stringify(value)}</output></>
    }
    render(<EditableNode />)
    const number = screen.getByRole('textbox', { name: '/effects/0/value' })
    fireEvent.change(number, { target: { value: '-' } })
    expect(screen.getByRole('combobox', { name: '/effects/0 定義の種類' })).toBeDisabled()
    expect(screen.getByRole('button', { name: '削除' })).toBeDisabled()
    expect(screen.getByRole('button', { name: '＋ 追加' })).toBeDisabled()
    expect(screen.getByRole('textbox', { name: '/effects/0/type' })).toHaveAttribute('readonly')
    fireEvent.change(screen.getByRole('textbox', { name: '/effects/0/type' }), { target: { value: 'skill' } })
    expect(screen.getByRole('combobox', { name: '/effects/0 定義の種類' })).toHaveValue('1')
    fireEvent.change(screen.getByRole('textbox', { name: '表示名' }), { target: { value: '変更' } })
    expect(number).toHaveValue('-')
    expect(screen.getByTestId('node')).toHaveTextContent('"name":"変更"')
    expect(screen.getByTestId('node')).toHaveTextContent('"value":5')
    fireEvent.change(number, { target: { value: '6' } })
    expect(screen.getByRole('combobox', { name: '/effects/0 定義の種類' })).not.toBeDisabled()
    expect(screen.getByRole('button', { name: '削除' })).not.toBeDisabled()
    expect(screen.getByTestId('node')).toHaveTextContent('"value":6')
  })

  it.each([
    [{ minimum: 2 }, '1', /2 以上/],
    [{ exclusiveMinimum: 2 }, '2', /2 より大きい/],
    [{ exclusiveMaximum: 2 }, '2', /2 より小さい/],
    [{ multipleOf: .5 }, '1.3', /0.5 の倍数/],
  ] as const)('validates declared number constraints %j', (constraint, text, expected) => {
    const onChange = vi.fn(); const onInvalid = vi.fn()
    render(<SchemaForm schema={{ type: 'number', ...constraint }} value={3} path="/value" onChange={onChange} onInvalid={onInvalid} />)
    fireEvent.change(screen.getByRole('textbox', { name: '/value' }), { target: { value: text } })
    expect(onChange).not.toHaveBeenCalled()
    expect(onInvalid).toHaveBeenLastCalledWith('/value', expect.stringMatching(expected))
  })

  it('does not invalidate absent optional defaults, but validates actual out-of-range values', () => {
    const onInvalid = vi.fn()
    render(<SchemaForm schema={{ type: 'object', properties: { optional: { type: 'integer', minimum: 1 }, existing: { type: 'integer', minimum: 1 } } }}
      value={{ existing: -1 }} onChange={vi.fn()} onInvalid={onInvalid} />)
    expect(screen.getByRole('textbox', { name: '/optional' })).toHaveAttribute('aria-invalid', 'false')
    expect(screen.getByRole('textbox', { name: '/existing' })).toHaveAttribute('aria-invalid', 'true')
    expect(onInvalid).toHaveBeenCalledWith('/optional', null)
    expect(onInvalid).toHaveBeenCalledWith('/existing', expect.stringMatching(/1 以上/))
  })

  it('keeps an explicitly null numeric field invalid instead of presenting a default zero', () => {
    const onInvalid = vi.fn(); const onChange = vi.fn()
    render(<SchemaForm schema={{ type: 'object', properties: { count: { type: 'integer' } } }} value={{ count: null }} onChange={onChange} onInvalid={onInvalid} />)
    expect(screen.getByRole('textbox', { name: '/count' })).toHaveValue('')
    expect(screen.getByRole('textbox', { name: '/count' })).toHaveAttribute('aria-invalid', 'true')
    expect(onInvalid).toHaveBeenCalledWith('/count', expect.stringMatching(/数値/))
    expect(onChange).not.toHaveBeenCalled()
  })
})

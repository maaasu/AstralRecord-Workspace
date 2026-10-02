import { fireEvent, render, screen, within } from '@testing-library/react'
import { useState } from 'react'
import { describe, expect, it, vi } from 'vitest'
import type { JsonObject, JsonValue } from '../types/editor'
import { closestContainerPointer } from '../data/masterDataNavigation'
import { MasterDataDetails, MasterDataOutline } from './MasterDataNavigation'

const initial = { schemaVersion: 1, id: 'test', unknown: 'root', skills: [{ params: { 'a/b~c': 2, UnknownRpc: 'keep' } }, { id: 'sibling' }] }
function Editable({ readOnly = false, value: startingValue = initial, schema = {} }: { readOnly?: boolean; value?: JsonValue; schema?: JsonObject }) {
  const [value, setValue] = useState<JsonValue>(startingValue)
  const [path, setPath] = useState('')
  const selected = closestContainerPointer(value, path)
  return <><MasterDataOutline value={value} fields={[]} schema={schema} selectedPath={selected} onSelect={setPath} onFocus={vi.fn()} />
    <MasterDataDetails value={value} path={selected} fields={[]} references={[]} schema={schema} disabled={readOnly} navigationDisabled={false} onSelect={setPath} onInvalid={vi.fn()} onChange={setValue} />
    <output data-testid="value">{JSON.stringify(value)}</output></>
}
describe('master data tree and details', () => {
  it('edits an escaped deep pointer without dropping unknown fields or array siblings', () => {
    render(<Editable />)
    fireEvent.click(screen.getByRole('button', { name: '/skills を開く' }))
    fireEvent.click(screen.getByRole('button', { name: '/skills/0 を開く' }))
    fireEvent.click(screen.getByRole('button', { name: '/skills/0/params を開く' }))
    fireEvent.change(screen.getByRole('textbox', { name: '/skills/0/params/a~1b~0c' }), { target: { value: '8' } })
    expect(JSON.parse(screen.getByTestId('value').textContent!)).toEqual({ ...initial, skills: [{ params: { 'a/b~c': 8, UnknownRpc: 'keep' } }, { id: 'sibling' }] })
    fireEvent.click(within(screen.getByRole('navigation', { name: '現在の階層' })).getByRole('button', { name: '全体' }))
    expect(screen.getByRole('textbox', { name: '/id' })).toHaveValue('test')
  })
  it('searches Japanese schema titles, keys and values across collapsed nodes', () => {
    const onFocus = vi.fn()
    const schema = { properties: { skills: { items: { properties: { params: { properties: { UnknownRpc: { title: '固有の設定' } } } } } } } }
    render(<MasterDataOutline value={initial} fields={[]} schema={schema} selectedPath="" onSelect={vi.fn()} onFocus={onFocus} />)
    fireEvent.change(screen.getByRole('textbox', { name: 'フォーム内検索' }), { target: { value: '固有の設定' } })
    fireEvent.click(screen.getByRole('button', { name: '/skills/0/params/UnknownRpc に移動' }))
    expect(onFocus).toHaveBeenCalledWith('/skills/0/params/UnknownRpc')
    fireEvent.change(screen.getByRole('textbox', { name: 'フォーム内検索' }), { target: { value: 'keep' } })
    expect(screen.getByRole('button', { name: '/skills/0/params/UnknownRpc に移動' })).toBeInTheDocument()
  })
  it('keeps navigation available for read-only documents while disabling deep mutations', () => {
    render(<Editable readOnly />)
    fireEvent.click(screen.getByRole('button', { name: '/skills を編集' }))
    fireEvent.click(screen.getByRole('button', { name: '/skills/0 を編集' }))
    fireEvent.click(screen.getByRole('button', { name: '/skills/0/params を編集' }))
    expect(screen.getByRole('textbox', { name: '/skills/0/params/UnknownRpc' })).toBeDisabled()
    expect(screen.getByTestId('value')).toHaveTextContent(JSON.stringify(initial))
  })
  it('searches the end of long values while keeping result summaries short', () => {
    const onFocus = vi.fn()
    render(<MasterDataOutline value={{ description: 'x'.repeat(100) + 'UNIQUE_LATE_VALUE' }} fields={[]} schema={{}} selectedPath="" onSelect={vi.fn()} onFocus={onFocus} />)
    fireEvent.change(screen.getByRole('textbox', { name: 'フォーム内検索' }), { target: { value: 'UNIQUE_LATE_VALUE' } })
    const result = screen.getByRole('button', { name: '/description に移動' })
    expect(result.querySelector('small')?.textContent?.length).toBeLessThanOrEqual(80)
    fireEvent.click(result)
    expect(onFocus).toHaveBeenCalledWith('/description')
  })
  it('keeps a union type picker available when editing an array element', () => {
    const schema = { properties: { skills: { type: 'array', items: { oneOf: [
      { title: '攻撃', type: 'object', required: ['type', 'amount'], properties: { type: { const: 'attack' }, amount: { type: 'integer' } } },
      { title: '回復', type: 'object', required: ['type', 'power'], properties: { type: { const: 'heal' }, power: { type: 'number' } } },
    ] } } } }
    render(<Editable value={{ skills: [{ type: 'attack', amount: 2, UnknownRpc: 'keep' }] }} schema={schema} />)
    fireEvent.click(screen.getByRole('button', { name: '/skills を開く' }))
    fireEvent.click(screen.getByRole('button', { name: '/skills/0 を開く' }))
    fireEvent.change(screen.getByRole('combobox', { name: '/skills/0 定義の種類' }), { target: { value: '1' } })
    expect(JSON.parse(screen.getByTestId('value').textContent!)).toEqual({ skills: [{ type: 'heal', power: 0, amount: 2, UnknownRpc: 'keep' }] })
  })
  it('pages structured arrays while applying row actions to the actual index', () => {
    const items = Array.from({ length: 27 }, (_, index) => ({ id: 'row-' + index }))
    render(<Editable value={{ items }} />)
    fireEvent.click(screen.getByRole('button', { name: '/items を開く' }))
    fireEvent.click(screen.getByRole('button', { name: '次の要素' }))
    fireEvent.click(screen.getByRole('button', { name: '/items/25 を削除' }))
    expect(JSON.parse(screen.getByTestId('value').textContent!).items.map((item: { id: string }) => item.id)).toEqual(items.filter((_, index) => index !== 25).map((item) => item.id))
  })
})

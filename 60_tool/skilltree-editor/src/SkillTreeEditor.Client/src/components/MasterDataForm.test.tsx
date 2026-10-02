import { fireEvent, render, screen } from '@testing-library/react'
import { useState } from 'react'
import { describe, expect, it, vi } from 'vitest'
import { fieldForPath, masterDefault, MasterDataForm, normalizeMasterType } from './MasterDataForm'
import type { JsonValue } from '../types/editor'
import type { MasterField } from '../types/masterData'

const fields: MasterField[] = [
  { path: '/name', key: 'name', label: '表示名', type: 'string', required: true, description: 'ゲーム内の名前' },
  { path: '/equipment/level', key: 'level', label: '装備レベル', type: 'integer', required: false, description: '' },
  { path: '/skills/*/params/damage', key: 'damage', label: 'ダメージ', type: 'number', required: false, description: '' },
]

function Editable({ initial, metadata = fields }: { initial: JsonValue; metadata?: MasterField[] }) {
  const [value, setValue] = useState(initial)
  return <><MasterDataForm value={value} onChange={setValue} fields={metadata} /><output data-testid="value">{JSON.stringify(value)}</output></>
}
function openAdd(path: string) {
  const input = screen.getByRole('textbox', { name: path + ' 新しいキー', hidden: true })
  fireEvent.click(input.closest('details')!.querySelector('summary')!)
}

describe('master form', () => {
  it('previews a material icon without changing unknown fields during free text edits', () => {
    render(<Editable initial={{ icon: 'iron_ingot', params: { AlienRPC: 'keep' } }} metadata={[]} />)
    expect(screen.getByRole('img', { name: '素材アイコン: iron_ingot' })).toBeInTheDocument()
    fireEvent.change(screen.getByRole('combobox', { name: '/icon' }), { target: { value: 'BOOK' } })
    expect(JSON.parse(screen.getByTestId('value').textContent!)).toEqual({ icon: 'BOOK', params: { AlienRPC: 'keep' } })
  })
  it('uses pet schema Japanese labels and item reference prefixes without dropping unknown draft fields', () => {
    const onChange = vi.fn()
    const schema = { type: 'object', properties: { eggItemId: { type: 'string', title: '卵のアイテム参照', pattern: '^item:[0-9]{2}[a-z][0-9]{5}$' }, AlienKey: { type: 'object' } } }
    render(<MasterDataForm value={{ eggItemId: 'item:81a00001', AlienKey: { CustomFlag: 4 } }} schema={schema}
      fields={[{ path: '/eggItemId', key: 'eggItemId', label: '卵', type: 'string', required: true, description: '', reference: 'item' }]}
      references={[{ path: '81.pet_egg/wolf.yml', pointer: '/id', value: '81a00001', kind: 'item', label: '犬の卵' }]} onChange={onChange} />)
    expect(screen.getByText('卵のアイテム参照')).toBeInTheDocument()
    expect(document.querySelector('option[value="item:81a00001"]')).toHaveTextContent('犬の卵')
    expect(screen.getByRole('textbox', { name: '/AlienKey/CustomFlag' })).toHaveValue('4')
    fireEvent.change(screen.getByRole('combobox', { name: '/eggItemId' }), { target: { value: 'item:81a00002' } })
    expect(onChange).toHaveBeenLastCalledWith({ eggItemId: 'item:81a00002', AlienKey: { CustomFlag: 4 } })
  })

  it('resolves wildcard definitions without mixing unrelated nested keys', () => {
    expect(fieldForPath(fields, '/skills/2/params/damage')?.label).toBe('ダメージ')
    expect(fieldForPath(fields, '/other/name')).toBeUndefined()
  })

  it('creates only required schema properties and resolves local references', () => {
    const schema = { type: 'object', required: ['entry'], properties: { entry: { $ref: '#/$defs/entry' }, optional: { type: 'string' } }, $defs: { entry: { type: 'integer', default: 3 } } }
    expect(masterDefault(schema)).toEqual({ entry: 3 })
  })

  it('recognizes documented types and adds pointer leaf keys instead of source dotted keys', () => {
    expect(normalizeMasterType('Integer')).toBe('integer')
    expect(normalizeMasterType('List<Object>')).toBe('array')
    expect(normalizeMasterType('Map<String, Double>')).toBe('object')
    expect(normalizeMasterType('String|Integer')).toBeUndefined()
    render(<Editable initial={{ equipment: {} }} metadata={[{ path: '/equipment/level', key: 'equipment[].level', label: 'レベル', type: 'Integer', required: false, description: '' }]} />)
    openAdd('/equipment')
    fireEvent.change(screen.getByRole('combobox', { name: '/equipment 定義済みの項目を追加' }), { target: { value: 'level' } })
    expect(JSON.parse(screen.getByTestId('value').textContent!)).toEqual({ equipment: { level: 0 } })
  })

  it('adds orb options with documented defaults and Japanese labels', () => {
    const orbFields: MasterField[] = [
      { path: '/iconGlint', key: 'iconGlint', label: 'アイコンのエンチャントエフェクト', type: 'Boolean', required: false, default: 'false', description: 'アイコンだけを光らせる' },
      { path: '/orb/effect/chargeSaleValue', key: 'chargeSaleValue', label: '売却額分のGoldを消費', type: 'Boolean', required: false, default: 'false', description: '売却額分のGoldを消費する' },
      { path: '/orb/effect/rankBasis', key: 'rankBasis', label: '状態変化のランク判定基準', type: 'String', required: false, default: 'TARGET', description: 'CURRENT / TARGET', enum: ['CURRENT', 'TARGET'] },
    ]
    render(<Editable initial={{ orb: { effect: { type: 'TRANSCENDENCE' } } }} metadata={orbFields} />)
    fireEvent.change(screen.getByRole('combobox', { name: '/ 定義済みの項目を追加' }), { target: { value: 'iconGlint' } })
    fireEvent.change(screen.getByRole('combobox', { name: '/orb/effect 定義済みの項目を追加' }), { target: { value: 'chargeSaleValue' } })
    fireEvent.change(screen.getByRole('combobox', { name: '/orb/effect 定義済みの項目を追加' }), { target: { value: 'rankBasis' } })
    expect(JSON.parse(screen.getByTestId('value').textContent!)).toEqual({ iconGlint: false, orb: { effect: { type: 'TRANSCENDENCE', chargeSaleValue: false, rankBasis: 'TARGET' } } })
    expect(screen.getByText('状態変化のランク判定基準')).toBeInTheDocument()
    expect(screen.getByRole('combobox', { name: '/orb/effect/rankBasis' })).toHaveValue('"TARGET"')
    fireEvent.click(screen.getByRole('checkbox', { name: '/iconGlint' }))
    fireEvent.click(screen.getByRole('checkbox', { name: '/orb/effect/chargeSaleValue' }))
    expect(JSON.parse(screen.getByTestId('value').textContent!)).toEqual({ iconGlint: true, orb: { effect: { type: 'TRANSCENDENCE', chargeSaleValue: true, rankBasis: 'TARGET' } } })
  })

  it('keeps unknown RPC parameters while editing a Japanese field', () => {
    render(<Editable initial={{ name: '旧名', skills: [{ params: { AlienRPCParameter: 'kept', damage: 5 } }] }} />)
    fireEvent.change(screen.getByRole('textbox', { name: '/name' }), { target: { value: '新名' } })
    expect(JSON.parse(screen.getByTestId('value').textContent!)).toEqual({ name: '新名', skills: [{ params: { AlienRPCParameter: 'kept', damage: 5 } }] })
    expect(screen.getByText('表示名')).toBeInTheDocument()
    expect(screen.getByRole('textbox', { name: '/skills/0/params/AlienRPCParameter' })).toHaveValue('kept')
  })

  it('can add absent documented optional fields and arbitrary typed keys', () => {
    render(<Editable initial={{ equipment: {} }} />)
    openAdd('/equipment')
    fireEvent.change(screen.getByRole('combobox', { name: '/equipment 定義済みの項目を追加' }), { target: { value: 'level' } })
    expect(JSON.parse(screen.getByTestId('value').textContent!)).toEqual({ equipment: { level: 0 } })
    fireEvent.change(screen.getByRole('textbox', { name: '/equipment 新しいキー' }), { target: { value: 'custom' } })
    fireEvent.change(screen.getByRole('combobox', { name: '/equipment 追加する型' }), { target: { value: 'object' } })
    const customKey = screen.getByRole('textbox', { name: '/equipment 新しいキー' })
    fireEvent.click(customKey.parentElement!.querySelector('button')!)
    expect(JSON.parse(screen.getByTestId('value').textContent!)).toEqual({ equipment: { level: 0, custom: {} } })
  })

  it('reorders arrays without dropping custom parameters', () => {
    render(<Editable initial={{ skills: [{ params: { key: 'first' } }, { params: { key: 'second' } }] }} metadata={[]} />)
    fireEvent.click(screen.getByRole('button', { name: '/skills/1 上へ' }))
    expect(JSON.parse(screen.getByTestId('value').textContent!).skills).toEqual([{ params: { key: 'second' } }, { params: { key: 'first' } }])
  })

  it('reports invalid numbers without truncating fractions into integers', () => {
    const onInvalid = vi.fn(); const onChange = vi.fn()
    render(<MasterDataForm value={2} fields={fields} path="/equipment/level" onChange={onChange} onInvalid={onInvalid} />)
    fireEvent.change(screen.getByRole('textbox', { name: '/equipment/level' }), { target: { value: '1.5' } })
    expect(onChange).not.toHaveBeenCalled()
    expect(onInvalid).toHaveBeenLastCalledWith('/equipment/level', '有限の整数を入力してください。')
  })

  it('offers named references without restricting free text', () => {
    render(<MasterDataForm value="" path="/skillId" fields={[{ path: '/skillId', key: 'skillId', label: 'スキルID', type: 'string', required: true, description: '', reference: 'skill' }]} references={[{ path: 'skills/test.yml', pointer: '/id', value: 'slash', kind: 'skill', label: '斬撃' }]} onChange={vi.fn()} />)
    expect(screen.getByRole('combobox', { name: '/skillId' })).toHaveAttribute('list')
    expect(document.querySelector('option[value="slash"]')).toHaveTextContent('斬撃')
  })

  it('inherits only the reference kind for scalar entries of a documented list', () => {
    render(<MasterDataForm value={['']} path="/tags" fields={[{ path: '/tags', key: 'tags', label: 'タグ', type: 'List<String>', required: false, description: '', reference: 'tag' }]} references={[{ path: 'tags.yml', pointer: '/tags/0/id', value: 'weapon', kind: 'tag', label: '武器' }]} onChange={vi.fn()} />)
    expect(screen.getByRole('combobox', { name: '/tags/0' })).toHaveAttribute('list')
    expect(document.querySelector('option[value="weapon"]')).toHaveTextContent('武器')
  })

  it('shows an existing mismatched scalar without silently coercing its value', () => {
    render(<MasterDataForm value="bad" path="/equipment/level" fields={fields} onChange={vi.fn()} />)
    expect(screen.getByRole('textbox', { name: '/equipment/level' })).toHaveValue('bad')
  })
})

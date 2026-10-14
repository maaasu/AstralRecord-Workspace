import { fireEvent, render, screen } from '@testing-library/react'
import { useCallback, useState } from 'react'
import { describe, expect, it, vi } from 'vitest'
import { fieldForPath, masterDefault, MasterDataForm, normalizeMasterType } from './MasterDataForm'
import type { JsonValue } from '../types/editor'
import type { MasterField } from '../types/masterData'
import petSchema from '../../../../../../40_filebase/55.features.pet/schemas/pet-master.v1.schema.json'
import nodeSchema from '../../../../../../40_filebase/35.features.skilltree/schemas/node.v1.schema.json'

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
  it('renders committed pet experience coefficients and growth correction as editable numbers', () => {
    const onChange = vi.fn()
    const experience = { combatGain: { base: 50, perLevel: 25 }, gatheringGain: { base: 20, perLevel: 10 }, growthRateMin: .25 }
    render(<MasterDataForm value={experience} path="/experience" schema={petSchema.properties.experience}
      fields={[]} onChange={onChange} />)
    expect(screen.getByText('討伐経験値の基本値')).toBeInTheDocument()
    expect(screen.getByText('採集レベルごとの経験値係数')).toBeInTheDocument()
    expect(screen.getByText('個体の成長補正の下限')).toBeInTheDocument()
    expect(screen.getByRole('textbox', { name: '/experience/combatGain/base' })).toHaveValue('50')
    expect(screen.getByRole('textbox', { name: '/experience/gatheringGain/perLevel' })).toHaveValue('10')
    expect(screen.queryByRole('combobox', { name: '/experience/combatGain 定義の種類' })).not.toBeInTheDocument()
    fireEvent.change(screen.getByRole('textbox', { name: '/experience/growthRateMin' }), { target: { value: '.4' } })
    expect(onChange).toHaveBeenLastCalledWith({ ...experience, growthRateMin: .4 })
    expect(masterDefault(petSchema.properties.experience.properties.combatGain)).toEqual({ base: 50, perLevel: 25 })
  })
  it('previews names and multiline lore in color while retaining the editable source', () => {
    render(<Editable initial={{ name: '&6&l利用券', lore: ['§a説明\n&r通常'] }} />)
    expect(screen.getByRole('textbox', { name: '/name' })).toHaveValue('&6&l利用券')
    expect(screen.getByText('利用券')).toHaveStyle({ color: '#ffaa00', fontWeight: '700' })
    expect(screen.getByRole('textbox', { name: '/lore' })).toHaveValue('§a説明\n&r通常')
    expect(screen.getByText('説明')).toHaveStyle({ color: '#55ff55' })
    expect(JSON.parse(screen.getByTestId('value').textContent!).lore).toEqual(['§a説明\n&r通常'])
    fireEvent.change(screen.getByRole('textbox', { name: '/name' }), { target: { value: '&#12abef新名' } })
    expect(screen.getByText('新名')).toHaveStyle({ color: '#12abef' })
    expect(JSON.parse(screen.getByTestId('value').textContent!).name).toBe('&#12abef新名')
  })

  it('edits lore as one textarea and stores each line as a separate array element', () => {
    render(<Editable initial={{ lore: ['&7最初の行', '&a次の行'], description: '&6短い概要' }} />)
    const lore = screen.getByRole('textbox', { name: '/lore' })
    expect(lore).toHaveValue('&7最初の行\n&a次の行')
    expect(screen.getByText('1 行が YAML の配列 1 要素になります。', { exact: false })).toBeInTheDocument()
    expect(screen.getByText('最初の行')).toHaveStyle({ color: '#aaaaaa' })
    expect(screen.getByText('次の行')).toHaveStyle({ color: '#55ff55' })
    fireEvent.change(lore, { target: { value: '&c変更\n\n&b最後' } })
    expect(JSON.parse(screen.getByTestId('value').textContent!)).toEqual({ lore: ['&c変更', '', '&b最後'], description: '&6短い概要' })
    fireEvent.change(lore, { target: { value: '' } })
    expect(JSON.parse(screen.getByTestId('value').textContent!).lore).toEqual([])
    expect(screen.getByRole('textbox', { name: '/description' })).toHaveValue('&6短い概要')
    expect(screen.getByText('短い概要')).toHaveStyle({ color: '#ffaa00' })
    expect(screen.getByRole('textbox', { name: '/description' }).closest('.master-field-wide')).toBeInTheDocument()
    fireEvent.change(screen.getByRole('textbox', { name: '/description' }), { target: { value: '&6短い概要\n追記' } })
    expect(JSON.parse(screen.getByTestId('value').textContent!).description).toBe('&6短い概要\n追記')
  })

  it('keeps lore inline when other structured fields use navigation', () => {
    render(<MasterDataForm value={{ lore: ['&f表示行'], detail: { key: 'value' } }} fields={[]} onNavigate={vi.fn()} onChange={vi.fn()} />)
    expect(screen.getByRole('textbox', { name: '/lore' })).toHaveValue('&f表示行')
    expect(screen.getByRole('button', { name: '/detail を開く' })).toBeInTheDocument()
  })

  it('previews hex colors as they change', () => {
    render(<Editable initial={{ lore: ['&f表示行'], appearance: { color: '#243B5A' } }} metadata={[]} />)
    expect(screen.getByRole('textbox', { name: '/lore' })).toBeInTheDocument()
    const color = screen.getByRole('textbox', { name: '/appearance/color' })
    const swatch = screen.getByLabelText('/appearance/color 色プレビュー').querySelector('.master-color-swatch')
    expect(swatch).toHaveStyle({ backgroundColor: '#243B5A' })
    fireEvent.change(color, { target: { value: '#E85B67' } })
    expect(swatch).toHaveStyle({ backgroundColor: '#E85B67' })
    expect(JSON.parse(screen.getByTestId('value').textContent!).appearance.color).toBe('#E85B67')
  })

  it('shows colored reference candidates and selects their original ID', () => {
    const onChange = vi.fn()
    render(<MasterDataForm value="犬の卵" path="/eggItemId" fields={[{ path: '/eggItemId', key: 'eggItemId', label: '卵', type: 'string', required: false, description: '', reference: 'item' }]}
      references={[{ path: 'egg.yml', pointer: '/id', value: '81a00001', kind: 'item', label: '&6犬の&a卵' }]} onChange={onChange} />)
    expect(document.querySelector('option[value="81a00001"]')).toHaveTextContent('犬の卵')
    expect(screen.getByText('犬の')).toHaveStyle({ color: '#ffaa00' })
    expect(screen.getByText('卵', { selector: '.minecraft-text span' })).toHaveStyle({ color: '#55ff55' })
    fireEvent.click(screen.getByText('名前と色を確認して選択'))
    fireEvent.click(screen.getByRole('button', { name: '犬の卵81a00001' }))
    expect(onChange).toHaveBeenCalledWith('81a00001')
  })

  it('selects enchant master candidates with their required prefix in inputs and candidate buttons', () => {
    const onChange = vi.fn()
    render(<MasterDataForm value="" path="/orb/effect/enchantMasterId"
      fields={[{ path: '/orb/effect/enchantMasterId', key: 'enchantMasterId', label: 'エンチャント候補マスタ', type: 'String', required: false, description: '', reference: 'enchant' }]}
      references={[
        { path: '12.features.enchant/v1.enchant002.yml', pointer: '/id', value: 'enchant002', kind: 'enchant', label: '中級候補' },
        { path: '12.features.enchant/v1.enchant003.yml', pointer: '/id', value: 'enchant:enchant003', kind: 'enchant', label: '上級候補' },
      ]} onChange={onChange} />)
    expect(document.querySelector('option[value="enchant:enchant002"]')).toHaveTextContent('中級候補')
    expect(document.querySelector('option[value="enchant:enchant003"]')).toHaveTextContent('上級候補')
    expect(document.querySelector('option[value="enchant:enchant:enchant003"]')).toBeNull()
    fireEvent.change(screen.getByRole('combobox', { name: '/orb/effect/enchantMasterId' }), { target: { value: 'enchant:enchant003' } })
    expect(onChange).toHaveBeenLastCalledWith('enchant:enchant003')
    fireEvent.click(screen.getByText('名前と色を確認して選択'))
    fireEvent.click(screen.getByRole('button', { name: '中級候補enchant:enchant002' }))
    expect(onChange).toHaveBeenLastCalledWith('enchant:enchant002')
  })

  it('shows fishing reference candidates with their prefixes and displays the chance total', () => {
    const onChange = vi.fn()
    const references = [
      { path: '44.features.fishing.fish/v1.river_cod.yml', pointer: '/id', value: 'river_cod', kind: 'fishing_fish', label: '川のタラ' },
      { path: '10.features.item/10.material/v1.cod.yml', pointer: '/id', value: '10a00001', kind: 'item', label: 'タラ' },
      { path: '10.features.item/20.equipment/v1.rod.yml', pointer: '/id', value: '20a00001', kind: 'item', label: '釣り竿' },
    ]
    render(<><MasterDataForm value={{ ref: 'fishing_fish:river_cod' }} path="/levelRanges/0/fish/0/fishId" fields={[]} references={references} onChange={onChange} />
      <MasterDataForm value={{ ref: 'item:10a00001' }} path="/caughtItemId" fields={[]} references={references} onChange={onChange} />
      <MasterDataForm value={[{ fishId: { ref: 'fishing_fish:river_cod' }, chancePercent: 70 }, { fishId: { ref: 'fishing_fish:river_cod' }, chancePercent: 20 }]} path="/levelRanges/0/fish" fields={[]} onChange={onChange} /></>)
    expect(document.querySelector('option[value="fishing_fish:river_cod"]')).toHaveTextContent('川のタラ')
    expect(document.querySelector('option[value="item:10a00001"]')).toHaveTextContent('タラ')
    expect(document.querySelector('option[value="item:20a00001"]')).not.toBeInTheDocument()
    expect(screen.getByRole('status')).toHaveTextContent('90% / 100%')
  })

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
    expect(onInvalid).toHaveBeenLastCalledWith('/equipment/level', '有限の整数を入力してください。', '1.5')
  })

  it('selects referenced status effects and preserves values when selecting their existing branch', () => {
    const initial = { type: 'status', status: 'MAX_HEALTH', modifierType: 'FLAT', value: 25 }
    const onChange = vi.fn()
    render(<MasterDataForm value={initial} schema={nodeSchema.properties.effects.items} rootSchema={nodeSchema} path="/effects/0" fields={[]} onChange={onChange} />)
    const choice = screen.getByRole('combobox', { name: '/effects/0 定義の種類' })
    expect(choice).toHaveValue('1')
    expect(screen.getByRole('option', { name: 'ステータス効果' })).toHaveValue('1')
    expect(screen.getByRole('textbox', { name: '/effects/0/value' })).toHaveValue('25')
    fireEvent.change(choice, { target: { value: '1' } })
    expect(onChange).toHaveBeenLastCalledWith(initial)
    fireEvent.change(choice, { target: { value: '0' } })
    expect(onChange).toHaveBeenLastCalledWith({ type: 'skill', skillId: '' })
    expect(masterDefault(nodeSchema.properties.effects.items, 'object', nodeSchema)).toEqual({ type: 'skill', skillId: '' })
  })

  it('retains invalid number text and blocks array mutations until it is corrected', () => {
    function RestorableArray() {
      const [value, setValue] = useState<JsonValue>([0, 90])
      const [invalidValues, setInvalidValues] = useState<Record<string, string>>({})
      const onInvalid = useCallback((path: string, message: string | null, text?: string) => setInvalidValues(current => {
        if (message && current[path] === text || !message && !Object.hasOwn(current, path)) return current
        const next = { ...current }
        if (message) next[path] = text ?? ''
        else delete next[path]
        return next
      }), [])
      return <><MasterDataForm value={value} path="/experience/wave" fields={[]} schema={petSchema.properties.experience.properties.wave} onChange={setValue}
        onInvalid={onInvalid} invalidValues={invalidValues} navigationDisabled={Object.keys(invalidValues).length > 0} onNavigate={vi.fn()} /><output data-testid="array">{JSON.stringify(value)}</output></>
    }
    render(<RestorableArray />)
    const first = screen.getByRole('textbox', { name: '/experience/wave/0' })
    fireEvent.change(first, { target: { value: '-' } })
    expect(first).toHaveValue('-')
    expect(first).toHaveAttribute('aria-invalid', 'true')
    expect(screen.getByRole('button', { name: '/experience/wave/1 上へ' })).toBeDisabled()
    expect(screen.getByRole('button', { name: '/experience/wave/0 を削除' })).toBeDisabled()
    expect(screen.getAllByRole('button', { name: '複製' }).every(button => button.hasAttribute('disabled'))).toBe(true)
    fireEvent.click(screen.getByRole('button', { name: '/experience/wave/1 上へ' }))
    expect(first).toHaveValue('-')
    expect(screen.getByTestId('array')).toHaveTextContent('[0,90]')
    fireEvent.change(first, { target: { value: '15' } })
    expect(first).toHaveValue('15')
    expect(first).toHaveAttribute('aria-invalid', 'false')
    fireEvent.click(screen.getByRole('button', { name: '/experience/wave/1 上へ' }))
    expect(screen.getByTestId('array')).toHaveTextContent('[90,15]')
  })

  it('restores unfinished number text without overwriting the last valid value or clearing it on unmount', () => {
    const onInvalid = vi.fn(); const onChange = vi.fn()
    const { unmount } = render(<MasterDataForm value={2} fields={fields} path="/equipment/level" invalidValues={{ '/equipment/level': '-' }} onChange={onChange} onInvalid={onInvalid} />)
    expect(screen.getByRole('textbox', { name: '/equipment/level' })).toHaveValue('-')
    expect(onInvalid).toHaveBeenLastCalledWith('/equipment/level', '有限の整数を入力してください。', '-')
    expect(onChange).not.toHaveBeenCalled()
    onInvalid.mockClear()
    unmount()
    expect(onInvalid).not.toHaveBeenCalled()
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

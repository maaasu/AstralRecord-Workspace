import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createScenario, saveScenario } from '../data/balanceLab'
import type { BalanceCatalog } from '../data/balanceLab'
import { BalanceLab } from './BalanceLab'

const firstPath = '20.features.class/adventurer.yml'
const secondPath = '40.features.mob/boar.yml'
const catalog: BalanceCatalog = {
  masterHash: 'draft-master', sourceHash: 'draft-source', modelVersion: 'draft-model', sources: [], diagnostics: [],
  supportedSkillIds: [], skillCatalog: [],
  limits: { maxTrials: 500, maxSeconds: 600, maxBuilds: 4, maxTickWork: 2000000, wallSeconds: 30 },
  masters: [
    { path: firstPath, kind: 'class', content: { id: 'adventurer', name: '冒険者', baseStats: [] } },
    { path: secondPath, kind: 'mob', content: { id: 'midgard_grassboar', name: '草原イノシシ' } },
    { path: '10.features.item/sword.yml', kind: 'equipment', content: { id: '20a00001', name: '剣', equipment: {} } },
  ],
}
const editor = () => screen.getByRole('textbox', { name: '調整案JSON' })
const target = () => screen.getByRole('combobox', { name: '調整するマスター' })
const save = () => screen.getByRole('button', { name: 'ブラウザに保存' })
const row = (path: string) => within(screen.getByText(path, { selector: 'code' }).closest('.balance-override') as HTMLElement)
const choose = (path: string) => fireEvent.change(target(), { target: { value: path } })
const draft = (text = '{未完成の原稿') => fireEvent.change(editor(), { target: { value: text } })
const fileInput = () => screen.getByLabelText('条件JSONファイル') as HTMLInputElement
const importFile = (text: () => Promise<string>) => fireEvent.change(fileInput(), { target: { files: [{ text }] } })

async function setup() {
  const scenario = createScenario(catalog)
  scenario.seed = 44
  scenario.overrides = [{ path: firstPath, content: { id: 'adventurer', name: '調整済み冒険者' } }, { path: secondPath, content: { id: 'midgard_grassboar', name: '調整済みイノシシ' } }]
  saveScenario(scenario)
  render(<BalanceLab />)
  await screen.findByText('draft-master')
  return scenario
}

beforeEach(() => {
  localStorage.clear(); sessionStorage.clear()
  vi.spyOn(window, 'confirm').mockReturnValue(false)
  vi.stubGlobal('fetch', vi.fn(async () => ({ ok: true, json: async () => catalog })))
})
afterEach(() => { vi.restoreAllMocks(); vi.unstubAllGlobals(); localStorage.clear(); sessionStorage.clear() })

describe('BalanceLab uncommitted drafts', () => {
  it('keeps an invalid proposal on cancelled target changes and loads the accepted target', async () => {
    await setup(); choose(firstPath); draft()
    choose(secondPath)
    expect(target()).toHaveValue(firstPath)
    expect(editor()).toHaveValue('{未完成の原稿')
    expect(save()).toBeDisabled()
    vi.mocked(window.confirm).mockReturnValue(true)
    choose(secondPath)
    expect(target()).toHaveValue(secondPath)
    expect(editor()).toHaveValue(JSON.stringify({ id: 'midgard_grassboar', name: '調整済みイノシシ' }, null, 2))
    expect(save()).toBeEnabled()
  })

  it('protects the draft when opening another applied proposal and when resetting its input', async () => {
    await setup(); choose(firstPath); draft()
    fireEvent.click(row(secondPath).getByRole('button', { name: '編集' }))
    expect(target()).toHaveValue(firstPath)
    expect(editor()).toHaveValue('{未完成の原稿')
    fireEvent.click(screen.getByRole('button', { name: '入力を元に戻す' }))
    expect(editor()).toHaveValue('{未完成の原稿')
    vi.mocked(window.confirm).mockReturnValue(true)
    fireEvent.click(screen.getByRole('button', { name: '入力を元に戻す' }))
    expect(editor()).toHaveValue(JSON.stringify({ id: 'adventurer', name: '調整済み冒険者' }, null, 2))
    expect(save()).toBeEnabled()
    expect(window.confirm).toHaveBeenCalledTimes(3)
  })

  it('only clears the editor when deletion of its own proposal is accepted', async () => {
    await setup(); choose(firstPath); draft()
    fireEvent.click(row(secondPath).getByRole('button', { name: '削除' }))
    expect(window.confirm).not.toHaveBeenCalled()
    expect(editor()).toHaveValue('{未完成の原稿')
    fireEvent.click(row(firstPath).getByRole('button', { name: '削除' }))
    expect(row(firstPath).getByRole('button', { name: '編集' })).toBeInTheDocument()
    vi.mocked(window.confirm).mockReturnValue(true)
    fireEvent.click(row(firstPath).getByRole('button', { name: '削除' }))
    expect(screen.queryByText(firstPath, { selector: 'code' })).not.toBeInTheDocument()
    expect(target()).toHaveValue(''); expect(editor()).toHaveValue(''); expect(save()).toBeEnabled()
  })

  it.each(['基本ケースを読込', '保存分を復元'])('protects pending text and resets the editor after %s', async action => {
    await setup(); choose(firstPath); draft()
    fireEvent.change(screen.getByLabelText('乱数seed'), { target: { value: '777' } })
    fireEvent.click(screen.getByRole('button', { name: action }))
    expect(screen.getByLabelText('乱数seed')).toHaveValue(777)
    expect(editor()).toHaveValue('{未完成の原稿')
    vi.mocked(window.confirm).mockReturnValue(true)
    fireEvent.click(screen.getByRole('button', { name: action }))
    expect(screen.getByLabelText('乱数seed')).toHaveValue(action === '基本ケースを読込' ? 123 : 44)
    expect(target()).toHaveValue(''); expect(editor()).toHaveValue(''); expect(save()).toBeEnabled()
    if (action === '保存分を復元') {
      fireEvent.click(row(firstPath).getByRole('button', { name: '編集' }))
      expect(editor()).toHaveValue(JSON.stringify({ id: 'adventurer', name: '調整済み冒険者' }, null, 2))
    }
  })

  it.each([false, true])('keeps or replaces drafts after validating an import (accept=%s)', async accept => {
    const scenario = await setup(); choose(firstPath); draft()
    vi.mocked(window.confirm).mockReturnValue(accept)
    scenario.seed = 88; scenario.overrides = []
    importFile(async () => JSON.stringify({ scenario }))
    await waitFor(() => expect(window.confirm).toHaveBeenCalledTimes(1))
    expect(screen.getByLabelText('乱数seed')).toHaveValue(accept ? 88 : 44)
    expect(editor()).toHaveValue(accept ? '' : '{未完成の原稿')
    expect(fileInput()).toHaveValue('')
    if (accept) expect(save()).toBeEnabled()
    else expect(save()).toBeDisabled()
  })

  it('retains all drafts after malformed import and unlocks inputs for a retry', async () => {
    await setup(); choose(firstPath); draft()
    importFile(async () => '{bad file')
    await screen.findByText(/SyntaxError/)
    expect(window.confirm).not.toHaveBeenCalled()
    expect(editor()).toHaveValue('{未完成の原稿')
    expect(target()).toHaveValue(firstPath); expect(editor()).toBeEnabled()
    expect(fileInput()).toHaveValue('')
  })

  it('locks editing while reading a file and ignores another concurrent import', async () => {
    const scenario = await setup(); choose(firstPath); draft()
    let resolve!: (value: string) => void
    importFile(() => new Promise<string>(done => { resolve = done }))
    expect(editor()).toBeDisabled(); expect(target()).toBeDisabled()
    expect(screen.getByLabelText('乱数seed')).toBeDisabled()
    expect(screen.getByRole('button', { name: '保存分を復元' })).toBeDisabled()
    const anotherRead = vi.fn(async () => JSON.stringify(scenario))
    importFile(anotherRead)
    expect(anotherRead).not.toHaveBeenCalled()
    await act(async () => resolve(JSON.stringify(scenario)))
    expect(editor()).toHaveValue('{未完成の原稿'); expect(editor()).toBeEnabled()
  })

  it('resets unchanged-value detailed inputs only after confirmed scenario replacement', async () => {
    await setup()
    const label = 'ビルド1の基礎ステータスJSON'
    fireEvent.change(screen.getByLabelText(label), { target: { value: '{broken details' } })
    fireEvent.blur(screen.getByLabelText(label))
    fireEvent.click(screen.getByRole('button', { name: '保存分を復元' }))
    expect(screen.getByLabelText(label)).toHaveValue('{broken details')
    expect(save()).toBeDisabled()
    vi.mocked(window.confirm).mockReturnValue(true)
    fireEvent.click(screen.getByRole('button', { name: '保存分を復元' }))
    expect(screen.getByLabelText(label)).toHaveValue('{}')
    expect(save()).toBeEnabled()
  })
})

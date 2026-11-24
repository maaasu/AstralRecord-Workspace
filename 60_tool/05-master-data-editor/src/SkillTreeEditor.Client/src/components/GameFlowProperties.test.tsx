import { fireEvent, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { ReactNode } from 'react'
import { expect, it, vi } from 'vitest'
import { emptyGameFlow, type FlowSuggestion, type GameFlowEdge, type GameFlowNode } from '../data/gameFlow'
import type { MasterFileSummary } from '../types/masterData'
import { flowCopyCommands, flowReferenceCopyTarget, useGameFlowCopyMenu, type FlowCopyContextMenu } from './GameFlowContextMenu'
import { EdgeProperties, FlowProperties, NodeProperties, ShopItemsDialog } from './GameFlowProperties'
import { MasterEntityPicker } from './MasterEntityPicker'

const flowPath = '95.design.game_flow/v1.test.yml'
const itemPath = 'items/v1.apple.yml'
const classPath = 'classes/v1.swordman.yml'
const itemRef = 'item:apple'
const classRef = 'class:swordman'
const file = (path: string): MasterFileSummary => ({ path, category: '', format: 'yaml', size: 1, modifiedUtc: '', revision: '1' })
const resolved = {
  [itemRef]: { name: '&cリンゴ', file: file(itemPath) },
  [classRef]: { name: 'ソードマン', file: file(classPath) },
}
const resolve = (ref: string) => resolved[ref as keyof typeof resolved]
const className = (ref: string) => resolve(ref)?.name ?? ref
const classes = [{ ref: classRef, name: className(classRef) }]
const nodeDefaults = { x: 0, y: 0, classes: [], rest: {} }
const shop = { ...nodeDefaults, id: 'start', ref: 'shop:store' }
const apple = { ...nodeDefaults, id: 'apple_node', ref: itemRef }
const note = { ...nodeDefaults, id: 'finish', label: '次の段階へ' }
const flow = { ...emptyGameFlow('test', 'チュートリアル'), nodes: [shop, apple, note] }

function renderCopy(children: (onCopyContextMenu: FlowCopyContextMenu) => ReactNode) {
  const copy = vi.fn()
  function Harness() {
    const { onCopyContextMenu, copyMenu } = useGameFlowCopyMenu(copy)
    return <>{children(onCopyContextMenu)}{copyMenu}</>
  }
  return { ...render(<Harness />), copy }
}

const menu = () => screen.getByRole('menu')
const copyBy = (name: string) => fireEvent.click(within(menu()).getByRole('menuitem', { name }))

it('実ファイルのパスと選択していない入力値をコピーし、範囲選択・IME・Shift右クリック・空欄に標準メニューを残す', () => {
  const onChange = vi.fn()
  const description = '先にいずれかを選択\n  次の行の字下げを維持  '
  const { copy } = renderCopy(copyContextMenu => <FlowProperties
    flow={{ ...flow, description }} path={flowPath} className={className} onEditClasses={vi.fn()} onChange={onChange}
    copyContextMenu={copyContextMenu} resolve={resolve} />)
  const id = screen.getByRole('textbox', { name: 'ID' }) as HTMLInputElement
  id.focus()
  expect(fireEvent.contextMenu(id)).toBe(false)
  copyBy('ワークスペース相対パスをコピー')
  expect(copy).toHaveBeenLastCalledWith(`40_filebase/${flowPath}`)
  expect(id).toHaveFocus()

  const field = screen.getByRole('textbox', { name: '説明' }) as HTMLTextAreaElement
  field.focus()
  field.setSelectionRange(0, 4)
  expect(fireEvent.contextMenu(field)).toBe(true)
  expect(screen.queryByRole('menu')).not.toBeInTheDocument()
  expect(field.selectionEnd).toBe(4)
  field.setSelectionRange(4, 4)
  fireEvent.compositionStart(field)
  expect(fireEvent.contextMenu(field)).toBe(true)
  fireEvent.compositionEnd(field)
  expect(fireEvent.contextMenu(field, { shiftKey: true })).toBe(true)
  expect(fireEvent.contextMenu(field)).toBe(false)
  copyBy('説明をコピー')
  expect(copy).toHaveBeenLastCalledWith(description)
  expect(field).toHaveFocus()
  fireEvent.change(field, { target: { value: '編集後' } })
  expect(onChange).toHaveBeenCalledWith({ description: '編集後' })

  fireEvent.contextMenu(screen.getByText('表示名', { exact: true }))
  copyBy('表示名をコピー')
  expect(copy).toHaveBeenLastCalledWith('チュートリアル')
  expect(onChange).toHaveBeenCalledTimes(1)
})

it('未入力の項目・見出し・削除ボタンにはコピー項目を出さず、付箋と操作を区別する', () => {
  const onDelete = vi.fn()
  const inspector = (node: GameFlowNode, kind: string, copyContextMenu: FlowCopyContextMenu) => <NodeProperties
    node={node} kind={kind} classes={[]} context={null} flow={flow} resolveName={() => undefined}
    onChange={vi.fn()} onPick={vi.fn()} onOpenMaster={vi.fn()} onShopItems={vi.fn()} onDelete={onDelete}
    suggestions={null} resolve={resolve} prefixes={{}} onAccept={vi.fn()} copyContextMenu={copyContextMenu} />
  const { unmount } = renderCopy(copyContextMenu => inspector(note, 'note', copyContextMenu))
  expect(screen.getByRole('heading', { name: '付箋' })).toBeVisible()
  expect(screen.getByRole('textbox', { name: 'メモ' })).toHaveValue('次の段階へ')
  expect(fireEvent.contextMenu(screen.getByRole('heading', { name: '付箋' }))).toBe(true)
  expect(fireEvent.contextMenu(screen.getByRole('textbox', { name: '補足' }))).toBe(true)
  const remove = screen.getByRole('button', { name: 'ノードを削除' })
  expect(fireEvent.contextMenu(remove)).toBe(true)
  expect(screen.queryByRole('menu')).not.toBeInTheDocument()
  fireEvent.click(remove)
  expect(onDelete).toHaveBeenCalledOnce()
  unmount()
  renderCopy(copyContextMenu => inspector(note, 'action', copyContextMenu))
  expect(screen.getByRole('heading', { name: '操作' })).toBeVisible()
})

it('未解決の参照にパスを付けず、配置候補と職業には実ファイルのパスを付ける', async () => {
  const onAccept = vi.fn()
  const onChange = vi.fn()
  const unresolved = { ...nodeDefaults, id: 'missing_item', ref: 'item:missing' }
  const suggestion: FlowSuggestion = { direction: 'next', ref: itemRef, label: '購入', existingNodeId: apple.id }
  const { copy } = renderCopy(copyContextMenu => <NodeProperties
    node={unresolved} kind="item" classes={classes} context={null} flow={flow} resolveName={ref => resolve(ref)?.name}
    onChange={onChange} onPick={vi.fn()} onOpenMaster={vi.fn()} onShopItems={vi.fn()} onDelete={vi.fn()}
    suggestions={{ next: [suggestion], previous: [] }} resolve={resolve} prefixes={{}} onAccept={onAccept} copyContextMenu={copyContextMenu} />)
  fireEvent.contextMenu(screen.getByText('参照先が見つかりません'))
  expect(within(menu()).getAllByRole('menuitem').map(button => button.textContent)).toEqual(['参照をコピー', 'マスターIDをコピー'])
  copyBy('参照をコピー')
  expect(copy).toHaveBeenLastCalledWith('item:missing')

  const candidate = screen.getByRole('button', { name: /リンゴ/ })
  fireEvent.contextMenu(candidate)
  copyBy('表示名をコピー')
  expect(copy).toHaveBeenLastCalledWith('リンゴ')
  fireEvent.contextMenu(candidate)
  copyBy('ノードIDをコピー')
  expect(copy).toHaveBeenLastCalledWith(apple.id)
  expect(onAccept).not.toHaveBeenCalled()
  fireEvent.click(candidate)
  expect(onAccept).toHaveBeenCalledWith([suggestion])

  const job = screen.getByRole('checkbox', { name: 'ソードマン' })
  fireEvent.contextMenu(job)
  copyBy('ワークスペース相対パスをコピー')
  expect(copy).toHaveBeenLastCalledWith(`40_filebase/${classPath}`)
  expect(job).not.toBeChecked()
  await userEvent.click(job)
  expect(onChange).toHaveBeenCalledWith({ classes: [classRef] })
})

it('矢印の起点・終点とラベルをコピーする', () => {
  const edge: GameFlowEdge = { from: apple.id, to: note.id, label: '購入', classes: [], rest: {} }
  const nodeName = (node: GameFlowNode) => node.label ?? (node.ref && resolve(node.ref)?.name) ?? node.id
  const { copy } = renderCopy(copyContextMenu => <EdgeProperties
    edge={edge} flow={flow} nodeName={nodeName} classes={classes} onChange={vi.fn()} onDelete={vi.fn()}
    copyContextMenu={copyContextMenu} resolve={resolve} />)
  fireEvent.contextMenu(screen.getByText('リンゴ'))
  copyBy('ワークスペース相対パスをコピー')
  expect(copy).toHaveBeenLastCalledWith(`40_filebase/${itemPath}`)
  fireEvent.contextMenu(screen.getByText('次の段階へ'))
  copyBy('矢印の向きをコピー')
  expect(copy).toHaveBeenLastCalledWith('リンゴ → 次の段階へ')
  fireEvent.contextMenu(screen.getByRole('combobox', { name: 'ラベル' }))
  copyBy('ラベルをコピー')
  expect(copy).toHaveBeenLastCalledWith('購入')
})

it('商品ダイアログでメニューだけを閉じ、商品・職業のコピーと商品の追加ができる', async () => {
  const user = userEvent.setup()
  const onClose = vi.fn()
  const onAdd = vi.fn()
  const { copy } = renderCopy(copyContextMenu => <ShopItemsDialog
    document={document} flow={flow} shopNodeId={shop.id} context={{
      shops: [{ id: 'store', path: 'shops/v1.store.yml', items: ['apple'] }], itemClasses: { apple: ['swordman'] },
    }}
    resolve={id => resolve('item:' + id)} resolveClass={resolve} className={className} copyContextMenu={copyContextMenu}
    isItem={(ref, id) => ref === 'item:' + id} onAdd={onAdd} onClose={onClose} />)
  const dialog = within(screen.getByRole('dialog'))
  const row = dialog.getByRole('checkbox')
  row.focus()
  fireEvent.contextMenu(row)
  expect(dialog.getByRole('menuitem', { name: '表示名をコピー' })).toHaveFocus()
  await user.keyboard('{Escape}')
  expect(onClose).not.toHaveBeenCalled()
  expect(screen.queryByRole('menu')).not.toBeInTheDocument()
  expect(row).toHaveFocus()
  fireEvent.contextMenu(row)
  copyBy('参照をコピー')
  expect(copy).toHaveBeenLastCalledWith(itemRef)
  fireEvent.contextMenu(dialog.getByText('ソードマン'))
  copyBy('マスターIDをコピー')
  expect(copy).toHaveBeenLastCalledWith('swordman')
  await user.click(row)
  await user.click(dialog.getByRole('button', { name: '1件を追加' }))
  expect(onAdd).toHaveBeenCalledWith(['apple'])
})

it('マスター選択でコピーしても候補を選択せず、Tabでメニューを閉じると候補にフォーカスを戻す', async () => {
  const user = userEvent.setup()
  const onSelect = vi.fn()
  const onClose = vi.fn()
  const { copy } = renderCopy(onCopyContextMenu => <MasterEntityPicker
    title="アイテムを選択" choices={[{ id: 'apple', name: 'リンゴ' }]} onSelect={onSelect} onClose={onClose}
    onChoiceContextMenu={(choice, event) => {
      const ref = 'item:' + choice.id
      onCopyContextMenu(flowReferenceCopyTarget(ref, resolve(ref)), '候補')(event)
    }} />)
  const candidate = within(screen.getByRole('dialog')).getByRole('button', { name: /リンゴ/ })
  candidate.focus()
  fireEvent.contextMenu(candidate)
  await user.keyboard('{Tab}')
  expect(onClose).not.toHaveBeenCalled()
  expect(screen.queryByRole('menu')).not.toBeInTheDocument()
  expect(candidate).toHaveFocus()
  fireEvent.contextMenu(candidate)
  copyBy('マスターIDをコピー')
  expect(copy).toHaveBeenLastCalledWith('apple')
  expect(onSelect).not.toHaveBeenCalled()
  await user.click(candidate)
  expect(onSelect).toHaveBeenCalledWith('apple')
})

it('コピー値が空の場合と相対パスを作れない場合は項目を作らない', () => {
  const copy = vi.fn()
  expect(flowCopyCommands({ name: '&6 ', ref: '', path: '', values: [{ label: '補足をコピー', value: ' ' }] }, copy)).toEqual([])
  for (const path of ['../outside.yml', '/absolute.yml', 'C:\\outside.yml', '   ']) {
    expect(flowCopyCommands({ path }, copy)).toEqual([])
  }
  flowCopyCommands({ path: '40_filebase\\items\\v1.apple.yml' }, copy)[0]?.run()
  expect(copy).toHaveBeenCalledWith(`40_filebase/${itemPath}`)
})

it('別ウィンドウの値に対してそのウィンドウでメニューを開き、選択した本文の標準メニューを残す', () => {
  const iframe = document.createElement('iframe')
  document.body.append(iframe)
  let unmount: (() => void) | undefined
  try {
    const ownerDocument = iframe.contentDocument!
    const container = ownerDocument.body.appendChild(ownerDocument.createElement('div'))
    const copy = vi.fn()
    function TextView() {
      const { onCopyContextMenu, copyMenu } = useGameFlowCopyMenu(copy)
      return <><pre onContextMenu={onCopyContextMenu({ values: [{ label: '本文をコピー', value: '本文の内容' }] }, '本文')}>本文の内容</pre>{copyMenu}</>
    }
    const view = render(<TextView />, { container })
    unmount = view.unmount
    const text = within(container).getByText('本文の内容')
    const selection = ownerDocument.getSelection()!
    const range = ownerDocument.createRange()
    range.setStart(text.firstChild!, 0)
    range.setEnd(text.firstChild!, 2)
    selection.addRange(range)
    expect(fireEvent.contextMenu(text)).toBe(true)
    expect(selection.toString()).toBe('本文')
    selection.removeAllRanges()

    expect(fireEvent.contextMenu(text)).toBe(false)
    expect(screen.queryByRole('menu')).not.toBeInTheDocument()
    fireEvent.click(within(ownerDocument.body).getByRole('menuitem', { name: '本文をコピー' }))
    expect(copy).toHaveBeenCalledWith('本文の内容')
  } finally {
    unmount?.()
    iframe.remove()
  }
})

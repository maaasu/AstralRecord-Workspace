import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../api/editorApi'
import { masterDataApi } from '../api/masterDataApi'
import type { JsonObject } from '../types/editor'
import type { MasterDocument, MasterFileSummary } from '../types/masterData'
import { ChallengeLocationEditor } from './ChallengeLocationEditor'

vi.mock('../api/masterDataApi', () => ({
  masterDataApi: { files: vi.fn(), file: vi.fn(), render: vi.fn(), validate: vi.fn(), save: vi.fn() },
}))

const bossPath = '40.features.mob/boss/v1.boss.yml'
const dungeonPath = '65.features.dungeon/v1.dungeon.yml'
const boss: JsonObject = {
  id: 'boss', challenge: { entryLocation: { worldId: 'old_bukkit_world', x: 1, y: 64, z: 2 }, arena: { worldId: 'arena' } },
}
const dungeon: JsonObject = {
  id: 'dungeon', entry: { worldRef: 'world:other_world', x: 4, y: 70, z: 6, yaw: 25, radius: 2 },
  encounter: { bossMobId: 'mob:boss' },
}
const file = (path: string, content: JsonObject, revision = 'original'): MasterDocument => ({
  path, format: 'yaml', content, revision, raw: '# 元の YAML', issues: [],
})
const summary = (path: string, id: string): MasterFileSummary => ({
  path, id, name: id, category: '', format: 'yaml', size: 0, modifiedUtc: '', revision: 'original',
})
const callbacks = () => ({ onPickingChange: vi.fn(), onDirtyChange: vi.fn(), onBusyChange: vi.fn(), onPositionChange: vi.fn() })

async function chooseMaster(path: string) {
  await screen.findByRole('option', { name: path === bossPath ? 'boss (boss)' : 'dungeon (dungeon)' })
  fireEvent.change(screen.getByRole('combobox', { name: '受付地点のマスター' }), { target: { value: path } })
}

beforeEach(() => {
  vi.resetAllMocks()
  vi.mocked(masterDataApi.files).mockImplementation(async category => {
    if (category === '40.features.mob/boss') return [summary(bossPath, 'boss')]
    if (category === '65.features.dungeon') return [summary(dungeonPath, 'dungeon')]
    return [summary('60.features.world/v1.current.yml', 'current_world'), summary('60.features.world/v1.other.yml', 'other_world')]
  })
  vi.mocked(masterDataApi.file).mockImplementation(async path => file(path, path === bossPath ? boss : dungeon))
  vi.mocked(masterDataApi.render).mockResolvedValue({ raw: '変更後の YAML', commentsPreserved: true, warnings: [] })
  vi.mocked(masterDataApi.validate).mockResolvedValue({ isValid: true, issues: [] })
})

describe('チャレンジ受付地点の編集', () => {
  it('既存ボスの Bukkit ワールド指定と他の設定を維持し、元原稿と revision を使って保存する', async () => {
    const props = callbacks()
    const changedBoss = { ...boss, challenge: { entryLocation: { worldId: 'old_bukkit_world', x: 2.5, y: 64, z: 2 }, arena: { worldId: 'arena' } } }
    vi.mocked(masterDataApi.save).mockResolvedValue(file(bossPath, changedBoss, 'saved'))
    render(<ChallengeLocationEditor worldId="current_world" point={null} {...props} />)

    await chooseMaster(bossPath)
    expect(await screen.findByRole('combobox', { name: '受付地点のワールド' })).toHaveValue('old_bukkit_world')
    fireEvent.change(screen.getByRole('textbox', { name: 'X' }), { target: { value: '2.5' } })
    expect(props.onDirtyChange).toHaveBeenLastCalledWith(true)
    fireEvent.click(screen.getByRole('button', { name: '受付地点を保存' }))

    expect(await screen.findByText('受付地点を Filebase に保存しました。')).toBeInTheDocument()
    expect(masterDataApi.render).toHaveBeenCalledWith(bossPath, changedBoss, '# 元の YAML')
    expect(masterDataApi.validate).toHaveBeenCalledWith(bossPath, '変更後の YAML')
    expect(masterDataApi.save).toHaveBeenCalledWith(bossPath, '変更後の YAML', 'original')
    await waitFor(() => {
      expect(props.onDirtyChange).toHaveBeenLastCalledWith(false)
      expect(props.onBusyChange).toHaveBeenLastCalledWith(false)
    })
  })

  it('新しい地図選択だけを反映し、高さが取得できなければ保存前に Y 入力を求める', async () => {
    const props = callbacks()
    const earlier = { key: 1, x: 90, y: 91, z: 92 }
    const { rerender } = render(<ChallengeLocationEditor worldId="current_world" worldName="表示中の大陸" point={earlier} {...props} />)
    await chooseMaster(dungeonPath)
    expect(await screen.findByRole('textbox', { name: 'X' })).toHaveValue('4')
    expect(props.onPositionChange).toHaveBeenLastCalledWith(null)
    const mapPick = screen.getByRole('button', { name: '地図で受付地点を選択' })
    expect(mapPick).toBeDisabled()
    expect(screen.getByText(/受付先の地図に切り替えるか/)).toHaveTextContent('表示中の大陸')
    const world = screen.getByRole('combobox', { name: '受付地点のワールド' })
    expect(world).toHaveValue('world:other_world')
    fireEvent.change(world, { target: { value: 'world:current_world' } })
    expect(mapPick).toBeEnabled()
    fireEvent.click(mapPick)
    expect(props.onPickingChange).toHaveBeenLastCalledWith(true)
    expect(screen.getByRole('textbox', { name: 'X' })).toHaveValue('4')

    rerender(<ChallengeLocationEditor worldId="current_world" worldName="表示中の大陸" point={{ key: 2, x: 18, y: null, z: -9 }} {...props} />)
    expect(await screen.findByText(/この地点は地図に高さのデータがありません/)).toBeInTheDocument()
    expect(props.onPickingChange).toHaveBeenLastCalledWith(false)
    expect(world).toHaveValue('world:current_world')
    expect(screen.getByRole('textbox', { name: 'X' })).toHaveValue('18')
    const height = screen.getByRole('textbox', { name: '高さ (Y)' })
    expect(height).toHaveValue('')
    expect(height).toBeRequired()
    expect(height).toHaveAccessibleDescription(/ゲーム内で足元の Y 座標を確認/)
    expect(screen.getByRole('button', { name: '受付地点を保存' })).toBeDisabled()
    fireEvent.change(height, { target: { value: '71' } })
    expect(props.onPositionChange).toHaveBeenLastCalledWith({ x: 18, y: 71, z: -9 })

    const changedDungeon = { ...dungeon, entry: { worldRef: 'world:current_world', x: 18, y: 71, z: -9, yaw: 25, radius: 2 } }
    vi.mocked(masterDataApi.save).mockResolvedValue(file(dungeonPath, changedDungeon, 'saved'))
    fireEvent.click(screen.getByRole('button', { name: '受付地点を保存' }))
    expect(await screen.findByText('受付地点を Filebase に保存しました。')).toBeInTheDocument()
    expect(masterDataApi.render).toHaveBeenCalledWith(dungeonPath, changedDungeon, '# 元の YAML')
  })

  it('地図で計測した高さを直せて、変更を取り消すと元の座標に戻る', async () => {
    const props = callbacks()
    const { rerender } = render(<ChallengeLocationEditor worldId="other_world" point={null} {...props} />)
    await chooseMaster(dungeonPath)
    const height = await screen.findByRole('textbox', { name: '高さ (Y)' })
    expect(height).toHaveValue('70')
    fireEvent.click(screen.getByRole('button', { name: '地図で受付地点を選択' }))

    rerender(<ChallengeLocationEditor worldId="other_world" point={{ key: 1, x: 18, y: 65, z: -9 }} {...props} />)
    expect(height).toHaveValue('65')
    expect(height).toHaveAccessibleDescription(/高さは目安/)
    expect(props.onPositionChange).toHaveBeenLastCalledWith({ x: 18, y: 65, z: -9 })

    fireEvent.change(height, { target: { value: '68.5' } })
    expect(height).not.toHaveAccessibleDescription(/高さは目安/)
    expect(props.onPositionChange).toHaveBeenLastCalledWith({ x: 18, y: 68.5, z: -9 })

    fireEvent.click(screen.getByRole('button', { name: '変更を取り消す' }))
    expect(height).toHaveValue('70')
    expect(height).toHaveFocus()
    expect(props.onPositionChange).toHaveBeenLastCalledWith({ x: 4, y: 70, z: 6 })
    expect(props.onDirtyChange).toHaveBeenLastCalledWith(false)
    expect(masterDataApi.save).not.toHaveBeenCalled()
  })

  it('古いマスターの読込結果を無視する', async () => {
    let finish!: (value: MasterDocument) => void
    vi.mocked(masterDataApi.file).mockReturnValueOnce(new Promise(resolve => { finish = resolve }))
    render(<ChallengeLocationEditor worldId="current_world" point={null} {...callbacks()} />)
    await chooseMaster(bossPath)
    await chooseMaster(dungeonPath)
    expect(await screen.findByRole('textbox', { name: 'X' })).toHaveValue('4')
    await act(async () => { finish(file(bossPath, boss)) })
    expect(screen.getByRole('textbox', { name: 'X' })).toHaveValue('4')
  })

  it('モードを閉じると地点の選択だけを解除し、下書きを保持する', async () => {
    const props = callbacks()
    const { rerender } = render(<ChallengeLocationEditor worldId="other_world" point={null} {...props} />)
    await chooseMaster(dungeonPath)
    const x = await screen.findByRole('textbox', { name: 'X' })
    fireEvent.change(x, { target: { value: '29' } })
    fireEvent.click(screen.getByRole('button', { name: '地図で受付地点を選択' }))
    rerender(<ChallengeLocationEditor worldId="other_world" point={null} {...props} active={false} />)
    expect(props.onPickingChange).toHaveBeenLastCalledWith(false)
    expect(props.onDirtyChange).toHaveBeenLastCalledWith(true)

    rerender(<ChallengeLocationEditor worldId="other_world" point={{ key: 1, x: 10, y: 64, z: 12 }} {...props} />)
    expect(screen.getByRole('textbox', { name: 'X' })).toHaveValue('29')
    expect(props.onPositionChange).toHaveBeenLastCalledWith({ x: 29, y: 70, z: 6 })
  })

  it('競合時は下書きを保持し、最新 revision の再読込前の上書きを防ぐ', async () => {
    const props = callbacks()
    vi.mocked(masterDataApi.save).mockRejectedValue(new ApiError('conflict', 409))
    render(<ChallengeLocationEditor worldId="current_world" point={null} {...props} />)
    await chooseMaster(bossPath)
    const x = await screen.findByRole('textbox', { name: 'X' })
    fireEvent.change(x, { target: { value: '15' } })
    const save = screen.getByRole('button', { name: '受付地点を保存' })
    fireEvent.click(save)
    expect(await screen.findByRole('alert')).toHaveTextContent('同じマスターが別の操作で変更されました。')
    expect(x).toHaveValue('15')
    expect(save).toBeDisabled()
    expect(props.onDirtyChange).toHaveBeenLastCalledWith(true)
  })

  it('YAML 変換の警告時は差分を確認でき、検証失敗後も入力を保持する', async () => {
    vi.mocked(masterDataApi.render).mockResolvedValue({ raw: '変更後の YAML', commentsPreserved: false, warnings: ['コメントの位置が変わります。'] })
    vi.mocked(masterDataApi.validate).mockResolvedValue({ isValid: false, issues: [{ code: 'TEST', message: '検証内容', severity: 'error', path: '/challenge/entryLocation' }] })
    render(<ChallengeLocationEditor worldId="current_world" point={null} {...callbacks()} />)
    await chooseMaster(bossPath)
    fireEvent.click(await screen.findByText('詳細設定'))
    const yaw = screen.getByRole('textbox', { name: '左右の向き (Yaw)' })
    fireEvent.change(yaw, { target: { value: '90' } })
    const save = screen.getByRole('button', { name: '受付地点を保存' })
    fireEvent.click(save)
    expect(await screen.findByRole('dialog', { name: '保存前の差分' })).toBeInTheDocument()
    expect(masterDataApi.validate).not.toHaveBeenCalled()
    fireEvent.click(screen.getByRole('button', { name: '閉じる' }))
    await waitFor(() => expect(save).toBeEnabled())
    fireEvent.click(save)
    expect(await screen.findByText(/検証内容/)).toBeInTheDocument()
    expect(masterDataApi.save).not.toHaveBeenCalled()
    expect(yaw).toHaveValue('90')
  })
})

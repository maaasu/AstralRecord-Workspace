import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { SettingsDialog } from './SettingsDialog'
import type { PluginSkillTreeSettings, StructureDocument } from '../types/editor'

const settings: PluginSkillTreeSettings = { worldName: 'world', structureId: 'main', centerX: 1, centerY: 2, centerZ: 3 }
const structures = [{ structureId: 'main', name: 'メイン' }] as StructureDocument[]

describe('表示設定の座標', () => {
  it('不正な数値の編集中でも閉じる前に破棄を確認する', () => {
    const onCancel = vi.fn()
    const onDirtyChange = vi.fn()
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(false)
    try {
      render(<SettingsDialog settings={settings} structures={structures} saving={false} onSave={vi.fn()} onCancel={onCancel} onDirtyChange={onDirtyChange} />)
      fireEvent.change(screen.getByLabelText('X'), { target: { value: '-' } })
      fireEvent.keyDown(screen.getByLabelText('X'), { key: 'Escape' })
      expect(confirm).toHaveBeenCalledWith('未保存の表示設定を破棄して閉じますか？')
      expect(onCancel).not.toHaveBeenCalled()
      expect(screen.getByLabelText('X')).toHaveValue('-')
      expect(onDirtyChange).toHaveBeenLastCalledWith(true)
    } finally { confirm.mockRestore() }
  })
  it.each(['', '1.5', '2147483648', 'abc'])('不正な座標 %s は自動変換せず保持し、保存を止める', (value) => {
    const onSave = vi.fn()
    render(<SettingsDialog settings={settings} structures={structures} saving={false} onSave={onSave} onCancel={vi.fn()} />)
    const input = screen.getByLabelText('X')
    fireEvent.change(input, { target: { value } })
    expect(input).toHaveValue(value)
    expect(screen.getByRole('button', { name: 'config.ymlへ保存' })).toBeDisabled()
    expect(onSave).not.toHaveBeenCalled()
    fireEvent.change(input, { target: { value: '-10' } })
    fireEvent.click(screen.getByRole('button', { name: 'config.ymlへ保存' }))
    expect(onSave).toHaveBeenCalledWith({ ...settings, centerX: -10 })
  })

  it('競合エラー表示後も編集中の値を保持する', () => {
    const props = { settings, structures, saving: false, onSave: vi.fn(), onCancel: vi.fn() }
    const { rerender } = render(<SettingsDialog {...props} />)
    fireEvent.change(screen.getByLabelText('ワールド名'), { target: { value: 'draft' } })
    rerender(<SettingsDialog {...props} errorMessage="ファイルが変更されています。" />)
    expect(screen.getByRole('alert')).toHaveTextContent('ファイルが変更されています。')
    expect(screen.getByLabelText('ワールド名')).toHaveValue('draft')
  })
})

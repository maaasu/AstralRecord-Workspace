import { useEffect, useState } from 'react'
import type { PluginSkillTreeSettings, StructureDocument } from '../types/editor'
import { Modal } from './Modal'

interface SettingsDialogProps {
  settings: PluginSkillTreeSettings
  structures: StructureDocument[]
  saving: boolean
  errorMessage?: string
  onDirtyChange?: (dirty: boolean) => void
  onSave: (settings: PluginSkillTreeSettings) => Promise<void>
  onCancel: () => void
}

export function SettingsDialog({ settings, structures, saving, errorMessage, onDirtyChange, onSave, onCancel }: SettingsDialogProps) {
  const [draft, setDraft] = useState(settings)
  const [coordinates, setCoordinates] = useState(() => ({ centerX: String(settings.centerX), centerY: String(settings.centerY), centerZ: String(settings.centerZ) }))
  useEffect(() => {
    setDraft(settings)
    setCoordinates({ centerX: String(settings.centerX), centerY: String(settings.centerY), centerZ: String(settings.centerZ) })
  }, [settings])

  const validCoordinate = (value: string) => /^[+-]?[0-9]+$/.test(value)
    && Number.isSafeInteger(Number(value)) && Number(value) >= -2147483648 && Number(value) <= 2147483647
  const coordinatesValid = Object.values(coordinates).every(validCoordinate)
  const dirty = JSON.stringify(draft) !== JSON.stringify(settings)
    || coordinates.centerX !== String(settings.centerX) || coordinates.centerY !== String(settings.centerY) || coordinates.centerZ !== String(settings.centerZ)
  useEffect(() => { onDirtyChange?.(dirty) }, [dirty, onDirtyChange])
  useEffect(() => () => onDirtyChange?.(false), [onDirtyChange])
  const requestClose = () => {
    if (dirty && !window.confirm('未保存の表示設定を破棄して閉じますか？')) return
    onCancel()
  }

  const number = (key: 'centerX' | 'centerY' | 'centerZ', value: string) => {
    setCoordinates((current) => ({ ...current, [key]: value }))
    if (validCoordinate(value)) setDraft((current) => ({ ...current, [key]: Number(value) }))
  }

  return (
    <Modal ariaLabel="プラグイン設定" className="settings-dialog" onClose={requestClose} closeDisabled={saving}>
        <header className="modal-header">
          <div>
            <span className="eyebrow">プラグイン設定</span>
            <h2>スキルツリー表示設定</h2>
            <p className="muted">config.yml の skilltree ブロックだけを更新します。</p>
          </div>
          <button className="icon-button" onClick={requestClose} disabled={saving} aria-label="閉じる">×</button>
        </header>
        <div className="modal-body form-grid">
          {errorMessage && <p className="error-message" role="alert">{errorMessage}</p>}
          <label>ワールド名<input value={draft.worldName} disabled={saving} onChange={(event) => setDraft({ ...draft, worldName: event.target.value })} /></label>
          <label>構造ID
            <select value={draft.structureId} disabled={saving} onChange={(event) => setDraft({ ...draft, structureId: event.target.value })}>
              <option value="">選択してください</option>
              {structures.map((structure) => <option key={structure.structureId} value={structure.structureId}>{structure.name} ({structure.structureId})</option>)}
            </select>
          </label>
          <fieldset disabled={saving}>
            <legend>中心座標</legend>
            {(['centerX', 'centerY', 'centerZ'] as const).map((key) => (
              <label key={key}>{key.slice(-1)}<input inputMode="numeric" value={coordinates[key]} aria-invalid={!validCoordinate(coordinates[key])} onChange={(event) => number(key, event.target.value)} /></label>
            ))}
            {!coordinatesValid && <p className="error-message" role="alert">中心座標は -2147483648～2147483647 の整数で入力してください。入力内容は自動変換しません。</p>}
          </fieldset>
        </div>
        <footer className="modal-footer">
          <span className="spacer" />
          <button className="button subtle" onClick={requestClose} disabled={saving}>キャンセル</button>
          <button className="button primary" onClick={() => void onSave(draft)} disabled={saving || !coordinatesValid || !draft.worldName || !draft.structureId}>
            {saving ? '保存中…' : 'config.ymlへ保存'}
          </button>
        </footer>
    </Modal>
  )
}

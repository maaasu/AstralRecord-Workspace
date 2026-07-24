import { useEffect, useState } from 'react'
import type { PluginSkillTreeSettings, StructureDocument } from '../types/editor'

interface SettingsDialogProps {
  settings: PluginSkillTreeSettings
  structures: StructureDocument[]
  saving: boolean
  onSave: (settings: PluginSkillTreeSettings) => Promise<void>
  onCancel: () => void
}

export function SettingsDialog({ settings, structures, saving, onSave, onCancel }: SettingsDialogProps) {
  const [draft, setDraft] = useState(settings)
  useEffect(() => setDraft(settings), [settings])

  const number = (key: 'centerX' | 'centerY' | 'centerZ', value: string) => {
    const parsed = Number(value)
    const rounded = Number.isFinite(parsed) ? Math.round(parsed) : 0
    setDraft((current) => ({
      ...current,
      [key]: Math.max(-2147483648, Math.min(2147483647, rounded)),
    }))
  }

  return (
    <div className="modal-backdrop">
      <section className="modal settings-dialog" role="dialog" aria-modal="true" aria-label="プラグイン設定">
        <header className="modal-header">
          <div>
            <span className="eyebrow">PLUGIN CONFIG</span>
            <h2>スキルツリー表示設定</h2>
            <p className="muted">config.yml の skilltree ブロックだけを更新します。</p>
          </div>
          <button className="icon-button" onClick={onCancel}>×</button>
        </header>
        <div className="modal-body form-grid">
          <label>ワールド名<input value={draft.worldName} onChange={(event) => setDraft({ ...draft, worldName: event.target.value })} /></label>
          <label>構造ID
            <select value={draft.structureId} onChange={(event) => setDraft({ ...draft, structureId: event.target.value })}>
              <option value="">選択してください</option>
              {structures.map((structure) => <option key={structure.structureId} value={structure.structureId}>{structure.name} ({structure.structureId})</option>)}
            </select>
          </label>
          <fieldset>
            <legend>中心座標</legend>
            <label>X<input type="number" step={1} min={-2147483648} max={2147483647} value={draft.centerX} onChange={(event) => number('centerX', event.target.value)} /></label>
            <label>Y<input type="number" step={1} min={-2147483648} max={2147483647} value={draft.centerY} onChange={(event) => number('centerY', event.target.value)} /></label>
            <label>Z<input type="number" step={1} min={-2147483648} max={2147483647} value={draft.centerZ} onChange={(event) => number('centerZ', event.target.value)} /></label>
          </fieldset>
        </div>
        <footer className="modal-footer">
          <span className="spacer" />
          <button className="button subtle" onClick={onCancel} disabled={saving}>キャンセル</button>
          <button className="button primary" onClick={() => void onSave(draft)} disabled={saving || !draft.worldName || !draft.structureId}>
            {saving ? '保存中…' : 'config.ymlへ保存'}
          </button>
        </footer>
      </section>
    </div>
  )
}

import { type IncludeMode, type NetworkTarget, type PluginTestMode, type RecoveryMode, type ToolId, type ToolSettings } from '../api/toolJobApi'

export const defaultToolSettings: { [Id in ToolId]: Extract<ToolSettings, { toolId: Id }> } = {
  '03': { toolId: '03', serverAlreadyRunning: false, recovery: 'Auto' },
  '01': { toolId: '01', pluginTestMode: 'All', recovery: 'Auto' },
  '09': { toolId: '09', architectMode: 'BuildOnly' },
  '12': { toolId: '12', networkTarget: 'All', skipTests: false },
  '16': { toolId: '16', worldCopy: 'Skip', networkPlugins: 'Skip', recovery: 'Auto' },
}

const deployReadiness = '配布設定で必要な入場制限、自動書き込み処理の停止、対象サーバーの準備を済ませてから実行してください。'

const pluginTestModes = {
  All: 'すべて実行',
  Selected: '対象を指定',
  Skip: '省略',
} satisfies Record<PluginTestMode, string>

const networkTargets = {
  All: 'すべて',
  Lobby: 'Lobby',
  Proxy: 'Velocity Proxy',
  Extension: 'Geyser Extension',
} satisfies Record<NetworkTarget, string>

const includeModes = {
  Skip: 'スキップ',
  Include: '設定済みの対象を含める',
} satisfies Record<IncludeMode, string>

function Choice<T extends string>({ label, value, choices, onChange }: {
  label: string; value: T; choices: Record<T, string>; onChange: (value: T) => void
}) {
  return <label className="tool-field"><span>{label}</span>
    <select aria-label={label} value={value} onChange={event => onChange(event.target.value as T)}>
      {Object.keys(choices).map(key => <option key={key} value={key}>{choices[key as T]}</option>)}
    </select>
  </label>
}

function RecoveryChoice({ value, onChange }: { value: RecoveryMode; onChange: (value: RecoveryMode) => void }) {
  return <Choice label="再開・復旧" value={value} onChange={onChange} choices={{
    Auto: '通常実行 / 再開可能な処理を続行',
    Restart: '新しい実行としてやり直す',
    Restore: '配布前の状態に復旧する',
  }} />
}

function RestartChoice({ value, onChange }: {
  value: Exclude<RecoveryMode, 'Restore'>; onChange: (value: Exclude<RecoveryMode, 'Restore'>) => void
}) {
  return <Choice label="再開・復旧" value={value} onChange={onChange} choices={{
    Auto: '通常実行 / 再開可能な処理を続行',
    Restart: '新しい実行としてやり直す',
  }} />
}

export function ToolSettingsFields({ value, onChange }: { value: ToolSettings; onChange: (value: ToolSettings) => void }) {
  switch (value.toolId) {
    case '03':
      return <>
        <Choice label="Dev の稼働状態" value={value.serverAlreadyRunning ? 'running' : 'stopped'}
          choices={{ stopped: '配布設定の停止・起動手順を使用', running: 'Dev を稼働したまま反映' }}
          onChange={state => onChange({ ...value, serverAlreadyRunning: state === 'running', recovery: state === 'running' ? 'Auto' : value.recovery })} />
        {!value.serverAlreadyRunning && <RestartChoice value={value.recovery} onChange={recovery => onChange({ ...value, recovery })} />}
        <p className="tool-muted">{deployReadiness}</p>
      </>
    case '01':
      return <>
        <Choice label="Plugin テスト" value={value.pluginTestMode} choices={pluginTestModes}
          onChange={pluginTestMode => onChange({ ...value, pluginTestMode })} />
        {value.pluginTestMode === 'Selected' && <label className="tool-field"><span>対象テスト</span>
          <input value={value.pluginTests ?? ''} onChange={event => onChange({ ...value, pluginTests: event.target.value })} placeholder="ClassRepositoryTest" />
          <small>テストクラス名またはメソッドをカンマ区切りで指定します。* と ? も使えます。</small>
        </label>}
        <RestartChoice value={value.recovery} onChange={recovery => onChange({ ...value, recovery })} />
        <p className="tool-muted">{deployReadiness}</p>
      </>
    case '09':
      return <Choice label="実行方法" value={value.architectMode}
        choices={{ BuildOnly: 'ビルドのみ', Deploy: 'ビルドして配置' }}
        onChange={architectMode => onChange({ ...value, architectMode })} />
    case '12':
      return <>
        <Choice label="ビルド対象" value={value.networkTarget} choices={networkTargets} onChange={networkTarget => onChange({ ...value, networkTarget })} />
        <Choice label="テスト" value={value.skipTests ? 'skip' : 'run'} choices={{ run: '実行する', skip: '省略する' }}
          onChange={test => onChange({ ...value, skipTests: test === 'skip' })} />
      </>
    case '16':
      return <>
        <Choice label="ワールドのコピー" value={value.worldCopy} choices={includeModes} onChange={worldCopy => onChange({ ...value, worldCopy })} />
        <Choice label="ネットワーク Plugin" value={value.networkPlugins} choices={includeModes} onChange={networkPlugins => onChange({ ...value, networkPlugins })} />
        <RecoveryChoice value={value.recovery} onChange={recovery => onChange({ ...value, recovery })} />
        <p className="tool-muted">配布対象の停止・起動は運用者が行います。対象サーバーは実行計画で確認できます。</p>
      </>
  }
}

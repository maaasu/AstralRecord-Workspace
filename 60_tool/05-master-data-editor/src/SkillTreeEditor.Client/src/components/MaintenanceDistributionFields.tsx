import { useEffect, useRef, useState } from 'react'
import { toolJobApi, type IncludeMode, type MaintenanceDistributionOption, type MaintenanceDistributionOptions, type MaintenanceDistributionSelection } from '../api/toolJobApi'
import { toolError } from './ToolRunDetails'

const kindLabels: Record<MaintenanceDistributionOption['kind'], string> = {
  Jar: 'プラグイン JAR',
  Placement: '配置データ',
  Filebase: 'Filebase',
  World: 'ワールド',
  Network: 'ネットワークプラグイン',
}

function scopedOptions(catalog: MaintenanceDistributionOptions, networkOnly: boolean) {
  return networkOnly ? catalog.options.filter(option => option.kind === 'Network') : catalog.options
}

export function MaintenanceDistributionFields({ networkOnly, worldCopy, networkPlugins, selection, onChange }: {
  networkOnly: boolean
  worldCopy: IncludeMode
  networkPlugins: IncludeMode
  selection: MaintenanceDistributionSelection | null
  onChange: (selection: MaintenanceDistributionSelection | null) => void
}) {
  const [catalog, setCatalog] = useState<MaintenanceDistributionOptions>()
  const [error, setError] = useState('')
  const [reload, setReload] = useState(0)
  const [configurationChanged, setConfigurationChanged] = useState(false)
  const previousSelection = useRef<MaintenanceDistributionSelection | null>(null)

  useEffect(() => {
    const controller = new AbortController()
    void toolJobApi.maintenanceOptions(controller.signal).then(result => {
      if (controller.signal.aborted) return
      const before = previousSelection.current
      const changed = before !== null && before.distributionConfigurationDigest !== result.configurationDigest
      const options = scopedOptions(result, networkOnly)
      const selectedOptions = before
        ? options.filter(option => !changed && before.distributionSelection.includes(option.id))
        : options.filter(option => networkOnly || (option.kind !== 'World' || worldCopy === 'Include') &&
            (option.kind !== 'Network' || networkPlugins === 'Include'))
      const next = {
        distributionConfigurationDigest: result.configurationDigest,
        distributionSelection: selectedOptions.map(option => option.id),
      }
      previousSelection.current = next
      setConfigurationChanged(changed)
      setCatalog(result)
      onChange(next)
    }).catch(reason => {
      if (!controller.signal.aborted) setError(toolError(reason))
    })
    return () => controller.abort()
  }, [networkOnly, networkPlugins, onChange, reload, worldCopy])

  const refresh = () => {
    onChange(null)
    setCatalog(undefined)
    setError('')
    setReload(current => current + 1)
  }

  if (error) return <div className="tool-note error" role="alert">
    <p>配布候補を取得できませんでした。{error}</p>
    <button type="button" className="button" onClick={refresh}>再取得</button>
  </div>
  if (!catalog) return <p className="tool-muted" role="status">配布候補を読み込んでいます…</p>

  const options = scopedOptions(catalog, networkOnly)
  const optionIds = options.map(option => option.id)
  const selected = new Set(optionIds.filter(id => selection?.distributionSelection.includes(id)))
  const saved = catalog.savedSelection
  const savedOutsideScope = networkOnly && saved?.some(id => !optionIds.includes(id))
  const choose = (ids: Iterable<string>) => {
    const requested = new Set(ids)
    const next = {
      distributionConfigurationDigest: catalog.configurationDigest,
      distributionSelection: optionIds.filter(id => requested.has(id)),
    }
    previousSelection.current = next
    setConfigurationChanged(false)
    onChange(next)
  }
  const selectionMessage = configurationChanged
    ? '配布設定が変わりました。配布対象を選び直してください。'
    : '配布対象を1件以上選択してください。'

  return <div className="tool-distribution">
    <div className="tool-button-row">
      <button type="button" className="button" disabled={selected.size === options.length}
        onClick={() => choose(optionIds)}>全選択</button>
      <button type="button" className="button" disabled={!selected.size} onClick={() => choose([])}>全解除</button>
      <button type="button" className="button" onClick={refresh}>候補を再取得</button>
      {!!saved?.length && !savedOutsideScope && <button type="button" className="button"
        onClick={() => choose(saved)}>前回の選択を適用</button>}
    </div>
    {savedOutsideScope && <p className="tool-muted">前回の選択は「配布先ごとに選ぶ」で適用できます。</p>}
    {options.length ? <>
      <p className="tool-muted" role="status">
        {selected.size ? `${selected.size} / ${options.length} 件選択中` : selectionMessage}
      </p>
      <div className="tool-distribution-list" role="region" aria-label="配布候補" tabIndex={0}>
        {Object.entries(kindLabels).map(([kind, label]) => {
          const group = options.filter(option => option.kind === kind)
          return group.length > 0 && <fieldset key={kind}>
            <legend>{label}</legend>
            {group.map(option => <label className="tool-distribution-option" key={option.id}>
              <input type="checkbox" checked={selected.has(option.id)} onChange={event => {
                if (event.target.checked) selected.add(option.id)
                else selected.delete(option.id)
                choose(selected)
              }} />
              <span><strong>{option.label}</strong><small>{option.source} → {option.destination}</small></span>
            </label>)}
          </fieldset>
        })}
      </div>
    </> : <p className="tool-muted" role="status">{networkOnly
      ? 'ネットワークの配布先が設定されていません。配布設定を確認してください。'
      : '有効な配布候補がありません。'}</p>}
  </div>
}

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

export function MaintenanceDistributionFields({ worldCopy, networkPlugins, selection, onChange }: {
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
      const selectedOptions = before
        ? result.options.filter(option => !changed && before.distributionSelection.includes(option.id))
        : result.options.filter(option => (option.kind !== 'World' || worldCopy === 'Include') &&
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
  }, [networkPlugins, onChange, reload, worldCopy])

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

  const selected = new Set(selection?.distributionSelection)
  const choose = (ids: string[]) => {
    const next = { distributionConfigurationDigest: catalog.configurationDigest, distributionSelection: ids }
    previousSelection.current = next
    setConfigurationChanged(false)
    onChange(next)
  }
  const selectionMessage = configurationChanged
    ? '配布設定が変わりました。配布対象を選び直してください。'
    : '配布対象を1件以上選択してください。'

  return <div className="tool-distribution">
    <div className="tool-button-row">
      <button type="button" className="button" disabled={selected.size === catalog.options.length}
        onClick={() => choose(catalog.options.map(option => option.id))}>全選択</button>
      <button type="button" className="button" disabled={!selected.size} onClick={() => choose([])}>全解除</button>
      <button type="button" className="button" onClick={refresh}>候補を再取得</button>
      {catalog.savedSelection && <button type="button" className="button"
        onClick={() => choose(catalog.savedSelection ?? [])}>前回の選択を適用</button>}
    </div>
    {catalog.options.length ? <>
      <p className="tool-muted" role="status">
        {selected.size ? `${selected.size} / ${catalog.options.length} 件選択中` : selectionMessage}
      </p>
      <div className="tool-distribution-list" role="region" aria-label="配布候補" tabIndex={0}>
        {Object.entries(kindLabels).map(([kind, label]) => {
          const options = catalog.options.filter(option => option.kind === kind)
          return options.length > 0 && <fieldset key={kind}>
            <legend>{label}</legend>
            {options.map(option => <label className="tool-distribution-option" key={option.id}>
              <input type="checkbox" checked={selected.has(option.id)} onChange={event => {
                if (event.target.checked) selected.add(option.id)
                else selected.delete(option.id)
                choose(catalog.options.filter(candidate => selected.has(candidate.id)).map(candidate => candidate.id))
              }} />
              <span><strong>{option.label}</strong><small>{option.source} → {option.destination}</small></span>
            </label>)}
          </fieldset>
        })}
      </div>
    </> : <p className="tool-muted" role="status">有効な配布候補がありません。</p>}
  </div>
}

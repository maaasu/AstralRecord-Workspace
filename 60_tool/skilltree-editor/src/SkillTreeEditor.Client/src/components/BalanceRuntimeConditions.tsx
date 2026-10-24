import { useEffect, useState } from 'react'
import type { BalanceScenario } from '../data/balanceLab'
import { validateRuntimeData } from '../data/balanceLab'

type Props = { scenario: BalanceScenario; disabled: boolean; onChange: (scenario: BalanceScenario) => void; onDraft: (dirty: boolean) => void }

export function BalanceRuntimeConditions({ scenario, disabled, onChange, onDraft }: Props) {
  const value = JSON.stringify({ world: scenario.world ?? {}, inputs: scenario.inputs ?? [], allies: scenario.allies ?? [] }, null, 2)
  const [draft, setDraft] = useState(value)
  const [dirty, setDirty] = useState(false)
  const [error, setError] = useState('')
  useEffect(() => { if (!dirty) setDraft(value) }, [value, dirty])
  const apply = () => {
    try {
      const next = JSON.parse(draft)
      if (!next || typeof next !== 'object' || Array.isArray(next) || !next.world || typeof next.world !== 'object' || Array.isArray(next.world) || !Array.isArray(next.inputs) || !Array.isArray(next.allies)) throw new Error('worldはオブジェクト、inputsとalliesは配列にしてください。')
      if (Object.keys(next).some(key => !['world', 'inputs', 'allies'].includes(key))) throw new Error('world・inputs・allies以外の項目があります。')
      validateRuntimeData(next)
      onChange({ ...scenario, world: Object.keys(next.world).length ? next.world : undefined, inputs: next.inputs.length ? next.inputs : undefined, allies: next.allies.length ? next.allies : undefined })
      setDirty(false); onDraft(false); setError('')
    } catch (reason) { setError(String(reason)) }
  }
  return <section className="balance-panel"><h3>位置・操作入力・味方</h3>
    <p className="balance-muted">遮蔽のない読み込み済みの平面を使います。Pluginのダッシュ・瞬間移動と弾道は計算します。速度変更を伴う技は、各tickの座標軌跡を渡すreplayが必要です。サーバーの物理や敵AIの移動は再現しません。</p>
    <fieldset disabled={disabled || dirty} className="balance-runtime-presets">
      <label className="balance-toggle"><input type="checkbox" checked={Boolean(scenario.world)} onChange={event => onChange({ ...scenario, world: event.target.checked ? { floorY: 0, playerPosition: [0, 0, 0], facing: [0, 0, 1] } : undefined })} />位置を明示して検証する</label>
      <button type="button" className="button compact" onClick={() => onChange({ ...scenario, inputs: [{ tick: 0, sneaking: true, onGround: true }, { tick: Math.min(201, scenario.maxSeconds * 20), sneaking: false }] })}>入力例：しゃがみ維持</button>
      <button type="button" className="button compact" onClick={() => onChange({ ...scenario, allies: [{ id: '00000000-0000-0000-0000-000000000001', position: [0, 0, 2], stats: { MAX_HEALTH: 100, MAX_MANA: 50, MAX_ENERGY: 100, DEFENSE: 5 }, hp: 40, mp: 10, eng: 50 }] })}>入力例：負傷した味方</button>
    </fieldset>
    <p className="balance-muted">入力 {scenario.inputs?.length ?? 0}件 · 味方 {scenario.allies?.length ?? 0}人。味方のstatsは設定値の派生能力です。攻撃先は敵設定の「攻撃する味方」で固定します。パッシブはビルドの装着欄で選択してください。</p>
    <details><summary>位置・tick入力・味方のJSON</summary>
      <p className="balance-muted">座標と視線は [X,Y,Z]。tickは0から始まり20tickで1秒です。inputsのkindはdodge・air_shift・normal_attack、sneakingとonGroundは真偽値です。速度変更にはworld.velocityModeをreplayにして、変更後の各tickにplayerPositionまたはenemyPositionを記録してください。</p>
      <textarea aria-label="位置と操作入力JSON" value={draft} disabled={disabled && !dirty} spellCheck={false} onChange={event => { setDraft(event.target.value); setDirty(true); onDraft(true) }} onBlur={() => { if (dirty) apply() }} />
      {error && <p role="alert" className="balance-error">{error}</p>}
    </details>
  </section>
}

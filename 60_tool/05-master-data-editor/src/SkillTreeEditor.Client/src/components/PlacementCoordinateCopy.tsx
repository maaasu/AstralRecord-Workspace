import { useEffect, useMemo, useRef, useState } from 'react'
import type { WorldPlacement } from '../api/worldPlacementApi'

interface Props {
  placement: WorldPlacement
  worldLabel: string
  pendingInput: boolean
  disabled: boolean
}

export function PlacementCoordinateCopy({ placement, worldLabel, pendingInput, disabled }: Props) {
  const { placementId, worldId, worldName, x, y, z } = placement
  const context = useMemo(() => ({}), [placementId, worldId, worldName, x, y, z, pendingInput, disabled])
  const [feedback, setFeedback] = useState<{ context: object; state: 'copied' | 'failed' } | null>(null)
  const [copying, setCopying] = useState(false)
  const inFlight = useRef(false), mounted = useRef(false)
  useEffect(() => { mounted.current = true; return () => { mounted.current = false } }, [])
  const valid = [x, y, z].every(Number.isFinite)
  const text = valid ? `${x} ${y} ${z}` : ''
  const state = feedback?.context === context ? feedback.state : null
  async function copy() {
    if (disabled || pendingInput || !valid || inFlight.current) return
    inFlight.current = true; setCopying(true); setFeedback(null)
    try {
      await navigator.clipboard.writeText(text)
      if (mounted.current) setFeedback({ context, state: 'copied' })
    } catch {
      if (mounted.current) setFeedback({ context, state: 'failed' })
    } finally {
      inFlight.current = false
      if (mounted.current) setCopying(false)
    }
  }
  return <div className="wp-coordinate-copy">
    <div className="wp-actions"><button className="button" type="button" disabled={disabled || pendingInput || !valid || copying} onClick={() => void copy()}>{copying ? '座標をコピー中…' : '座標をコピー'}</button><small>X Y Z の順でコピーします</small></div>
    <small>ワールド: {worldLabel} · {worldName}</small>
    {pendingInput && <p>座標の入力をEnterまたは入力欄からの移動で確定してください。</p>}
    {!valid && <p role="alert">座標が不正なためコピーできません。</p>}
    {state === 'copied' && <p role="status">座標をコピーしました: {text}</p>}
    {state === 'failed' && <><p role="alert">コピーできませんでした。下の座標欄を選択して手動でコピーしてください。</p><label>手動コピー用の座標<input readOnly value={text} onFocus={event => event.currentTarget.select()} /></label></>}
  </div>
}

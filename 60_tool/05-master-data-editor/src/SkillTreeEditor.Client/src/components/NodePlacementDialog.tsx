import { useEffect, useState } from 'react'
import type { NodeMaster, StructureDocument, StructurePlacement } from '../types/editor'
import { parsePlacementCoordinate } from '../state/placementCoordinates'
import { Modal } from './Modal'
import { stripMinecraftFormatting } from '../utils/minecraft'

interface NodePlacementDialogProps {
  node: NodeMaster
  structure: StructureDocument
  disabled: boolean
  onPlace: (placement: StructurePlacement) => boolean
  onClose: () => void
  onDirtyChange: (dirty: boolean) => void
}

export function NodePlacementDialog({ node, structure, disabled, onPlace, onClose, onDirtyChange }: NodePlacementDialogProps) {
  const [initial] = useState(() => {
    const occupied = new Set(structure.nodes.map((placement) => `${placement.x}|${placement.y}|${placement.z}`))
    let x = 0
    while (occupied.has(`${x}|0|0`)) x += 2
    return { x: String(x), y: '0', z: '0' }
  })
  const [draft, setDraft] = useState(initial)
  const values = { x: parsePlacementCoordinate(draft.x), y: parsePlacementCoordinate(draft.y), z: parsePlacementCoordinate(draft.z) }
  const valid = values.x !== null && values.y !== null && values.z !== null
  const duplicate = valid && structure.nodes.some((placement) => placement.x === values.x && placement.y === values.y && placement.z === values.z)
  const placed = structure.nodes.some((placement) => placement.nodeId === node.nodeId)
  const dirty = (['x', 'y', 'z'] as const).some((key) => draft[key] !== initial[key])
  useEffect(() => { onDirtyChange(dirty) }, [dirty, onDirtyChange])
  useEffect(() => () => onDirtyChange(false), [onDirtyChange])
  const close = () => {
    if (!disabled && (!dirty || window.confirm('入力中の配置座標を破棄しますか？'))) onClose()
  }
  return <Modal ariaLabel="ノードを座標指定で配置" className="node-placement-dialog" onClose={close} closeDisabled={disabled}>
    <form onSubmit={(event) => {
      event.preventDefault()
      if (disabled || !valid || duplicate || placed) return
      if (onPlace({ nodeId: node.nodeId, x: values.x!, y: values.y!, z: values.z! })) onClose()
    }}>
      <header className="modal-header"><div><span className="eyebrow">構造へノードを追加</span><h2>{stripMinecraftFormatting(node.name)}</h2><p className="muted">#{node.nodeId} · {structure.name}</p></div></header>
      <div className="modal-body"><p className="muted">X / Z はキャンバス上の位置、Y は高さです。配置後も移動・接続でき、Undoで戻せます。</p><div className="coordinate-grid">{(['x', 'y', 'z'] as const).map((key) => <label key={key}>{key.toUpperCase()}<input aria-label={`配置先 ${key.toUpperCase()}`} inputMode="decimal" value={draft[key]} disabled={disabled} aria-invalid={values[key] === null || duplicate} onChange={(event) => setDraft((current) => ({ ...current, [key]: event.target.value }))} /></label>)}</div>
      {!valid && <p className="error-message" role="alert">座標は -2147483648～2147483647 の範囲で0.1単位の数値を入力してください。</p>}
      {duplicate && <p className="error-message" role="alert">同じ座標に配置済みのノードがあります。別の座標を指定してください。</p>}
      {placed && <p className="error-message" role="alert">このノードはすでに配置されています。</p>}
      </div><footer className="modal-footer"><button type="button" className="button" disabled={disabled} onClick={close}>キャンセル</button><span className="spacer" /><button type="submit" className="button primary" disabled={disabled || !valid || duplicate || placed}>構造へ配置</button></footer>
    </form>
  </Modal>
}

import { lazy, Suspense, useRef, useState } from 'react'
import { createPortal } from 'react-dom'
import minecraftBlockMaterials from '../data/minecraft-block-materials.1.21.11.json'
import { usePanelDocument, usePanelVisibility } from '../docking/dockPanel'
import { MaterialPickerLoading } from './MaterialIconInput'
import { MinecraftIcon } from './MinecraftIcon'

const MaterialIconPickerDialog = lazy(() => import('./MaterialIconPickerDialog').then(module => ({ default: module.MaterialIconPickerDialog })))
const blocks = minecraftBlockMaterials.filter(material => !['AIR', 'CAVE_AIR', 'VOID_AIR'].includes(material))
const stairs = blocks.filter(material => material.endsWith('_STAIRS'))

export function DungeonMaterialField({ path, value, disabled, onChange, add }: {
  path: string
  value: string
  disabled?: boolean
  add?: boolean
  onChange: (value: string) => void
}) {
  const document = usePanelDocument()
  const visible = usePanelVisibility()
  const [open, setOpen] = useState(false)
  const opener = useRef<HTMLButtonElement>(null)
  const close = () => setOpen(false)
  const name = value.replace(/^minecraft:/i, '').toLowerCase().split('_').map(word => word.charAt(0).toUpperCase() + word.slice(1)).join(' ')
  return <div className="dungeon-material-field">
    <button ref={opener} type="button" className="button compact dungeon-material-choice" aria-haspopup="dialog"
      data-master-field-path={add ? undefined : path} title={value} disabled={disabled} onClick={() => setOpen(true)}>
      {!add && <MinecraftIcon icon={value} className="master-reference-icon" />}
      <span>{add ? '＋ 素材を追加' : name || 'ブロック素材を選ぶ'}</span>
    </button>
    {!add && <details className="master-reference-id"><summary>IDを入力</summary>
      <input aria-label={path} value={value} onChange={event => onChange(event.target.value)} />
    </details>}
    {open && visible && !disabled && createPortal(<Suspense fallback={<MaterialPickerLoading onClose={close} />}>
      <MaterialIconPickerDialog value={value} materials={path === '/theme/pillar/stairMaterial' ? stairs : blocks} onClose={close} onSelect={material => {
        if (opener.current && !opener.current.matches(':disabled')) onChange(material)
        close()
      }} />
    </Suspense>, document.body)}
  </div>
}

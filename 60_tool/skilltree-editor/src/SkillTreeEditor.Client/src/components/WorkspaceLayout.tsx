import { useEffect, useRef, useState, type PointerEvent as ReactPointerEvent, type ReactNode } from 'react'

interface WorkspaceLayoutProps {
  left: ReactNode
  center: ReactNode
  right: ReactNode
  leftVisible: boolean
  centerVisible: boolean
  rightVisible: boolean
  onActivatePane?: (pane: 'left' | 'center' | 'right') => void
}

const LEFT_DEFAULT = 280
const RIGHT_DEFAULT = 370
const LEFT_MIN = 210
const LEFT_MAX = 620
const RIGHT_MIN = 290
const RIGHT_MAX = 720

export function WorkspaceLayout({
  left,
  center,
  right,
  leftVisible,
  centerVisible,
  rightVisible,
  onActivatePane,
}: WorkspaceLayoutProps) {
  const [leftWidth, setLeftWidth] = useState(LEFT_DEFAULT)
  const [rightWidth, setRightWidth] = useState(RIGHT_DEFAULT)
  const [focusedPane, setFocusedPane] = useState('center')
  const panes = [
    { id: 'left', label: 'ノード一覧', visible: leftVisible },
    { id: 'center', label: 'キャンバス', visible: centerVisible },
    { id: 'right', label: '詳細・検証', visible: rightVisible },
  ] as const
  const activePane = panes.find((pane) => pane.id === focusedPane && pane.visible)?.id ?? panes.find((pane) => pane.visible)?.id

  return (
    <div className="workspace-layout">
    <div className="compact-pane-navigation" role="group" aria-label="作業領域の切替">{panes.filter((pane) => pane.visible || onActivatePane).map((pane) => <button key={pane.id} type="button" className={`button ${activePane === pane.id ? 'active' : ''}`} aria-pressed={activePane === pane.id} onClick={() => { setFocusedPane(pane.id); onActivatePane?.(pane.id) }}>{pane.label}</button>)}</div>
    <div className={centerVisible ? 'workspace-grid' : 'workspace-grid no-center'}>
      {leftVisible && <div className="workspace-pane left-pane" data-compact-active={activePane === 'left'} style={{ flexBasis: leftWidth }}>{left}</div>}
      {leftVisible && centerVisible && (
        <ResizeHandle
          label="ノード一覧の幅を変更"
          width={leftWidth}
          min={LEFT_MIN}
          max={LEFT_MAX}
          defaultWidth={LEFT_DEFAULT}
          direction={1}
          onResize={setLeftWidth}
        />
      )}
      {centerVisible && <div className="workspace-pane center-pane" data-compact-active={activePane === 'center'}>{center}</div>}
      {rightVisible && (centerVisible || leftVisible) && (
        <ResizeHandle
          label="インスペクターの幅を変更"
          width={rightWidth}
          min={RIGHT_MIN}
          max={RIGHT_MAX}
          defaultWidth={RIGHT_DEFAULT}
          direction={-1}
          onResize={setRightWidth}
        />
      )}
      {rightVisible && <div className="workspace-pane right-pane" data-compact-active={activePane === 'right'} style={{ flexBasis: rightWidth }}>{right}</div>}
    </div>
    </div>
  )
}

interface ResizeHandleProps {
  label: string
  width: number
  min: number
  max: number
  defaultWidth: number
  direction: 1 | -1
  onResize: (width: number) => void
}

function ResizeHandle({ label, width, min, max, defaultWidth, direction, onResize }: ResizeHandleProps) {
  const cleanupRef = useRef<() => void>(() => undefined)

  useEffect(() => () => cleanupRef.current(), [])

  const startResize = (event: ReactPointerEvent<HTMLDivElement>) => {
    if (event.button !== 0) return
    event.preventDefault()
    cleanupRef.current()
    const startX = event.clientX
    const startWidth = width
    document.body.classList.add('resizing-panels')

    const move = (pointerEvent: PointerEvent) => {
      onResize(clamp(startWidth + ((pointerEvent.clientX - startX) * direction), min, max))
    }
    const stop = () => {
      window.removeEventListener('pointermove', move)
      window.removeEventListener('pointerup', stop)
      window.removeEventListener('pointercancel', stop)
      document.body.classList.remove('resizing-panels')
    }
    cleanupRef.current = stop
    window.addEventListener('pointermove', move)
    window.addEventListener('pointerup', stop)
    window.addEventListener('pointercancel', stop)
  }

  return (
    <div
      className="workspace-resizer"
      role="separator"
      aria-label={label}
      aria-orientation="vertical"
      aria-valuemin={min}
      aria-valuemax={max}
      aria-valuenow={width}
      tabIndex={0}
      onPointerDown={startResize}
      onDoubleClick={() => onResize(defaultWidth)}
      onKeyDown={(event) => {
        if (event.key !== 'ArrowLeft' && event.key !== 'ArrowRight') return
        event.preventDefault()
        const screenDirection = event.key === 'ArrowRight' ? 1 : -1
        onResize(clamp(width + (screenDirection * direction * 16), min, max))
      }}
      title={`${label}（ダブルクリックで初期幅）`}
    />
  )
}

const clamp = (value: number, min: number, max: number) => Math.max(min, Math.min(max, Math.round(value)))

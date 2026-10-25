import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { cellAtWorld, levelColor, reasonColor, terrainColor, planningRoad } from '../data/spawnerPlanning'
import type { PlanningPlan, PlanningSnapshot } from '../data/spawnerPlanning'

export type PlanningLayer = 'terrain' | 'levels' | 'reasons'
interface Props {
  snapshot: PlanningSnapshot; plan: PlanningPlan | null; layer: PlanningLayer; region: string; reason: string
  radii: boolean; active: boolean; selectedCell: number | null; selectedProposal: string | null
  focus: { key: number; x: number; z: number } | null
  onCell: (index: number) => void; onProposal: (id: string) => void
}
export function SpawnerPlanningMap(props: Props) {
  const { snapshot, plan, layer, region, reason, radii, active, selectedCell, selectedProposal, focus, onCell, onProposal } = props
  const canvas = useRef<HTMLCanvasElement>(null)
  const drag = useRef<{ x: number; y: number; moved: number } | null>(null)
  const [size, setSize] = useState({ width: 800, height: 620 })
  const [view, setView] = useState({ scale: .1, x: 0, z: 0 })
  const [coordinates, setCoordinates] = useState('X — / Z —')
  const bounds = snapshot.manifest.bounds
  const grid = useMemo(() => new Map(snapshot.cells.map((cell, index) => [`${cell.x},${cell.z}`, index])), [snapshot.cells])
  const visibleProposals = useMemo(() => plan?.proposals.filter(item => !region || item.regionId === region) ?? [], [plan, region])
  const fit = useCallback(() => {
    const scale = Math.min((size.width - 32) / bounds.width, (size.height - 32) / bounds.height)
    setView({ scale, x: (size.width - bounds.width * scale) / 2, z: (size.height - bounds.height * scale) / 2 })
  }, [size, bounds])
  useEffect(() => { fit() }, [fit])
  useEffect(() => {
    const element = canvas.current?.parentElement
    if (!element || !active) return
    const resize = () => {
      const rect = element.getBoundingClientRect()
      if (rect.width && rect.height) setSize({ width: rect.width, height: rect.height })
    }
    resize()
    const observer = new ResizeObserver(resize)
    observer.observe(element)
    return () => observer.disconnect()
  }, [active])
  useEffect(() => {
    if (!focus) return
    const scale = Math.max(.7, Math.min(size.width, size.height) / 700)
    setView({ scale, x: size.width / 2 - (focus.x - bounds.minX) * scale, z: size.height / 2 - (focus.z - bounds.minZ) * scale })
  }, [focus, bounds, size])
  const raster = useMemo(() => {
    const image = document.createElement('canvas')
    image.width = bounds.width / bounds.cellSize
    image.height = bounds.height / bounds.cellSize
    const ctx = image.getContext('2d')
    if (!ctx) return image
    for (let index = 0; index < snapshot.cells.length; index++) {
      const cell = snapshot.cells[index]
      const code = plan?.reasonCodes[index] ?? cell.reasonCode
      let color = layer === 'levels' ? levelColor(cell.targetLevel) : layer === 'reasons' ? reasonColor(code) : terrainColor(cell)
      if (layer === 'levels' && cell.status !== 'ok') color = terrainColor(cell)
      if (layer === 'terrain' && cell.candidateY != null && !/water|lava/.test(cell.surface ?? '')) {
        const east = snapshot.cells[grid.get(`${cell.x + bounds.cellSize},${cell.z}`) ?? -1]
        const south = snapshot.cells[grid.get(`${cell.x},${cell.z + bounds.cellSize}`) ?? -1]
        const slope = ((east?.candidateY ?? cell.candidateY) + (south?.candidateY ?? cell.candidateY) - cell.candidateY * 2) / bounds.cellSize
        const shade = Math.max(.68, Math.min(1.16, 1 - slope * .18))
        color = `rgb(${[1, 3, 5].map(offset => Math.min(255, Math.round(parseInt(color.slice(offset, offset + 2), 16) * shade))).join(',')})`
      }
      ctx.fillStyle = color
      const x = (cell.x - bounds.minX) / bounds.cellSize, z = (cell.z - bounds.minZ) / bounds.cellSize
      ctx.fillRect(x, z, 1, 1)
      if ((region && cell.regionId !== region) || (reason && code !== reason)) {
        ctx.fillStyle = '#0b1423dc'
        ctx.fillRect(x, z, 1, 1)
      }
    }
    return image
  }, [snapshot.cells, bounds, grid, layer, plan, region, reason])
  useEffect(() => {
    const element = canvas.current, ctx = element?.getContext('2d')
    if (!element || !ctx || !active) return
    const ratio = window.devicePixelRatio || 1
    element.width = Math.round(size.width * ratio)
    element.height = Math.round(size.height * ratio)
    ctx.setTransform(ratio, 0, 0, ratio, 0, 0)
    ctx.clearRect(0, 0, size.width, size.height)
    ctx.imageSmoothingEnabled = false
    ctx.drawImage(raster, view.x, view.z, bounds.width * view.scale, bounds.height * view.scale)
    const screen = (x: number, z: number) => [(x - bounds.minX) * view.scale + view.x, (z - bounds.minZ) * view.scale + view.z]
    const road = planningRoad(snapshot.manifest)
    if (road) {
      ctx.beginPath()
      for (const [start, end] of road.edges) {
        const a = road.nodes[start], b = road.nodes[end]
        if (!a || !b) continue
        const [ax, az] = screen(a.x, a.z), [bx, bz] = screen(b.x, b.z)
        ctx.moveTo(ax, az); ctx.lineTo(bx, bz)
      }
      ctx.strokeStyle = '#142736'; ctx.lineWidth = 5; ctx.stroke()
      ctx.strokeStyle = '#f5cd80'; ctx.lineWidth = 2; ctx.stroke()
    }
    for (const proposal of visibleProposals) {
      const [x, z] = screen(proposal.x + .5, proposal.z + .5)
      const selected = selectedProposal === proposal.proposalId
      const radius = proposal.radiusMeters * view.scale
      if (x + radius < 0 || z + radius < 0 || x - radius > size.width || z - radius > size.height) continue
      if (radii || selected) {
        ctx.beginPath(); ctx.arc(x, z, radius, 0, Math.PI * 2)
        ctx.strokeStyle = selected ? '#fff1a2' : '#d3dfb83d'; ctx.lineWidth = selected ? 2 : .7; ctx.stroke()
      }
      ctx.beginPath(); ctx.arc(x, z, selected ? 5 : view.scale > .3 ? 2.5 : 1.2, 0, Math.PI * 2)
      ctx.fillStyle = selected ? '#fff0ac' : '#e3f2c8'; ctx.fill()
    }
    const spawn = snapshot.manifest.spawn
    const [sx, sz] = screen(spawn.x, spawn.z)
    ctx.beginPath(); ctx.arc(sx, sz, 48 * view.scale, 0, Math.PI * 2)
    ctx.strokeStyle = '#f3d690'; ctx.stroke()
    ctx.beginPath(); ctx.arc(sx, sz, 5, 0, Math.PI * 2); ctx.fillStyle = '#ffdb85'; ctx.fill()
    ctx.font = '12px sans-serif'; ctx.fillText('開始地点', sx + 9, sz + 4)
    if (selectedCell !== null) {
      const cell = snapshot.cells[selectedCell]
      const [x, z] = screen(cell.x, cell.z)
      ctx.strokeStyle = '#fff'; ctx.lineWidth = 2; ctx.strokeRect(x, z, bounds.cellSize * view.scale, bounds.cellSize * view.scale)
    }
    const metres = view.scale > 1 ? 50 : view.scale > .3 ? 100 : 500
    ctx.fillStyle = '#d7e1eb'; ctx.fillRect(size.width - metres * view.scale - 18, size.height - 22, metres * view.scale, 2)
    ctx.fillText(`${metres} m`, size.width - metres * view.scale - 18, size.height - 29)
  }, [active, raster, view, size, bounds, radii, visibleProposals, snapshot, selectedCell, selectedProposal])
  const zoom = useCallback((factor: number, x = size.width / 2, z = size.height / 2) => setView(current => {
    const scale = Math.max(.035, Math.min(5, current.scale * factor)), ratio = scale / current.scale
    return { scale, x: x - (x - current.x) * ratio, z: z - (z - current.z) * ratio }
  }), [size])
  useEffect(() => {
    const element = canvas.current
    if (!element || !active) return
    const wheel = (event: WheelEvent) => {
      event.preventDefault()
      const rect = element.getBoundingClientRect()
      zoom(event.deltaY < 0 ? 1.18 : 1 / 1.18, event.clientX - rect.left, event.clientY - rect.top)
    }
    element.addEventListener('wheel', wheel, { passive: false })
    return () => element.removeEventListener('wheel', wheel)
  }, [active, zoom])
  return <div className="sp-map-wrap">
    <canvas ref={canvas} aria-label="エリヴァ全大陸の地図。ドラッグで移動、ホイールで拡大、クリックで地域と未配置理由を確認" tabIndex={0}
      onPointerDown={event => { drag.current = { x: event.clientX, y: event.clientY, moved: 0 }; event.currentTarget.setPointerCapture(event.pointerId) }}
      onPointerMove={event => {
        const rect = event.currentTarget.getBoundingClientRect()
        const x = (event.clientX - rect.left - view.x) / view.scale + bounds.minX, z = (event.clientY - rect.top - view.z) / view.scale + bounds.minZ
        setCoordinates(`X ${Math.floor(x)} / Z ${Math.floor(z)}`)
        const previous = drag.current
        if (previous) {
          const dx = event.clientX - previous.x, dz = event.clientY - previous.y
          drag.current = { x: event.clientX, y: event.clientY, moved: previous.moved + Math.abs(dx) + Math.abs(dz) }
          setView(current => ({ ...current, x: current.x + dx, z: current.z + dz }))
        }
      }}
      onPointerUp={event => {
        const moved = drag.current?.moved ?? 0; drag.current = null
        if (moved > 5) return
        const rect = event.currentTarget.getBoundingClientRect(), px = event.clientX - rect.left, pz = event.clientY - rect.top
        const x = (px - view.x) / view.scale + bounds.minX, z = (pz - view.z) / view.scale + bounds.minZ
        // At overview scale dots are dense; prioritize the terrain cell until zoomed in.
        let distance = 6
        let nearest: (typeof visibleProposals)[number] | undefined
        if (view.scale > .25) for (const value of visibleProposals) {
          const next = Math.hypot((value.x + .5 - x) * view.scale, (value.z + .5 - z) * view.scale)
          if (next < distance) { distance = next; nearest = value }
        }
        if (nearest) onProposal(nearest.proposalId)
        else { const index = cellAtWorld(bounds, grid, x, z); if (index !== undefined) onCell(index) }
      }}
      onPointerCancel={() => { drag.current = null }}
      onKeyDown={event => {
        if (['+', '-', 'Home', 'ArrowLeft', 'ArrowRight', 'ArrowUp', 'ArrowDown'].includes(event.key)) {
          event.preventDefault()
          if (event.key === '+') zoom(1.4)
          else if (event.key === '-') zoom(1 / 1.4)
          else if (event.key === 'Home') fit()
          else setView(current => ({ ...current, x: current.x + (event.key === 'ArrowLeft' ? 60 : event.key === 'ArrowRight' ? -60 : 0), z: current.z + (event.key === 'ArrowUp' ? 60 : event.key === 'ArrowDown' ? -60 : 0) }))
        }
      }} />
    <div className="sp-map-tools"><button className="button" onClick={() => zoom(1.5)} aria-label="地図を拡大">＋</button><button className="button" onClick={() => zoom(1 / 1.5)} aria-label="地図を縮小">−</button><button className="button" onClick={fit}>全大陸</button></div>
    <span className="sp-north">N ↑</span><output className="sp-coordinates">{coordinates}</output>
  </div>
}

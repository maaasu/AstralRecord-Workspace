import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { cellAtWorld, levelColor, reasonColor, terrainColor, planningRoad } from '../data/spawnerPlanning'
import type { PlanningPlan, PlanningSnapshot } from '../data/spawnerPlanning'
import type { WorldPlacement } from '../api/worldPlacementApi'
import type { PlacementCatalog } from './WorldPlacementEditor'

export type PlanningLayer = 'terrain' | 'levels' | 'reasons'
interface Props {
  snapshot: PlanningSnapshot; plan: PlanningPlan | null; layer: PlanningLayer; region: string; reason: string
  radii: boolean; active: boolean; selectedCell: number | null; selectedProposal: string | null
  focus: { key: number; x: number; z: number } | null
  onCell: (index: number) => void; onProposal: (id: string) => void
  placements?: WorldPlacement[]; placementCatalog?: PlacementCatalog; selectedPlacement?: string | null
  onPlacement?: (id: string) => void; onPlacePoint?: (x: number, y: number | null, z: number) => void; placing?: boolean
}
export function SpawnerPlanningMap(props: Props) {
  const { snapshot, plan, layer, region, reason, radii, active, selectedCell, selectedProposal, focus, onCell, onProposal } = props
  const canvas = useRef<HTMLCanvasElement>(null)
  const drag = useRef<{ x: number; y: number; moved: number } | null>(null)
  const [size, setSize] = useState({ width: 800, height: 620 })
  const sizeRef = useRef(size)
  const measured = useRef(false)
  const [view, setView] = useState({ scale: .1, x: 0, z: 0 })
  const [coordinates, setCoordinates] = useState('X — / Z —')
  const [detail, setDetail] = useState<HTMLImageElement | null>(null)
  const bounds = snapshot.manifest.bounds
  const grid = useMemo(() => new Map(snapshot.cells.map((cell, index) => [`${cell.x},${cell.z}`, index])), [snapshot.cells])
  const visibleProposals = useMemo(() => plan?.proposals.filter(item => !region || item.regionId === region) ?? [], [plan, region])
  const fit = useCallback(() => {
    const { width, height } = sizeRef.current
    const scale = Math.min((width - 32) / bounds.width, (height - 32) / bounds.height)
    setView({ scale, x: (width - bounds.width * scale) / 2, z: (height - bounds.height * scale) / 2 })
  }, [bounds])
  useEffect(() => { fit() }, [fit])
  useEffect(() => {
    const element = canvas.current?.parentElement
    if (!element || !active) return
    const resize = () => {
      const rect = element.getBoundingClientRect()
      if (rect.width && rect.height && (!measured.current || rect.width !== sizeRef.current.width || rect.height !== sizeRef.current.height)) {
        const previous = sizeRef.current
        sizeRef.current = { width: rect.width, height: rect.height }
        if (!measured.current) {
          measured.current = true
          const scale = Math.min((rect.width - 32) / bounds.width, (rect.height - 32) / bounds.height)
          setView({ scale, x: (rect.width - bounds.width * scale) / 2, z: (rect.height - bounds.height * scale) / 2 })
        } else {
          setView(current => ({ ...current, x: current.x + (rect.width - previous.width) / 2, z: current.z + (rect.height - previous.height) / 2 }))
        }
        setSize(sizeRef.current)
      }
    }
    resize()
    const observer = new ResizeObserver(resize)
    observer.observe(element)
    return () => observer.disconnect()
  }, [active, bounds])
  useEffect(() => {
    if (!focus) return
    const { width, height } = sizeRef.current
    const scale = Math.max(.7, Math.min(width, height) / 700)
    setView({ scale, x: width / 2 - (focus.x - bounds.minX) * scale, z: height / 2 - (focus.z - bounds.minZ) * scale })
  }, [focus, bounds])
  useEffect(() => {
    let cancelled = false
    setDetail(null)
    if (typeof fetch !== 'function') return
    const revision = encodeURIComponent(snapshot.manifest.exportRevision)
    const controller = new AbortController()
    fetch(`/api/spawner-planning/terrain-detail?revision=${revision}`, { signal: controller.signal })
      .then(response => response.ok ? response.json() : null)
      .then(metadata => {
        if (cancelled || !metadata || metadata.schemaVersion !== 1 || metadata.blocksPerPixel !== 1 ||
            metadata.terrainFingerprint !== snapshot.manifest.terrainFingerprint || metadata.minX !== bounds.minX ||
            metadata.minZ !== bounds.minZ || metadata.width !== bounds.width || metadata.height !== bounds.height) return
        const image = new Image()
        image.onload = () => { if (!cancelled && image.naturalWidth === bounds.width && image.naturalHeight === bounds.height) setDetail(image) }
        image.src = `/api/spawner-planning/terrain-detail/image?revision=${revision}`
      }).catch(() => { /* The 16m raster remains available. */ })
    return () => { cancelled = true; controller.abort() }
  }, [snapshot.manifest.exportRevision, snapshot.manifest.terrainFingerprint, bounds])
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
  const detailMask = useMemo(() => {
    if (!region && !reason) return null
    const mask = document.createElement('canvas')
    mask.width = bounds.width / bounds.cellSize
    mask.height = bounds.height / bounds.cellSize
    const ctx = mask.getContext('2d')
    if (!ctx) return mask
    ctx.fillStyle = '#0b1423dc'
    snapshot.cells.forEach((cell, index) => {
      const code = plan?.reasonCodes[index] ?? cell.reasonCode
      if ((!region || cell.regionId === region) && (!reason || code === reason)) return
      ctx.fillRect((cell.x - bounds.minX) / bounds.cellSize, (cell.z - bounds.minZ) / bounds.cellSize, 1, 1)
    })
    return mask
  }, [snapshot.cells, bounds, plan, region, reason])
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
    if (layer === 'terrain' && detail && view.scale >= .4)
      ctx.drawImage(detail, view.x, view.z, bounds.width * view.scale, bounds.height * view.scale)
    if (layer === 'terrain' && detail && view.scale >= .4 && detailMask)
      ctx.drawImage(detailMask, view.x, view.z, bounds.width * view.scale, bounds.height * view.scale)
    const screen = (x: number, z: number) => [(x - bounds.minX) * view.scale + view.x, (z - bounds.minZ) * view.scale + view.z]
    const road = planningRoad(snapshot.manifest)
    if (road) {
      for (const terrain of [false, true]) {
        ctx.beginPath()
        for (const [start, end] of road.edges) {
          const a = road.nodes[start], b = road.nodes[end]
          if (!a || !b || (a.kind === 'terrain' || b.kind === 'terrain') !== terrain) continue
          const [ax, az] = screen(a.x, a.z), [bx, bz] = screen(b.x, b.z)
          ctx.moveTo(ax, az); ctx.lineTo(bx, bz)
        }
        ctx.setLineDash(terrain ? [4, 3] : [])
        ctx.strokeStyle = '#142736'; ctx.lineWidth = 5; ctx.stroke()
        ctx.strokeStyle = terrain ? '#8de0e6' : '#f5cd80'; ctx.lineWidth = 2; ctx.stroke()
      }
      ctx.setLineDash([])
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
    for (const placement of props.placements ?? []) {
      if (placement.worldId !== snapshot.manifest.worldId) continue
      const [x, z] = screen(placement.x + .5, placement.z + .5)
      const selected = props.selectedPlacement === placement.placementId
      const template = props.placementCatalog?.spawners.find(item => item.id === placement.templateId)
      if (placement.kind === 'spawner' && template && (radii || selected)) {
        ctx.beginPath(); ctx.arc(x, z, template.radiusMeters * view.scale, 0, Math.PI * 2)
        ctx.strokeStyle = selected ? '#63edff' : '#46d6e06a'; ctx.lineWidth = selected ? 2 : 1; ctx.stroke()
      }
      ctx.beginPath(); ctx.arc(x, z, selected ? 7 : view.scale > .3 ? 4 : 2.2, 0, Math.PI * 2)
      ctx.fillStyle = placement.kind === 'spawner' ? '#33d5e6' : placement.kind === 'npc' ? '#ec99fa' : '#f7cf6d'; ctx.fill()
      if (selected) { ctx.strokeStyle = '#fff'; ctx.lineWidth = 1.5; ctx.stroke() }
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
    const metres = view.scale > 8 ? 5 : view.scale > 2 ? 20 : view.scale > 1 ? 50 : view.scale > .3 ? 100 : 500
    ctx.fillStyle = '#d7e1eb'; ctx.fillRect(size.width - metres * view.scale - 18, size.height - 22, metres * view.scale, 2)
    ctx.fillText(`${metres} m`, size.width - metres * view.scale - 18, size.height - 29)
  }, [active, raster, detail, detailMask, layer, view, size, bounds, radii, visibleProposals, snapshot, selectedCell, selectedProposal, props.placements, props.placementCatalog, props.selectedPlacement])
  const zoom = useCallback((factor: number, x = size.width / 2, z = size.height / 2) => setView(current => {
    const minimum = Math.min((size.width - 32) / bounds.width, (size.height - 32) / bounds.height)
    const scale = Math.max(minimum, Math.min(16, current.scale * factor)), ratio = scale / current.scale
    return { scale, x: x - (x - current.x) * ratio, z: z - (z - current.z) * ratio }
  }), [size, bounds])
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
        if (props.placing && props.onPlacePoint) {
          const index = cellAtWorld(bounds, grid, x, z)
          if (index !== undefined) props.onPlacePoint(Math.floor(x), snapshot.cells[index].candidateY, Math.floor(z))
          return
        }
        let placementDistance = 8
        let closest: WorldPlacement | undefined
        for (const value of view.scale > .25 ? props.placements ?? [] : []) {
          if (value.worldId !== snapshot.manifest.worldId) continue
          const next = Math.hypot((value.x + .5 - x) * view.scale, (value.z + .5 - z) * view.scale)
          if (next < placementDistance) { placementDistance = next; closest = value }
        }
        if (closest && props.onPlacement) { props.onPlacement(closest.placementId); return }
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
    <span className="sp-north">N ↑</span><output className="sp-coordinates">{coordinates} · {view.scale.toFixed(view.scale < 1 ? 2 : 1)} px/m · {layer === 'terrain' && detail && view.scale >= .4 ? '1m地形' : '16m区画'}</output>
  </div>
}

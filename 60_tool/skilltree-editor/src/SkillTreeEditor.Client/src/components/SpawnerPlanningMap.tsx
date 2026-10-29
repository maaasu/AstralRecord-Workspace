import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react'
import { cellAtWorld, levelColor, reasonColor, terrainColor, planningRoad } from '../data/spawnerPlanning'
import type { PlanningPlan, PlanningSnapshot } from '../data/spawnerPlanning'
import type { WorldPlacement } from '../api/worldPlacementApi'
import type { PlacementCatalog } from './WorldPlacementEditor'
import { spawnerLevelBands, spawnerLevels, spawnerMasterColor, spawnerMasterNumbers } from '../data/spawnerMapStyle'
import { stripMinecraftFormatting as plainName } from '../utils/minecraft'
import { circleIntersectsViewport, drawSpawnerRanges, type MapCircle } from '../data/spawnerRangeDrawing'
import { calculateCoverage, coverageColors, coverageTerrain, type CoverageCircle } from '../data/spawnerCoverage'

export type PlanningLayer = 'terrain' | 'levels' | 'reasons'
const hasRadius = (radius: number | null | undefined): radius is number => typeof radius === 'number' && Number.isFinite(radius) && radius > 0
const unknownLevels = spawnerLevels([])
interface Props {
  snapshot: PlanningSnapshot; plan: PlanningPlan | null; layer: PlanningLayer; region: string; reason: string
  radii: boolean; active: boolean; selectedCell: number | null; selectedProposal: string | null
  focus: { key: number; x: number; z: number } | null
  onCell: (index: number) => void; onProposal: (id: string) => void
  placements?: WorldPlacement[]; placementCatalog?: PlacementCatalog; selectedPlacement?: string | null
  onPlacement?: (id: string) => void; onPlacePoint?: (x: number, y: number | null, z: number) => void; placing?: boolean
  showProposals?: boolean
  placementsReady?: boolean
}
export function SpawnerPlanningMap(props: Props) {
  const { snapshot, plan, layer, region, reason, radii, active, selectedCell, selectedProposal, focus, onCell, onProposal } = props
  const canvas = useRef<HTMLCanvasElement>(null)
  const drag = useRef<{ x: number; y: number; moved: number } | null>(null)
  const [size, setSize] = useState({ width: 800, height: 620 })
  const [pixelRatio, setPixelRatio] = useState(window.devicePixelRatio || 1)
  const sizeRef = useRef(size)
  const measured = useRef(false)
  const [view, setView] = useState({ scale: .1, x: 0, z: 0 })
  const [coordinates, setCoordinates] = useState('X — / Z —')
  const [detailResource, setDetailResource] = useState<{ identity: object; image: HTMLImageElement } | null>(null)
  const [markerMode, setMarkerMode] = useState<'masters' | 'levels'>('masters')
  const [showCoverage, setShowCoverage] = useState(true)
  const [pointer, setPointer] = useState<{ x: number; z: number } | null>(null)
  const tooltip = useRef<HTMLDivElement>(null)
  const tooltipContent = useRef<HTMLDivElement>(null)
  const [tooltipSize, setTooltipSize] = useState({ width: 0, height: 0 })
  const [tooltipClipped, setTooltipClipped] = useState(false)
  const bounds = snapshot.manifest.bounds
  const detailIdentity = useMemo(() => ({ revision: snapshot.manifest.exportRevision,
    fingerprint: snapshot.manifest.terrainFingerprint, minX: bounds.minX, minZ: bounds.minZ, width: bounds.width, height: bounds.height }),
  [snapshot.manifest.exportRevision, snapshot.manifest.terrainFingerprint, bounds.minX, bounds.minZ, bounds.width, bounds.height])
  const detail = detailResource?.identity === detailIdentity ? detailResource.image : null
  const detailNeeded = active && layer === 'terrain' && view.scale >= .4
  const grid = useMemo(() => new Map(snapshot.cells.map((cell, index) => [`${cell.x},${cell.z}`, index])), [snapshot.cells])
  const visibleProposals = useMemo(() => plan?.proposals.filter(item => !region || item.regionId === region) ?? [], [plan, region])
  const masters = useMemo(() => new Map(props.placementCatalog?.spawners.map(item => [item.id, item]) ?? []), [props.placementCatalog])
  const masterStyles = useMemo(() => new Map([...masters].map(([id, master]) => {
    const levels = spawnerLevels(master.levels ?? [])
    return [id, { levels, color: markerMode === 'masters' ? spawnerMasterColor(id) : levels.color }]
  })), [masters, markerMode])
  const masterNumbers = useMemo(() => spawnerMasterNumbers([...masters.keys()]), [masters])
  const placements = useMemo(() => (props.placements ?? []).filter(item => item.worldId === snapshot.manifest.worldId), [props.placements, snapshot.manifest.worldId])
  const terrain = useMemo(() => coverageTerrain(bounds, snapshot.cells), [bounds, snapshot.cells])
  const coverageReady = props.showProposals === false
    ? props.placementsReady ?? (props.placements !== undefined && props.placementCatalog !== undefined) : plan !== null
  const coverageSources = useMemo(() => {
    const circles: CoverageCircle[] = []
    let unknown = 0
    const add = (x: number, z: number, radius: number | null | undefined) => {
      if (!hasRadius(radius) || !Number.isFinite(x) || !Number.isFinite(z)) { unknown++; return }
      circles.push({ x: x + .5, z: z + .5, radius })
    }
    if (props.showProposals === false) {
      for (const item of placements) if (item.kind === 'spawner' && item.enabled) add(item.x, item.z, masters.get(item.templateId ?? '')?.radiusMeters)
    } else {
      for (const item of visibleProposals) add(item.x, item.z, item.radiusMeters)
    }
    return { circles, unknown }
  }, [props.showProposals, placements, masters, visibleProposals])
  const coverage = useMemo(() => showCoverage && coverageReady ? calculateCoverage(terrain, coverageSources.circles) : null,
    [showCoverage, coverageReady, terrain, coverageSources])
  const coverageTotals = useMemo(() => {
    const totals = [0, 0, 0]
    coverage?.counts.forEach((count, index) => {
      const cell = snapshot.cells[index]
      if (count >= 0 && (!region || cell.regionId === region) && (!reason || (plan?.reasonCodes[index] ?? cell.reasonCode) === reason)) totals[count]++
    })
    return totals
  }, [coverage, snapshot.cells, region, reason, plan])
  const coverageRaster = useMemo(() => {
    if (!coverage) return null
    const image = document.createElement('canvas')
    image.width = terrain.width; image.height = terrain.height
    const ctx = image.getContext('2d')
    if (!ctx) return image
    for (let index = 0; index < snapshot.cells.length; index++) {
      const cell = snapshot.cells[index], count = coverage.counts[index]
      if (count < 0 || (region && cell.regionId !== region) || (reason && (plan?.reasonCodes[index] ?? cell.reasonCode) !== reason)) continue
      ctx.fillStyle = coverageColors[count]
      ctx.fillRect((cell.x - bounds.minX) / bounds.cellSize, (cell.z - bounds.minZ) / bounds.cellSize, 1, 1)
    }
    return image
  }, [coverage, terrain, snapshot.cells, bounds, region, reason, plan])
  const orderedPlacements = useMemo(() => [...placements].sort((a, b) => Number(a.placementId === props.selectedPlacement) - Number(b.placementId === props.selectedPlacement)), [placements, props.selectedPlacement])
  const placementAt = (px: number, pz: number) => {
    let distance = 8
    let nearest: WorldPlacement | undefined
    // Equal-distance overlaps use the point drawn last, including a selected point.
    for (let index = orderedPlacements.length - 1; index >= 0; index--) {
      const item = orderedPlacements[index]
      const next = Math.hypot((item.x + .5 - bounds.minX) * view.scale + view.x - px, (item.z + .5 - bounds.minZ) * view.scale + view.z - pz)
      if (next < distance) { distance = next; nearest = item }
    }
    return nearest
  }
  const hovered = (() => {
    if (!pointer || !active || props.placing || drag.current || pointer.x < 0 || pointer.z < 0 || pointer.x >= size.width || pointer.z >= size.height) return null
    const placement = placementAt(pointer.x, pointer.z)
    if (placement) {
      if (placement.kind !== 'spawner') return null
      const master = masters.get(placement.templateId ?? '')
      const levels = [...new Set(master?.levels?.filter(level => Number.isFinite(level) && level > 0) ?? [])].sort((a, b) => a - b)
      return { title: `Dev スポナー · ${placement.enabled ? '有効' : '無効'}`, id: placement.templateId ?? 'マスター未選択',
        placementId: placement.placementId, proposalId: null, radiusMeters: hasRadius(master?.radiusMeters) ? master.radiusMeters : null,
        // Catalog names already pair every mob's Japanese name with its spawn level.
        names: [plainName(master?.name ?? '').trim() || `出現モブ不明 (${placement.templateId ?? 'マスター未選択'})`],
        levels: levels.length ? levels.map(level => `Lv.${level}`).join(' / ') : 'Lv.不明' }
    }
    if (props.showProposals === false) return null
    let distance = 6
    let nearest: (typeof visibleProposals)[number] | undefined
    for (let index = visibleProposals.length - 1; index >= 0; index--) {
      const item = visibleProposals[index]
      const next = Math.hypot((item.x + .5 - bounds.minX) * view.scale + view.x - pointer.x, (item.z + .5 - bounds.minZ) * view.scale + view.z - pointer.z)
      if (next < distance) { distance = next; nearest = item }
    }
    return nearest ? { title: 'AI 配置案', id: nearest.spawnerId, placementId: null, proposalId: nearest.proposalId,
      radiusMeters: hasRadius(nearest.radiusMeters) ? nearest.radiusMeters : null, levels: '', names: nearest.spawnMobs.map(mob => {
      const name = plainName(mob.name).trim() || `モブ名不明 (${mob.mobId})`
      const suffix = name.match(/\s+Lv\.\s*(\d+)\s*$/i)
      const sameLevel = suffix && Number(suffix[1]) === mob.level
      return `${sameLevel ? name.slice(0, suffix.index).trim() : name} · ${suffix && !sameLevel ? '出現 ' : ''}${Number.isFinite(mob.level) && mob.level > 0 ? `Lv.${mob.level}` : 'Lv.不明'}`
    }) } : null
  })()
  const hoveredPlacementId = hovered?.placementId ?? null, hoveredProposalId = hovered?.proposalId ?? null
  const tooltipText = hovered ? [hovered.title, hovered.id, hovered.levels, hovered.radiusMeters, ...hovered.names].join('\n') : ''
  useLayoutEffect(() => {
    if (!tooltip.current) return
    const rect = tooltip.current.getBoundingClientRect()
    setTooltipSize(current => current.width === rect.width && current.height === rect.height ? current : { width: rect.width, height: rect.height })
    const content = tooltipContent.current
    setTooltipClipped(!!content && content.scrollHeight > content.clientHeight)
  }, [tooltipText, size])
  useEffect(() => { setPointer(null) }, [active, props.placing, props.showProposals])
  useEffect(() => {
    const clear = () => { drag.current = null; setPointer(null) }
    window.addEventListener('blur', clear)
    return () => window.removeEventListener('blur', clear)
  }, [])
  const visibleMasters = useMemo(() => [...new Set(placements.filter(item => item.kind === 'spawner').map(item => item.templateId))]
    .sort((a, b) => (masterNumbers.get(a ?? '') ?? Infinity) - (masterNumbers.get(b ?? '') ?? Infinity)), [placements, masterNumbers])
  const selected = placements.find(item => item.placementId === props.selectedPlacement)
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
      setPixelRatio(window.devicePixelRatio || 1)
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
    window.addEventListener('resize', resize)
    return () => { observer.disconnect(); window.removeEventListener('resize', resize) }
  }, [active, bounds])
  useEffect(() => {
    if (!focus) return
    const { width, height } = sizeRef.current
    const scale = Math.max(.7, Math.min(width, height) / 700)
    setView({ scale, x: width / 2 - (focus.x - bounds.minX) * scale, z: height / 2 - (focus.z - bounds.minZ) * scale })
  }, [focus, bounds])
  useEffect(() => {
    if (!detailNeeded || detail) return
    let cancelled = false
    let image: HTMLImageElement | null = null
    let loaded = false
    if (typeof fetch !== 'function') return
    const revision = encodeURIComponent(detailIdentity.revision)
    const controller = new AbortController()
    fetch(`/api/spawner-planning/terrain-detail?revision=${revision}`, { signal: controller.signal })
      .then(response => response.ok ? response.json() : null)
      .then(metadata => {
        if (cancelled || !metadata || metadata.schemaVersion !== 1 || metadata.blocksPerPixel !== 1 ||
            metadata.terrainFingerprint !== detailIdentity.fingerprint || metadata.minX !== detailIdentity.minX ||
            metadata.minZ !== detailIdentity.minZ || metadata.width !== detailIdentity.width || metadata.height !== detailIdentity.height) return
        image = new Image()
        const candidate = image
        image.onload = () => {
          if (!cancelled && candidate.naturalWidth === detailIdentity.width && candidate.naturalHeight === detailIdentity.height) {
            loaded = true
            setDetailResource({ identity: detailIdentity, image: candidate })
          }
        }
        image.src = `/api/spawner-planning/terrain-detail/image?revision=${revision}`
      }).catch(() => { /* The 16m raster remains available. */ })
    return () => {
      cancelled = true; controller.abort()
      if (image) {
        image.onload = null
        if (!loaded) image.src = ''
      }
    }
  }, [detailIdentity, detailNeeded, detail])
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
      if (layer === 'terrain' && cell.status === 'ok' && cell.candidateY != null && !/water|lava/.test(cell.surface ?? '')) {
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
  // Cache the complete scene at the current view. Pointer and tooltip movement do not
  // invalidate terrain, roads, markers or the batched range layer.
  const scene = useMemo(() => {
    const element = document.createElement('canvas'), ctx = element.getContext('2d')
    if (!ctx || !active) return null
    const ratio = pixelRatio
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
    if (coverageRaster) ctx.drawImage(coverageRaster, view.x, view.z, bounds.width * view.scale, bounds.height * view.scale)
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
    const ranges: MapCircle[] = []
    if (radii) {
      for (const proposal of props.showProposals === false ? [] : visibleProposals) {
        if (!hasRadius(proposal.radiusMeters) || selectedProposal === proposal.proposalId) continue
        const [x, z] = screen(proposal.x + .5, proposal.z + .5), radius = proposal.radiusMeters * view.scale
        if (circleIntersectsViewport(x, z, radius, size.width, size.height)) ranges.push({ x, z, radius, color: '#d3dfb83d' })
      }
      for (const placement of placements) {
        const template = masters.get(placement.templateId ?? '')
        if (placement.kind !== 'spawner' || !hasRadius(template?.radiusMeters) || props.selectedPlacement === placement.placementId) continue
        const [x, z] = screen(placement.x + .5, placement.z + .5), radius = template.radiusMeters * view.scale
        const color = masterStyles.get(placement.templateId ?? '')?.color ?? (markerMode === 'masters' ? spawnerMasterColor(placement.templateId) : unknownLevels.color)
        if (circleIntersectsViewport(x, z, radius, size.width, size.height)) ranges.push({ x, z, radius, color: `${color}80` })
      }
      drawSpawnerRanges(ctx, ranges)
    }
    for (const proposal of props.showProposals === false ? [] : visibleProposals) {
      const [x, z] = screen(proposal.x + .5, proposal.z + .5)
      const selected = selectedProposal === proposal.proposalId
      const radius = hasRadius(proposal.radiusMeters) ? proposal.radiusMeters * view.scale : 0
      if (radius > 0 && selected && circleIntersectsViewport(x, z, radius, size.width, size.height)) {
        ctx.beginPath(); ctx.arc(x, z, radius, 0, Math.PI * 2)
        ctx.strokeStyle = '#fff1a2'; ctx.lineWidth = 2; ctx.stroke()
      }
      if (x < -6 || z < -6 || x > size.width + 6 || z > size.height + 6) continue
      const r = selected ? 6 : view.scale > .3 ? 4 : 2
      ctx.beginPath(); ctx.moveTo(x, z - r); ctx.lineTo(x + r, z + r); ctx.lineTo(x - r, z + r); ctx.closePath()
      ctx.fillStyle = selected ? '#fff0ac' : '#e3f2c8'; ctx.fill()
      ctx.strokeStyle = '#101b29'; ctx.lineWidth = 1; ctx.stroke()
    }
    const labels: { x: number; z: number; width: number }[] = []
    // Draw the selection last so dense neighboring points cannot cover it.
    for (const placement of orderedPlacements) {
      const [x, z] = screen(placement.x + .5, placement.z + .5)
      const selected = props.selectedPlacement === placement.placementId
      const template = masters.get(placement.templateId ?? '')
      const style = masterStyles.get(placement.templateId ?? ''), levels = style?.levels ?? unknownLevels
      const color = placement.kind === 'spawner' ? style?.color ?? (markerMode === 'masters' ? spawnerMasterColor(placement.templateId) : levels.color) : placement.kind === 'npc' ? '#ec99fa' : '#f7cf6d'
      const number = masterNumbers.get(placement.templateId ?? '')
      const radius = hasRadius(template?.radiusMeters) ? template.radiusMeters * view.scale : 0
      if (placement.kind === 'spawner' && radius > 0 && selected && circleIntersectsViewport(x, z, radius, size.width, size.height)) {
        ctx.beginPath(); ctx.arc(x, z, radius, 0, Math.PI * 2)
        ctx.strokeStyle = '#ffffff'; ctx.lineWidth = 2; ctx.stroke()
      }
      if (x < -60 || z < -60 || x > size.width + 60 || z > size.height + 60) continue
      const r = selected ? 8 : view.scale > .3 ? 5 : 2.8
      ctx.beginPath()
      if (placement.kind === 'npc') { ctx.moveTo(x, z - r); ctx.lineTo(x + r, z); ctx.lineTo(x, z + r); ctx.lineTo(x - r, z); ctx.closePath() }
      else if (placement.kind === 'waystone') ctx.rect(x - r, z - r, r * 2, r * 2)
      else ctx.arc(x, z, r, 0, Math.PI * 2)
      ctx.fillStyle = placement.enabled ? color : '#152131'; ctx.fill()
      ctx.strokeStyle = '#0b1320'; ctx.lineWidth = selected ? 4 : 2; ctx.stroke()
      ctx.strokeStyle = placement.enabled ? selected ? '#fff' : '#edf4f7' : color
      ctx.lineWidth = selected ? 2 : 1; ctx.stroke()
      if (!placement.enabled) { ctx.beginPath(); ctx.moveTo(x - r, z + r); ctx.lineTo(x + r, z - r); ctx.stroke() }
      if (selected || (placement.kind === 'spawner' && view.scale >= 2)) {
        const text = placement.kind === 'spawner' ? `#${number ?? '?'} ${selected ? plainName(template?.name ?? placement.templateId ?? 'マスター不明') + ' · ' : ''}${levels.label}${placement.enabled ? '' : ' · 無効'}`
          : `${placement.kind === 'npc' ? plainName(props.placementCatalog?.npcs.find(item => item.id === placement.templateId)?.name ?? placement.templateId ?? 'NPC') : plainName(placement.waystone?.name ?? '道標')}${placement.enabled ? '' : ' · 無効'}`
        ctx.font = 'bold 11px sans-serif'
        const width = Math.min(size.width - 16, ctx.measureText(text).width + 12)
        const lx = Math.max(4, Math.min(size.width - width - 4, x + r + 5)), lz = Math.max(4, Math.min(size.height - 20, z - 10))
        if (selected || !labels.some(label => lx < label.x + label.width + 4 && lx + width + 4 > label.x && Math.abs(lz - label.z) < 22)) {
          ctx.fillStyle = '#101b29ee'; ctx.fillRect(lx, lz, width, 20)
          ctx.strokeStyle = selected ? '#fff' : color; ctx.lineWidth = 1; ctx.strokeRect(lx, lz, width, 20)
          ctx.fillStyle = '#fff'; ctx.fillText(text, lx + 6, lz + 14, width - 12)
          labels.push({ x: lx, z: lz, width })
        }
      }
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
    return element
  }, [active, raster, detail, detailMask, coverageRaster, layer, view, size, pixelRatio, bounds, radii, visibleProposals, snapshot, selectedCell, selectedProposal, orderedPlacements, placements, masters, masterStyles, masterNumbers, markerMode, props.placementCatalog, props.selectedPlacement, props.showProposals])
  useEffect(() => {
    const element = canvas.current, ctx = element?.getContext('2d')
    if (!element || !ctx || !active || !scene) return
    // Resizing resets the context and backing store; only do it when dimensions change.
    if (element.width !== scene.width) element.width = scene.width
    if (element.height !== scene.height) element.height = scene.height
    const ratio = pixelRatio
    ctx.setTransform(ratio, 0, 0, ratio, 0, 0)
    ctx.imageSmoothingEnabled = false
    ctx.clearRect(0, 0, size.width, size.height)
    ctx.drawImage(scene, 0, 0, size.width, size.height)
    const placement = hoveredPlacementId ? placements.find(item => item.placementId === hoveredPlacementId && item.placementId !== props.selectedPlacement) : null
    const proposal = hoveredProposalId ? visibleProposals.find(item => item.proposalId === hoveredProposalId && item.proposalId !== selectedProposal) : null
    const point = placement ?? proposal
    const radius = placement ? masters.get(placement.templateId ?? '')?.radiusMeters : proposal?.radiusMeters
    if (point && hasRadius(radius)) {
      const x = (point.x + .5 - bounds.minX) * view.scale + view.x, z = (point.z + .5 - bounds.minZ) * view.scale + view.z
      ctx.beginPath(); ctx.arc(x, z, radius * view.scale, 0, Math.PI * 2)
      // An opaque wider stroke replaces the normal ring visually, without rebuilding it.
      ctx.strokeStyle = '#9ee9ef'; ctx.lineWidth = 2; ctx.stroke()
    }
  }, [active, scene, size, pixelRatio, view, bounds, hoveredPlacementId, hoveredProposalId, placements, visibleProposals, masters, props.selectedPlacement, selectedProposal])
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
  return <div className="sp-map-panel">
    <div className="sp-coverage-controls" aria-label="出現範囲の重複と未カバー">
      <label><input type="checkbox" checked={showCoverage} onChange={event => setShowCoverage(event.target.checked)} />重複・未カバーを色で確認</label>
      <strong>判定: {props.showProposals === false ? '表示中の有効な Dev スポナー' : '選択地域の AI 配置案のみ'}</strong>
      <div className="sp-coverage-key">{[coverageSources.unknown ? '既知範囲0件' : '未カバー（0件）', coverageSources.unknown ? '既知範囲1件' : '1件の範囲内', '重複（2件以上）'].map((label, index) => <span key={index}><i style={{ background: coverageColors[index] }} />{label}{coverage ? ` · ${coverageTotals[index].toLocaleString('ja-JP')}区画` : ''}</span>)}</div>
      {showCoverage && !coverageReady && <p role="status">{props.showProposals === false ? 'Dev 配置とマスターの取得が完了していないため' : 'AI 配置案を取得できていないため'}、重複・未カバーの判定を表示していません。</p>}
      {showCoverage && coverageReady && coverageSources.unknown > 0 && <p className="sp-coverage-warning" role="status">半径・座標不明の有効な対象が{coverageSources.unknown}件あります。色は既知範囲の件数の下限です。0件の場所も未カバーとは断定できません。</p>}
      <p>{bounds.cellSize}m区画の中心点と水平円の概算です。橙は既知の円に含まれない地点、紫は重複の目安。区画全体や実際のスポーン可否は保証しません。地形画像が1m表示でも判定は{bounds.cellSize}mです。</p>
      <details><summary>判定の対象と除外条件</summary><p>安全な足場を一部含み、候補座標が測定済みの陸地区画だけを着色します。区画中心そのものの足場は保証しません。水域・溶岩・未解析・解析失敗・未保存・地表や生息環境が不明・安全な足場や候補座標が不明の区画は着色しません。保護範囲や将来地域も含む地形の確認であり、AI 計画の配置対象・カバー率とは判定条件が異なります。高度差・地形の接続・区画四隅・モブ条件は判定しません。</p><p>{props.showProposals === false ? '編集の表示フィルターと、条件外でも地図に残る選択中の配置が対象です。未保存の編集も反映します。無効な配置・NPC・道標は数えません。Dev の半径はマスター API の値を使います。' : '地域で絞り込んだ全 AI 案だけを数えます。併記される Dev 配置は数えません。配置状況は着色する区画を絞り込み、一覧の検索・件数制限は計数対象を減らしません。'} 円の表示スイッチとは独立しています。色をOFFにすると元の地形・レイヤーを確認できます。凡例の件数は地図の地域・配置状況に合う区画数です（画面外を含みます）。</p></details>
      {coverage && selectedCell !== null && <p aria-live="polite">選択区画: {coverage.counts[selectedCell] < 0 ? '判定対象外（測定済みの安全な陸地を確認できません）' : coverage.counts[selectedCell] === 2 ? '既知範囲が2件以上重複' : `既知範囲${coverage.counts[selectedCell]}件`}</p>}
    </div>
    <div className="sp-marker-controls"><label>スポナーの色<select aria-label="スポナーの色分け" value={markerMode} onChange={event => setMarkerMode(event.target.value as 'masters' | 'levels')}><option value="masters">マスター別</option><option value="levels">出現する実レベル別</option></select></label><span>● Dev スポナー　◆ NPC　■ 道標{props.showProposals !== false && '　▲ AI 配置案'}　◉ 選択中　⊘ 無効</span></div>
    <div className="sp-map-wrap">
    <canvas ref={canvas} aria-label={props.showProposals === false ? '保存済み Dev 配置の地図。ドラッグで移動、ホイールで拡大、点をクリックして配置を選択' : 'エリヴァ全大陸の地図。ドラッグで移動、ホイールで拡大、クリックで地域と未配置理由を確認'} tabIndex={0}
      onPointerDown={event => { setPointer(null); drag.current = { x: event.clientX, y: event.clientY, moved: 0 }; event.currentTarget.setPointerCapture(event.pointerId) }}
      onPointerMove={event => {
        const rect = event.currentTarget.getBoundingClientRect()
        const x = (event.clientX - rect.left - view.x) / view.scale + bounds.minX, z = (event.clientY - rect.top - view.z) / view.scale + bounds.minZ
        setCoordinates(`X ${Math.floor(x)} / Z ${Math.floor(z)}`)
        const previous = drag.current
        setPointer(!previous && event.pointerType !== 'touch' && event.buttons === 0 ? { x: event.clientX - rect.left, z: event.clientY - rect.top } : null)
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
          if (index !== undefined) {
            const cell = snapshot.cells[index]
            props.onPlacePoint(cell.candidateX ?? Math.floor(x), cell.candidateX == null || cell.candidateY == null || cell.candidateZ == null ? null : cell.candidateY, cell.candidateZ ?? Math.floor(z))
          }
          return
        }
        const closest = placementAt(px, pz)
        if (closest && props.onPlacement) { props.onPlacement(closest.placementId); return }
        // At overview scale dots are dense; prioritize the terrain cell until zoomed in.
        let distance = 6
        let nearest: (typeof visibleProposals)[number] | undefined
        if (props.showProposals !== false && view.scale > .25) for (const value of visibleProposals) {
          const next = Math.hypot((value.x + .5 - x) * view.scale, (value.z + .5 - z) * view.scale)
          if (next < distance) { distance = next; nearest = value }
        }
        if (nearest) onProposal(nearest.proposalId)
        else { const index = cellAtWorld(bounds, grid, x, z); if (index !== undefined) onCell(index) }
      }}
      onPointerCancel={() => { drag.current = null; setPointer(null) }}
      onLostPointerCapture={() => { drag.current = null; setPointer(null) }}
      onPointerLeave={() => setPointer(null)}
      onBlur={() => setPointer(null)}
      onKeyDown={event => {
        if (['+', '-', 'Home', 'ArrowLeft', 'ArrowRight', 'ArrowUp', 'ArrowDown'].includes(event.key)) {
          event.preventDefault()
          if (event.key === '+') zoom(1.4)
          else if (event.key === '-') zoom(1 / 1.4)
          else if (event.key === 'Home') fit()
          else setView(current => ({ ...current, x: current.x + (event.key === 'ArrowLeft' ? 60 : event.key === 'ArrowRight' ? -60 : 0), z: current.z + (event.key === 'ArrowUp' ? 60 : event.key === 'ArrowDown' ? -60 : 0) }))
        }
      }} />
    {hovered && pointer && <div ref={tooltip} role="tooltip" className="sp-map-tooltip" style={{
      width: Math.min(360, size.width - 16),
      left: Math.max(8, Math.min(size.width - tooltipSize.width - 8, pointer.x + 14 + tooltipSize.width <= size.width - 8 ? pointer.x + 14 : pointer.x - tooltipSize.width - 14)),
      top: Math.max(8, Math.min(size.height - tooltipSize.height - 8, pointer.z + 14 + tooltipSize.height <= size.height - 8 ? pointer.z + 14 : pointer.z - tooltipSize.height - 14)),
    }}><strong>{hovered.title}</strong><div ref={tooltipContent} className="sp-map-tooltip-content">{hovered.names.map((name, index) => <div key={index}>{name}</div>)}{hovered.levels && <small>出現する実レベル: {hovered.levels}</small>}<small>出現半径: {hovered.radiusMeters == null ? '不明' : `${hovered.radiusMeters} m`}</small><small>{hovered.id}</small></div>{tooltipClipped && <small className="sp-map-tooltip-more">クリック・一覧の詳細で全文を確認</small>}</div>}
    <div className="sp-map-tools"><button className="button" onClick={() => zoom(1.5)} aria-label="地図を拡大">＋</button><button className="button" onClick={() => zoom(1 / 1.5)} aria-label="地図を縮小">−</button><button className="button" onClick={fit}>全大陸</button></div>
    <span className="sp-north">N ↑</span><output className="sp-coordinates">{coordinates} · {view.scale.toFixed(view.scale < 1 ? 2 : 1)} px/m · {layer === 'terrain' && detail && view.scale >= .4 ? '1m地形' : '16m区画'}</output>
    </div>
    {selected && <p className="sp-map-selection" aria-live="polite">選択中: {selected.kind === 'spawner' ? `#${masterNumbers.get(selected.templateId ?? '') ?? '?'} ${plainName(masters.get(selected.templateId ?? '')?.name ?? selected.templateId ?? 'マスター不明')} · ${spawnerLevels(masters.get(selected.templateId ?? '')?.levels ?? []).label}` : selected.kind === 'npc' ? plainName(props.placementCatalog?.npcs.find(item => item.id === selected.templateId)?.name ?? selected.templateId ?? 'NPC') : plainName(selected.waystone?.name ?? '道標')} · {selected.enabled ? '有効' : '無効'} · X {selected.x} / Z {selected.z}</p>}
    <div className="sp-marker-key" aria-label="スポナーの凡例">
      {markerMode === 'levels' && <div className="sp-marker-bands">{spawnerLevelBands.map(band => <span key={band.label}><i style={{ background: band.color }} />{band.label}</span>)}<span><i style={{ background: '#b0bbc8' }} />複数レベル・不明</span></div>}
      <details><summary>表示中のスポナーマスター ({visibleMasters.length}) · 番号は一覧と共通</summary><div className="sp-master-key-list">{visibleMasters.map(id => {
        const master = masters.get(id ?? ''), levels = spawnerLevels(master?.levels ?? [])
        return <span key={id ?? 'unknown'} title={id ?? 'マスター未選択'}><b style={{ borderColor: markerMode === 'masters' ? spawnerMasterColor(id) : levels.color }}>#{masterNumbers.get(id ?? '') ?? '?'}</b><span>{plainName(master?.name ?? id ?? 'マスター未選択')}<small>{levels.label} · {id ?? 'マスター未選択'}</small></span></span>
      })}</div></details>
      <p>2px/m以上に拡大すると番号と実レベルを表示します。通常は選択した点だけにラベルを表示し、密集時は重なるラベルを省略します。</p>
      {layer === 'terrain' && <p><i className="sp-unknown-key" />青灰: 未解析・解析失敗　<i className="sp-absent-key" />濃灰: 未保存。地形や水域とは区別しています。{props.showProposals !== false && '詳細は区画の選択、または「配置・未配置の理由」で確認できます。'}</p>}
    </div>
  </div>
}

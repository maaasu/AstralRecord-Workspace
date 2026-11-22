import { useMemo } from 'react'
import { describeNodeCost, nodeTooltip } from '../data/nodeEffectPresentation'
import { masterTagSearchText, masterTagTooltip } from '../data/masterTagPresentation'
import type { ClassMasterSummary, NodeMaster, SkillMasterSummary } from '../types/editor'
import { stripMinecraftFormatting } from '../utils/minecraft'
import { NODE_KIND_LABEL, mapNodeEffects, nodeClassName, nodeKind, type MapEffect, type NodeKind } from './skillTreeMapPresentation'

export interface NodeListingEntry {
  node: NodeMaster
  name: string
  effects: MapEffect[]
  className: string
  kind: NodeKind
  tooltip: string
  searchText: string
}

const EMPTY_CLASS_MASTERS: readonly ClassMasterSummary[] = []
const normalizeSearch = (text: string) => stripMinecraftFormatting(text).toLocaleLowerCase('ja-JP')

export function useNodeListing({
  nodes, skillMasters, classMasters = EMPTY_CLASS_MASTERS, rootNodeId, query, selectedTag, selectedKind,
}: {
  nodes: readonly NodeMaster[]
  skillMasters: readonly SkillMasterSummary[]
  classMasters?: readonly ClassMasterSummary[]
  rootNodeId?: string
  query: string
  selectedTag: string
  selectedKind?: NodeKind | ''
}) {
  const entries = useMemo<NodeListingEntry[]>(() => nodes.map(node => {
    const effects = mapNodeEffects(node, skillMasters)
    const cost = describeNodeCost(node, classMasters)
    const name = stripMinecraftFormatting(node.name)
    const tags = (node.tags ?? []).map(masterTagTooltip)
    const kind = nodeKind(node, node.nodeId === rootNodeId)
    return {
      node, name, effects, className: nodeClassName(node, classMasters), kind,
      tooltip: [nodeTooltip(node, effects, classMasters), ...(tags.length ? ['', 'タグ:', ...tags] : [])].join('\n'),
      searchText: normalizeSearch([node.nodeId, name, ...(node.tags ?? []).map(masterTagSearchText), cost.searchText, NODE_KIND_LABEL[kind], ...effects.map(effect => effect.searchText)].join('\n')),
    }
  }), [nodes, skillMasters, classMasters, rootNodeId])
  const words = useMemo(() => normalizeSearch(query).trim().split(/\s+/u).filter(Boolean), [query])

  return useMemo(() => {
    if (!selectedTag && !selectedKind && !words.length) return { entries, matchingNodeIds: null }
    const filtered = entries.filter(entry => (!selectedTag || entry.node.tags?.includes(selectedTag))
      && (!selectedKind || entry.kind === selectedKind)
      && words.every(word => entry.searchText.includes(word)))
    return { entries: filtered, matchingNodeIds: new Set(filtered.map(entry => entry.node.nodeId)) }
  }, [entries, words, selectedTag, selectedKind])
}

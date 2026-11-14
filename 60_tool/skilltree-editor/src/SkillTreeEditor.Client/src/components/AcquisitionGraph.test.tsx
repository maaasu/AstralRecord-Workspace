import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import type { DesignItem, DesignSource, ProgressionDesignCatalog } from '../types/progressionDesign'
import { buildAcquisitionDiagram, indexAcquisition, selectedAcquisitionPath } from '../data/progressionGraph'
import { AcquisitionGraph } from './AcquisitionGraph'

function item(id: string, name = id): DesignItem {
  return { id, name, path: `${id}.yml`, category: null, rarity: null, icon: null, customModelData: null, iconTexture: null,
    slot: null, handType: null, requiredLevel: 1, requiredClasses: [], compatibleSlots: [], maxEnhanceLevel: 0 }
}

function source(id: string, itemId: string, fields: Partial<DesignSource> = {}): DesignSource {
  const path = `${id}.yml`
  return { id, kind: 'mob', name: id, itemId, path, pointer: '/result', itemPath: path, itemPointer: '/result/item', quantity: [],
    rates: [], costs: [], ingredients: [], ingredientContainer: null, context: [], level: null, mode: null,
    availability: 'configured', warnings: [], ...fields }
}

const recipe = source('forge', 'blade', { kind: 'recipe', name: '鍛造定義', availability: 'definitionOnly', ingredients: [
  { itemId: 'ore', path: 'forge.yml', pointer: '/ingredients/0', itemPointer: '/ingredients/0/ref', quantity: [{ path: 'forge.yml', pointer: '/ingredients/0/amount', value: 3 }] },
  { itemId: 'thread', path: 'forge.yml', pointer: '/ingredients/1', itemPointer: '/ingredients/1/ref', quantity: [] },
] })
const shop = source('shop', 'blade', { kind: 'shop', name: '武器店', ingredients: recipe.ingredients,
  context: [{ kind: 'recipe', path: recipe.path, pointer: recipe.pointer, id: recipe.id, name: recipe.name }] })
const ore = source('mine', 'ore', { kind: 'gathering', name: '鉱脈', rates: [{ path: 'mine.yml', pointer: '/rate', value: .25 }] })
const direct = source('wolf', 'blade', { name: '大狼' })
const catalog: ProgressionDesignCatalog = { items: [item('blade', '&l長剣'), item('ore', '鉄鉱石'), item('thread', '糸')],
  sources: [recipe, shop, ore, direct], usages: [], classes: [], presets: [], addTargets: [] }

describe('acquisition diagram', () => {
  it('collapses the recipe represented by the shop and highlights only the selected branch', () => {
    const data = { ...catalog, sources: [...catalog.sources, { ...ore, id: 'mine-alias' }] }
    const graph = buildAcquisitionDiagram(indexAcquisition(data), 'blade')
    expect(graph.columns.map(column => column.map(node => node.label))).toEqual([['大狼', '鉱脈'], ['鉄鉱石', '糸'], ['武器店'], ['長剣']])
    expect(graph.columns[0][1].sourceIds).toEqual(['mine', 'mine-alias'])
    expect(graph.columns[1][1].notes).toContain('入手経路未確認')
    expect([...selectedAcquisitionPath(graph, 'shop')].sort()).toEqual(['0:mine', '1:ore', '1:thread', '2:shop', '3:blade'])
  })

  it('keeps a selected route beyond the display limit, marks cycles and counts omissions', () => {
    const sources = Array.from({ length: 18 }, (_, n) => source(`drop-${n}`, 'blade'))
    const cycle = source('cycle', 'blade', { kind: 'recipe', ingredients: [
      { itemId: 'blade', path: 'cycle.yml', pointer: '/ingredients/0', itemPointer: '/ingredients/0/ref', quantity: [] },
    ] })
    const graph = buildAcquisitionDiagram(indexAcquisition({ ...catalog, sources: [...sources, cycle] }), 'blade', 'cycle')
    expect(graph.routes).toBe(19)
    expect(graph.hidden).toBe(7)
    expect(graph.columns[2][0].source?.id).toBe('cycle')
    expect(graph.columns[1][0].notes).toContain('循環参照')
  })

  it('uses the same source and material actions in the graph and the list', () => {
    const onSelectSource = vi.fn()
    const onSelectItem = vi.fn()
    render(<AcquisitionGraph catalog={catalog} itemId="blade" selectedSourceId="shop" onSelectItem={onSelectItem} onSelectSource={onSelectSource} />)
    expect(screen.getByRole('region', { name: /入手経路図/ })).toBeVisible()
    expect(screen.getByRole('button', { name: /武器店/ })).toHaveAttribute('aria-pressed', 'true')
    fireEvent.click(screen.getByRole('button', { name: /鉱脈/ }))
    expect(onSelectSource).toHaveBeenCalledWith('mine')
    expect(screen.getByText(/設定値 rate 0.25/)).toBeVisible()
    fireEvent.click(screen.getByRole('button', { name: '一覧' }))
    expect(screen.getByText('← 鉄鉱石 · 数量 3')).toBeVisible()
    fireEvent.click(screen.getByRole('button', { name: /必要素材、鉄鉱石/ }))
    expect(onSelectItem).toHaveBeenCalledWith('ore')
  })
})

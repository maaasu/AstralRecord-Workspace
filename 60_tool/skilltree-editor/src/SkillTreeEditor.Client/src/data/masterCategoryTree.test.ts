import { describe, expect, it } from 'vitest'
import { buildMasterCategoryTree } from './masterCategoryTree'
import type { MasterCategory, MasterFileSummary } from '../types/masterData'

const category = (id: string, directory: string, label: string): MasterCategory => ({ id, directory, label, fileCount: 999, documents: [], fields: [], jsonSchemas: [], templates: [] })
const file = (path: string): MasterFileSummary => ({ path, category: '', format: 'yaml', revision: '', size: 0, modifiedUtc: '' })

describe('master category tree structure', () => {
  it('creates virtual parents from label segments and preserves real category IDs', () => {
    const roots = buildMasterCategoryTree([category('original-pool-id', '80.shared.loot/pool', 'ルート / ルートプール'), category('original-table-id', '80.shared.loot/table', 'ルート / ルートテーブル')], [file('80.shared.loot/pool/v1.a.yml'), file('80.shared.loot/table/v1.b.yml')])
    expect(roots[0]).toMatchObject({ directory: '80.shared.loot', number: '80', label: 'ルート', count: 2 })
    expect(roots[0].category).toBeUndefined()
    expect(roots[0].children.map((node) => node.category?.id)).toEqual(['original-pool-id', 'original-table-id'])
  })
  it('keeps root settings separate, unknown folders intact, and numeric ordering', () => {
    const roots = buildMasterCategoryTree([category('settings', '.', 'Filebase 設定'), category('later', '20.unknown', '20.unknown'), category('early', '3.unknown', '3.unknown')], [file('config.yml'), file('20.unknown/a.yml')])
    expect(roots.map((node) => node.directory)).toEqual(['.', '3.unknown', '20.unknown'])
    expect(roots[0]).toMatchObject({ label: 'Filebase 設定', count: 1, children: [] })
    expect(roots[2].label).toBe('20.unknown')
  })
  it('counts parent descendants with existing item set-effect exclusions', () => {
    const roots = buildMasterCategoryTree([category('item', '10.features.item', 'アイテム'), category('effect', '10.features.item/20.equipment/set_effect', 'アイテム / 装備 / セット効果')], [file('10.features.item/v1.a.yml'), file('10.features.item/20.equipment/set_effect/v1.b.yml')])
    expect(roots[0].count).toBe(1)
    expect(roots[0].children[0].count).toBe(0)
    expect(roots[0].children[0].children[0].count).toBe(1)
  })
})

import { expect, it } from 'vitest'
import { filterMasterReferences, indexMasterReferences } from './masterReferenceSearch'
import type { MasterCategory, MasterFileSummary, MasterReference } from '../types/masterData'

const category = (directory: string, label: string): MasterCategory => ({ directory, id: directory, label, fileCount: 0, documents: [], fields: [], jsonSchemas: [], templates: [] })
const file = (path: string): MasterFileSummary => ({ path, category: path.slice(0, path.lastIndexOf('/')), format: 'yaml', size: 1, revision: '1', modifiedUtc: '' })
it('参照先kindとは別に最深の参照元カテゴリを選び、境界・未分類・一覧外を区別する', () => {
  const paths = ['spawners/mobs/wolf.yml', 'spawners/other.yml', 'spawners-old/wolf.yml', '../plugin/config.yml']
  const references: MasterReference[] = paths.map(path => ({ path, kind: 'mob', pointer: '/spawnMobs/0/mobId', value: 'mob:wolf', label: '森&a林&#aabbcc狼' }))
  const index = indexMasterReferences(references, paths.slice(0, 3).map(file), [category('spawners', 'スポナー'), category('spawners/mobs', 'モブスポナー')])
  expect(index.rows.map(row => row.categoryLabel)).toEqual(['モブスポナー', 'スポナー', 'spawners-old', 'ファイル一覧外'])
  expect(index.rows.map(row => row.entry.kind)).toEqual(['mob', 'mob', 'mob', 'mob'])
  expect(index.rows.map(row => row.canOpen)).toEqual([true, true, true, false])
  expect(filterMasterReferences(index, '森林狼　MOB:WOLF /spawnMobs', 'category:spawners/mobs').map(row => row.entry.path)).toEqual([paths[0]])
  expect(filterMasterReferences(index, 'ファイル一覧外 CONFIG', '')[0].entry.path).toBe(paths[3])
  expect(filterMasterReferences(index, ' \t　', '')).toHaveLength(4)
  expect(filterMasterReferences(index, '存在しない', '')).toHaveLength(0)
})

it('同じファイルの複数参照を箇所数として数え、同名カテゴリを別の条件に保つ', () => {
  const references: MasterReference[] = [{ path: 'one/a.yml', pointer: '/a', value: 'x' }, { path: 'one/a.yml', pointer: '/b', value: 'x' }, { path: 'two/a.yml', pointer: '/a', value: 'x' }]
  const index = indexMasterReferences(references, [file('one/a.yml'), file('two/a.yml')], [category('one', '同名'), category('two', '同名')])
  expect(index.categories).toEqual([{ key: 'category:one', label: '同名', count: 2 }, { key: 'category:two', label: '同名', count: 1 }])
  expect(filterMasterReferences(index, '', 'category:one')).toHaveLength(2)
})

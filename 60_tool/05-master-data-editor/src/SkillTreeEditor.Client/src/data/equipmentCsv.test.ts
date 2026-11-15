import { afterEach, describe, expect, it, vi } from 'vitest'
import { equipmentCsvRows } from './equipmentCsv'
import { downloadCsv, equipmentRows } from './masterAnalytics'

afterEach(() => { vi.unstubAllGlobals(); vi.restoreAllMocks() })

describe('equipment CSV contract', () => {
  it('appends context after existing bounds and preserves raw numbers and absent values', () => {
    const rows = equipmentRows([{ path: 'item/test.yml', progression: null, content: { id: 'a', name: '&6剣', equipment: { stats: [] } } }], 0)
    rows[0].stats = { A: { min: -1, max: 2, expected: 0.1234567890123 }, B: { min: 0, max: 0, expected: 0 }, C: { min: 0, max: 0 } }
    const csv = equipmentCsvRows(rows, ['A', 'B', 'C', 'D'], 99)
    expect(csv[0]).toEqual(['ID', '名前', 'スロット', 'タグ', '要求Lv', '進行度', 'A 下限', 'A 上限', 'B 下限', 'B 上限', 'C 下限', 'C 上限', 'D 下限', 'D 上限', 'A 期待値', 'B 期待値', 'C 期待値', 'D 期待値', '指定強化Lv', 'ソースパス'])
    expect(csv[1]).toEqual(['a', '剣', '', '', 0, null, -1, 2, 0, 0, 0, 0, undefined, undefined, 0.1234567890123, 0, undefined, undefined, 99, 'item/test.yml'])
    expect(equipmentCsvRows([], [], 0)).toEqual([['ID', '名前', 'スロット', 'タグ', '要求Lv', '進行度', '指定強化Lv', 'ソースパス']])
  })

  it('uses existing CSV quoting, empty cells, numeric precision and formula protection for new fields', async () => {
    const rows = equipmentRows([{ path: '=source,"name"\nfile.yml', progression: null, content: { id: 'a', name: '=名前,"注記"\n次行', equipment: { stats: [{ status: 'A', value: -3 }] } } }], 0)
    rows[0].stats.B = { min: 0, max: 1, expected: 0.1234567890123 }
    rows[0].stats.C = { min: 0, max: 0, expected: 0 }
    let exported: Blob | undefined
    const create = vi.fn((blob: Blob) => { exported = blob; return 'blob:equipment-test' })
    const revoke = vi.fn()
    vi.stubGlobal('URL', { createObjectURL: create, revokeObjectURL: revoke })
    let filename = ''
    vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function (this: HTMLAnchorElement) { filename = this.download })
    downloadCsv('equipment-comparison.csv', equipmentCsvRows(rows, ['A', 'B', 'C', 'D'], 0))
    expect(filename).toBe('equipment-comparison.csv')
    expect(exported?.type).toBe('text/csv;charset=utf-8')
    const text = await new Promise<string>((resolve, reject) => {
      const reader = new FileReader()
      reader.onload = () => resolve(String(reader.result))
      reader.onerror = () => reject(reader.error)
      reader.readAsText(exported!)
    })
    expect(text).toContain('"\'=名前,""注記""\n次行"')
    expect(text).toContain('"-3","-3"')
    expect(text).toContain('"","0.1234567890123","0","","0","\'=source,""name""\nfile.yml"')
    expect(revoke).toHaveBeenCalledWith('blob:equipment-test')
  })
})

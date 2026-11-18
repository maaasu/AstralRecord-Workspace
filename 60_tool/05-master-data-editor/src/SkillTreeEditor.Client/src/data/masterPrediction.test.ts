import { describe, expect, it } from 'vitest'
import { buildMasterPredictionIndex, findLocalPredictions, updatePredictionHistory } from './masterPrediction'

const path = 'items/material/current.yml'
const pointer = '/variants/0/name'

describe('master predictions', () => {
  it('offers ranked visible candidates for an empty field and rejects whitespace-only input', () => {
    const fieldPointer = '/name'
    const index = buildMasterPredictionIndex([
      { path: 'items/weapon/other.yml', fieldPointer, text: '&6かけら' },
      { path: 'items/material/other.yml', fieldPointer, text: '&fグラスボア' },
      { path: 'items/material/other.yml', fieldPointer, text: '&aグラスボア' },
      { path: 'items/material/other.yml', fieldPointer, text: '&b先頭\n続き' },
    ])
    const history = updatePredictionHistory([], path, fieldPointer, '&f月の欠片')
    expect(findLocalPredictions(index, history, path, fieldPointer, '', 10))
      .toEqual(['月の欠片', 'グラスボア', 'かけら'])
    expect(findLocalPredictions(index, history, path, fieldPointer, ' ', 10)).toEqual([])
  })

  it('ranks same-category matches by exact and normalized field pointers', () => {
    const index = buildMasterPredictionIndex([
      { path: 'items/weapon/other.yml', fieldPointer: pointer, text: '星の剣' },
      { path: 'items/material/other.yml', fieldPointer: '/name', text: '星の粉' },
      { path: 'items/material/other.yml', fieldPointer: '/variants/2/name', text: '星の枝' },
      { path: 'items/material/other.yml', fieldPointer: pointer, text: '星の石' },
      { path: 'items/material/other.yml', fieldPointer: '/lore', text: '星の説明' },
    ])
    expect(findLocalPredictions(index, [], path, pointer, '星の', Infinity)).toEqual(['星の石', '星の枝', '星の粉'])
  })

  it('offers the saved value from the current file after shortening it', () => {
    const index = buildMasterPredictionIndex([{ path, fieldPointer: pointer, text: '月の欠片' }])
    expect(findLocalPredictions(index, [], path, pointer, '月の', Infinity)).toEqual(['月の欠片'])
    expect(findLocalPredictions(index, [], path, pointer, '月の欠片', Infinity)).toEqual([])
  })

  it('prioritizes recent local history and deduplicates against other files', () => {
    const other = 'items/material/other.yml'
    const index = buildMasterPredictionIndex([
      { path: other, fieldPointer: pointer, text: '星の杖' },
      { path: other, fieldPointer: pointer, text: '星の石' },
    ])
    let history = updatePredictionHistory([], path, pointer, '星の石')
    history = updatePredictionHistory(history, path, pointer, '星の結晶')
    expect(findLocalPredictions(index, history, path, pointer, '星の', Infinity)).toEqual(['星の結晶', '星の石', '星の杖'])
    history = updatePredictionHistory(history, path, pointer, '星の石')
    expect(history.map(entry => entry.text)).toEqual(['星の石', '星の結晶'])
  })

  it('keeps the literal prefix and respects the input length and line count', () => {
    const fieldPointer = '/lore'
    const other = 'items/material/other.yml'
    const index = buildMasterPredictionIndex([
      { path: other, fieldPointer, text: '星の羽根' },
      { path: other, fieldPointer, text: '§b星の' },
      { path: other, fieldPointer, text: '§b星の\n青' },
      { path: other, fieldPointer, text: '§b星の長い羽根' },
      { path: other, fieldPointer, text: '§b星の翼' },
    ])
    expect(findLocalPredictions(index, [], path, fieldPointer, '§b星の', 6)).toEqual(['§b星の翼'])
    expect(findLocalPredictions(index, [], path, fieldPointer, '§b星の', 6, true)).toEqual(['§b星の\n青', '§b星の翼'])
  })

  it('bounds retained history and skips blank inputs', () => {
    let history: ReturnType<typeof updatePredictionHistory> = []
    for (let index = 0; index <= 200; index++) {
      history = updatePredictionHistory(history, path, pointer, `星の石 ${index}`)
    }
    expect(history).toHaveLength(200)
    expect(history[0].text).toBe('星の石 200')
    expect(history.at(-1)?.text).toBe('星の石 1')
    expect(updatePredictionHistory(history, path, pointer, '  ')).toBe(history)
  })

  it('matches the visible text and preserves explicit Minecraft formatting', () => {
    const other = 'items/material/other.yml'
    const index = buildMasterPredictionIndex([
      { path: other, fieldPointer: '/name', text: '&fグラスボア' },
      { path: other, fieldPointer: '/name', text: 'グラスボア' },
      { path: other, fieldPointer: '/lore', text: '§aグラスの丘\n§r野草が生える' },
    ])
    expect(findLocalPredictions(index, [], path, '/name', 'グラス', Infinity)).toEqual(['グラスボア'])
    expect(findLocalPredictions(index, [], path, '/name', '&fグ', Infinity)).toEqual(['&fグラスボア'])
    expect(findLocalPredictions(index, [], path, '/lore', 'グラス', Infinity, true))
      .toEqual(['グラスの丘\n野草が生える'])
  })

  it('falls back to visible matches without changing the typed color', () => {
    const other = 'items/material/other.yml'
    const index = buildMasterPredictionIndex([
      { path: other, fieldPointer: '/name', text: '&fグラスボア' },
      { path: other, fieldPointer: '/name', text: '&bグラスダガー' },
      { path: other, fieldPointer: '/name', text: '&cグラス\nボア' },
    ])
    expect(findLocalPredictions(index, [], path, '/name', '&aグラス', 7)).toEqual(['&aグラスボア'])
    expect(findLocalPredictions(index, [], path, '/name', '&aグラス', 6)).toEqual([])
    expect(findLocalPredictions(index, [], path, '/name', '&fグラス', Infinity)).toEqual(['&fグラスボア'])
  })
})

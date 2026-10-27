import { expect, it } from 'vitest'
import { spawnerLevels, spawnerMasterColor, spawnerMasterNumbers } from './spawnerMapStyle'

it('マスターの番号と色は配置順や重複に依存せず、同レベルの異なるマスターを番号で区別できる', () => {
  const first = spawnerMasterNumbers(['wolf', 'slime', 'wolf', null])
  const second = spawnerMasterNumbers(['slime', 'wolf'])
  expect(first).toEqual(second)
  expect(first.get('wolf')).not.toBe(first.get('slime'))
  expect(spawnerMasterColor('wolf')).toBe(spawnerMasterColor('wolf'))
})

it('実レベルの不明と複数レベルを単一レベルに見せず、範囲は実測値を表示する', () => {
  expect(spawnerLevels([30, 15, 30])).toEqual({ label: 'Lv.15–30', color: '#b0bbc8' })
  expect(spawnerLevels([NaN, 0])).toEqual({ label: 'Lv.不明', color: '#b0bbc8' })
  expect(spawnerLevels([20, 20]).label).toBe('Lv.20')
  expect(spawnerLevels([19]).color).not.toBe(spawnerLevels([20]).color)
  expect(spawnerLevels([120]).label).toBe('Lv.120')
})

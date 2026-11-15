import { describe, expect, it } from 'vitest'
import {
  describeMasterTag,
  masterTagLabel,
  masterTagSearchText,
  masterTagTooltip,
  skillTreeNodeTagDefinitions,
} from './masterTagPresentation'

describe('masterTagPresentation', () => {
  it('共有カタログの日本語名とIDを表示できる', () => {
    expect(masterTagLabel('primary')).toBe('基本能力')
    expect(masterTagLabel('primary', true)).toBe('基本能力（primary）')
    expect(masterTagTooltip('primary')).toContain('基本能力（primary）')
  })

  it('日本語名とIDの両方を検索対象にする', () => {
    const searchText = masterTagSearchText('healing_inhibition')
    expect(searchText).toContain('回復阻害')
    expect(searchText).toContain('healing_inhibition')
  })

  it('未登録IDを失わずに表示する', () => {
    expect(describeMasterTag('legacy_tag')).toEqual({
      id: 'legacy_tag',
      displayName: 'legacy_tag',
      description: '共有タグカタログに未登録のIDです: legacy_tag',
      known: false,
    })
  })

  it('スキルツリーノード向け定義だけを候補にする', () => {
    expect(skillTreeNodeTagDefinitions.some((tag) => tag.id === 'primary')).toBe(true)
    expect(skillTreeNodeTagDefinitions.some((tag) => tag.id === 'AMULET')).toBe(false)
  })
})

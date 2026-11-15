import { stripMinecraftFormatting } from '../utils/minecraft'

export const placementSearchText = (value: string) => stripMinecraftFormatting(value).toLocaleLowerCase('ja-JP')

export const placementSearchWords = (query: string) => placementSearchText(query).trim().split(/\s+/u).filter(Boolean)

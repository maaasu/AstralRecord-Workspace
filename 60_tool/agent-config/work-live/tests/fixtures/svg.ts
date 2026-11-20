// Parse the XML subset emitted by the SVG renderers, including escaped text and attributes.
export function parseSvg(source: string) {
  const entity = /&(?:amp|lt|gt|quot|apos);/gu
  const token = /<(\/?)([a-z][\w:-]*)((?: [a-z][\w:-]*="[^<"]*")*)(\/?)>|([^<]+)/giy
  const stack: string[] = []
  const elements: { name: string; attributes: Map<string, string> }[] = []
  let root = ''
  for (let offset = 0; offset < source.length; offset = token.lastIndex) {
    const match = token.exec(source)
    if (!match) throw new Error('SVG の XML 構文エラー: ' + offset)
    const [, close, name, raw, empty, text] = match
    if (text !== undefined) {
      if (!stack.length || /[&]|\]\]>/u.test(text.replace(entity, ''))) throw new Error('SVG のテキストが不正です')
    } else if (close) {
      if (stack.pop() !== name || raw || empty) throw new Error('SVG の閉じタグが不正です')
    } else {
      if (!stack.length) {
        if (root || name !== 'svg') throw new Error('SVG のルート要素が不正です')
        root = match[0]
      }
      const attributes = new Map<string, string>()
      for (const [, key, value] of raw!.matchAll(/ ([\w:-]+)="([^"]*)"/gu)) {
        if (attributes.has(key!) || value!.replace(entity, '').includes('&')) throw new Error('SVG の属性が不正です')
        attributes.set(key!, value!)
      }
      elements.push({ name: name!, attributes })
      if (!empty) stack.push(name!)
    }
  }
  if (!root || stack.length) throw new Error('SVG が閉じられていません')
  return { root, elements }
}

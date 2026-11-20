function codeSpan(text: string): string {
  const longest = Math.max(0, ...Array.from(text.matchAll(/\x60+/gu), match => match[0].length))
  if (!text) return ''
  const fence = '\x60'.repeat(longest + 1)
  return longest || /^\s|\s$/u.test(text) ? fence + ' ' + text + ' ' + fence : fence + text + fence
}

function escapeLinkSyntax(text: string): string {
  return text.replace(/\\*[\[<]/gu, marker => marker.length % 2 === 0 ? marker : '\\' + marker)
}

function plainAddresses(text: string): string {
  const address = /(?:https?:\/\/|file:|www\.)[^\s<>"'\x60]+|(?<![\p{L}\p{N}._~%/\\-])(?:(["'])((?:[A-Z]:[\\/]|\\\\|\/)[^\r\n]*?)\1|(?:[A-Z]:[\\/]|\\\\|\/)[^\s<>"'\x60]*)/giu
  let output = ''
  let from = 0
  for (const match of text.matchAll(address)) {
    const [value, quote, path] = match
    const suffix = /[.,;!?、。)\]}]+$/u.exec(value)?.[0] ?? ''
    output += escapeLinkSyntax(text.slice(from, match.index))
    output += quote ? quote + codeSpan(path ?? '') + quote : codeSpan(value.slice(0, value.length - suffix.length)) + suffix
    from = match.index + value.length
  }
  return output + escapeLinkSyntax(text.slice(from))
}

function linkDestination(text: string, from: number) {
  let depth = 1
  let angle = false
  let quote = ''
  for (let at = from; at < text.length; at++) {
    const char = text[at]
    if (char === '\\') { at++; continue }
    if (quote) {
      if (char === quote) quote = ''
    } else if (angle) {
      if (char === '>') angle = false
    } else if (char === '<') angle = true
    else if ((char === '"' || char === "'") && /\s/u.test(text[at - 1] ?? '')) quote = char
    else if (char === '(') depth++
    else if (char === ')' && --depth === 0) {
      const body = text.slice(from, at).trim()
      const url = (body.startsWith('<') ? /^<([^>]*)>/u : /^(\S+)/u).exec(body)?.[1] ?? ''
      return { url, end: at + 1 }
    }
  }
}

function inlineMarkdown(text: string): string {
  const token = /(\x60+)|!?\[((?:\\.|[^\[\]\\]|\[[^\]\n]*\])*)\]\(|<((?:https?:\/\/|file:)[^>\n]+)>|^[ \t]{0,3}\[([^\]\n]+)\]:[ \t]*([^\n]+)|<\/?(?:a|img)\b[^>\n]*>/gimu
  const ticks = /\x60+/gu
  let output = ''
  let from = 0
  for (let match = token.exec(text); match; match = token.exec(text)) {
    const [marker, delimiter, label, autolink, reference, definition] = match
    let replacement: string
    if (delimiter) {
      let start = match.index
      while (text[start - 1] === '\\') start--
      if ((match.index - start) % 2 !== 0) continue
      ticks.lastIndex = token.lastIndex
      let end = ticks.exec(text)
      while (end && end[0] !== delimiter) end = ticks.exec(text)
      if (!end) continue
      token.lastIndex = ticks.lastIndex
      replacement = text.slice(match.index, token.lastIndex)
    } else if (label !== undefined) {
      const destination = linkDestination(text, token.lastIndex)
      if (!destination) continue
      token.lastIndex = destination.end
      replacement = inlineMarkdown(label) + '（' + codeSpan(destination.url) + '）'
    } else if (autolink) {
      replacement = codeSpan(autolink)
    } else if (reference && definition) {
      replacement = plainAddresses(reference) + '（' + codeSpan(definition.trim()) + '）'
    } else {
      replacement = codeSpan(marker)
    }
    output += plainAddresses(text.slice(from, match.index)) + replacement
    from = token.lastIndex
  }
  return output + plainAddresses(text.slice(from))
}

export function displayMarkdown(text: string): string {
  const output: string[] = []
  let prose: string[] = []
  let fence = ''
  let quoteDepth = 0
  for (const line of text.split('\n')) {
    const prefix = /^(?: {0,3}>[ \t]?)+/u.exec(line)?.[0] ?? ''
    const depth = prefix.replace(/[^>]/gu, '').length
    const marker = /^ {0,3}(\x60{3,}|~{3,})(.*)$/u.exec(line.slice(prefix.length))
    if (fence && depth < quoteDepth) fence = ''
    const opens = marker && (!marker[1]?.startsWith('\x60') || !marker[2]?.includes('\x60'))
    if (fence || opens) {
      if (prose.length) {
        output.push(inlineMarkdown(prose.join('\n')))
        prose = []
      }
      output.push(line)
      if (!fence) {
        fence = marker?.[1] ?? ''
        quoteDepth = depth
      } else if (depth === quoteDepth && marker?.[1]?.startsWith(fence) && !marker[2]?.trim()) {
        fence = ''
      }
    } else {
      prose.push(line)
    }
  }
  if (prose.length) output.push(inlineMarkdown(prose.join('\n')))
  return output.join('\n')
}

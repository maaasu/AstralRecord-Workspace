import { lstat, open, readdir } from 'node:fs/promises'
import type { FileHandle } from 'node:fs/promises'
import { join } from 'node:path'
import { parseJson, recordOf } from './model.ts'

const CHUNK_BYTES = 256 * 1024
const MAX_LINE_BYTES = 256 * 1024
const TAIL_BYTES = 64 * 1024
const SCAN_BYTES = 1024 * 1024
const decoder = new TextDecoder()

type Stamp = { size: number; mtimeMs: number; identity: string }
type LinePosition = { offset: number; skipping: boolean; partial: Uint8Array }
export type JsonlCursor = LinePosition & { readTo: number; stamp: Stamp; boundary: Uint8Array }
export type JsonlLine = { offset: number; text: string } | { offset: number; bytes: number }
export type SmallJson = { stamp: Stamp; row: Record<string, unknown> }

function missing(error: unknown): boolean {
  return recordOf(error).code === 'ENOENT'
}

export async function entries(path: string, kind: 'file' | 'directory'): Promise<string[]> {
  try {
    return (await readdir(path, { withFileTypes: true })).filter(entry =>
      kind === 'file' ? entry.isFile() : entry.isDirectory()).map(entry => join(path, entry.name))
  } catch (error) {
    if (missing(error)) return []
    throw error
  }
}

export async function fileStamp(path: string): Promise<Stamp | undefined> {
  try {
    const info = await lstat(path)
    if (info.isFile()) return { size: info.size, mtimeMs: info.mtimeMs, identity: [info.dev, info.ino, info.birthtimeMs].join(':') }
  } catch (error) {
    if (!missing(error)) throw error
  }
}

export async function isDirectory(path: string): Promise<boolean> {
  try {
    return (await lstat(path)).isDirectory()
  } catch (error) {
    if (missing(error)) return false
    throw error
  }
}

function unchanged(a: Stamp, b: Stamp): boolean {
  return a.size === b.size && a.mtimeMs === b.mtimeMs && a.identity === b.identity
}

async function bytesAt(file: FileHandle, offset: number, length: number): Promise<Uint8Array> {
  const bytes = new Uint8Array(length)
  let read = 0
  while (read < length) {
    const { bytesRead } = await file.read(bytes, read, length - read, offset + read)
    if (!bytesRead) break
    read += bytesRead
  }
  return bytes.subarray(0, read)
}

export async function smallJson(path: string, cache: Map<string, SmallJson>) {
  const stamp = await fileStamp(path)
  if (!stamp || stamp.size > TAIL_BYTES) {
    cache.delete(path)
    return {}
  }
  const cached = cache.get(path)
  if (cached && unchanged(cached.stamp, stamp)) return cached.row
  const file = await open(path, 'r')
  try {
    const value = parseJson(decoder.decode(await bytesAt(file, 0, stamp.size)))
    if (value === undefined) return cached?.row ?? {}
    const row = recordOf(value)
    cache.set(path, { stamp, row })
    return row
  } finally {
    await file.close()
  }
}

function blankPosition(offset: number, skipping = false): LinePosition {
  return { offset, skipping, partial: new Uint8Array() }
}

export function completeLines(bytes: Uint8Array, skipFirst: boolean) {
  const lines: { offset: number; text: string }[] = []
  let start = 0
  for (let end = bytes.indexOf(10); end !== -1; end = bytes.indexOf(10, start)) {
    if (!skipFirst) lines.push({ offset: start, text: decoder.decode(bytes.subarray(start, end)) })
    skipFirst = false
    start = end + 1
  }
  return { lines, consumed: skipFirst ? bytes.length : start, skipping: skipFirst }
}

async function scan(file: FileHandle, start: number, end: number, previous: LinePosition) {
  const lines: JsonlLine[] = []
  const pending = new Uint8Array(MAX_LINE_BYTES)
  pending.set(previous.partial)
  let { offset, skipping } = previous
  let readTo = start
  while (readTo < end) {
    const bytes = await bytesAt(file, readTo, Math.min(CHUNK_BYTES, end - readTo))
    if (!bytes.length) break
    for (let part = 0; part < bytes.length;) {
      const newline = bytes.indexOf(10, part)
      const limit = newline < 0 ? bytes.length : newline
      const length = readTo + limit - offset
      if (!skipping && length <= MAX_LINE_BYTES) pending.set(bytes.subarray(part, limit), readTo + part - offset)
      if (newline < 0) break
      if (!skipping) lines.push(length > MAX_LINE_BYTES ? { offset, bytes: length }
        : { offset, text: decoder.decode(pending.subarray(0, length)) })
      offset = readTo + newline + 1
      skipping = false
      part = newline + 1
    }
    readTo += bytes.length
  }
  const remaining = readTo - offset
  const partial = !skipping && remaining <= MAX_LINE_BYTES ? pending.slice(0, remaining) : new Uint8Array()
  return { lines, readTo, offset, skipping, partial }
}

async function initialPosition(file: FileHandle, size: number, isStart?: (line: string) => boolean) {
  const tail = Math.max(0, size - TAIL_BYTES)
  if (!isStart || !tail) return blankPosition(0)
  const bytes = await bytesAt(file, tail - 1, size - tail + 1)
  const startsMidLine = bytes[0] !== 10
  const complete = completeLines(bytes.subarray(1), startsMidLine).lines
  if (complete.some(line => isStart(line.text))) return blankPosition(tail, startsMidLine)
  const start = Math.max(0, size - SCAN_BYTES)
  return blankPosition(start, start > 0 && (await bytesAt(file, start - 1, 1))[0] !== 10)
}

export async function readJsonl(
  path: string, stamp: Stamp, previous: JsonlCursor | undefined,
  options: { isStart?: (line: string) => boolean; readLastLine?: boolean } = {},
) {
  if (previous && unchanged(previous.stamp, stamp) && previous.readTo >= stamp.size) {
    return { cursor: previous, lines: [], initial: false, fromTail: false }
  }
  const file = await open(path, 'r')
  try {
    let prior = previous
    if (prior && (prior.stamp.identity !== stamp.identity || prior.readTo > stamp.size)) prior = undefined
    if (prior && !unchanged(prior.stamp, stamp)) {
      const expected = prior.boundary
      const boundary = await bytesAt(file, prior.readTo - expected.length, expected.length)
      if (expected.length !== boundary.length || expected.some((byte, i) => byte !== boundary[i])) prior = undefined
    }
    const position = prior ?? await initialPosition(file, stamp.size, options.isStart)
    const start = prior?.readTo ?? position.offset
    const { lines, readTo, ...next } = await scan(file, start, Math.min(stamp.size, start + SCAN_BYTES), position)
    // The index can end in a complete JSON value without a trailing newline.
    if (options.readLastLine && readTo === stamp.size && next.partial.length) {
      const text = decoder.decode(next.partial)
      if (parseJson(text) !== undefined) {
        lines.push({ offset: next.offset, text })
        Object.assign(next, blankPosition(readTo))
      }
    }
    const boundaryStart = Math.max(0, readTo - 1024)
    const boundary = await bytesAt(file, boundaryStart, readTo - boundaryStart)
    return { cursor: { ...next, readTo, stamp, boundary }, lines, initial: !prior, fromTail: !prior && start > 0 }
  } finally {
    await file.close()
  }
}

export async function inspectHead(path: string, size: number, visit: (line: string, offset: number) => boolean) {
  const file = await open(path, 'r')
  const end = Math.min(size, SCAN_BYTES)
  try {
    let offset = 0
    let position = blankPosition(0)
    while (offset < end) {
      const result = await scan(file, offset, Math.min(end, offset + TAIL_BYTES), position)
      if (result.lines.some(line => 'text' in line && visit(line.text, line.offset)) || offset === result.readTo) break
      offset = result.readTo
      position = result
    }
  } finally {
    await file.close()
  }
}

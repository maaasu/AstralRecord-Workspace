// Node APIs used by this tool. These declarations keep the standalone type check
// independent of @types/node and any installed packages in the workspace.
declare module 'node:fs/promises' {
  interface Stats {
    size: number
    mtimeMs: number
    birthtimeMs: number
    dev: number
    ino: number
    isFile(): boolean
    isDirectory(): boolean
  }
  interface Dirent {
    name: string
    isFile(): boolean
    isDirectory(): boolean
  }
  export interface FileHandle {
    read(buffer: Uint8Array, offset: number, length: number, position: number): Promise<{ bytesRead: number }>
    close(): Promise<void>
  }
  export function open(path: string, flags: 'r'): Promise<FileHandle>
  export function lstat(path: string): Promise<Stats>
  export function readdir(path: string, options: { withFileTypes: true }): Promise<Dirent[]>
  export function readFile(path: string | URL, encoding: 'utf8'): Promise<string>
  export function writeFile(path: string, data: string | Uint8Array): Promise<void>
  export function appendFile(path: string, data: string | Uint8Array): Promise<void>
  export function mkdir(path: string, options: { recursive: true }): Promise<string | undefined>
  export function mkdtemp(prefix: string): Promise<string>
  export function rm(path: string, options?: { recursive?: boolean; force?: boolean }): Promise<void>
  export function utimes(path: string, atime: Date, mtime: Date): Promise<void>
}

declare module 'node:path' {
  export function join(...parts: string[]): string
  export function resolve(...parts: string[]): string
  export function basename(path: string, suffix?: string): string
  export function dirname(path: string): string
  export function isAbsolute(path: string): boolean
}

declare module 'node:process' {
  const process: {
    argv: string[]
    env: Record<string, string | undefined>
    execPath: string
    pid: number
    platform: string
    exitCode?: number
    kill(pid: number, signal: 0): boolean
  }
  export default process
}

declare module 'node:os' {
  export function homedir(): string
  export function tmpdir(): string
}

declare module 'node:url' {
  export function pathToFileURL(path: string): URL
  export function fileURLToPath(url: URL | string): string
}

declare module 'node:http' {
  interface IncomingMessage {
    headers: Record<string, string | string[] | undefined> & { host?: string }
    method?: string
    url?: string
    statusCode?: number
    setEncoding(encoding: 'utf8'): this
    on(event: 'data', listener: (chunk: string) => void): this
    once(event: 'end', listener: () => void): this
  }
  export interface ServerResponse {
    setHeader(name: string, value: string): this
    writeHead(status: number, headers: Record<string, string>): this
    end(body?: string): void
  }
  interface Server {
    address(): { address: string; family: string; port: number } | string | null
    listen(port: number, host: string, callback?: () => void): this
    once(event: 'error', listener: (error: Error) => void): this
    once(event: 'listening' | 'close', listener: () => void): this
    close(callback?: (error?: Error) => void): this
  }
  interface ClientRequest {
    on(event: 'error', listener: (error: Error) => void): this
    end(): void
  }
  export function createServer(listener: (request: IncomingMessage, response: ServerResponse) => void): Server
  export function request(url: string, options: { method?: string; headers?: Record<string, string> },
    callback: (response: IncomingMessage) => void): ClientRequest
}

declare module 'node:child_process' {
  export function execFile(file: string, args: string[], options: {
    encoding: 'utf8'; timeout?: number; windowsHide?: boolean; env?: Record<string, string | undefined>
  }, callback: (error: (Error & { code?: number | string }) | null, stdout: string, stderr: string) => void): void
}

declare module 'node:test' {
  type Test = (context: TestContext) => void | Promise<void>
  export interface TestContext {
    after(callback: () => void | Promise<void>): void
  }
  export function test(name: string, callback: Test): void
  export function test(name: string, options: { skip?: boolean }, callback: Test): void
}

declare module 'node:assert/strict' {
  const assert: {
    ok(value: unknown, message?: string): asserts value
    equal(actual: unknown, expected: unknown, message?: string): void
    notEqual(actual: unknown, expected: unknown, message?: string): void
    deepEqual(actual: unknown, expected: unknown, message?: string): void
    partialDeepStrictEqual(actual: unknown, expected: unknown, message?: string): void
    match(actual: string, expected: RegExp, message?: string): void
    doesNotMatch(actual: string, expected: RegExp, message?: string): void
    fail(message: string): never
  }
  export default assert
}

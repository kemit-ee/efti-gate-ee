import {getTaraRedirectUri, isCallbackPath} from './runtimeConfig'

describe('runtimeConfig', () => {
  afterEach(() => vi.restoreAllMocks())

  it('uses the deployment-provided redirect URI', async () => {
    vi.spyOn(window, 'fetch').mockResolvedValue({ok: true, json: () => Promise.resolve({taraRedirectUri: 'https://efti.example/auth/callback/'})} as any)
    expect(await getTaraRedirectUri()).toBe('https://efti.example/auth/callback/')
  })

  it('falls back to the origin callback route when unset or unreachable', async () => {
    vi.spyOn(window, 'fetch').mockResolvedValue({ok: true, json: () => Promise.resolve({taraRedirectUri: ''})} as any)
    expect(await getTaraRedirectUri()).toBe(`${location.origin}/auth/callback`)
    vi.spyOn(window, 'fetch').mockRejectedValue(new TypeError('x'))
    expect(await getTaraRedirectUri()).toBe(`${location.origin}/auth/callback`)
  })

  it('treats a trailing slash as the callback path', () => {
    expect(isCallbackPath('/auth/callback')).toBe(true)
    expect(isCallbackPath('/auth/callback/')).toBe(true)
    expect(isCallbackPath('/gates')).toBe(false)
  })
})

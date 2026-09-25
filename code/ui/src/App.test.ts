import {render, waitFor} from '@testing-library/svelte'
import App from './App.svelte'
import api, {getToken} from 'src/api/api'
import {currentUser} from 'src/stores/session'
import {get} from 'svelte/store'
import {activePath} from 'src/router'

vi.mock('src/api/api', () => ({default: {get: vi.fn()}, getToken: vi.fn()}))

function mockLocation() {
  const original = window.location
  // @ts-ignore
  delete window.location
  window.location = {...original, href: ''} as any
  return () => window.location = original
}

const user = {id: '60001019906', taraSub: 'EE60001019906', name: 'Super Admin', createdAt: '2026-01-01T00:00:00Z'}

describe('App', () => {
  let restoreLocation: () => void

  beforeEach(() => {
    vi.clearAllMocks()
    currentUser.set(undefined)
    activePath.set('/')
    restoreLocation = mockLocation()
    window.matchMedia = vi.fn().mockImplementation((query: string) => ({
      matches: false, media: query, addEventListener: vi.fn(), removeEventListener: vi.fn()
    })) as any
    vi.spyOn(window, 'fetch').mockResolvedValue({
      json: () => Promise.resolve({authorization_url: 'https://tara-mock:8080/oidc/authorize?x=1'})
    } as any)
  })

  afterEach(() => restoreLocation())

  it('redirects to TARA login when there is no token', async () => {
    vi.mocked(getToken).mockReturnValue(null)
    render(App)

    await waitFor(() => expect(window.fetch).toHaveBeenCalledWith(expect.stringContaining('/tim/auth/login/tara?redirect_uri=')))
    await waitFor(() => expect(window.location.href).to.equal('/tara/oidc/authorize?x=1'))
    expect(api.get).not.toHaveBeenCalled()
  })

  it('loads the current user and stores it when a token is present', async () => {
    vi.mocked(getToken).mockReturnValue('jwt-token')
    vi.mocked(api.get).mockResolvedValue(user)

    render(App)

    await waitFor(() => expect(api.get).toHaveBeenCalledWith('/auth/user'))
    await waitFor(() => expect(get(currentUser)).to.deep.equal(user))
    expect(window.fetch).not.toHaveBeenCalled()
  })

  it('redirects to TARA login when the user fetch fails (stale/invalid token)', async () => {
    vi.mocked(getToken).mockReturnValue('stale-token')
    vi.mocked(api.get).mockRejectedValue({message: 'Unauthorized'})

    render(App)

    await waitFor(() => expect(window.fetch).toHaveBeenCalled())
    await waitFor(() => expect(window.location.href).to.equal('/tara/oidc/authorize?x=1'))
  })

  it('does not redirect while on the /callback route', () => {
    activePath.set('/callback')
    vi.mocked(getToken).mockReturnValue(null)

    render(App)

    expect(api.get).not.toHaveBeenCalled()
    expect(window.fetch).not.toHaveBeenCalled()
  })
})

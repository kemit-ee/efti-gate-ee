import {render, waitFor, screen} from '@testing-library/svelte'
import AuthCallbackPage from './AuthCallbackPage.svelte'
import api from 'src/api/api'
import {activePath} from 'src/router'
import {get} from 'svelte/store'

vi.mock('src/api/api', () => ({default: {post: vi.fn()}, setToken: vi.fn()}))

function setSearch(search: string) {
  window.history.pushState({}, '', `/auth/callback${search}`)
}

describe('AuthCallbackPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    activePath.set('/auth/callback')
  })

  it('shows an error when the code or state parameter is missing', async () => {
    setSearch('?code=abc') // no state
    render(AuthCallbackPage)

    await screen.findByText(/Missing code or state parameter/)
    expect(api.post).not.toHaveBeenCalled()
  })

  it('exchanges the code for a token and navigates to the gates page', async () => {
    setSearch('?code=abc&state=xyz')
    vi.mocked(api.post).mockResolvedValue({token: 'jwt-token'})
    const {setToken} = await import('src/api/api')
    render(AuthCallbackPage)

    await waitFor(() => expect(api.post).toHaveBeenCalledWith('/auth/callback', {code: 'abc', state: 'xyz'}))
    await waitFor(() => expect(setToken).toHaveBeenCalledWith('jwt-token'))
    await waitFor(() => expect(get(activePath)).to.equal('/gates'))
  })

  it('shows the error message from a failed exchange', async () => {
    setSearch('?code=abc&state=xyz')
    vi.mocked(api.post).mockRejectedValue({message: 'invalid_grant'})
    render(AuthCallbackPage)

    await screen.findByText(/invalid_grant/)
  })

  it('falls back to a generic message when the failure has none', async () => {
    setSearch('?code=abc&state=xyz')
    vi.mocked(api.post).mockRejectedValue({})
    render(AuthCallbackPage)

    await screen.findByText(/Unsuccessful authentication/)
  })
})

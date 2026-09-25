import {render, waitFor} from '@testing-library/svelte'
import AuthCallbackPage from './AuthCallbackPage.svelte'
import api, {setToken} from 'src/api/api'
import {navigate} from 'src/router'

vi.mock('src/api/api', () => ({
  default: {post: vi.fn()},
  setToken: vi.fn()
}))

vi.mock('src/router', () => ({navigate: vi.fn()}))

function setSearch(search: string) {
  window.history.pushState({}, '', '/auth/callback' + search)
}

describe('AuthCallbackPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    window.history.pushState({}, '', '/auth/callback')
  })

  it('shows an authenticating message while the exchange is pending', () => {
    setSearch('?code=abc&state=xyz')
    vi.mocked(api.post).mockReturnValue(new Promise(() => {}))
    const {container} = render(AuthCallbackPage)
    expect(container.textContent).to.contain('Authenticating')
  })

  it('exchanges the code and state, stores the token and navigates to /gates', async () => {
    setSearch('?code=abc&state=xyz')
    vi.mocked(api.post).mockResolvedValue({token: 'jwt-token'})

    render(AuthCallbackPage)

    await waitFor(() => expect(api.post).toHaveBeenCalledWith('/auth/callback', {code: 'abc', state: 'xyz'}))
    expect(setToken).toHaveBeenCalledWith('jwt-token')
    expect(navigate).toHaveBeenCalledWith('/gates', {replace: true})
  })

  it('shows an error and does not navigate when code or state is missing', async () => {
    setSearch('?code=abc')
    const {container} = render(AuthCallbackPage)

    await waitFor(() => expect(container.textContent).to.contain('Missing code or state parameter'))
    expect(api.post).not.toHaveBeenCalled()
    expect(navigate).not.toHaveBeenCalled()
  })

  it('shows the error message when the exchange fails', async () => {
    setSearch('?code=abc&state=xyz')
    vi.mocked(api.post).mockRejectedValue({message: 'Authentication failed'})

    const {container} = render(AuthCallbackPage)

    await waitFor(() => expect(container.textContent).to.contain('Authentication failed'))
    expect(setToken).not.toHaveBeenCalled()
    expect(navigate).not.toHaveBeenCalled()
  })

  it('falls back to a generic error message when the failure has no message', async () => {
    setSearch('?code=abc&state=xyz')
    vi.mocked(api.post).mockRejectedValue({})

    const {container} = render(AuthCallbackPage)

    await waitFor(() => expect(container.textContent).to.contain('Unsuccessful authentication'))
  })
})

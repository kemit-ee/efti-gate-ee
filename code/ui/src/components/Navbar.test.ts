import {fireEvent, render} from '@testing-library/svelte'
import Navbar from './Navbar.svelte'
import api, {clearToken} from 'src/api/api'
import type {User} from 'src/api/ruuterTypes'
import {activePath} from 'src/router'

vi.mock('src/api/api', () => ({default: {post: vi.fn().mockResolvedValue(undefined)}, clearToken: vi.fn()}))

const routes = [{name: 'Gates', path: '/gates'}, {name: 'Platforms', path: '/platforms'}] as any

function mockMatchMedia(matches: boolean) {
  const listeners: ((e: MediaQueryListEvent) => void)[] = []
  window.matchMedia = vi.fn().mockImplementation((query: string) => ({
    matches,
    media: query,
    addEventListener: (_: string, cb: any) => listeners.push(cb),
    removeEventListener: vi.fn()
  })) as any
  return {trigger: (m: boolean) => listeners.forEach(cb => cb({matches: m} as MediaQueryListEvent))}
}

const user: User = {id: '60001019906', taraSub: 'EE60001019906', name: 'Super Admin', createdAt: '2026-01-01T00:00:00Z'}

describe('Navbar', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockMatchMedia(false)
    window.history.pushState({}, '', '/gates')
    activePath.set('/gates')
  })

  it('renders the navigation links', () => {
    const {getByText} = render(Navbar, {routes, user: undefined})
    expect(getByText('Gates')).to.exist
    expect(getByText('Platforms')).to.exist
  })

  it('shows the user name and TARA sub when logged in', () => {
    const {container} = render(Navbar, {routes, user})
    expect(container.textContent).to.contain('Super Admin')
    expect(container.textContent).to.contain('EE60001019906')
  })

  it('does not show user info when logged out', () => {
    const {container} = render(Navbar, {routes, user: undefined})
    expect(container.textContent).to.not.contain('Super Admin')
  })

  it('logs out, clears the token and redirects home', async () => {
    const originalLocation = window.location
    // @ts-ignore
    delete window.location
    window.location = {...originalLocation, href: ''} as any

    const {getByText} = render(Navbar, {routes, user})
    await fireEvent.click(getByText('Super Admin'))
    await fireEvent.click(getByText('Log out'))

    expect(api.post).toHaveBeenCalledWith('/auth/logout')
    expect(clearToken).toHaveBeenCalled()
    expect(window.location.href).to.equal('/')

    window.location = originalLocation
  })
})

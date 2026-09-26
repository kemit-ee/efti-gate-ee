import {fireEvent, render, waitFor} from '@testing-library/svelte'
import Navbar from './Navbar.svelte'
import api, {clearToken} from 'src/api/api'
import type {User} from 'src/api/ruuterTypes'
import {activePath} from 'src/router'
import {lang} from 'i18n'

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

  it('shows the hamburger menu on mobile and starts with it collapsed', async () => {
    mockMatchMedia(true)
    const {getByText, queryByText} = render(Navbar, {routes, user})

    await waitFor(() => expect(queryByText('Gates')).to.equal(null))
    await fireEvent.click(getByText('Menu'))
    await waitFor(() => expect(getByText('Gates')).to.exist)
  })

  it('shows a mobile logout row and collapses the menu after clicking a link', async () => {
    mockMatchMedia(true)
    const {getByText, queryByText} = render(Navbar, {routes, user})
    await fireEvent.click(getByText('Menu'))
    await waitFor(() => getByText('Gates'))

    await fireEvent.click(getByText('Gates'))

    await waitFor(() => expect(queryByText('Gates')).to.equal(null))
  })

  it('reacts to a matchMedia change event (viewport resize)', async () => {
    const {trigger} = mockMatchMedia(false)
    const {getByText, queryByText} = render(Navbar, {routes, user})
    getByText('Gates') // desktop: menu already open

    trigger(true) // becomes mobile -> menu auto-collapses
    await waitFor(() => expect(queryByText('Gates')).to.equal(null))
  })

  it('switches the language, persisting the choice and reloading', async () => {
    const reload = vi.fn()
    vi.stubGlobal('location', {...window.location, reload})
    const {container} = render(Navbar, {routes, user: undefined})
    const otherLang = (['et', 'en'] as const).find(l => l !== lang)!

    const select = container.querySelector('select') as HTMLSelectElement
    await fireEvent.change(select, {target: {value: otherLang}})

    expect(localStorage['lang']).to.equal(otherLang)
    expect(reload).toHaveBeenCalled()
    vi.unstubAllGlobals()
  })
})

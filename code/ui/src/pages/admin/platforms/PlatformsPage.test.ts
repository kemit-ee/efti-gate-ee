import {fireEvent, render, screen, waitFor} from '@testing-library/svelte'
import PlatformsPage from './PlatformsPage.svelte'
import api from 'src/api/api'
import type {Platform} from 'src/api/ruuterTypes'
import {Status} from 'src/api/ruuterTypes'

vi.mock('src/api/api', () => ({default: {get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn()}}))

function newPlatform(): Platform {
  return {
    id: 'mock', baseUrl: 'https://mock.example/api', headers: {'X-Api-Key': 'secret'},
    eDeliveryCert: '-BEGIN CERTIFICATE-\nabc\n-END CERTIFICATE-',
    status: Status.ONLINE,
  }
}

function mockPlatformsOf(platforms: Platform[]) {
  vi.mocked(api.get).mockImplementation((path: string) => {
    if (path === 'platforms') return Promise.resolve(platforms)
    return Promise.reject(new Error(`unexpected GET ${path}`))
  })
}

describe('PlatformsPage', () => {
  let platform: Platform

  beforeEach(() => {
    vi.clearAllMocks()
    platform = newPlatform()
    mockPlatformsOf([platform])
  })

  it('loads platforms on mount', async () => {
    render(PlatformsPage)

    await waitFor(() => expect(api.get).toHaveBeenCalledWith('platforms'))
    await screen.findByText('mock')
    screen.getByText('Platforms (1)')
  })

  it('does not offer creating, pinging, rotating keys or deleting, the registry is declarative', async () => {
    render(PlatformsPage)
    await screen.findByText('mock')

    for (const name of ['Add', 'Edit', 'Delete', 'Ping', 'Generate API key']) {
      expect(screen.queryByRole('button', {name})).to.equal(null)
    }
  })

  it('shows a platform in read-only details without calling the API', async () => {
    render(PlatformsPage)
    await screen.findByText('mock')

    await fireEvent.click(screen.getByRole('button', {name: 'Details'}))

    await waitFor(() => expect((screen.getByLabelText('Base URL') as HTMLInputElement).value).to.equal('https://mock.example/api'))
    const key = screen.getByLabelText('Key') as HTMLInputElement
    const value = screen.getByLabelText('Value') as HTMLInputElement

    expect((screen.getByLabelText('Platform ID') as HTMLInputElement).value).to.equal('mock')
    expect(key.value).to.equal('X-Api-Key')
    expect(value.value).to.equal('secret')

    expect((screen.getByLabelText('Platform ID') as HTMLInputElement).disabled).to.equal(true)
    expect((screen.getByLabelText('Base URL') as HTMLInputElement).disabled).to.equal(true)
    expect(key.disabled).to.equal(true)
    expect(value.disabled).to.equal(true)
    expect((screen.getByLabelText('API key') as HTMLInputElement).value).to.equal('No key')
    expect(screen.queryByRole('button', {name: '+'})).to.equal(null)
    expect(screen.queryAllByRole('button', {name: '×'}).length).to.equal(0)
    expect(document.querySelectorAll('input[type=file]').length).to.equal(0)
    expect(screen.queryByRole('button', {name: 'Save'})).to.equal(null)

    expect(api.post).not.toHaveBeenCalled()
    expect(api.put).not.toHaveBeenCalled()
    expect(api.delete).not.toHaveBeenCalled()
  })

  it('shows whether an inbound API key is configured, without exposing or generating one', async () => {
    mockPlatformsOf([{...newPlatform(), hasApiKey: true}])
    render(PlatformsPage)

    await screen.findByText('mock')
    await screen.findByText('Key configured')
    expect(screen.queryByRole('button', {name: 'Generate API key'})).to.equal(null)
    expect(api.post).not.toHaveBeenCalled()
  })

  it('shows a platform without a configured inbound API key', async () => {
    render(PlatformsPage)

    await screen.findByText('mock')
    await screen.findByText('No key')
    expect(screen.queryByText('Key configured')).to.equal(null)
  })

  it('shows the API key as configured in read-only details', async () => {
    mockPlatformsOf([{...newPlatform(), hasApiKey: true}])
    render(PlatformsPage)
    await screen.findByText('mock')

    await fireEvent.click(screen.getByRole('button', {name: 'Details'}))

    await waitFor(() => expect((screen.getByLabelText('API key') as HTMLInputElement).value).to.equal('Key configured'))
    expect((screen.getByLabelText('API key') as HTMLInputElement).disabled).to.equal(true)
    expect(document.querySelectorAll('input[type=file]').length).to.equal(0)
    expect(api.post).not.toHaveBeenCalled()
  })

  it('shows a platform without eDelivery certificates in read-only details', async () => {
    mockPlatformsOf([{...newPlatform(), baseUrl: 'https://plain.example/api', eDeliveryCert: undefined}])
    render(PlatformsPage)
    await screen.findByText('mock')

    await fireEvent.click(screen.getByRole('button', {name: 'Details'}))

    await waitFor(() => expect((screen.getByLabelText('Base URL') as HTMLInputElement).value).to.equal('https://plain.example/api'))
    expect(screen.queryByLabelText('eDelivery certificate')).to.equal(null)
    expect((screen.getByLabelText('EDelivery') as HTMLInputElement).checked).to.equal(false)
  })
})

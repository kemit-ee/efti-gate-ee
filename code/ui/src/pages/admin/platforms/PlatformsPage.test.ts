import {render, waitFor, fireEvent, screen} from '@testing-library/svelte'
import PlatformsPage from './PlatformsPage.svelte'
import api from 'src/api/api'
import {Status} from 'src/api/ruuterTypes'
import type {Platform} from 'src/api/ruuterTypes'
import {toastStore} from 'src/stores/toasts'
import {get} from 'svelte/store'

vi.mock('src/api/api', () => ({default: {get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn()}}))

function newPlatform(): Platform {
  return {
    id: 'mock', baseUrl: 'https://mock.example/api', status: Status.ONLINE, createdAt: '2026-01-01T00:00:00Z',
  }
}

describe('PlatformsPage', () => {
  let platform: Platform

  beforeEach(() => {
    vi.clearAllMocks()
    platform = newPlatform()
    vi.mocked(api.get).mockImplementation((path: string) => {
      if (path === 'platforms') return Promise.resolve([platform])
      if (path === 'gates/own') return Promise.resolve({id: 'EU-EE', status: Status.ONLINE})
      return Promise.reject(new Error(`unexpected GET ${path}`))
    })
  })

  it('loads platforms on mount', async () => {
    render(PlatformsPage)

    await waitFor(() => expect(api.get).toHaveBeenCalledWith('platforms'))
    await screen.findByText('mock')
    screen.getByText('Platforms (1)')
  })

  it('adds a new platform', async () => {
    vi.mocked(api.post).mockResolvedValue({})
    render(PlatformsPage)
    await screen.findByText('mock')

    await fireEvent.click(screen.getByRole('button', {name: 'Add'}))
    await fireEvent.input(screen.getByLabelText('Platform ID'), {target: {value: 'new-platform'}})
    await fireEvent.input(screen.getByLabelText('Base URL'), {target: {value: 'https://new.example/api'}})

    await fireEvent.click(screen.getByRole('button', {name: 'Save'}))

    await waitFor(() => expect(api.post).toHaveBeenCalledWith('platforms', expect.objectContaining({
      id: 'new-platform', baseUrl: 'https://new.example/api',
    })))
  })

  it('an eDelivery-enabled platform requires a cert and is saved with it', async () => {
    vi.mocked(api.post).mockResolvedValue({})
    render(PlatformsPage)
    await screen.findByText('mock')

    await fireEvent.click(screen.getByRole('button', {name: 'Add'}))
    await fireEvent.input(screen.getByLabelText('Platform ID'), {target: {value: 'new-platform'}})
    await fireEvent.input(screen.getByLabelText('Base URL'), {target: {value: 'https://new.example/api'}})
    await fireEvent.click(screen.getByLabelText('EDelivery'))
    await fireEvent.input(screen.getByLabelText('eDelivery certificate'), {target: {value: '-BEGIN CERTIFICATE-\nabc\n-END CERTIFICATE-'}})

    await fireEvent.click(screen.getByRole('button', {name: 'Save'}))

    await waitFor(() => expect(api.post).toHaveBeenCalledWith('platforms', expect.objectContaining({
      eDeliveryCert: '-BEGIN CERTIFICATE-\nabc\n-END CERTIFICATE-',
    })))
  })

  it('adds and removes a custom header', async () => {
    vi.mocked(api.post).mockResolvedValue({})
    render(PlatformsPage)
    await screen.findByText('mock')

    await fireEvent.click(screen.getByRole('button', {name: 'Add'}))
    await fireEvent.input(screen.getByLabelText('Platform ID'), {target: {value: 'new-platform'}})
    await fireEvent.input(screen.getByLabelText('Base URL'), {target: {value: 'https://new.example/api'}})
    await fireEvent.click(screen.getByRole('button', {name: '+'}))
    await fireEvent.input(screen.getByLabelText('Key'), {target: {value: 'X-Api-Key'}})
    await fireEvent.input(screen.getByLabelText('Value'), {target: {value: 'secret'}})

    await fireEvent.click(screen.getByRole('button', {name: 'Save'}))

    await waitFor(() => expect(api.post).toHaveBeenCalledWith('platforms', expect.objectContaining({
      headers: {'X-Api-Key': 'secret'},
    })))
  })

  it('edits an existing platform', async () => {
    vi.mocked(api.put).mockResolvedValue({})
    render(PlatformsPage)
    await screen.findByText('mock')

    await fireEvent.click(screen.getByRole('button', {name: 'Edit'}))
    expect((screen.getByLabelText('Platform ID') as HTMLInputElement).disabled).to.equal(true)
    await fireEvent.input(screen.getByLabelText('Base URL'), {target: {value: 'https://mock-new.example/api'}})
    await fireEvent.click(screen.getByRole('button', {name: 'Save'}))

    await waitFor(() => expect(api.put).toHaveBeenCalledWith('platforms/mock', expect.objectContaining({
      baseUrl: 'https://mock-new.example/api',
    })))
  })

  it('generates an API key and shows it in a modal, closing reloads the list', async () => {
    vi.mocked(api.post).mockResolvedValue({id: 'mock', apiKey: 'super-secret-key', apiKeyHint: 'supe', apiKeyGeneratedAt: '2026-01-01T00:00:00Z'})
    render(PlatformsPage)
    await screen.findByText('mock')

    await fireEvent.click(screen.getByRole('button', {name: 'Generate API key'}))

    await waitFor(() => expect(api.post).toHaveBeenCalledWith('platforms/api-key/mock'))
    await screen.findByText('super-secret-key')

    await fireEvent.click(screen.getByRole('button', {name: 'Close'}))
    await waitFor(() => expect(api.get).toHaveBeenCalledTimes(2)) // initial load + reload after closing the modal
  })

  it('regenerating an existing key requires confirmation', async () => {
    platform.hasApiKey = true
    vi.spyOn(window, 'confirm').mockReturnValue(false)
    render(PlatformsPage)
    await screen.findByText('mock')

    await fireEvent.click(screen.getByRole('button', {name: 'Generate API key'}))

    expect(api.post).not.toHaveBeenCalled()
  })

  it('pings a platform and shows a toast on success', async () => {
    vi.mocked(api.post).mockResolvedValue({...platform, status: Status.ONLINE})
    render(PlatformsPage)
    await screen.findByText('mock')

    await fireEvent.click(screen.getByRole('button', {name: 'Ping'}))

    await waitFor(() => expect(api.post).toHaveBeenCalledWith('platforms/ping/mock'))
    await waitFor(() => expect(get(toastStore).some(t => t.message.includes('pinged'))).to.equal(true))
  })

  it('falls back to OFFLINE when a ping fails', async () => {
    vi.mocked(api.post).mockRejectedValue(new Error('unreachable'))
    render(PlatformsPage)
    await screen.findByText('mock')

    await fireEvent.click(screen.getByRole('button', {name: 'Ping'}))

    await waitFor(() => screen.getByText('OFFLINE'))
  })

  it('deletes a platform after confirmation', async () => {
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    vi.mocked(api.delete).mockResolvedValue({})
    render(PlatformsPage)
    await screen.findByText('mock')

    await fireEvent.click(screen.getByRole('button', {name: 'Delete'}))

    await waitFor(() => expect(api.delete).toHaveBeenCalledWith('platforms/mock'))
  })
})

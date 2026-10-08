import {fireEvent, render, screen, waitFor} from '@testing-library/svelte'
import GatesPage from './GatesPage.svelte'
import api from 'src/api/api'
import type {Gate} from 'src/api/ruuterTypes'
import {CountryCode, Status} from 'src/api/ruuterTypes'

vi.mock('src/api/api', () => ({default: {get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn()}}))

function newGate(): Gate {
  return {
    id: 'EU-EE', rowId: 'row-1', countryCode: CountryCode.EE, eDeliveryUrl: 'https://gate-ee.example/msh',
    eDeliveryCert: '-BEGIN CERTIFICATE-\nabc\n-END CERTIFICATE-',
    status: Status.ONLINE, createdAt: '2026-01-01T00:00:00Z',
  }
}

describe('GatesPage', () => {
  let gate: Gate

  beforeEach(() => {
    vi.clearAllMocks()
    gate = newGate()
    vi.mocked(api.get).mockImplementation((path: string) => {
      if (path === 'gates') return Promise.resolve([gate])
      if (path === 'gates/own') return Promise.resolve(gate)
      return Promise.reject(new Error(`unexpected GET ${path}`))
    })
  })

  it('loads gates and its own gate on mount', async () => {
    render(GatesPage)

    await waitFor(() => expect(api.get).toHaveBeenCalledWith('gates'))
    await waitFor(() => expect(api.get).toHaveBeenCalledWith('gates/own'))
    await screen.findByText('EU-EE')
    screen.getByText('Gates (1)')
  })

  it('does not offer adding a gate, the registry is declarative', async () => {
    render(GatesPage)
    await screen.findByText('EU-EE')

    expect(screen.queryByRole('button', {name: 'Add'})).to.equal(null)
    expect(screen.queryByRole('button', {name: 'Edit'})).to.equal(null)
    expect(screen.queryByRole('button', {name: 'Delete'})).to.equal(null)
  })

  it('shows a gate in read-only details without calling the API', async () => {
    render(GatesPage)
    await screen.findByText('EU-EE')

    await fireEvent.click(screen.getByRole('button', {name: 'Details'}))

    await waitFor(() => expect((screen.getByLabelText('Gate ID') as HTMLInputElement).value).to.equal('EU-EE'))
    const url = screen.getByLabelText('eDelivery URL') as HTMLInputElement
    const country = screen.getByLabelText('Country') as HTMLSelectElement
    const cert = screen.getByLabelText('eDelivery certificate') as HTMLTextAreaElement

    expect(url.value).to.equal('https://gate-ee.example/msh')
    expect(country.value).to.equal('EE')
    expect(cert.value).to.contain('BEGIN CERTIFICATE')

    expect((screen.getByLabelText('Gate ID') as HTMLInputElement).disabled).to.equal(true)
    expect(url.disabled).to.equal(true)
    expect(country.disabled).to.equal(true)
    expect(cert.disabled).to.equal(true)
    expect(document.querySelectorAll('input[type=file]').length).to.equal(0)
    expect(screen.queryByRole('button', {name: 'Save'})).to.equal(null)

    expect(api.post).not.toHaveBeenCalled()
    expect(api.put).not.toHaveBeenCalled()
    expect(api.delete).not.toHaveBeenCalled()
  })

  it('shows a disabled gate and still opens its read-only details', async () => {
    vi.mocked(api.get).mockImplementation((path: string) => {
      if (path === 'gates') return Promise.resolve([{...gate, status: Status.DISABLED}])
      if (path === 'gates/own') return Promise.resolve(gate)
      return Promise.reject(new Error(`unexpected GET ${path}`))
    })
    render(GatesPage)

    await screen.findByText('DISABLED')
    await fireEvent.click(screen.getByRole('button', {name: 'Details'}))

    await waitFor(() => expect((screen.getByLabelText('Gate ID') as HTMLInputElement).value).to.equal('EU-EE'))
  })

  it("shows this gate's own details via the own-gate button", async () => {
    render(GatesPage)
    await screen.findByText('EU-EE')

    await fireEvent.click(screen.getByRole('button', {name: 'This gate details'}))

    await waitFor(() => expect((screen.getByLabelText('Gate ID') as HTMLInputElement).value).to.equal('EU-EE'))
    expect((screen.getByLabelText('Gate ID') as HTMLInputElement).disabled).to.equal(true)
  })
})

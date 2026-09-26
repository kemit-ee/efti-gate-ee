import {render, waitFor, fireEvent, screen} from '@testing-library/svelte'
import GatesPage from './GatesPage.svelte'
import api from 'src/api/api'
import {CountryCode, Status} from 'src/api/ruuterTypes'
import type {Gate} from 'src/api/ruuterTypes'
import {toastStore} from 'src/stores/toasts'
import {get} from 'svelte/store'

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
    gate = newGate() // GateForm mutates the object it's handed, so each test needs its own
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

  it('adds a new gate', async () => {
    vi.mocked(api.post).mockResolvedValue({})
    render(GatesPage)
    await screen.findByText('EU-EE')

    await fireEvent.click(screen.getByRole('button', {name: 'Add'}))
    await fireEvent.input(screen.getByLabelText('Gate ID'), {target: {value: 'EU-LV'}})
    await fireEvent.change(screen.getByLabelText('Country'), {target: {value: 'EE'}})
    await fireEvent.input(screen.getByLabelText('eDelivery URL'), {target: {value: 'https://gate-lv.example/msh'}})
    await fireEvent.input(screen.getByLabelText('eDelivery certificate'), {target: {value: '-BEGIN CERTIFICATE-\nabc\n-END CERTIFICATE-'}})

    await fireEvent.click(screen.getByRole('button', {name: 'Save'}))

    await waitFor(() => expect(api.post).toHaveBeenCalledWith('gates', expect.objectContaining({
      id: 'EU-LV', eDeliveryUrl: 'https://gate-lv.example/msh', status: Status.OFFLINE,
    })))
    // OFFLINE (not disabled) gates get an immediate ping attempt after creation
    await waitFor(() => expect(api.post).toHaveBeenCalledWith('gates/ping/EU-LV'))
  })

  it('marking a new gate disabled skips the post-create ping', async () => {
    vi.mocked(api.post).mockResolvedValue({})
    render(GatesPage)
    await screen.findByText('EU-EE')

    await fireEvent.click(screen.getByRole('button', {name: 'Add'}))
    await fireEvent.input(screen.getByLabelText('Gate ID'), {target: {value: 'EU-LV'}})
    await fireEvent.change(screen.getByLabelText('Country'), {target: {value: 'EE'}})
    await fireEvent.input(screen.getByLabelText('eDelivery URL'), {target: {value: 'https://gate-lv.example/msh'}})
    await fireEvent.input(screen.getByLabelText('eDelivery certificate'), {target: {value: '-BEGIN CERTIFICATE-\nabc\n-END CERTIFICATE-'}})
    await fireEvent.click(screen.getByLabelText('Disabled'))

    await fireEvent.click(screen.getByRole('button', {name: 'Save'}))

    await waitFor(() => expect(api.post).toHaveBeenCalledWith('gates', expect.objectContaining({status: Status.DISABLED})))
    expect(api.post).not.toHaveBeenCalledWith('gates/ping/EU-LV')
  })

  it('edits an existing gate', async () => {
    vi.mocked(api.put).mockResolvedValue({})
    render(GatesPage)
    await screen.findByText('EU-EE')

    await fireEvent.click(screen.getByRole('button', {name: 'Edit'}))
    expect((screen.getByLabelText('Gate ID') as HTMLInputElement).disabled).to.equal(true)
    await fireEvent.input(screen.getByLabelText('eDelivery URL'), {target: {value: 'https://gate-ee-new.example/msh'}})
    await fireEvent.click(screen.getByRole('button', {name: 'Save'}))

    await waitFor(() => expect(api.put).toHaveBeenCalledWith('gates/EU-EE', expect.objectContaining({
      id: 'EU-EE', eDeliveryUrl: 'https://gate-ee-new.example/msh',
    })))
  })

  it('pings a gate and shows a toast on success', async () => {
    vi.mocked(api.post).mockResolvedValue({...gate, status: Status.ONLINE})
    render(GatesPage)
    await screen.findByText('EU-EE')

    await fireEvent.click(screen.getByRole('button', {name: 'Ping'}))

    await waitFor(() => expect(api.post).toHaveBeenCalledWith('gates/ping/EU-EE'))
    await waitFor(() => expect(get(toastStore).some(t => t.message.includes('pinged'))).to.equal(true))
  })

  it('falls back to OFFLINE when a ping fails', async () => {
    vi.mocked(api.post).mockRejectedValue(new Error('unreachable'))
    render(GatesPage)
    await screen.findByText('EU-EE')

    await fireEvent.click(screen.getByRole('button', {name: 'Ping'}))

    await waitFor(() => screen.getByText('OFFLINE'))
  })

  it('deletes a gate after confirmation', async () => {
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    vi.mocked(api.delete).mockResolvedValue({})
    render(GatesPage)
    await screen.findByText('EU-EE')

    await fireEvent.click(screen.getByRole('button', {name: 'Delete'}))

    await waitFor(() => expect(api.delete).toHaveBeenCalledWith('gates/EU-EE'))
    await waitFor(() => expect(api.get).toHaveBeenCalledTimes(4)) // initial (gates+own) + reload (gates+own)
  })

  it('does not delete when the confirmation is declined', async () => {
    vi.spyOn(window, 'confirm').mockReturnValue(false)
    render(GatesPage)
    await screen.findByText('EU-EE')

    await fireEvent.click(screen.getByRole('button', {name: 'Delete'}))

    expect(api.delete).not.toHaveBeenCalled()
  })

  it("shows this gate's own details via the own-gate button", async () => {
    render(GatesPage)
    await screen.findByText('EU-EE')

    await fireEvent.click(screen.getByRole('button', {name: 'This gate details'}))

    await waitFor(() => expect((screen.getByLabelText('Gate ID') as HTMLInputElement).value).to.equal('EU-EE'))
    expect((screen.getByLabelText('Gate ID') as HTMLInputElement).disabled).to.equal(true)
  })
})

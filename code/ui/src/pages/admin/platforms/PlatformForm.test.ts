import {fireEvent, render, waitFor} from '@testing-library/svelte'
import PlatformForm from './PlatformForm.svelte'
import api from 'src/api/api'
import {type Platform, Status} from 'src/api/ruuterTypes'

vi.mock('src/api/api', () => ({default: {post: vi.fn().mockResolvedValue(undefined), put: vi.fn().mockResolvedValue(undefined)}}))

const CERT = '-----BEGIN CERTIFICATE-----\nabc\n-----END CERTIFICATE-----'

describe('PlatformForm', () => {
  beforeEach(() => vi.clearAllMocks())

  it('creates a new platform without eDelivery certs when the checkbox stays unchecked', async () => {
    const platform = {} as Platform
    const onSaved = vi.fn()
    const {getByLabelText, getByText} = render(PlatformForm, {platform, onSaved})

    await fireEvent.input(getByLabelText('Platform ID'), {target: {value: 'mock'}})
    await fireEvent.input(getByLabelText('Base URL'), {target: {value: 'https://mock.example/api'}})
    await fireEvent.click(getByText('Save'))

    await waitFor(() => expect(api.post).toHaveBeenCalledWith('platforms', {
      id: 'mock', baseUrl: 'https://mock.example/api', headers: {}, eDeliveryCert: '', tlsCert: ''
    }))
    expect(onSaved).toHaveBeenCalled()
  })

  it('auto-checks eDelivery when the base URL ends with /msh', async () => {
    const platform = {} as Platform
    const {getByLabelText} = render(PlatformForm, {platform})

    await fireEvent.input(getByLabelText('Base URL'), {target: {value: 'https://mock.example/services/msh'}})

    expect((getByLabelText('EDelivery') as HTMLInputElement).checked).to.be.true
    expect(getByLabelText('eDelivery certificate')).to.exist
  })

  it('includes eDelivery certs when eDelivery is checked', async () => {
    const platform = {} as Platform
    const onSaved = vi.fn()
    const {getByLabelText, getByText} = render(PlatformForm, {platform, onSaved})

    await fireEvent.input(getByLabelText('Platform ID'), {target: {value: 'mock'}})
    await fireEvent.input(getByLabelText('Base URL'), {target: {value: 'https://mock.example/api'}})
    await fireEvent.click(getByLabelText('EDelivery'))
    await fireEvent.input(getByLabelText('eDelivery certificate'), {target: {value: CERT}})
    await fireEvent.click(getByText('Save'))

    await waitFor(() => expect(api.post).toHaveBeenCalledWith('platforms', {
      id: 'mock', baseUrl: 'https://mock.example/api', headers: {}, eDeliveryCert: CERT, tlsCert: ''
    }))
    expect(onSaved).toHaveBeenCalled()
  })

  it('adds a header row and includes it in the request', async () => {
    const platform = {} as Platform
    const onSaved = vi.fn()
    const {getByLabelText, getByText, container} = render(PlatformForm, {platform, onSaved})

    await fireEvent.input(getByLabelText('Platform ID'), {target: {value: 'mock'}})
    await fireEvent.input(getByLabelText('Base URL'), {target: {value: 'https://mock.example/api'}})
    await fireEvent.click(getByText('+'))
    await fireEvent.input(container.querySelector('#key-0')!, {target: {value: 'X-Custom'}})
    await fireEvent.input(container.querySelector('#value-0')!, {target: {value: 'abc'}})
    await fireEvent.click(getByText('Save'))

    await waitFor(() => expect(api.post).toHaveBeenCalledWith('platforms', {
      id: 'mock', baseUrl: 'https://mock.example/api', headers: {'X-Custom': 'abc'}, eDeliveryCert: '', tlsCert: ''
    }))
  })

  it('updates an existing platform with a disabled id field', async () => {
    const platform: Platform = {id: 'mock', baseUrl: 'https://mock.example/api', status: Status.ONLINE, createdAt: '2026-01-01T00:00:00Z'}
    const onSaved = vi.fn()
    const {getByLabelText, getByText} = render(PlatformForm, {platform, onSaved})

    expect((getByLabelText('Platform ID') as HTMLInputElement).disabled).to.be.true

    await fireEvent.click(getByText('Save'))

    await waitFor(() => expect(api.put).toHaveBeenCalledWith('platforms/mock', {
      id: 'mock', baseUrl: 'https://mock.example/api', headers: {}, eDeliveryCert: '', tlsCert: ''
    }))
    expect(onSaved).toHaveBeenCalled()
  })

  it('marks the platform DISABLED when the checkbox is checked', async () => {
    const platform: Platform = {id: 'mock', baseUrl: 'https://mock.example/api', status: Status.ONLINE, createdAt: '2026-01-01T00:00:00Z'}
    const {getByLabelText, getByText} = render(PlatformForm, {platform})

    await fireEvent.click(getByLabelText('Disabled'))
    await fireEvent.click(getByText('Save'))

    await waitFor(() => expect(api.put).toHaveBeenCalledWith('platforms/mock', expect.objectContaining({id: 'mock'})))
    expect(platform.status).to.equal(Status.DISABLED)
  })
})

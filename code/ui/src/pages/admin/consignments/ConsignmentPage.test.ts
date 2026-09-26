import {render, waitFor, fireEvent, screen} from '@testing-library/svelte'
import ConsignmentPage from './ConsignmentPage.svelte'
import api from 'src/api/api'
import {ConsignmentStatus} from 'src/api/ruuterTypes'
import type {Consignment} from 'src/api/ruuterTypes'

vi.mock('src/api/api', () => ({default: {get: vi.fn(), delete: vi.fn()}}))

function newConsignment(): Consignment {
  return {
    datasetId: 'dataset-1', platformId: 'mock', gateId: 'EU-EE', status: ConsignmentStatus.ACTIVE,
    xml: '<FTI010GetCmdsResponse><value>hello</value></FTI010GetCmdsResponse>',
    dangerousGoods: '', createdAt: '2026-01-01T00:00:00Z',
    usedEquipmentIds: ['used-1'], carriedEquipmentIds: ['carried-1'], mainTransportId: 'main-1',
  }
}

describe('ConsignmentPage', () => {
  let consignment: Consignment

  beforeEach(() => {
    vi.clearAllMocks()
    consignment = newConsignment()
    vi.mocked(api.get).mockResolvedValue([consignment])
  })

  it('loads consignments on mount', async () => {
    render(ConsignmentPage)

    await waitFor(() => expect(api.get).toHaveBeenCalledWith('consignments'))
    await screen.findByText('dataset-1')
    screen.getByText('mock')
    screen.getByText('carried-1,used-1,main-1')
  })

  it('shows the UI dataset view by default and can switch to XML', async () => {
    render(ConsignmentPage)
    await screen.findByText('dataset-1')

    await fireEvent.click(screen.getByRole('button', {name: 'View'}))

    await screen.findByText('hello')

    await fireEvent.click(screen.getByRole('button', {name: 'XML'}))
    await waitFor(() => expect(screen.getAllByText(/FTI010GetCmdsResponse/).length).to.be.greaterThan(0))
  })

  it('shows a QR code for the UIL', async () => {
    render(ConsignmentPage)
    await screen.findByText('dataset-1')

    await fireEvent.click(screen.getByRole('button', {name: 'View QR Code'}))

    await screen.findByText('UIL QR Code')
  })

  it('deletes a consignment after confirmation, scoped to its platform and gate', async () => {
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    vi.mocked(api.delete).mockResolvedValue({})
    render(ConsignmentPage)
    await screen.findByText('dataset-1')

    await fireEvent.click(screen.getByRole('button', {name: 'Delete'}))

    await waitFor(() => expect(api.delete).toHaveBeenCalledWith('consignments/dataset-1?platformId=mock&gateId=EU-EE'))
  })

  it('does not delete when the confirmation is declined', async () => {
    vi.spyOn(window, 'confirm').mockReturnValue(false)
    render(ConsignmentPage)
    await screen.findByText('dataset-1')

    await fireEvent.click(screen.getByRole('button', {name: 'Delete'}))

    expect(api.delete).not.toHaveBeenCalled()
  })
})

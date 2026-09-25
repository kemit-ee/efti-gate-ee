import {fireEvent, render, waitFor} from '@testing-library/svelte'
import ConsignmentList from './ConsignmentList.svelte'
import api from 'src/api/api'
import type {Consignment} from 'src/api/ruuterTypes'

vi.mock('src/api/api', () => ({default: {delete: vi.fn().mockResolvedValue(undefined)}}))
vi.mock('qrcode', () => ({default: {toCanvas: vi.fn()}}))

const consignment: Consignment = {
  datasetId: 'ds-1', platformId: 'mock', gateId: 'EE', status: 'ACTIVE' as any,
  xml: '<Consignment><MainCarriageTransportMovement><ID>TRK-1</ID></MainCarriageTransportMovement></Consignment>',
  dangerousGoods: 'false', createdAt: '2026-01-01T00:00:00Z',
  usedEquipmentIds: ['EQ1'], carriedEquipmentIds: ['EQ2'], mainTransportId: 'TRK-1'
}

describe('ConsignmentList', () => {
  let onDeleted: (c: Consignment) => void

  beforeEach(() => {
    vi.clearAllMocks()
    onDeleted = vi.fn()
    vi.spyOn(window, 'confirm').mockReturnValue(true)
  })

  it('renders a row with identifiers joined together', () => {
    const {container} = render(ConsignmentList, {consignments: [consignment], onDeleted})
    expect(container.textContent).to.contain('ds-1')
    expect(container.textContent).to.contain('mock')
    expect(container.textContent).to.contain('EQ2,EQ1,TRK-1')
  })

  it('deletes the consignment with owner query params after confirmation', async () => {
    const {getByText} = render(ConsignmentList, {consignments: [consignment], onDeleted})
    await fireEvent.click(getByText('Delete'))
    expect(api.delete).toHaveBeenCalledWith('consignments/ds-1?platformId=mock&gateId=EE')
    expect(onDeleted).toHaveBeenCalledWith(consignment)
  })

  it('does not delete when confirmation is declined', async () => {
    vi.spyOn(window, 'confirm').mockReturnValue(false)
    const {getByText} = render(ConsignmentList, {consignments: [consignment], onDeleted})
    await fireEvent.click(getByText('Delete'))
    expect(api.delete).not.toHaveBeenCalled()
  })

  it('opens the QR code modal for the combined UIL', async () => {
    const {getByText} = render(ConsignmentList, {consignments: [consignment], onDeleted})
    await fireEvent.click(getByText('View QR Code'))
    await waitFor(() => expect(document.body.classList.contains('modal-open')).to.be.true)
    expect(document.body.textContent).to.contain('UIL QR Code')
  })

  it('opens the consignment viewer defaulting to the UI tab', async () => {
    const {getByText} = render(ConsignmentList, {consignments: [consignment], onDeleted})
    await fireEvent.click(getByText('View'))
    await waitFor(() => expect(document.body.classList.contains('modal-open')).to.be.true)
    expect(document.body.textContent).to.contain('TRK-1')
  })

  it('switches the consignment viewer to the XML tab', async () => {
    const {getByText} = render(ConsignmentList, {consignments: [consignment], onDeleted})
    await fireEvent.click(getByText('View'))
    await waitFor(() => expect(document.body.classList.contains('modal-open')).to.be.true)

    await fireEvent.click(getByText('XML'))

    expect(document.body.textContent).to.contain('MainCarriageTransportMovement')
  })
})

import {render, screen} from '@testing-library/svelte'
import EDeliveryFields from './EDeliveryFields.svelte'

describe('EDeliveryFields', () => {
  it('renders the certificates read-only without any upload control', () => {
    const entity = {eDeliveryCert: '-BEGIN CERTIFICATE-\nabc\n-END CERTIFICATE-', tlsCert: ''}
    render(EDeliveryFields, {entity, disabled: true})

    const cert = screen.getByLabelText('eDelivery certificate') as HTMLTextAreaElement
    expect(cert.value).to.equal(entity.eDeliveryCert)
    expect(cert.disabled).to.equal(true)
    expect((screen.getByLabelText(/TLS certificate/) as HTMLTextAreaElement).disabled).to.equal(true)
    expect(document.querySelectorAll('input[type=file]').length).to.equal(0)
    expect(entity.eDeliveryCert).to.equal('-BEGIN CERTIFICATE-\nabc\n-END CERTIFICATE-')
  })

  it('offers upload controls when editable', () => {
    render(EDeliveryFields, {entity: {eDeliveryCert: '', tlsCert: ''}, disabled: false})

    expect(document.querySelectorAll('input[type=file]').length).to.equal(2)
    expect((screen.getByLabelText('eDelivery certificate') as HTMLTextAreaElement).disabled).to.equal(false)
  })
})

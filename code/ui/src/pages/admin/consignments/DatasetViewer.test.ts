import {render} from '@testing-library/svelte'
import DatasetViewer from './DatasetViewer.svelte'

describe('DatasetViewer', () => {
  it('renders a plain scalar as-is', () => {
    const {container} = render(DatasetViewer, {data: 'hello'})
    expect(container.textContent).to.contain('hello')
  })

  it('renders an amount with its currency', () => {
    const {container} = render(DatasetViewer, {data: {value: '42.5', currencyId: 'EUR'}})
    expect(container.textContent).to.contain('42.5')
    expect(container.textContent).to.contain('EUR')
  })

  it('renders an id with its scheme agency', () => {
    const {container} = render(DatasetViewer, {data: {value: 'ABC123', schemeAgencyId: 'ISO'}})
    expect(container.textContent).to.contain('ABC123')
    expect(container.textContent).to.contain('ISO')
  })

  it('renders a simple object as a labelled grid', () => {
    const {container} = render(DatasetViewer, {data: {firstName: 'Jane', lastName: 'Doe'}})
    expect(container.textContent).to.contain('First Name')
    expect(container.textContent).to.contain('Jane')
    expect(container.textContent).to.contain('Last Name')
    expect(container.textContent).to.contain('Doe')
  })

  it('renders nested objects recursively', () => {
    const {container} = render(DatasetViewer, {data: {consignor: {name: 'Acme'}}})
    expect(container.textContent).to.contain('Consignor')
    expect(container.textContent).to.contain('Acme')
  })

  it('renders arrays as a numbered list', () => {
    const {container} = render(DatasetViewer, {data: [{value: 'A'}, {value: 'B'}]})
    expect(container.textContent).to.contain('1')
    expect(container.textContent).to.contain('A')
    expect(container.textContent).to.contain('2')
    expect(container.textContent).to.contain('B')
  })
})

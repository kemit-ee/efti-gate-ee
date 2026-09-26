import {render, screen} from '@testing-library/svelte'
import DatasetViewer from './DatasetViewer.svelte'

describe('DatasetViewer', () => {
  it('renders a plain primitive value', () => {
    render(DatasetViewer, {data: 'hello'})
    screen.getByText('hello')
  })

  it('renders an amount with a currency', () => {
    render(DatasetViewer, {data: {value: 42, currencyId: 'EUR'}})
    screen.getByText('42 EUR')
  })

  it('renders an amount with a unit', () => {
    render(DatasetViewer, {data: {value: 5, unitId: 'KGM'}})
    screen.getByText('5 KGM')
  })

  it('renders an id with a scheme agency', () => {
    render(DatasetViewer, {data: {value: 'ABC123', schemeAgencyId: 'ISO'}})
    screen.getByText('(ISO)')
  })

  it('renders an id with a format', () => {
    render(DatasetViewer, {data: {value: 'ABC123', formatId: 'ISO6346'}})
    screen.getByText('[ISO6346]')
  })

  it('renders a simple object as a key/value grid, humanizing camelCase keys', () => {
    render(DatasetViewer, {data: {mainTransportId: 'main-1', platformId: 'mock'}})
    screen.getByText('Main Transport Id')
    screen.getByText('main-1')
    screen.getByText('Platform Id')
  })

  it('renders a nested object recursively, hiding the "value" key label', () => {
    render(DatasetViewer, {data: {consignment: {value: {mainTransportId: 'main-1'}}}})
    screen.getByText('Consignment')
    expect(screen.queryByText('Value')).to.equal(null)
    screen.getByText('Main Transport Id')
  })

  it('renders an array of nested objects with position markers', () => {
    render(DatasetViewer, {data: [{mainTransportId: 'main-1'}, {mainTransportId: 'main-2'}]})
    screen.getByText('1')
    screen.getByText('2')
    screen.getByText('main-1')
    screen.getByText('main-2')
  })

  it('renders null/undefined leaves without throwing', () => {
    render(DatasetViewer, {data: null})
  })
})

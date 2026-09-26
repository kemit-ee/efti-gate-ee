import {fireEvent, render} from '@testing-library/svelte'
import SortableTable from './SortableTable.svelte'
import enDict from 'i18n/en.json'

describe('SortableTable', () => {
  it('sorts items by clicking on headers', async () => {
    const items = [{a: 2, b: 'zzz'}, {a: 1, b: 'aaa'}]
    const labels = enDict.gates
    const columns: any[] = [['id', 'a'], ['eDeliveryUrl', 'b']]
    const {container, component} = render(SortableTable, {labels, items, columns, class: 'another-class'})
    expect(container.querySelector('.another-class')).to.be.ok
    expect(container.textContent).to.contain(enDict.gates.id)
    expect(container.textContent).to.contain(enDict.gates.eDeliveryUrl)
    // TODO: expect(component.items).to.deep.equal(items)

    const headers = container.querySelectorAll('th')
    await fireEvent.click(headers[0])
    expect(headers[0].classList.contains('sorted')).to.be.true
    expect(headers[0].classList.contains('asc')).to.be.true
    // TODO: how to get internal state of the component in Svelte 5?
    // expect(component.items).to.deep.equal(items)

    await fireEvent.click(headers[0])
    expect(headers[0].classList.contains('desc')).to.be.true
    // expect(component.items).to.deep.equal(items.reverse())
  })

  it('do not sort columns with empty headers', async () => {
    const items = [{a: 2, b: 'zzz'}, {a: 1, b: 'aaa'}]
    const labels = enDict.gates
    const columns: any[] = [['', 'a'], ['time', 'b']]
    const {container, component} = render(SortableTable, {labels, items, columns, class: 'another-class'})

    const headers = container.querySelectorAll('th')
    await fireEvent.click(headers[0])
    expect(headers[0].classList.contains('sorted')).to.be.false
    // TODO expect(component.items).to.deep.equal(items)
  })

  it('shows spinner if items not yet loaded', async () => {
    const {container} = render(SortableTable, {items: undefined, columns: []})
    expect(container.querySelector('tbody')).to.exist
    expect(container.querySelector('.spinner')).to.exist
  })

  it('handles no items', async () => {
    const {container} = render(SortableTable, {items: [], columns: []})
    expect(container.querySelector('tbody')!.textContent).to.contain(enDict.general.noItems)
  })

  it('right-aligns every column from rightAlignFrom onward', () => {
    const items = [{a: 1, b: 'x', c: 'y'}]
    const columns: any[] = ['a', 'b', 'c']
    const {container} = render(SortableTable, {items, columns, rightAlignFrom: 'b' as any})

    const cells = container.querySelectorAll('td')
    expect(cells[0].classList.contains('text-right')).to.equal(false)
    expect(cells[1].classList.contains('text-right')).to.equal(true)
    expect(cells[2].classList.contains('text-right')).to.equal(true)
  })

  it('shows a spinner while more local pages remain to be rendered (no onLoadMore)', () => {
    const items = Array.from({length: 150}, (_, i) => ({a: i}))
    const {container} = render(SortableTable, {items, columns: ['a'] as any, renderMax: 100})

    expect(container.querySelector('tbody .spinner')).to.exist
  })

  it('shows a spinner while the server has more pages (via onLoadMore/hasMore)', () => {
    const items = [{a: 1}]
    const {container} = render(SortableTable, {items, columns: ['a'] as any, onLoadMore: () => {}, hasMore: true})

    expect(container.querySelector('tbody .spinner')).to.exist
  })

  it('does not show a spinner once onLoadMore reports there is nothing more', () => {
    const items = [{a: 1}]
    const {container} = render(SortableTable, {items, columns: ['a'] as any, onLoadMore: () => {}, hasMore: false})

    expect(container.querySelector('tbody .spinner')).to.equal(null)
  })
})

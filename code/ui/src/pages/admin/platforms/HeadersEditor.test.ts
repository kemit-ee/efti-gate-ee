import {fireEvent, render} from '@testing-library/svelte'
import HeadersEditor from './HeadersEditor.svelte'

describe('HeadersEditor', () => {
  it('renders no rows when there are no headers', () => {
    const {container} = render(HeadersEditor, {headers: []})
    expect(container.querySelectorAll('#key-0').length).to.equal(0)
  })

  it('renders a row per header with its key and value', () => {
    const {container} = render(HeadersEditor, {headers: [['X-Custom', 'abc']]})
    expect((container.querySelector('#key-0') as HTMLInputElement).value).to.equal('X-Custom')
    expect((container.querySelector('#value-0') as HTMLInputElement).value).to.equal('abc')
  })

  it('adds a new empty row when + is clicked', async () => {
    const {getByText, container, component} = render(HeadersEditor, {headers: []})
    await fireEvent.click(getByText('+'))
    expect(container.querySelector('#key-0')).to.exist
  })

  it('removes a row when × is clicked', async () => {
    const {getByTitle, container} = render(HeadersEditor, {headers: [['X-Custom', 'abc']]})
    await fireEvent.click(getByTitle('Remove'))
    expect(container.querySelector('#key-0')).to.be.null
  })
})

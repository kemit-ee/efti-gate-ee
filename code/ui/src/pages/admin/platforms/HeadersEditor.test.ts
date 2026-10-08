import {render, screen} from '@testing-library/svelte'
import HeadersEditor from './HeadersEditor.svelte'

describe('HeadersEditor', () => {
  it('renders the current headers read-only without any add or remove control', () => {
    render(HeadersEditor, {headers: [['X-Api-Key', 'secret']], disabled: true})

    const key = screen.getByLabelText('Key') as HTMLInputElement
    const value = screen.getByLabelText('Value') as HTMLInputElement
    expect(key.value).to.equal('X-Api-Key')
    expect(value.value).to.equal('secret')
    expect(key.disabled).to.equal(true)
    expect(value.disabled).to.equal(true)
    expect(screen.queryByRole('button', {name: '+'})).to.equal(null)
    expect(screen.queryAllByRole('button', {name: '×'}).length).to.equal(0)
  })

  it('renders no rows for a platform without headers', () => {
    render(HeadersEditor, {headers: [], disabled: true})

    expect(screen.queryByLabelText('Key')).to.equal(null)
  })

  it('offers add and remove controls when editable', () => {
    render(HeadersEditor, {headers: [['X-Api-Key', 'secret']], disabled: false})

    screen.getByRole('button', {name: '+'})
    expect(screen.getAllByRole('button', {name: '×'}).length).to.equal(1)
    expect((screen.getByLabelText('Key') as HTMLInputElement).disabled).to.equal(false)
  })
})

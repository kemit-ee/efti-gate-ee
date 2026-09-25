import {createRawSnippet} from 'svelte'
import {fireEvent, render} from '@testing-library/svelte'
import Dropdown from './Dropdown.svelte'

const children = createRawSnippet(() => ({
  render: () => `<span>Opener</span>`
}))

const menu = createRawSnippet(() => ({
  render: () => `<div>Menu content</div>`
}))

describe('Dropdown', () => {
  it('is closed by default', () => {
    const {queryByText, getByRole} = render(Dropdown, {children, menu})
    expect(queryByText('Menu content')).to.be.null
    expect(getByRole('button').getAttribute('aria-expanded')).to.equal('false')
  })

  it('opens the menu when the opener is clicked', async () => {
    const {getByRole, queryByText} = render(Dropdown, {children, menu})
    await fireEvent.click(getByRole('button'))
    expect(queryByText('Menu content')).to.exist
    expect(getByRole('button').getAttribute('aria-expanded')).to.equal('true')
  })

  it('closes when clicking outside', async () => {
    const {getByRole} = render(Dropdown, {children, menu})
    const opener = getByRole('button')
    await fireEvent.click(opener)
    expect(opener.getAttribute('aria-expanded')).to.equal('true')

    await fireEvent.click(document.body)
    expect(opener.getAttribute('aria-expanded')).to.equal('false')
  })

  it('does not close when clicking inside the wrapper', async () => {
    const {getByRole, getByText} = render(Dropdown, {children, menu})
    const opener = getByRole('button')
    await fireEvent.click(opener)

    await fireEvent.click(getByText('Menu content'))
    expect(opener.getAttribute('aria-expanded')).to.equal('true')
  })

  it('closes on Escape', async () => {
    const {getByRole} = render(Dropdown, {children, menu})
    const opener = getByRole('button')
    await fireEvent.click(opener)
    expect(opener.getAttribute('aria-expanded')).to.equal('true')

    await fireEvent.keyDown(document.body, {key: 'Escape'})
    expect(opener.getAttribute('aria-expanded')).to.equal('false')
  })

  it('toggles open on Space and Enter keys', async () => {
    const {getByRole} = render(Dropdown, {children, menu})
    const opener = getByRole('button')

    await fireEvent.keyDown(opener, {key: 'Enter'})
    expect(opener.getAttribute('aria-expanded')).to.equal('true')

    await fireEvent.keyDown(opener, {key: ' '})
    expect(opener.getAttribute('aria-expanded')).to.equal('false')
  })

  it('starts open when open=true', () => {
    const {queryByText} = render(Dropdown, {children, menu, open: true})
    expect(queryByText('Menu content')).to.exist
  })
})

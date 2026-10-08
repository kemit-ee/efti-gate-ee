import {render, screen, waitFor} from '@testing-library/svelte'
import SubsetsEditor from './SubsetsEditor.svelte'

describe('SubsetsEditor', () => {
  it('renders the current subsets read-only without any add or remove control', async () => {
    render(SubsetsEditor, {subsets: ['EU01'], disabled: true})

    const input = document.querySelector('.subset input') as HTMLInputElement
    await waitFor(() => expect(input).to.not.equal(null))
    expect(input.value).to.equal('EU01')
    expect(input.disabled).to.equal(true)
    expect(screen.queryByRole('button', {name: '+'})).to.equal(null)
    expect(screen.queryAllByRole('button', {name: '×'}).length).to.equal(0)
  })

  it('does not add a row to an empty read-only list', async () => {
    render(SubsetsEditor, {subsets: [], disabled: true})

    await waitFor(() => expect(document.querySelectorAll('.subset input').length).to.equal(0))
  })

  it('starts an editable list off with one row', async () => {
    render(SubsetsEditor, {subsets: [], disabled: false})

    await waitFor(() => expect(document.querySelectorAll('.subset input').length).to.equal(1))
    screen.getByRole('button', {name: '+'})
  })

  it('marks duplicate editable subsets as invalid', async () => {
    render(SubsetsEditor, {subsets: ['EU01', 'EU01'], disabled: false})

    await waitFor(() => {
      const inputs = document.querySelectorAll('.subset input') as NodeListOf<HTMLInputElement>
      expect(inputs.length).to.equal(2)
      expect(inputs[0].validationMessage).to.equal('No duplicate values allowed')
    })
  })
})

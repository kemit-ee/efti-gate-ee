import {render, fireEvent, screen} from '@testing-library/svelte'
import MultiCheckboxField from './MultiCheckboxField.svelte'

describe('MultiCheckboxField', () => {
  it('renders a checkbox per option, checked according to the current value', () => {
    render(MultiCheckboxField, {label: 'Roles', value: ['admin'], options: {admin: 'Admin', viewer: 'Viewer'}})

    expect((screen.getByLabelText('Admin') as HTMLInputElement).checked).to.equal(true)
    expect((screen.getByLabelText('Viewer') as HTMLInputElement).checked).to.equal(false)
  })

  it('adds an option to the value when checked', async () => {
    render(MultiCheckboxField, {label: 'Roles', value: [], options: {admin: 'Admin', viewer: 'Viewer'}})

    await fireEvent.click(screen.getByLabelText('Admin'))

    expect((screen.getByLabelText('Admin') as HTMLInputElement).checked).to.equal(true)
  })

  it('removes an option from the value when unchecked', async () => {
    render(MultiCheckboxField, {label: 'Roles', value: ['admin', 'viewer'], options: {admin: 'Admin', viewer: 'Viewer'}})

    await fireEvent.click(screen.getByLabelText('Admin'))

    expect((screen.getByLabelText('Admin') as HTMLInputElement).checked).to.equal(false)
    expect((screen.getByLabelText('Viewer') as HTMLInputElement).checked).to.equal(true)
  })

  it('disables every checkbox when disabled', () => {
    render(MultiCheckboxField, {label: 'Roles', value: [], options: {admin: 'Admin'}, disabled: true})

    expect((screen.getByLabelText('Admin') as HTMLInputElement).disabled).to.equal(true)
  })
})

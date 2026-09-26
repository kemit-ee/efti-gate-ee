import {render, screen} from '@testing-library/svelte'
import PlusSelectField from './PlusSelectField.svelte'

describe('PlusSelectField', () => {
  it('renders a select of the options not already in values', () => {
    render(PlusSelectField, {values: ['EE'], options: {EE: 'Estonia', LV: 'Latvia', LT: 'Lithuania'}, select: undefined as any})

    const select = screen.getByRole('combobox') as HTMLSelectElement
    const optionValues = Array.from(select.options).map(o => o.value)
    expect(optionValues).to.not.contain('EE')
    expect(optionValues).to.contain('LV')
    expect(optionValues).to.contain('LT')
  })

  it('renders nothing once every option is already selected', () => {
    render(PlusSelectField, {values: ['EE', 'LV'], options: {EE: 'Estonia', LV: 'Latvia'}, select: undefined as any})

    expect(screen.queryByRole('combobox')).to.equal(null)
  })

  it('treats undefined options as empty', () => {
    render(PlusSelectField, {values: [], options: undefined, select: undefined as any})

    expect(screen.queryByRole('combobox')).to.equal(null)
  })
})

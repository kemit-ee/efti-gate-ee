import {render} from '@testing-library/svelte'
import CheckboxField from './CheckboxField.svelte'

it('generates id', () => {
  const {container} = render(CheckboxField, {label: 'merchant.consent'})
  const input = container.querySelector('input') as HTMLInputElement
  expect(input.id).to.eq('merchant-consent')
  expect(input.type).to.eq('checkbox')
  expect(input.checked).to.be.false
  expect(input.required).to.be.false
  expect(container.querySelector('label')!.textContent).to.eq('merchant.consent')
})

it('renders without a label', () => {
  const {container} = render(CheckboxField, {})
  expect(container.querySelector('input')!.id).to.eq('')
  expect(container.querySelector('label')).to.equal(null)
})

it('reflects a checked value', () => {
  const {container} = render(CheckboxField, {label: 'x', checked: true})
  expect((container.querySelector('input') as HTMLInputElement).checked).to.equal(true)
})

it('shows optional help text', () => {
  const {container} = render(CheckboxField, {label: 'x', helpText: 'more info'})
  expect(container.querySelector('.help-text')!.getAttribute('title')).to.equal('more info')
})

import {render} from '@testing-library/svelte'
import Icon from './Icon.svelte'

describe('Icon', () => {
  const name = 'x'
  const focusable = true

  it('renders', async () => {
    const {container} = render(Icon, {name, focusable})
    expect(container.querySelector('svg.icon.x')).to.exist
  })

  it('falls back to a placeholder for an unknown icon name', () => {
    const {container} = render(Icon, {name: 'not-a-real-icon'})
    expect(container.textContent).to.contain('NO-ICON-not-a-real-icon')
  })
})

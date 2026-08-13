import {act, render} from '@testing-library/svelte'
import Toasts from './Toasts.svelte'
import {showToast, ToastType} from 'src/stores/toasts'

describe('Toasts', () => {
  it('renders success toast', async () => {
    const {container} = render(Toasts)
    await act(() => showToast('Great success', {type: ToastType.SUCCESS, title: 'Success'}))
    const toast = container.querySelector('.toast')!
    expect(toast.textContent).to.contain('Great success')
    expect(toast.textContent).to.contain('Success')
  })

  it('renders warning toast with icon', async () => {
    const {container} = render(Toasts)
    await act(() => showToast('Great warning', {type: ToastType.WARNING, title: 'Warning'}))
    const toast = [...container.querySelectorAll('.toast')].last()
    expect(toast.textContent).to.contain('Great warning')
    expect(toast.textContent).to.contain('Warning')
    expect(toast.querySelector('svg')).to.exist
  })
})

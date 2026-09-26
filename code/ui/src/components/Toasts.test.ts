import {act, render} from '@testing-library/svelte'
import Toasts from './Toasts.svelte'
import {showToast, toastStore, ToastType} from 'src/stores/toasts'
import {get} from 'svelte/store'

describe('Toasts', () => {
  beforeEach(() => toastStore.set([]))

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

  it('renders an error toast', async () => {
    const {container} = render(Toasts)
    await act(() => showToast('Something broke', {type: ToastType.ERROR}))
    const toast = container.querySelector('.toast')!
    expect(toast.className).to.contain('bg-danger-100')
  })

  it('renders an info (default) toast without a title', async () => {
    const {container} = render(Toasts)
    await act(() => showToast('Just so you know', {type: ToastType.INFO}))
    const toast = container.querySelector('.toast')!
    expect(toast.className).to.contain('bg-primary-100')
    expect(toast.querySelector('p.font-semibold')).to.equal(null)
  })

  it('renders html messages unescaped', async () => {
    const {container} = render(Toasts)
    await act(() => showToast('<b>bold</b>', {html: true}))
    expect(container.querySelector('.toast b')).to.exist
  })

  it('can be dismissed by clicking its close button', async () => {
    const {container} = render(Toasts)
    await act(() => showToast('Dismiss me'))
    expect(container.querySelector('.toast')).to.exist

    await act(() => (container.querySelector('.toast button') as HTMLButtonElement).click())

    expect(get(toastStore)).to.have.length(0)
  })
})

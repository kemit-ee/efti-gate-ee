import {cleanup, fireEvent, render} from '@testing-library/svelte'
import Modal from './Modal.svelte'

it('Modal is shown', async () => {
  const {rerender} = render(Modal, {title: 'Title', show: false, flyParams: {duration: 0}})
  expect(document.querySelector('.modal')).not.to.exist

  await rerender({show: true})
  expect(document.body.textContent).to.contain('Title')
  expect(document.body.classList.contains('modal-open')).to.be.true

  await rerender({show: false})
  expect(document.body.classList.contains('modal-open')).to.be.false
})

it('body.modal-open is added on show and removed on destroy', () => {
  render(Modal, {title: 'Title', show: true, flyParams: {duration: 0}})
  expect(document.body.classList.contains('modal-open')).to.be.true
  cleanup()
  expect(document.body.classList.contains('modal-open')).to.be.false
  expect(document.querySelector('.modal')).not.to.exist
})

it('shows an optional description', () => {
  render(Modal, {title: 'Title', description: 'More details', show: true, flyParams: {duration: 0}})
  expect(document.body.textContent).to.contain('More details')
})

it('closes on pressing Escape', async () => {
  render(Modal, {title: 'Title', show: true, flyParams: {duration: 0}})
  expect(document.querySelector('.modal')).to.exist

  await fireEvent.keyUp(window, {code: 'Escape'})

  expect(document.querySelector('.modal')).not.to.exist
})

it('ignores other keys', async () => {
  render(Modal, {title: 'Title', show: true, flyParams: {duration: 0}})

  await fireEvent.keyUp(window, {code: 'Enter'})

  expect(document.querySelector('.modal')).to.exist
})

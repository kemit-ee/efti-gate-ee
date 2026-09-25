import {get} from 'svelte/store'
import {currentUser} from './session'
import type {User} from 'src/api/ruuterTypes'

describe('session store', () => {
  it('starts undefined', () => {
    expect(get(currentUser)).to.be.undefined
  })

  it('can be set and read back', () => {
    const user: User = {id: '60001019906', taraSub: 'EE60001019906', name: 'Super Admin', createdAt: '2026-01-01T00:00:00Z'}
    currentUser.set(user)
    expect(get(currentUser)).to.deep.equal(user)
  })
})

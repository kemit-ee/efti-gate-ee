import {writable} from 'svelte/store'
import type {User} from 'src/api/types'
import api from 'src/api/api'
import {navigate} from 'src/router'

export const user = writable<User|undefined>()

export function initSession(auth: User) {
  user.set(auth)
}

export async function userSwitch() {
  user.set(await api.get('switch'))
  navigate('/')
}

import {writable} from 'svelte/store'
import type {User} from 'src/api/ruuterTypes'

export const currentUser = writable<User | undefined>(undefined)

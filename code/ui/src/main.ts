import {mount} from 'svelte'
import './extensions/ArrayExtensions'
import './global.css'
import App from './App.svelte'
import {initErrorHandlers} from 'src/api/errorHandlers'
import api from 'src/api/api'
import {initSession} from 'src/stores/auth'
import type {User} from 'src/api/types'

initErrorHandlers()

api.get<User>('user').then(initSession)
mount(App, {target: document.getElementById('app')!})

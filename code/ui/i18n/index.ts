import type en from './en.json'
import langs from './langs.json'

export function changeLang(lang: typeof langs[number]) {
  localStorage['lang'] = lang
  location.reload()
}

export function resolve(key: string, from: Record<string, any> = t): any {
  return key.split('.').reduce((acc, key) => acc && acc[key], from)
}

function choosePreferredLang() {
  let lang = localStorage['lang'] ?? navigator.language.split('-')[0]
  return langs.includes(lang) ? lang : langs[0]
}

async function load() {
  if (lang === 'et') return (await import('./et.json')).default
  if (lang === 'en') return (await import('./en.json')).default
  else throw new Error('Unsupported lang: ' + lang)
}

export const lang = choosePreferredLang()
export let t: typeof en = await load()

export function formatDateTime(date?: string) {
  return date ? new Intl.DateTimeFormat(lang === 'et' ? 'et-EE' : 'en-GB', {dateStyle: 'long', timeStyle: 'medium'}).format(new Date(date)) : ''
}

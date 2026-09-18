import forms from '@tailwindcss/forms'

const base = (token) => `var(--tedi-${token})`
const token = (name) => `var(--${name})`

const primary = {
  100: base('primary-100'),
  200: base('primary-200'),
  300: base('primary-300'),
  400: base('primary-400'),
  500: base('primary-500'),
  600: base('primary-600'),
  700: base('primary-700'),
  800: base('primary-800'),
}

const neutral = {
  100: base('neutral-100'),
  200: base('neutral-200'),
  300: base('neutral-300'),
  350: base('neutral-350'),
  400: base('neutral-400'),
  450: base('neutral-450'),
  500: base('neutral-500'),
  550: base('neutral-550'),
  600: base('neutral-600'),
  700: base('neutral-700'),
  750: base('neutral-750'),
  800: base('neutral-800'),
  850: base('neutral-850'),
  900: base('neutral-900'),
}

const success = {
  100: base('green-100'),
  200: base('green-200'),
  300: base('green-300'),
  400: base('green-400'),
  500: base('green-500'),
  600: base('green-600'),
  700: base('green-700'),
  800: base('green-800'),
}

const warning = {
  100: base('yellow-100'),
  200: base('yellow-200'),
  300: base('yellow-300'),
  400: base('yellow-400'),
  500: base('yellow-500'),
  600: base('yellow-600'),
  700: base('yellow-700'),
  800: base('yellow-800'),
}

const danger = {
  100: base('red-100'),
  200: base('red-200'),
  300: base('red-300'),
  400: base('red-400'),
  500: base('red-500'),
  600: base('red-600'),
  700: base('red-700'),
  800: base('red-800'),
}

const accent = {
  100: base('orange-100'),
  200: base('orange-200'),
  300: base('orange-300'),
  400: base('orange-400'),
  500: base('orange-500'),
  600: base('orange-600'),
  700: base('orange-700'),
  800: base('orange-800'),
}

const spacing = Object.fromEntries(
  Array.from({length: 27}, (_, i) => [`tedi-${i}`, base(`dimensions-${String(i).padStart(2, '0')}`)])
)

export default {
  content: ["./src/**/*.svelte"],
  darkMode: 'class',
  theme: {
    extend: {
      fontFamily: {
        sans: [token('family-default'), 'Roboto', 'sans-serif'],
        serif: 'serif'
      },
      fontSize: {
        'tedi-h1': [token('heading-h1-size'), {lineHeight: token('heading-h1-line-height'), fontWeight: token('heading-h1-weight')}],
        'tedi-h2': [token('heading-h2-size'), {lineHeight: token('heading-h2-line-height'), fontWeight: token('heading-h2-weight')}],
        'tedi-h3': [token('heading-h3-size'), {lineHeight: token('heading-h3-line-height'), fontWeight: token('heading-h3-weight')}],
        'tedi-h4': [token('heading-h4-size'), {lineHeight: token('heading-h4-line-height'), fontWeight: token('heading-h4-weight')}],
        'tedi-h5': [token('heading-h5-size'), {lineHeight: token('heading-h5-line-height'), fontWeight: token('heading-h5-weight')}],
        'tedi-h6': [token('heading-h6-size'), {lineHeight: token('heading-h6-line-height'), fontWeight: token('heading-h6-weight')}],
        'tedi-body': [token('body-regular-size'), {lineHeight: token('body-regular-line-height')}],
        'tedi-body-small': [token('body-small-regular-size'), {lineHeight: token('body-small-regular-line-height')}],
        'tedi-body-xs': [token('body-extra-small-size'), {lineHeight: token('body-extra-small-line-height')}],
      },
      colors: {
        primary,
        secondary: neutral,
        neutral,
        success,
        warning,
        danger,
        accent,
        surface: {
          primary: token('general-surface-primary'),
          secondary: token('general-surface-secondary'),
          tertiary: token('general-surface-tertiary'),
          brand: token('general-surface-brand-primary'),
          'brand-secondary': token('general-surface-brand-secondary'),
          'brand-tertiary': token('general-surface-brand-tertiary'),
          'brand-quaternary': token('general-surface-brand-quaternary'),
          accent: token('general-surface-accent'),
          success: token('general-surface-success'),
          disabled: token('general-surface-disabled'),
          inverted: token('general-surface-inverted-primary'),
        },
        content: {
          primary: token('general-text-primary'),
          secondary: token('general-text-secondary'),
          tertiary: token('general-text-tertiary'),
          brand: token('general-text-brand'),
          disabled: token('general-text-disabled'),
          white: token('general-text-white'),
        },
        icon: {
          primary: token('general-icon-primary'),
          secondary: token('general-icon-secondary'),
          tertiary: token('general-icon-tertiary'),
          brand: token('general-icon-brand'),
          success: token('general-icon-success'),
          warning: token('general-icon-warning'),
          danger: token('general-icon-danger'),
        },
        line: {
          primary: token('general-border-primary'),
          secondary: token('general-border-secondary'),
          brand: token('general-border-brand'),
          white: token('general-border-white'),
        },
      },
      borderRadius: {
        'tedi-sm': base('radius-01'),
        'tedi': base('radius-02-default'),
        'tedi-md': base('radius-03'),
        'tedi-lg': base('radius-04'),
        'tedi-pill': base('radius-08'),
      },
      spacing,
      zIndex: {
        sticky: '1020',
        sidenav: '1030',
        'bottom-header': '1040',
        header: '1050',
        feedback: '1060',
        fixed: '1070',
        modal: '1080',
        tooltip: '1090',
        dropdown: '1100',
      },
    },
  },
  plugins: [
    forms,
  ]
}

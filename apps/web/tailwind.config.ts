import type { Config } from 'tailwindcss';

/** `rgb(var(--token) / <alpha>)`: every colour is a CSS variable (src/app/globals.css). */
function token(name: string): string {
  return `rgb(var(--${name}) / <alpha-value>)`;
}

const config: Config = {
  content: ['./src/**/*.{ts,tsx}'],
  // Light and dark follow the system; the variables switch, components never branch.
  darkMode: 'media',
  theme: {
    extend: {
      colors: {
        background: token('background'),
        foreground: token('foreground'),
        card: token('card'),
        surface: {
          low: token('surface-low'),
          DEFAULT: token('surface'),
          high: token('surface-high'),
          highest: token('surface-highest'),
        },
        'muted-foreground': token('muted-foreground'),
        outline: token('outline'),
        border: token('border'),
        ring: token('primary'),
        primary: {
          DEFAULT: token('primary'),
          foreground: token('primary-foreground'),
          container: token('primary-container'),
          'on-container': token('on-primary-container'),
        },
        secondary: {
          container: token('secondary-container'),
          'on-container': token('on-secondary-container'),
        },
        tertiary: {
          DEFAULT: token('tertiary'),
          container: token('tertiary-container'),
          'on-container': token('on-tertiary-container'),
        },
        destructive: {
          DEFAULT: token('destructive'),
          foreground: token('destructive-foreground'),
          container: token('destructive-container'),
          'on-container': token('on-destructive-container'),
        },
        positive: token('positive'),
        caution: token('caution'),
      },
      borderRadius: {
        DEFAULT: '0.5rem',
        lg: '0.75rem',
        xl: '1rem',
      },
      fontFamily: {
        // The platform font, like the Android app: it carries the fallback chain
        // that renders Urdu (Nastaliq/Naskh) and other scripts. Nothing is downloaded.
        sans: [
          'system-ui',
          '-apple-system',
          '"Segoe UI"',
          'Roboto',
          '"Noto Sans"',
          '"Noto Nastaliq Urdu"',
          '"Noto Naskh Arabic"',
          'sans-serif',
        ],
        mono: ['ui-monospace', 'SFMono-Regular', 'Menlo', 'Consolas', 'monospace'],
      },
    },
  },
  plugins: [],
};

export default config;

import nextPlugin from '@next/eslint-plugin-next';
import reactHooks from 'eslint-plugin-react-hooks';
import { createConfig } from '@scanova/tooling/eslint';

export default [
  { ignores: ['.next/**', 'next-env.d.ts', 'public/**'] },
  ...createConfig({ tsconfigRootDir: import.meta.dirname }),
  nextPlugin.configs['core-web-vitals'],
  reactHooks.configs.flat['recommended-latest'],
  {
    // Next.js finds these files by their default export.
    files: ['src/app/**/{page,layout,loading,error,not-found,global-error,template,default}.tsx'],
    rules: { 'no-restricted-syntax': 'off' },
  },
];

import { createConfig } from '@scanova/tooling/eslint';

export default [
  ...createConfig({ tsconfigRootDir: import.meta.dirname }),
  {
    // Only @scanova/keyring talks to the key database.
    files: ['src/**/*.ts'],
    rules: {
      'no-restricted-imports': [
        'error',
        {
          patterns: [
            {
              group: ['@scanova/keystore', '@scanova/keystore/*'],
              message: 'Use @scanova/keyring: it is the only code that talks to the key database.',
            },
          ],
        },
      ],
      // NestJS resolves constructor dependencies from runtime class references.
      '@typescript-eslint/consistent-type-imports': 'off',
    },
  },
];

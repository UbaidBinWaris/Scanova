// Shared flat ESLint preset for every TypeScript package and app.
//
// typescript-eslint 8 does not support the TypeScript 7 native compiler yet, so
// this package pins TypeScript 6 for the linter's parser and type service only.
// Builds and typechecks use the workspace's TypeScript 7 (`tsc`).
import js from '@eslint/js';
import tseslint from 'typescript-eslint';

const TODO_WITHOUT_ISSUE = /\b(TODO|FIXME)\b(?!\s*\(#\d+\))/u;

/** Local rules that have no off-the-shelf equivalent. */
const scanovaPlugin = {
  rules: {
    'todo-needs-issue': {
      meta: {
        type: 'suggestion',
        schema: [],
        messages: { missing: 'TODO/FIXME must reference an issue, e.g. "TODO(#123): …".' },
      },
      create(context) {
        return {
          Program() {
            for (const comment of context.sourceCode.getAllComments()) {
              if (TODO_WITHOUT_ISSUE.test(comment.value)) {
                context.report({ loc: comment.loc, messageId: 'missing' });
              }
            }
          },
        };
      },
    },
  },
};

/**
 * @param {{ tsconfigRootDir: string, allowProcessEnv?: boolean }} options
 * @returns {import('eslint').Linter.Config[]}
 */
export function createConfig({ tsconfigRootDir, allowProcessEnv = false }) {
  return tseslint.config(
    {
      ignores: ['dist/**', 'coverage/**', 'node_modules/**', 'src/generated/**', '.turbo/**'],
    },
    js.configs.recommended,
    ...tseslint.configs.recommendedTypeChecked,
    {
      languageOptions: {
        parserOptions: { projectService: true, tsconfigRootDir },
      },
      plugins: { scanova: scanovaPlugin },
      rules: {
        'no-console': 'error',
        eqeqeq: ['error', 'always'],
        'scanova/todo-needs-issue': 'error',
        'no-restricted-syntax': [
          'error',
          {
            selector: 'ExportDefaultDeclaration',
            message: 'Use named exports.',
          },
          ...(allowProcessEnv
            ? []
            : [
                {
                  selector: "MemberExpression[object.name='process'][property.name='env']",
                  message: 'Read configuration through @scanova/config, never process.env.',
                },
              ]),
        ],
        '@typescript-eslint/no-explicit-any': 'error',
        '@typescript-eslint/explicit-function-return-type': [
          'error',
          { allowExpressions: true, allowTypedFunctionExpressions: true },
        ],
        '@typescript-eslint/explicit-module-boundary-types': 'error',
        '@typescript-eslint/consistent-type-imports': [
          'error',
          { prefer: 'type-imports', fixStyle: 'inline-type-imports' },
        ],
        '@typescript-eslint/no-floating-promises': 'error',
        '@typescript-eslint/no-misused-promises': 'error',
        '@typescript-eslint/switch-exhaustiveness-check': 'error',
        '@typescript-eslint/no-unused-vars': [
          'error',
          { argsIgnorePattern: '^_', varsIgnorePattern: '^_' },
        ],
        '@typescript-eslint/restrict-template-expressions': [
          'error',
          { allowNumber: true, allowBoolean: true },
        ],
      },
    },
    {
      // Tool configuration files must default-export and are not part of a tsconfig project.
      files: ['*.config.{js,mjs,ts}', '**/*.mjs'],
      extends: [tseslint.configs.disableTypeChecked],
      rules: { 'no-restricted-syntax': 'off' },
    },
    {
      files: ['**/*.test.ts', '**/*.itest.ts', 'test/**/*.ts'],
      rules: {
        '@typescript-eslint/no-non-null-assertion': 'off',
        '@typescript-eslint/unbound-method': 'off',
      },
    },
  );
}

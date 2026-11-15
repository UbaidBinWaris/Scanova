import { createConfig } from '@scanova/tooling/eslint';

// This package is the single place allowed to read process.env.
export default createConfig({ tsconfigRootDir: import.meta.dirname, allowProcessEnv: true });

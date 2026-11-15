import { prismaCliDatasourceUrl } from '@scanova/config/prisma-cli';
import { defineConfig } from 'prisma/config';

// The datasource URL comes from @scanova/config (the migrator role). `generate`
// and `migrate diff` run without one.
export default defineConfig({
  schema: 'prisma/schema.prisma',
  migrations: { path: 'prisma/migrations' },
  datasource: { url: prismaCliDatasourceUrl('keystore') },
});

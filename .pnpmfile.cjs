// pnpm hooks (https://pnpm.io/pnpmfile). Kept to what changes the shipped images.

/**
 * @prisma/client lists the Prisma CLI and TypeScript as *optional* peer
 * dependencies. pnpm satisfies them from the workspace's dev tools, and then
 * `pnpm deploy --prod` ships both in the api and worker images: about 250 MB
 * (the CLI, Prisma Studio, a development database, the TypeScript compiler)
 * that the services never run, plus that code's vulnerabilities. The client
 * needs neither at run time; the CLI stays a dev dependency of
 * @scanova/database and @scanova/keystore, which is how `prisma generate` and
 * the migrate image reach it.
 */
function readPackage(pkg) {
  if (pkg.name === '@prisma/client') {
    for (const peer of ['prisma', 'typescript']) {
      if (pkg.peerDependenciesMeta?.[peer]?.optional) {
        delete pkg.peerDependencies[peer];
        delete pkg.peerDependenciesMeta[peer];
      }
    }
  }
  return pkg;
}

module.exports = { hooks: { readPackage } };

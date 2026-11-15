export interface IntegrationRole {
  readonly user: string;
  readonly password: string;
}

export interface IntegrationDatabase {
  readonly database: string;
  readonly app: IntegrationRole;
  readonly migrator: IntegrationRole;
}

export interface IntegrationAppRole {
  readonly roleId: string;
  readonly secretId: string;
}

/** Connection details of the throwaway integration stack. */
export interface IntegrationState {
  readonly pki: { readonly caCertPath: string };
  readonly postgres: {
    readonly host: string;
    readonly port: number;
    readonly superuser: IntegrationRole;
    readonly main: IntegrationDatabase;
    readonly keys: IntegrationDatabase;
  };
  readonly openbao: {
    readonly address: string;
    readonly rootToken: string;
    readonly transitMount: string;
    readonly keys: {
      readonly userTypeAdmin: string;
      readonly userTypeMember: string;
      readonly system: string;
      readonly jwt: string;
    };
    readonly api: IntegrationAppRole;
    readonly worker: IntegrationAppRole;
  };
  readonly s3: {
    readonly endpoint: string;
    readonly region: string;
    readonly bucket: string;
    readonly accessKeyId: string;
    readonly secretAccessKey: string;
  };
}

export declare const workspaceRoot: string;
export declare const stateDir: string;
export declare const stateFile: string;
export declare function readIntegrationState(): IntegrationState;

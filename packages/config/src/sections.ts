/**
 * Typed configuration sections. Packages accept these objects, never raw
 * environment variables, so tests can inject them directly.
 */

export type NodeEnv = 'production' | 'development' | 'test';
export type LogLevel = 'fatal' | 'error' | 'warn' | 'info' | 'debug' | 'trace';

export interface RuntimeConfig {
  readonly nodeEnv: NodeEnv;
  readonly logLevel: LogLevel;
}

/** One PostgreSQL role on one database. The connection string is assembled from the parts. */
export interface PostgresConfig {
  readonly host: string;
  readonly port: number;
  readonly database: string;
  readonly user: string;
  readonly password: string;
  /** CA that signed the server certificate. `null` means plaintext and is for tests only. */
  readonly tls: { readonly caCertPath: string } | null;
  /** `postgresql://…` in the dialect of the Prisma CLI's schema engine (migrations only). */
  readonly prismaCliUrl: string;
}

export interface StorageConfig {
  readonly endpoint: string;
  readonly region: string;
  readonly bucket: string;
  readonly forcePathStyle: boolean;
  readonly accessKeyId: string;
  readonly secretAccessKey: string;
}

export interface KmsConfig {
  /** OpenBao address, e.g. `https://openbao:8200`. */
  readonly address: string;
  readonly transitMount: string;
  /** CA for an https address. `null` uses the system trust store (or http in tests). */
  readonly caCertPath: string | null;
  readonly roleId: string;
  readonly secretId: string;
}

/** Names of the Transit master keys (§5.2). */
export interface KeyNamesConfig {
  readonly userTypeAdmin: string;
  readonly userTypeMember: string;
  readonly system: string;
  readonly jwt: string;
}

export interface RotationConfig {
  readonly userTypeKeyDays: number;
  readonly userKeyDays: number;
  readonly jwtKeyDays: number;
  readonly rewrapBatchSize: number;
}

export interface Argon2Config {
  readonly memoryKib: number;
  readonly timeCost: number;
  readonly parallelism: number;
}

export interface AuthConfig {
  readonly jwtIssuer: string;
  readonly accessTokenTtlSeconds: number;
  readonly refreshTokenTtlDays: number;
  readonly argon2: Argon2Config;
  readonly loginMaxAttemptsPerMinute: number;
  /** Proves a caller of `/internal/*` is the web app. */
  readonly webServiceToken: string;
}

export interface HttpConfig {
  readonly port: number;
  readonly maxUploadBytes: number;
}

export interface LimitsConfig {
  readonly userDefaultQuotaBytes: number;
  readonly trashRetentionDays: number;
}

export interface FeaturesConfig {
  readonly serverOcr: boolean;
  readonly ai: boolean;
  /** Open sign-up from the app (see SignupConfig). */
  readonly signup: boolean;
}

export interface OcrConfig {
  readonly concurrency: number;
  /** Tesseract language list: `eng` (the only pack in the worker image). */
  readonly languages: string;
  /** Plaintext scratch directory. Must be a tmpfs mount. */
  readonly tmpDir: string;
}

/** Self-service accounts from the app: who may create one, and how much they may store. */
export interface SignupConfig {
  readonly enabled: boolean;
  /** Storage quota of a self-registered account. */
  readonly quotaBytes: number;
  /** Sign-ups allowed per client IP per hour. */
  readonly maxPerIpPerHour: number;
  /** Sign-ups allowed per hour in total, whatever the source. */
  readonly maxPerHour: number;
}

export interface ApiConfig {
  readonly runtime: RuntimeConfig;
  readonly http: HttpConfig;
  readonly auth: AuthConfig;
  readonly limits: LimitsConfig;
  readonly features: FeaturesConfig;
  readonly signup: SignupConfig;
  readonly database: PostgresConfig;
  readonly keystore: PostgresConfig;
  readonly storage: StorageConfig;
  readonly kms: KmsConfig;
  readonly keys: KeyNamesConfig;
  readonly rotation: RotationConfig;
  /** The runtime an owner switches on and off (§11.8). Off unless the operator configures it. */
  readonly ai: AiConfig;
  readonly aiImage: AiImageConfig;
  readonly aiControl: AiControlConfig;
}

/** How the API loads and unloads the AI models for an owner (§11.8). */
export interface AiControlConfig {
  /** Free memory the server must still have once the models are loaded, for its other services. */
  readonly keepFreeBytes: number;
}

export interface WorkerConfig {
  readonly runtime: RuntimeConfig;
  readonly limits: Pick<LimitsConfig, 'trashRetentionDays'>;
  /** The worker retires a signing-key version once the longest-lived token has expired. */
  readonly auth: Pick<AuthConfig, 'accessTokenTtlSeconds'>;
  readonly ocr: OcrConfig;
  readonly database: PostgresConfig;
  readonly keystore: PostgresConfig;
  readonly storage: StorageConfig;
  readonly kms: KmsConfig;
  readonly keys: KeyNamesConfig;
  readonly rotation: RotationConfig;
  /** Off (`provider: 'none'`) unless the operator switches it on (§11). */
  readonly ai: AiConfig;
}

/** A migrator connection plus the runtime role that receives table-level grants. */
export interface MigrationTargetConfig {
  readonly migrator: PostgresConfig;
  readonly runtimeRole: string;
}

export interface MigrateConfig {
  readonly runtime: RuntimeConfig;
  readonly database: MigrationTargetConfig;
  readonly keystore: MigrationTargetConfig;
}

/** The Next.js web app (member portal and admin console). */
export interface WebConfig {
  readonly runtime: RuntimeConfig;
  /** The API on the private network, e.g. `http://api:3000`. Never the public edge. */
  readonly apiUrl: string;
  /** `X-Scanova-Service-Token` for `/internal/*`. */
  readonly serviceToken: string;
  /** Key material for the encrypted session cookie. */
  readonly cookieSecret: string;
  /** Origin of the member portal, e.g. `https://scanova.example.org`. */
  readonly siteOrigin: string;
  /** Origin of the admin console; null when it is not served (no admin host). */
  readonly adminOrigin: string | null;
}

// ── AI runtime (§11) ───────────────────────────────────────────────────────

/**
 * `openai-compatible` is any server that speaks the OpenAI chat-completions and
 * embeddings API: the bundled llama.cpp services (Compose profile `ai`) or
 * another runtime the operator chooses. Nothing else in the code knows which.
 */
export type AiProvider = 'none' | 'openai-compatible';

export interface AiEndpointConfig {
  /** Ends before `/chat/completions` or `/embeddings`, e.g. `http://ai-chat:8080/v1`. */
  readonly baseUrl: string;
  /** Model name sent with every request (the llama.cpp `--alias`). */
  readonly model: string;
}

export interface AiEmbeddingConfig extends AiEndpointConfig {
  /** Stored vector length. Longer model output is truncated (Matryoshka) and re-normalised. */
  readonly dimensions: number;
}

export type AiConfig =
  | { readonly provider: 'none' }
  | {
      readonly provider: 'openai-compatible';
      readonly chat: AiEndpointConfig;
      readonly embeddings: AiEmbeddingConfig;
      /** Bearer token the runtime requires (llama.cpp `--api-key-file`). */
      readonly apiKey: string;
    };

/**
 * The image AI runtime (`apps/ai-image`, §11.8): server-side page clean-up,
 * loaded only while an owner has "Page clean-up" switched on.
 */
export type AiImageConfig =
  | { readonly provider: 'none' }
  | {
      readonly provider: 'gcdrnet';
      /** Origin of the runtime (`http://ai-image:8080`). */
      readonly baseUrl: string;
      /** The model's name in the runtime's list. */
      readonly model: string;
      /** The bearer token the AI runtimes share (`AI_API_KEY`). */
      readonly apiKey: string;
    };

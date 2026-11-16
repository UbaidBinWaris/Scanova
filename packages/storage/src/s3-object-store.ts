import { Readable } from 'node:stream';
import {
  DeleteObjectCommand,
  DeleteObjectsCommand,
  GetObjectCommand,
  HeadBucketCommand,
  HeadObjectCommand,
  ListObjectsV2Command,
  S3Client,
} from '@aws-sdk/client-s3';
import { Upload } from '@aws-sdk/lib-storage';
import type { StorageConfig } from '@scanova/config/sections';
import { StorageError } from './errors.js';
import type { ObjectStore } from './object-store.js';

/** Multipart part size. With one part in flight, an upload holds about this much in memory. */
const PART_SIZE_BYTES = 8 * 1024 * 1024;
const DELETE_BATCH_SIZE = 500;
const S3_CONNECT_TIMEOUT_MS = 5_000;

export function createS3Client(config: StorageConfig): S3Client {
  return new S3Client({
    endpoint: config.endpoint,
    region: config.region,
    forcePathStyle: config.forcePathStyle,
    credentials: { accessKeyId: config.accessKeyId, secretAccessKey: config.secretAccessKey },
    // Checksums are sent only where the API requires them, because S3-compatible
    // stores differ in what they accept, and SCNV1 already guarantees integrity.
    requestChecksumCalculation: 'WHEN_REQUIRED',
    responseChecksumValidation: 'WHEN_REQUIRED',
    maxAttempts: 3,
    // An unreachable store fails within seconds instead of waiting out the
    // kernel's connect retries on every attempt. No request (idle) timeout:
    // a download is paced by the client and may legitimately pause.
    requestHandler: { connectionTimeout: S3_CONNECT_TIMEOUT_MS },
  });
}

function statusOf(error: unknown): number | undefined {
  return (error as { $metadata?: { httpStatusCode?: number } }).$metadata?.httpStatusCode;
}

function isNotFound(error: unknown): boolean {
  const name = (error as { name?: string }).name;
  return name === 'NoSuchKey' || name === 'NotFound' || statusOf(error) === 404;
}

function unavailable(error: unknown): StorageError {
  return new StorageError('UNAVAILABLE', 'Object storage request failed.', { cause: error });
}

/** `ObjectStore` on an S3-compatible bucket (Garage). */
export class S3ObjectStore implements ObjectStore {
  private readonly client: S3Client;
  private readonly bucket: string;

  public constructor(config: StorageConfig, client: S3Client = createS3Client(config)) {
    this.client = client;
    this.bucket = config.bucket;
  }

  public async put(key: string, body: Readable): Promise<void> {
    // Multipart upload: the length is unknown up front and the stream is never
    // buffered whole. If the stream fails, the upload is aborted and its parts
    // are discarded, so a failed upload leaves no object behind.
    const upload = new Upload({
      client: this.client,
      params: {
        Bucket: this.bucket,
        Key: key,
        Body: body,
        ContentType: 'application/octet-stream',
      },
      partSize: PART_SIZE_BYTES,
      queueSize: 1,
      leavePartsOnError: false,
    });
    try {
      await upload.done();
    } catch (error) {
      throw unavailable(error);
    }
  }

  public async get(key: string): Promise<Readable> {
    try {
      const response = await this.client.send(
        new GetObjectCommand({ Bucket: this.bucket, Key: key }),
      );
      if (!(response.Body instanceof Readable)) {
        throw new StorageError('UNAVAILABLE', 'Object storage returned no body.');
      }
      return response.Body;
    } catch (error) {
      if (error instanceof StorageError) {
        throw error;
      }
      if (isNotFound(error)) {
        throw new StorageError('NOT_FOUND', 'Object not found.');
      }
      throw unavailable(error);
    }
  }

  public async exists(key: string): Promise<boolean> {
    try {
      await this.client.send(new HeadObjectCommand({ Bucket: this.bucket, Key: key }));
      return true;
    } catch (error) {
      if (isNotFound(error)) {
        return false;
      }
      throw unavailable(error);
    }
  }

  public async delete(key: string): Promise<void> {
    try {
      await this.client.send(new DeleteObjectCommand({ Bucket: this.bucket, Key: key }));
    } catch (error) {
      if (!isNotFound(error)) {
        throw unavailable(error);
      }
    }
  }

  public async deleteMany(keys: readonly string[]): Promise<void> {
    for (let offset = 0; offset < keys.length; offset += DELETE_BATCH_SIZE) {
      const batch = keys.slice(offset, offset + DELETE_BATCH_SIZE);
      try {
        const response = await this.client.send(
          new DeleteObjectsCommand({
            Bucket: this.bucket,
            Delete: { Objects: batch.map((key) => ({ Key: key })), Quiet: true },
          }),
        );
        if (response.Errors !== undefined && response.Errors.length > 0) {
          throw new StorageError('UNAVAILABLE', 'Object storage could not delete every object.');
        }
      } catch (error) {
        throw error instanceof StorageError ? error : unavailable(error);
      }
    }
  }

  public async *list(prefix: string): AsyncIterable<string> {
    let continuationToken: string | undefined;
    do {
      let response;
      try {
        response = await this.client.send(
          new ListObjectsV2Command({
            Bucket: this.bucket,
            Prefix: prefix,
            ContinuationToken: continuationToken,
          }),
        );
      } catch (error) {
        throw unavailable(error);
      }
      for (const object of response.Contents ?? []) {
        if (object.Key !== undefined) {
          yield object.Key;
        }
      }
      continuationToken =
        response.IsTruncated === true ? response.NextContinuationToken : undefined;
    } while (continuationToken !== undefined);
  }

  public async health(): Promise<void> {
    try {
      await this.client.send(new HeadBucketCommand({ Bucket: this.bucket }));
    } catch (error) {
      throw unavailable(error);
    }
  }

  public close(): void {
    this.client.destroy();
  }
}

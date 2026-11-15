import {
  Body,
  Controller,
  Delete,
  Get,
  Param,
  Patch,
  Post,
  Put,
  Query,
  Req,
  Res,
  StreamableFile,
} from '@nestjs/common';
import { IdParamSchema, type IdParam, type Success } from '@scanova/contracts/common';
import {
  CreateDocumentRequestSchema,
  FileVariantQuerySchema,
  ListDocumentsQuerySchema,
  UpdateDocumentRequestSchema,
  UploadHeadersSchema,
  type CreateDocumentRequest,
  type Document,
  type DocumentText,
  type FileVariantQuery,
  type ListDocumentsQuery,
  type UpdateDocumentRequest,
  type UploadHeaders,
} from '@scanova/contracts/documents';
import type { FastifyReply, FastifyRequest } from 'fastify';
import type { AuthContext } from '../common/auth-context.js';
import { CurrentAuth, ValidatedHeaders } from '../common/decorators.js';
import type { Page } from '../common/page.js';
import { body, params, query } from '../common/zod.pipe.js';
import { DocumentsService } from './documents.service.js';
import { UploadService } from './upload.service.js';

@Controller('api/v1/documents')
export class DocumentsController {
  public constructor(
    private readonly documents: DocumentsService,
    private readonly uploads: UploadService,
  ) {}

  @Get()
  public list(
    @CurrentAuth() auth: AuthContext,
    @Query(query(ListDocumentsQuerySchema)) request: ListDocumentsQuery,
  ): Promise<Page<Document>> {
    return this.documents.list(auth, request);
  }

  /** 201 for a new document, 200 when the same id was already created by this user (a retry). */
  @Post()
  public async create(
    @CurrentAuth() auth: AuthContext,
    @Body(body(CreateDocumentRequestSchema)) request: CreateDocumentRequest,
    @Res({ passthrough: true }) reply: FastifyReply,
  ): Promise<Document> {
    const result = await this.documents.create(auth, request);
    void reply.status(result.created ? 201 : 200);
    return result.document;
  }

  @Get(':id')
  public get(
    @CurrentAuth() auth: AuthContext,
    @Param(params(IdParamSchema)) path: IdParam,
  ): Promise<Document> {
    return this.documents.get(auth, path.id);
  }

  @Patch(':id')
  public update(
    @CurrentAuth() auth: AuthContext,
    @Param(params(IdParamSchema)) path: IdParam,
    @Body(body(UpdateDocumentRequestSchema)) request: UpdateDocumentRequest,
  ): Promise<Document> {
    return this.documents.update(auth, path.id, request);
  }

  @Delete(':id')
  public async delete(
    @CurrentAuth() auth: AuthContext,
    @Param(params(IdParamSchema)) path: IdParam,
  ): Promise<Success> {
    await this.documents.softDelete(auth, path.id);
    return { success: true };
  }

  /** Raw `application/pdf` body, streamed. The HTTP layer hands the body over unread. */
  @Put(':id/file')
  public upload(
    @CurrentAuth() auth: AuthContext,
    @Param(params(IdParamSchema)) path: IdParam,
    @ValidatedHeaders(UploadHeadersSchema) uploadHeaders: UploadHeaders,
    @Req() request: FastifyRequest,
  ): Promise<Document> {
    return this.uploads.upload(
      auth,
      path.id,
      uploadHeaders,
      request.headers['content-type'],
      request.raw,
    );
  }

  /** PDF bytes, not enveloped. */
  @Get(':id/file')
  public async download(
    @CurrentAuth() auth: AuthContext,
    @Param(params(IdParamSchema)) path: IdParam,
    @Query(query(FileVariantQuerySchema)) request: FileVariantQuery,
    @Res({ passthrough: true }) reply: FastifyReply,
  ): Promise<StreamableFile> {
    const file = await this.documents.openFile(
      auth,
      path.id,
      request.variant === 'searchable' ? 'SEARCHABLE' : 'ORIGINAL',
    );
    void reply.header('cache-control', 'private, no-store');
    void reply.header('etag', `"${file.sha256Hex}"`);
    void reply.header('x-content-sha256', file.sha256Hex);
    return new StreamableFile(file.stream, { type: 'application/pdf', length: file.sizeBytes });
  }

  @Get(':id/text')
  public text(
    @CurrentAuth() auth: AuthContext,
    @Param(params(IdParamSchema)) path: IdParam,
  ): Promise<DocumentText> {
    return this.documents.getText(auth, path.id);
  }
}

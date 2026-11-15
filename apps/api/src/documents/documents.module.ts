import { Module } from '@nestjs/common';
import { DocumentsController } from './documents.controller.js';
import { DocumentsService } from './documents.service.js';
import { PagesService } from './pages.service.js';
import { UploadService } from './upload.service.js';

@Module({
  controllers: [DocumentsController],
  providers: [DocumentsService, PagesService, UploadService],
  exports: [DocumentsService],
})
export class DocumentsModule {}

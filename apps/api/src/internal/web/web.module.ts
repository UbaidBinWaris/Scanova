import { Module } from '@nestjs/common';
import { WebAuthController } from './web-auth.controller.js';

@Module({ controllers: [WebAuthController] })
export class WebModule {}

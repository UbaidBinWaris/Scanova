import { Module } from '@nestjs/common';
import { ServerInfoController } from './server-info.controller.js';

@Module({ controllers: [ServerInfoController] })
export class ServerInfoModule {}

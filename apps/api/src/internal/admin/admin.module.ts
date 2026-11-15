import { Module } from '@nestjs/common';
import { AdminKeysService } from './admin-keys.service.js';
import { AdminUsersService } from './admin-users.service.js';
import { AdminController } from './admin.controller.js';

@Module({
  controllers: [AdminController],
  providers: [AdminUsersService, AdminKeysService],
  exports: [AdminUsersService, AdminKeysService],
})
export class AdminModule {}

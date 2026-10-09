-- V38 user-delete-fk：删除用户的外键清理链
-- 删除用户时 app_user 的引用分三类处置（instance-identity 同批用户复核反馈）：
--   1) 随用户删除（服务层）：notification_message（发给该用户的消息）、名下 machine_allocation、
--      各类共享行（container_share/storage_pool_share/image_share，含其分享出的 container_share.shared_by）
--   2) 占用拦截（服务层）：容器（含历史）、存储池、工单（submitter）
--   3) 本迁移："操作人"类历史归属列去 NOT NULL + FK 改 ON DELETE SET NULL——
--      记录保留、操作人关联抹除（announcement 另有 author_name 冗余文本，展示不丢）
-- audit_log.operator_id 本就可空且无 FK（V27 触发器锁删改），不受影响。
-- SQL 全程不含 dollar-brace 占位符（含注释/字符串），避免 Flyway placeholder 未配置致启动失败。

-- 公告作者（置空后公告保留，author_name 文本仍在）
ALTER TABLE announcement ALTER COLUMN author_id DROP NOT NULL;
ALTER TABLE announcement DROP CONSTRAINT IF EXISTS announcement_author_id_fkey;
ALTER TABLE announcement ADD CONSTRAINT announcement_author_id_fkey
    FOREIGN KEY (author_id) REFERENCES app_user(id) ON DELETE SET NULL;

-- 注册链接创建者（保留链接）
ALTER TABLE registration_link ALTER COLUMN creator_id DROP NOT NULL;
ALTER TABLE registration_link DROP CONSTRAINT IF EXISTS registration_link_creator_id_fkey;
ALTER TABLE registration_link ADD CONSTRAINT registration_link_creator_id_fkey
    FOREIGN KEY (creator_id) REFERENCES app_user(id) ON DELETE SET NULL;

-- 机器分配的分配者（历史分配记录保留）
ALTER TABLE machine_allocation ALTER COLUMN allocated_by DROP NOT NULL;
ALTER TABLE machine_allocation DROP CONSTRAINT IF EXISTS machine_allocation_allocated_by_fkey;
ALTER TABLE machine_allocation ADD CONSTRAINT machine_allocation_allocated_by_fkey
    FOREIGN KEY (allocated_by) REFERENCES app_user(id) ON DELETE SET NULL;

-- 存储池迁移发起人
ALTER TABLE storage_pool_migration ALTER COLUMN initiated_by DROP NOT NULL;
ALTER TABLE storage_pool_migration DROP CONSTRAINT IF EXISTS storage_pool_migration_initiated_by_fkey;
ALTER TABLE storage_pool_migration ADD CONSTRAINT storage_pool_migration_initiated_by_fkey
    FOREIGN KEY (initiated_by) REFERENCES app_user(id) ON DELETE SET NULL;

-- 镜像同步批次发起人
ALTER TABLE image_sync_batch ALTER COLUMN initiated_by DROP NOT NULL;
ALTER TABLE image_sync_batch DROP CONSTRAINT IF EXISTS image_sync_batch_initiated_by_fkey;
ALTER TABLE image_sync_batch ADD CONSTRAINT image_sync_batch_initiated_by_fkey
    FOREIGN KEY (initiated_by) REFERENCES app_user(id) ON DELETE SET NULL;

-- NAS 邀请创建者 / 审核人
ALTER TABLE nas_invitation ALTER COLUMN created_by DROP NOT NULL;
ALTER TABLE nas_invitation DROP CONSTRAINT IF EXISTS nas_invitation_created_by_fkey;
ALTER TABLE nas_invitation ADD CONSTRAINT nas_invitation_created_by_fkey
    FOREIGN KEY (created_by) REFERENCES app_user(id) ON DELETE SET NULL;
ALTER TABLE nas_registration DROP CONSTRAINT IF EXISTS nas_registration_reviewed_by_fkey;
ALTER TABLE nas_registration ADD CONSTRAINT nas_registration_reviewed_by_fkey
    FOREIGN KEY (reviewed_by) REFERENCES app_user(id) ON DELETE SET NULL;

-- NewAPI 邀请创建者 / 审核人
ALTER TABLE newapi_invitation ALTER COLUMN created_by DROP NOT NULL;
ALTER TABLE newapi_invitation DROP CONSTRAINT IF EXISTS newapi_invitation_created_by_fkey;
ALTER TABLE newapi_invitation ADD CONSTRAINT newapi_invitation_created_by_fkey
    FOREIGN KEY (created_by) REFERENCES app_user(id) ON DELETE SET NULL;
ALTER TABLE newapi_registration DROP CONSTRAINT IF EXISTS newapi_registration_reviewed_by_fkey;
ALTER TABLE newapi_registration ADD CONSTRAINT newapi_registration_reviewed_by_fkey
    FOREIGN KEY (reviewed_by) REFERENCES app_user(id) ON DELETE SET NULL;

-- 工单回复人（提交人 submitter 由服务层占用拦截，不置空）
ALTER TABLE ticket DROP CONSTRAINT IF EXISTS ticket_replier_id_fkey;
ALTER TABLE ticket ADD CONSTRAINT ticket_replier_id_fkey
    FOREIGN KEY (replier_id) REFERENCES app_user(id) ON DELETE SET NULL;

COMMENT ON COLUMN announcement.author_id IS '公告作者（app_user.id，可空：作者被删除后置空，展示回退 author_name）';
COMMENT ON COLUMN machine_allocation.allocated_by IS '分配者（导师/管理员，可空：用户删除后置空，分配记录保留）';

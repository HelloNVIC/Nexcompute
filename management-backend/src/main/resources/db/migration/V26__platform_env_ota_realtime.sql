-- V26 platform-env-ota-realtime：受控端环境/OTA/实时响应/工单/权限/硬件指纹
-- 1.1 工单联系方式 / 1.2 公告多选课题组 / 1.3 环境文件元数据 / 1.4 升级任务记录 / 12.4 SMBIOS UUID 指纹

-- 1.1 工单增 contact 列（默认账户手机号，nullable）
ALTER TABLE ticket ADD COLUMN contact VARCHAR(100);
COMMENT ON COLUMN ticket.contact IS '联系方式（默认账户手机号，可改）';

-- 1.2 公告-课题组 多对多关联表（targetScope=GROUP 时替代单值 target_id）
CREATE TABLE announcement_group (
    id              BIGSERIAL PRIMARY KEY,
    announcement_id BIGINT NOT NULL REFERENCES announcement(id) ON DELETE CASCADE,
    group_id        BIGINT NOT NULL REFERENCES research_group(id),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(announcement_id, group_id)
);
CREATE INDEX idx_announcement_group_group ON announcement_group(group_id);
-- 回填：既有 announcement.target_scope='GROUP' AND target_id IS NOT NULL 的单值迁入关联表
INSERT INTO announcement_group (announcement_id, group_id)
SELECT a.id, a.target_id
FROM announcement a
WHERE a.target_scope = 'GROUP' AND a.target_id IS NOT NULL
  AND NOT EXISTS (
      SELECT 1 FROM announcement_group ag
      WHERE ag.announcement_id = a.id AND ag.group_id = a.target_id
  );
COMMENT ON TABLE announcement_group IS '公告-课题组多对多（D10：GROUP 多选）';

-- 1.3 环境文件元数据（管理员托管，受控端按 MD5 同步）
CREATE TABLE env_file (
    id               BIGSERIAL PRIMARY KEY,
    filename         VARCHAR(255) NOT NULL UNIQUE,
    md5              VARCHAR(64)  NOT NULL,
    size             BIGINT       NOT NULL,
    uploaded_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    uploaded_by      BIGINT,
    uploaded_by_name VARCHAR(100)
);
COMMENT ON TABLE env_file IS '受控端环境准备文件元数据（D4：MD5 增量同步）';

-- 1.4 受控端升级任务记录（OTA 单实例下发结果持久化）
CREATE TABLE agent_upgrade_task (
    id              BIGSERIAL PRIMARY KEY,
    instance_id     BIGINT       NOT NULL REFERENCES physical_instance(id),
    instance_number VARCHAR(50),
    version         VARCHAR(50)  NOT NULL,
    md5             VARCHAR(64),
    status          VARCHAR(20)  NOT NULL,   -- PENDING / SUCCESS / FAILED
    error           TEXT,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    finished_at     TIMESTAMPTZ
);
CREATE INDEX idx_agent_upgrade_task_instance ON agent_upgrade_task(instance_id);
CREATE INDEX idx_agent_upgrade_task_status ON agent_upgrade_task(status);
COMMENT ON TABLE agent_upgrade_task IS '受控端 OTA 升级任务记录（D7）';

-- 12.4 物理实例 SMBIOS UUID（D14：主板 BIOS 主指纹，注册去重优先匹配）
ALTER TABLE physical_instance ADD COLUMN smbios_uuid VARCHAR(100);
CREATE INDEX idx_physical_instance_smbios_uuid ON physical_instance(smbios_uuid);
COMMENT ON COLUMN physical_instance.smbios_uuid IS 'SMBIOS UUID（主板 BIOS），主指纹；空/全 0 回退 MachineGuid 再 MAC';

-- 受控端环境 / OTA 两个管理员模块（供 @RequirePermission 鉴权；仅 ADMIN 授权）
INSERT INTO permission_module (code, name, description) VALUES
    ('agent-env', '受控端环境', '环境文件托管与 MD5 同步'),
    ('agent-ota', '受控端升级', '受控端 OTA 远程升级')
ON CONFLICT (code) DO NOTHING;
INSERT INTO permission_matrix (role, module_id, can_view, can_edit, can_delete)
SELECT 'ADMIN', id, TRUE, TRUE, TRUE FROM permission_module WHERE code IN ('agent-env', 'agent-ota')
ON CONFLICT (role, module_id) DO NOTHING;

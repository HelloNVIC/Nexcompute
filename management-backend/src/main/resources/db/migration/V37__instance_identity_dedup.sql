-- V37 instance-identity：物理实例身份去重（指纹唯一索引 + 存量重复合并）
-- 身份模型：SMBIOS UUID（主指纹）与机器码 MachineGuid（辅助）决定实例身份；编号仅为显示标签。
-- 1) 存量合并：同指纹多行保留"最新心跳"一条（keeper），其余为 zombie：
--    引用重定向 zombie.id -> keeper.id（撞唯一约束的 zombie 行直接删除），凭证/监控历史随 zombie 删除，
--    最后删除 zombie 行。两轮合并（先 SMBIOS 后机器码）以"不处理已入映射表的行"防链式误删
--    （zombie 的 keeper 绝不会同时是 zombie），极端残余重复由管理员用实例删除功能手工清理。
-- 2) 唯一索引：machine_code / smbios_uuid 部分唯一（排除 NULL 与空串），此后注册解析按指纹命中复用。
-- SQL 全程不含 dollar-brace 占位符（含注释/字符串），避免 Flyway placeholder 未配置致启动失败。

-- ============ 合并映射（zombie_id -> keeper_id） ============
CREATE TEMP TABLE instance_merge_map (
    zombie_id BIGINT PRIMARY KEY,
    keeper_id BIGINT NOT NULL
);

-- 第 1 轮：按 SMBIOS UUID 分组（组内保留 last_heartbeat 最新一条，NULL 视为最旧，同刻按 id 大者优先）
INSERT INTO instance_merge_map (zombie_id, keeper_id)
SELECT z.id, z.keeper_id
FROM (
    SELECT id,
           FIRST_VALUE(id) OVER (PARTITION BY smbios_uuid
                                 ORDER BY last_heartbeat DESC NULLS LAST, id DESC) AS keeper_id
    FROM physical_instance
    WHERE smbios_uuid IS NOT NULL AND smbios_uuid <> ''
) z
WHERE z.id <> z.keeper_id;

-- 第 2 轮：按机器码分组（仅处理未入映射表的行：已映射的 zombie/keeper 均跳过，防链式误删）
INSERT INTO instance_merge_map (zombie_id, keeper_id)
SELECT z.id, z.keeper_id
FROM (
    SELECT p.id,
           FIRST_VALUE(p.id) OVER (PARTITION BY p.machine_code
                                   ORDER BY p.last_heartbeat DESC NULLS LAST, p.id DESC) AS keeper_id
    FROM physical_instance p
    WHERE p.machine_code IS NOT NULL AND p.machine_code <> ''
      AND NOT EXISTS (SELECT 1 FROM instance_merge_map m
                      WHERE m.zombie_id = p.id OR m.keeper_id = p.id)
) z
WHERE z.id <> z.keeper_id;

-- ============ 引用重定向（zombie.id -> keeper.id） ============

-- 用户分配：UNIQUE(instance_id, user_id)，撞唯一的 zombie 行先删（keeper 已有同用户分配）
DELETE FROM machine_allocation a
USING instance_merge_map m
WHERE a.instance_id = m.zombie_id
  AND EXISTS (SELECT 1 FROM machine_allocation k
              WHERE k.instance_id = m.keeper_id AND k.user_id = a.user_id);
UPDATE machine_allocation a
SET instance_id = m.keeper_id
FROM instance_merge_map m
WHERE a.instance_id = m.zombie_id;

-- 容器 / 存储池 / 端口分配 / 镜像同步任务 / 升级任务记录（instance_id 不参与唯一约束，直接重定向）
UPDATE container c SET instance_id = m.keeper_id
FROM instance_merge_map m WHERE c.instance_id = m.zombie_id;

UPDATE storage_pool sp SET instance_id = m.keeper_id
FROM instance_merge_map m WHERE sp.instance_id = m.zombie_id;

UPDATE port_allocation pa SET instance_id = m.keeper_id
FROM instance_merge_map m WHERE pa.instance_id = m.zombie_id;

UPDATE image_sync_task t SET instance_id = m.keeper_id
FROM instance_merge_map m WHERE t.instance_id = m.zombie_id;

UPDATE agent_upgrade_task t SET instance_id = m.keeper_id
FROM instance_merge_map m WHERE t.instance_id = m.zombie_id;

-- 存储池迁移（源/目标两列分别重定向）
UPDATE storage_pool_migration sm SET source_instance_id = m.keeper_id
FROM instance_merge_map m WHERE sm.source_instance_id = m.zombie_id;

UPDATE storage_pool_migration sm SET target_instance_id = m.keeper_id
FROM instance_merge_map m WHERE sm.target_instance_id = m.zombie_id;

-- 公共镜像同步状态（休眠表，无 JPA 实体）：UNIQUE(image_id, instance_id) 撞唯一先删
DELETE FROM public_image_sync p
USING instance_merge_map m
WHERE p.instance_id = m.zombie_id
  AND EXISTS (SELECT 1 FROM public_image_sync k
              WHERE k.instance_id = m.keeper_id AND k.image_id = p.image_id);
UPDATE public_image_sync p SET instance_id = m.keeper_id
FROM instance_merge_map m WHERE p.instance_id = m.zombie_id;

-- 凭证（UNIQUE(instance_id) 不可重定向）与监控历史：随 zombie 删除；
-- keeper 缺凭证时由注册复用路径补建，不丢数据
DELETE FROM agent_credential c
USING instance_merge_map m
WHERE c.instance_id = m.zombie_id;

DELETE FROM monitoring_history h
USING instance_merge_map m
WHERE h.instance_id = m.zombie_id;

-- ============ 删除 zombie 实例行 ============
DELETE FROM physical_instance p
USING instance_merge_map m
WHERE p.id = m.zombie_id;

DROP TABLE instance_merge_map;

-- ============ 指纹唯一索引（部分唯一，排除空值） ============
CREATE UNIQUE INDEX uq_instance_machine_code
    ON physical_instance(machine_code)
    WHERE machine_code IS NOT NULL AND machine_code <> '';

CREATE UNIQUE INDEX uq_instance_smbios_uuid
    ON physical_instance(smbios_uuid)
    WHERE smbios_uuid IS NOT NULL AND smbios_uuid <> '';

COMMENT ON INDEX uq_instance_machine_code IS '机器码（MachineGuid）部分唯一索引：物理实例身份判定依据（排除 NULL/空串），同一机器不得重复注册';
COMMENT ON INDEX uq_instance_smbios_uuid IS 'SMBIOS UUID 部分唯一索引：主指纹去重（排除 NULL/空串，全 0 已在注册入口规范化为 NULL）';

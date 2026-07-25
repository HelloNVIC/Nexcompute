-- V13 数据库表与字段注释（任务 14：完善文档）
-- 为所有表和关键字段添加中文注释，便于维护

-- ============ 审计日志 ============
COMMENT ON TABLE audit_log IS '审计日志表：记录所有受审计的用户操作（容器/存储池/镜像/PowerShell 等），含操作人、时间、目标、内容、结果';
COMMENT ON COLUMN audit_log.operator_id IS '操作人用户 ID';
COMMENT ON COLUMN audit_log.operator_name IS '操作人姓名';
COMMENT ON COLUMN audit_log.operator_role IS '操作人角色（ADMIN/MENTOR/STUDENT）';
COMMENT ON COLUMN audit_log.action IS '操作类型（如 CONTAINER_CREATE、POWERSHELL_EXEC）';
COMMENT ON COLUMN audit_log.target_type IS '操作目标类型（如 CONTAINER/STORAGE_POOL/PHYSICAL_INSTANCE）';
COMMENT ON COLUMN audit_log.target_id IS '操作目标 ID';
COMMENT ON COLUMN audit_log.content IS '操作内容（JSON 格式的参数快照）';
COMMENT ON COLUMN audit_log.result IS '执行结果（SUCCESS/FAILURE）';
COMMENT ON COLUMN audit_log.error_message IS '失败时的错误信息';
COMMENT ON COLUMN audit_log.ip_address IS '请求来源 IP';

-- ============ 课题组 ============
COMMENT ON TABLE research_group IS '课题组表：一个课题组由一名导师负责，包含若干学生';
COMMENT ON COLUMN research_group.mentor_id IS '导师用户 ID';
COMMENT ON COLUMN research_group.name IS '课题组名称';
COMMENT ON COLUMN research_group.description IS '课题组描述';

-- ============ 用户 ============
COMMENT ON TABLE app_user IS '用户表：三角色体系（ADMIN 管理员 / MENTOR 导师 / STUDENT 学生）';
COMMENT ON COLUMN app_user.username IS '登录用户名（学生注册时为工号/学号）';
COMMENT ON COLUMN app_user.password_hash IS 'BCrypt 加密的密码哈希';
COMMENT ON COLUMN app_user.real_name IS '真实姓名';
COMMENT ON COLUMN app_user.role IS '角色（ADMIN/MENTOR/STUDENT）';
COMMENT ON COLUMN app_user.student_id IS '工号/学号';
COMMENT ON COLUMN app_user.group_id IS '所属课题组 ID（学生注册时自动关联）';
COMMENT ON COLUMN app_user.status IS '账号状态（ACTIVE 正常 / DISABLED 禁用）';

-- ============ 课题组-学生关系 ============
COMMENT ON TABLE group_member IS '课题组-学生关系表：支持一个学生属于多个课题组';
COMMENT ON COLUMN group_member.group_id IS '课题组 ID';
COMMENT ON COLUMN group_member.user_id IS '学生用户 ID';

-- ============ 权限模块 ============
COMMENT ON TABLE permission_module IS '权限模块定义表：定义可配置权限的功能模块（物理实例/容器/镜像等）';
COMMENT ON COLUMN permission_module.code IS '模块代码（如 physical-instance/container/image）';
COMMENT ON COLUMN permission_module.name IS '模块中文名';

-- ============ 权限矩阵 ============
COMMENT ON TABLE permission_matrix IS '权限矩阵表：角色 × 模块 × 操作（查看/编辑/删除）细粒度权限配置';
COMMENT ON COLUMN permission_matrix.role IS '角色（ADMIN/MENTOR/STUDENT）';
COMMENT ON COLUMN permission_matrix.module_id IS '权限模块 ID';
COMMENT ON COLUMN permission_matrix.can_view IS '是否可查看';
COMMENT ON COLUMN permission_matrix.can_edit IS '是否可编辑';
COMMENT ON COLUMN permission_matrix.can_delete IS '是否可删除';

-- ============ 物理实例 ============
COMMENT ON TABLE physical_instance IS '物理实例表：一台受控的 Windows+GPU 主机，受控端首次心跳自动注册';
COMMENT ON COLUMN physical_instance.instance_number IS '物理机编号（全系统唯一，如 01），用于存储池命名前缀';
COMMENT ON COLUMN physical_instance.machine_name IS '机器名（hostname）';
COMMENT ON COLUMN physical_instance.ip_address IS '物理机 IP（直连模式用于容器连接信息）';
COMMENT ON COLUMN physical_instance.os_info IS '操作系统信息';
COMMENT ON COLUMN physical_instance.gpu_info IS 'GPU 信息';
COMMENT ON COLUMN physical_instance.connect_mode IS '连接模式（direct 直连 / tunnel 穿透-敬请期待）';
COMMENT ON COLUMN physical_instance.status IS '在线状态（ONLINE 在线 / OFFLINE 离线）';
COMMENT ON COLUMN physical_instance.agent_version IS '受控端版本号';
COMMENT ON COLUMN physical_instance.last_heartbeat IS '最后心跳时间（超过阈值标记离线）';
COMMENT ON COLUMN physical_instance.last_status IS '最后心跳状态快照（JSON：CPU/GPU/内存等）';
COMMENT ON COLUMN physical_instance.local_admin_password_hash IS '本地管理员密码哈希（管理端下发，保护退出与存储根目录修改）';
COMMENT ON COLUMN physical_instance.storage_root IS '受控端存储池根目录路径';

-- ============ 受控端凭证 ============
COMMENT ON TABLE agent_credential IS '受控端连接凭证表：受控端注册时分配的 token，用于心跳与 WS 鉴权';
COMMENT ON COLUMN agent_credential.instance_id IS '关联物理实例 ID';
COMMENT ON COLUMN agent_credential.token IS '鉴权 token（受控端上报与命令下发时校验）';
COMMENT ON COLUMN agent_credential.revoked IS '是否已吊销';

-- ============ 注册链接 ============
COMMENT ON TABLE registration_link IS '注册链接表：导师创建，UUID + 可用次数 + 过期时间，供学生注册入组';
COMMENT ON COLUMN registration_link.token IS 'UUID 令牌（链接参数）';
COMMENT ON COLUMN registration_link.group_id IS '注册成功后加入的课题组 ID';
COMMENT ON COLUMN registration_link.creator_id IS '创建者（导师）用户 ID';
COMMENT ON COLUMN registration_link.remaining_count IS '剩余可用注册次数';
COMMENT ON COLUMN registration_link.expire_at IS '过期时间';
COMMENT ON COLUMN registration_link.status IS '状态（ACTIVE 有效 / REVOKED 已作废 / EXHAUSTED 已耗尽 / EXPIRED 已过期）';

-- ============ 机器分配 ============
COMMENT ON TABLE machine_allocation IS '机器分配关系表：导师将物理实例分配给学生（一台机器可分给多个学生共享）';
COMMENT ON COLUMN machine_allocation.instance_id IS '物理实例 ID';
COMMENT ON COLUMN machine_allocation.user_id IS '被分配的学生用户 ID';
COMMENT ON COLUMN machine_allocation.group_id IS '所属课题组 ID';
COMMENT ON COLUMN machine_allocation.allocated_by IS '分配者（导师）用户 ID';

-- ============ 存储池 ============
COMMENT ON TABLE storage_pool IS '存储池表：项目级数据隔离，命名格式为「物理机编号-工号/学号-项目名」';
COMMENT ON COLUMN storage_pool.pool_name IS '完整存储池名称（物理机编号-工号/学号-项目名）';
COMMENT ON COLUMN storage_pool.project_name IS '用户自定义项目名';
COMMENT ON COLUMN storage_pool.owner_id IS '所有者用户 ID';
COMMENT ON COLUMN storage_pool.instance_id IS '所在物理实例 ID';
COMMENT ON COLUMN storage_pool.pool_path IS '受控端上的实际目录路径';
COMMENT ON COLUMN storage_pool.status IS '状态（ACTIVE 正常 / MIGRATING 迁移中 / MIGRATED 已迁移）';

-- ============ 存储池共享 ============
COMMENT ON TABLE storage_pool_share IS '存储池共享关系表：所有者将存储池共享给其他用户挂载使用';
COMMENT ON COLUMN storage_pool_share.pool_id IS '存储池 ID';
COMMENT ON COLUMN storage_pool_share.shared_to_user_id IS '被共享用户 ID';

-- ============ 存储池迁移 ============
COMMENT ON TABLE storage_pool_migration IS '存储池迁移记录表：跨物理机迁移，走管理端中转 + 断点续传';
COMMENT ON COLUMN storage_pool_migration.pool_id IS '迁移的存储池 ID';
COMMENT ON COLUMN storage_pool_migration.source_instance_id IS '源物理实例 ID';
COMMENT ON COLUMN storage_pool_migration.target_instance_id IS '目标物理实例 ID';
COMMENT ON COLUMN storage_pool_migration.transfer_id IS '关联文件传输 ID（断点续传用）';
COMMENT ON COLUMN storage_pool_migration.status IS '迁移状态（PENDING/TRANSFERRING/COMPLETED/FAILED/CONFIRMED）';
COMMENT ON COLUMN storage_pool_migration.initiated_by IS '发起者用户 ID';

-- ============ 容器 ============
COMMENT ON TABLE container IS '容器表：记录用户创建的 Docker 容器元数据、资源限制、端口映射、SSH 密码';
COMMENT ON COLUMN container.name IS 'Docker 容器名（全系统唯一）';
COMMENT ON COLUMN container.owner_id IS '容器所有者用户 ID';
COMMENT ON COLUMN container.instance_id IS '所在物理实例 ID';
COMMENT ON COLUMN container.image_ref IS '镜像引用（name:tag）';
COMMENT ON COLUMN container.storage_pool_id IS '挂载的存储池 ID（可空）';
COMMENT ON COLUMN container.cpu_limit IS 'CPU 硬限制（核数，对应 --cpus）';
COMMENT ON COLUMN container.memory_limit IS '内存硬限制（字节，对应 --memory）';
COMMENT ON COLUMN container.gpu_memory_limit IS 'GPU 显存软限制（MB，注入环境变量提示框架自觉）';
COMMENT ON COLUMN container.shm_size IS '/dev/shm 大小（字节，对应 --shm-size）';
COMMENT ON COLUMN container.port_mappings IS '端口映射（JSON：[{containerPort,hostPort}]）';
COMMENT ON COLUMN container.ssh_password IS '容器级 SSH 密码（创建时设置，可即时重置）';
COMMENT ON COLUMN container.docker_id IS 'Docker 容器 ID（受控端返回）';
COMMENT ON COLUMN container.status IS '容器状态（CREATED/RUNNING/STOPPED/EXITED/REMOVED）';

-- ============ 端口分配 ============
COMMENT ON TABLE port_allocation IS '端口分配表：管理端为容器自动分配宿主端口，防止单机端口冲突';
COMMENT ON COLUMN port_allocation.instance_id IS '物理实例 ID';
COMMENT ON COLUMN port_allocation.container_id IS '关联容器 ID';
COMMENT ON COLUMN port_allocation.container_port IS '容器内端口（如 SSH 22、Jupyter 8888）';
COMMENT ON COLUMN port_allocation.host_port IS '分配的宿主端口（30000-32767 范围）';

-- ============ 镜像元数据 ============
COMMENT ON TABLE image_metadata IS '镜像元数据表：容器 commit 为镜像后的 tar 文件元数据，含归属与可见性';
COMMENT ON COLUMN image_metadata.name IS '镜像名';
COMMENT ON COLUMN image_metadata.tag IS '镜像标签（默认 latest）';
COMMENT ON COLUMN image_metadata.owner_id IS '归属用户 ID（公共镜像为空）';
COMMENT ON COLUMN image_metadata.size_bytes IS 'tar 文件大小（字节）';
COMMENT ON COLUMN image_metadata.tar_path IS '管理端 tar 文件存储路径';
COMMENT ON COLUMN image_metadata.is_public IS '是否公共镜像（管理员维护，受控端自动同步）';
COMMENT ON COLUMN image_metadata.source_container IS '来源容器（commit 时记录）';
COMMENT ON COLUMN image_metadata.checksum IS 'tar 文件 SHA-256 校验和';
COMMENT ON COLUMN image_metadata.status IS '状态（UPLOADING 上传中 / READY 就绪 / FAILED 失败）';

-- ============ 镜像共享 ============
COMMENT ON TABLE image_share IS '镜像共享关系表：用户将自己的镜像共享给其他用户使用';
COMMENT ON COLUMN image_share.image_id IS '镜像 ID';
COMMENT ON COLUMN image_share.shared_to_user_id IS '被共享用户 ID';

-- ============ 公共镜像同步 ============
COMMENT ON TABLE public_image_sync IS '公共镜像同步状态表：记录每台受控端对每个公共镜像的同步情况';
COMMENT ON COLUMN public_image_sync.image_id IS '公共镜像 ID';
COMMENT ON COLUMN public_image_sync.instance_id IS '物理实例 ID';
COMMENT ON COLUMN public_image_sync.sync_status IS '同步状态（PENDING 待同步 / SYNCED 已同步 / FAILED 失败）';
COMMENT ON COLUMN public_image_sync.synced_at IS '同步完成时间';

-- ============ 监控历史 ============
COMMENT ON TABLE monitoring_history IS '监控历史数据表：心跳状态快照入库，支持历史趋势回溯（保留 30 日）';
COMMENT ON COLUMN monitoring_history.instance_id IS '物理实例 ID';
COMMENT ON COLUMN monitoring_history.cpu_usage IS 'CPU 占用百分比';
COMMENT ON COLUMN monitoring_history.cpu_temp IS 'CPU 温度（摄氏度）';
COMMENT ON COLUMN monitoring_history.gpu_usage IS 'GPU 占用百分比';
COMMENT ON COLUMN monitoring_history.gpu_temp IS 'GPU 温度（摄氏度）';
COMMENT ON COLUMN monitoring_history.memory_usage IS '内存占用百分比';
COMMENT ON COLUMN monitoring_history.memory_total IS '总内存（字节）';
COMMENT ON COLUMN monitoring_history.memory_used IS '已用内存（字节）';
COMMENT ON COLUMN monitoring_history.status_snapshot IS '完整状态快照（JSON）';
COMMENT ON COLUMN monitoring_history.recorded_at IS '记录时间';

-- ============ 工单 ============
COMMENT ON TABLE ticket IS '工单表：学生提交特需工单（五种类型），管理员回复关闭，学生通过未读消息收到通知';
COMMENT ON COLUMN ticket.title IS '工单标题';
COMMENT ON COLUMN ticket.type IS '工单类型（RESOURCE 资源申请 / FAULT 故障报告 / SPECIAL_CONFIG 特殊配置 / PERMISSION 权限申请 / IMAGE 镜像申请）';
COMMENT ON COLUMN ticket.content IS '工单内容';
COMMENT ON COLUMN ticket.submitter_id IS '提交人用户 ID';
COMMENT ON COLUMN ticket.group_id IS '提交人所属课题组 ID';
COMMENT ON COLUMN ticket.status IS '状态（PENDING 待处理 / CLOSED 已关闭）';
COMMENT ON COLUMN ticket.reply IS '管理员回复内容';
COMMENT ON COLUMN ticket.replier_id IS '回复管理员 ID';
COMMENT ON COLUMN ticket.replied_at IS '回复时间';

-- ============ 工单流转历史 ============
COMMENT ON TABLE ticket_history IS '工单流转历史表：记录工单状态变更（创建/回复/关闭）';
COMMENT ON COLUMN ticket_history.ticket_id IS '工单 ID';
COMMENT ON COLUMN ticket_history.action IS '动作（CREATED 创建 / REPLIED 回复 / CLOSED 关闭）';
COMMENT ON COLUMN ticket_history.operator_id IS '操作人用户 ID';
COMMENT ON COLUMN ticket_history.content IS '操作内容';

-- ============ 公告 ============
COMMENT ON TABLE announcement IS '公告表：管理员发布系统公告，支持定向（全体/课题组/角色）与定时发布';
COMMENT ON COLUMN announcement.title IS '公告标题';
COMMENT ON COLUMN announcement.content IS '公告内容';
COMMENT ON COLUMN announcement.target_scope IS '定向范围（ALL 全体 / GROUP 指定课题组 / ROLE 指定角色）';
COMMENT ON COLUMN announcement.target_id IS '定向目标 ID（课题组 ID，target_scope=GROUP 时）';
COMMENT ON COLUMN announcement.target_role IS '定向目标角色（target_scope=ROLE 时）';
COMMENT ON COLUMN announcement.publish_mode IS '发布方式（IMMEDIATE 立即 / SCHEDULED 定时）';
COMMENT ON COLUMN announcement.publish_at IS '发布时间（定时发布到点自动发布）';
COMMENT ON COLUMN announcement.status IS '状态（PENDING 待发布 / PUBLISHED 已发布）';
COMMENT ON COLUMN announcement.author_id IS '发布管理员 ID';

-- ============ 通知消息 ============
COMMENT ON TABLE notification_message IS '通知消息表：用户未读消息收件箱，含容器/存储池/工单/公告变动通知，永久保留';
COMMENT ON COLUMN notification_message.user_id IS '接收用户 ID';
COMMENT ON COLUMN notification_message.type IS '通知类型（CONTAINER 容器 / STORAGE_POOL 存储池 / TICKET 工单 / ANNOUNCEMENT 公告）';
COMMENT ON COLUMN notification_message.ref_id IS '关联资源 ID（如容器 ID、工单 ID）';
COMMENT ON COLUMN notification_message.title IS '通知标题';
COMMENT ON COLUMN notification_message.content IS '通知内容';
COMMENT ON COLUMN notification_message.is_read IS '是否已读（已读消息永久保留供回溯）';
COMMENT ON COLUMN notification_message.read_at IS '已读时间';

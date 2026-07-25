# file-transfer Specification

## Purpose
TBD - created by archiving change build-nexcompute-platform. Update Purpose after archive.
## Requirements
### Requirement: 可靠大文件传输

系统 SHALL 提供受控端与管理端之间的大文件传输能力，支持分块传输与断点续传。该能力被存储池迁移、镜像 tar 分发、公共镜像同步三个场景复用。

#### Scenario: 上传文件（受控端到管理端）
- **WHEN** 受控端向管理端上传大文件（如镜像 tar、迁移数据）
- **THEN** 文件分块传输，管理端按块接收并校验
- **AND** 传输完成后管理端组装为完整文件并校验完整性

#### Scenario: 下载文件（管理端到受控端）
- **WHEN** 管理端向受控端下发大文件
- **THEN** 文件分块传输，受控端按块接收并校验
- **AND** 传输完成后受控端组装为完整文件并校验完整性

### Requirement: 断点续传

大文件传输 SHALL 支持断点续传。传输中断后恢复时，系统从已完成的块位置继续，不重传已完成部分。

#### Scenario: 传输中断后恢复
- **WHEN** 大文件传输中途中断（网络断开、进程重启等）
- **THEN** 恢复传输时系统查询已完成的块位置
- **AND** 从断点处继续传输剩余块

#### Scenario: 传输进度跟踪
- **WHEN** 文件传输进行中
- **THEN** 系统记录并暴露传输进度（已传输块数/总块数）
- **AND** 管理端可在 UI 展示迁移/同步进度

### Requirement: 文件完整性校验

系统 SHALL 在文件传输完成后校验完整性（如校验和），校验失败则标记传输失败并支持重试。

#### Scenario: 校验成功
- **WHEN** 文件所有块传输完成
- **THEN** 系统计算校验和并与源端比对
- **AND** 校验一致则标记传输成功

#### Scenario: 校验失败重试
- **WHEN** 校验和不一致
- **THEN** 系统标记传输失败
- **AND** 支持重新发起传输


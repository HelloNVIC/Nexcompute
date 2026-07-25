## ADDED Requirements

### Requirement: 镜像应用端口与 tar 自动解析

管理员/用户增加镜像时，SHALL 支持拖拽上传 tar 文件，并可为镜像声明"应用端口"（支持多个）。上传时管理端 SHALL 自动解析 tar 文件内的镜像标签（RepoTags）与暴露端口（ExposedPorts），据此预填镜像名称、标签与应用端口。镜像元数据 SHALL 持久化应用端口列表，供容器创建时自动填入"容器内端口"。

#### Scenario: 拖拽上传 tar 并自动解析
- **WHEN** 用户拖拽一个 tar 文件到上传区并提交
- **THEN** 管理端解析 tar 的 RepoTags 与 ExposedPorts
- **AND** 预填镜像名称与标签（可由用户修改）
- **AND** 预填应用端口为解析到的暴露端口
- **AND** 镜像元数据保存应用端口列表

#### Scenario: 声明多个应用端口
- **WHEN** 用户为镜像声明多个应用端口（如 8888、6006）
- **THEN** 系统保存该镜像的多个应用端口
- **AND** 创建容器时这些端口自动填入"容器内端口"

#### Scenario: 无 ExposedPorts 的镜像
- **WHEN** 上传的 tar 不含 ExposedPorts
- **THEN** 应用端口为空，用户可手动添加

## MODIFIED Requirements

### Requirement: 镜像 tar 导出导入

镜像 SHALL 以 tar 文件形式在管理端存储与分发。管理端 SHALL 在创建容器前下发 `image.load`：受控端检查本地是否已持有该镜像，已持有则跳过，否则经 file-transfer 下载 tar 并执行 `docker load` 导入；导入成功后再创建并启动容器。无需管理端维护已同步镜像记录（由受控端本地存在性检查判定）。

#### Scenario: 使用非本地镜像创建容器
- **WHEN** 用户选择一个目标受控端本地尚未持有的镜像创建容器
- **THEN** 管理端将镜像 tar 传输至目标受控端（复用 file-transfer）
- **AND** 受控端执行 `docker load` 导入镜像
- **AND** 导入完成后创建并启动容器
- **AND** 受控端随后已本地持有该镜像

#### Scenario: 使用已本地镜像创建容器
- **WHEN** 用户选择的镜像已在目标受控端本地存在
- **THEN** 直接创建容器，不重复传输 tar

#### Scenario: 镜像分发失败中止
- **WHEN** tar 传输或 `docker load` 失败
- **THEN** 容器创建被中止并返回错误

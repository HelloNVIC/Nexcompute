# add-newapi-user-allocation

管理员后台新增 Token分配入口（邀请门控注册 + 管理员审批 + NewAPI REST 建用户），1:1 镜像 nas-allocation 模块，复用平台 ADMIN 鉴权与 nexcompute 库，额度走 QuotaForNewUser 全局默认

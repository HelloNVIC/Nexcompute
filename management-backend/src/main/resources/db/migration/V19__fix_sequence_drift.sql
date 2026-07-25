-- V19 修复 app_user / research_group 序列漂移（platform-refinements #4）
-- 根因：V14 用显式 id 插入 app_user(2,3) 与 research_group(1)，但 BIGSERIAL 序列未跟进，
-- 导致后续 createUser 时 nextval 取到已存在的 id，报 duplicate key。
-- setval 到 MAX(id)，nextval 即返回 MAX+1，避免冲突。
SELECT setval('app_user_id_seq', (SELECT COALESCE(MAX(id), 1) FROM app_user));
SELECT setval('research_group_id_seq', (SELECT COALESCE(MAX(id), 1) FROM research_group));

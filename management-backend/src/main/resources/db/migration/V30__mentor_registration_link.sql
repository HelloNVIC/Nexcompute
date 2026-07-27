-- V30 导师邀请注册：registration_link 增加 link_type，group_id 可空（导师链接注册时建组，无既有组）
-- link_type：STUDENT（学生加入既有课题组，group_id 必填）/ MENTOR（导师注册，注册时创建课题组，group_id 为空）
-- 既有链接全部为 STUDENT（默认值），行为不变。

ALTER TABLE registration_link ADD COLUMN link_type VARCHAR(20) NOT NULL DEFAULT 'STUDENT';
ALTER TABLE registration_link ALTER COLUMN group_id DROP NOT NULL;
COMMENT ON COLUMN registration_link.link_type IS 'STUDENT=学生加入既有课题组；MENTOR=导师注册（注册时创建课题组，group_id 为空）';

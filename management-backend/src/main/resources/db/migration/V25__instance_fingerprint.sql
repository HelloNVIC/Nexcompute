-- V25 物理实例硬件指纹（platform-refinements #1：MAC + 机器码，注册去重）
ALTER TABLE physical_instance ADD COLUMN mac VARCHAR(50);
ALTER TABLE physical_instance ADD COLUMN machine_code VARCHAR(200);
CREATE INDEX idx_physical_instance_mac_machine ON physical_instance(mac, machine_code);
COMMENT ON COLUMN physical_instance.mac IS '物理机 MAC（首个非虚拟网卡）';
COMMENT ON COLUMN physical_instance.machine_code IS '机器码（host HostID），与 MAC 共同作为指纹去重';

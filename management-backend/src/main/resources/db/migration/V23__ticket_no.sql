-- V23 工单唯一编号（platform-refinements #3）
ALTER TABLE ticket ADD COLUMN ticket_no VARCHAR(32);
COMMENT ON COLUMN ticket.ticket_no IS '工单唯一编号（如 TK20260724-000001）';
-- 回填存量工单编号（按 id 顺序）
UPDATE ticket SET ticket_no = 'TK' || to_char(created_at, 'YYYYMMDD') || '-' || lpad(id::text, 6, '0') WHERE ticket_no IS NULL;
ALTER TABLE ticket ALTER COLUMN ticket_no SET NOT NULL;
CREATE UNIQUE INDEX idx_ticket_no ON ticket(ticket_no);

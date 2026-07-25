-- V21 容器共享（platform-refinements #1/#2：按工号共享、限时、可取消）
CREATE TABLE container_share (
    id                BIGSERIAL PRIMARY KEY,
    container_id      BIGINT NOT NULL REFERENCES container(id),
    shared_to_user_id BIGINT NOT NULL REFERENCES app_user(id),
    shared_by         BIGINT NOT NULL REFERENCES app_user(id),
    expires_at        TIMESTAMPTZ,                 -- 可空=永久；非空=到期失效
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(container_id, shared_to_user_id)
);
CREATE INDEX idx_container_share_container ON container_share(container_id);
CREATE INDEX idx_container_share_user ON container_share(shared_to_user_id);
COMMENT ON TABLE container_share IS '容器共享关系（按工号/课题组共享，可限时，可取消）';

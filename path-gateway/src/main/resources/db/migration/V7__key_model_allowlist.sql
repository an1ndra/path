-- Per-key model allowlist (issue #15).

alter table client_keys add column allowed_models varchar(1024);

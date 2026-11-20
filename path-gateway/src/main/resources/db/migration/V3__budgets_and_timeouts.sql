-- Spend windows, output caps and per-provider timeouts (issues #16, #21).

alter table client_keys add column daily_limit_usd numeric(18,6);
alter table client_keys add column monthly_limit_usd numeric(18,6);
alter table client_keys add column max_output_tokens integer;

alter table providers add column request_timeout_ms integer;
alter table providers add column stream_idle_timeout_ms integer;

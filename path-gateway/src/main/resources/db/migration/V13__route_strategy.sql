-- Adaptive routing (issue #63). Routing was static: a degraded-but-alive provider kept
-- the traffic until it 5xxed. strategy defaults to 'priority', so nothing changes until
-- an operator opts in.

alter table model_routes add column strategy varchar(32) not null default 'priority';
alter table model_routes add column latency_slo_ms integer;

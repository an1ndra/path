-- Semantic cache tier (issue #71). Opt-in per route, on top of the exact-match cache.

alter table model_routes add column semantic_cache_enabled boolean not null default false;
alter table model_routes add column semantic_threshold numeric(4,3) not null default 0.950;

alter table response_cache_entries add column embedding text;
alter table response_cache_entries add column embedding_model varchar(120);

create index ix_cache_semantic on response_cache_entries
    (client_key_id, public_model_id, expires_at);

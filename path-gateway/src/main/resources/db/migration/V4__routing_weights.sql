-- Canary / A-B routing (issue #30): traffic weight per route.

alter table model_routes add column weight integer not null default 100;

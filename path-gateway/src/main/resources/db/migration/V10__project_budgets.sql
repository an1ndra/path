-- Project-level budgets and rollups (docs/LIMITS.md: projects grouped keys but not money).

alter table projects add column credit_limit_usd numeric(18,6);
alter table projects add column daily_limit_usd numeric(18,6);
alter table projects add column monthly_limit_usd numeric(18,6);

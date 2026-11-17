-- Budget reservation so concurrent requests cannot overshoot a credit limit (issue #20).

alter table client_keys add column reserved_usd numeric(18,6) not null default 0;
alter table client_keys add column reserved_updated_at timestamp(6) with time zone;

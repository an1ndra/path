-- Convert usage_records to monthly range partitions.
--
-- This is an OPT-IN, ONE-TIME operation, not a Flyway migration: it rewrites the table,
-- so it must run on purpose, in a maintenance window, against the database Path uses.
-- Nothing in the application runs this file. See docs/OPERATIONS.md
-- ("Retention, archiving and partitioning") for the full procedure, including the CSV
-- archive that should be taken first.
--
-- Requirements: PostgreSQL 13 or newer. Run as the schema owner with the gateway stopped.
--
--   psql "$DB_URL" -v ON_ERROR_STOP=1 -f deploy/postgres/partitions.sql
--
-- Afterwards the app keeps working unchanged: the columns, identity and indexes are the
-- same, so Hibernate's ddl-auto=validate still passes.

begin;

-- 1. Keep the old table under a name that makes its purpose obvious.
alter table usage_records rename to usage_records_unpartitioned;

-- 2. Recreate it as a partitioned parent. Like the original, but the primary key has to
--    include the partition key (PostgreSQL requires it), and indexes are added after the
--    data copy instead of being copied by LIKE.
create table usage_records (
    like usage_records_unpartitioned including defaults including identity
) partition by range (created_at);

-- 3. Monthly partitions, one per calendar month in [from_month, to_month]. Idempotent, so
--    the same function is what a monthly cron calls later.
create or replace function path_ensure_usage_partitions(from_month date, to_month date)
returns void
language plpgsql
as $$
declare
    month_start date := date_trunc('month', from_month)::date;
    month_end date := date_trunc('month', to_month)::date;
    part_end date;
    part_name text;
begin
    while month_start <= month_end loop
        part_end := (month_start + interval '1 month')::date;
        part_name := 'usage_records_' || to_char(month_start, 'YYYY_MM');
        if to_regclass(part_name) is null then
            execute format(
                'create table %I partition of usage_records for values from (%L) to (%L)',
                part_name, month_start, part_end);
        end if;
        month_start := part_end;
    end loop;
end;
$$;

-- 4. Cover the whole range the old table holds, so the copy never needs the default
--    partition (a partition cannot be added for a month whose rows already sit in it).
select path_ensure_usage_partitions(
    coalesce((select date_trunc('month', min(created_at))::date from usage_records_unpartitioned),
             date_trunc('month', now())::date),
    greatest((select date_trunc('month', max(created_at))::date from usage_records_unpartitioned),
             (date_trunc('month', now()) + interval '3 months')::date));

-- 5. A DEFAULT partition catches rows written outside the prepared months, so an insert
--    never fails. If a cron forgets to run, this is what absorbs the gap.
create table usage_records_default partition of usage_records default;

-- 6. Move the rows. One statement on purpose: it is transactional and runs in the
--    maintenance window where nobody is writing. For a very large history, split this by
--    year into separate transactions, but keep the gateway stopped so nothing races.
insert into usage_records select * from usage_records_unpartitioned;

-- 7. The identity sequence on the new table starts at 1 after a LIKE, which would collide
--    with the copied ids. Move it past the highest id.
select setval(pg_get_serial_sequence('usage_records', 'id'),
              coalesce((select max(id) from usage_records), 0) + 1, false);

-- 8. Same indexes the application expects. On a partitioned parent PostgreSQL creates one
--    per child automatically.
create index ix_usage_created on usage_records (created_at);
create index ix_usage_client on usage_records (client_key_id);
create index ix_usage_model on usage_records (public_model_id);
alter table usage_records add constraint usage_records_pkey primary key (id, created_at);

drop table usage_records_unpartitioned;

commit;

-- Optional: schedule the monthly top-up with pg_cron (the extension is not required).
--   create extension if not exists pg_cron;
--   select cron.schedule('path-usage-partitions', '0 3 1 * *', $$
--     select path_ensure_usage_partitions(
--       date_trunc('month', now())::date,
--       (date_trunc('month', now()) + interval '3 months')::date)
--   $$);
--
-- Optional: drop a month older than the retention once it has been archived. Detaching is
-- cheap; dropping the table releases the space without a row-by-row delete.
--   alter table usage_records detach partition usage_records_2025_01;
--   drop table usage_records_2025_01;
--
-- Retention (GATEWAY_USAGE_RETENTION) still deletes rows older than its window on a
-- schedule. Partitioning keeps that delete cheap and lets a whole month be detached instead.

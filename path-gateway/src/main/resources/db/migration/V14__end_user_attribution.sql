-- End-user attribution (issue #64). A key and a project are operator concepts; without
-- an end-user axis the gateway cannot bill a product's own customers.

alter table usage_records add column end_user varchar(128);
alter table client_keys add column end_user_daily_limit_usd numeric(18,6);

create index idx_usage_key_end_user_created
    on usage_records (client_key_id, end_user, created_at);

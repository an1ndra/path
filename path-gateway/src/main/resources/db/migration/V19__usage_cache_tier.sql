-- Which cache tier served a call. Null means the request was not a cache hit.
-- "exact" is the response cache, "semantic" the near-duplicate tier. Keeping it on the row
-- makes /admin/stats survive a restart and agree across replicas, which an in-process
-- counter cannot do.
alter table usage_records add column cache_tier varchar(16);

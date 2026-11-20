-- Project-level rate limits (issue #65). A project that owns forty keys could push
-- 4,800 rpm with nothing to cap it; these are the caps.

alter table projects add column rpm_limit integer;
alter table projects add column max_concurrent_requests integer;

-- Azure OpenAI (issue #73). Azure serves the same wire format at a different path, with
-- the deployment in the URL and the version in a query parameter. One column is enough;
-- a separate provider type would duplicate every route and every adapter lookup.

alter table providers add column api_version varchar(32);

-- Vertex AI needs a project and a region; AI Studio and the OpenAI-shaped providers leave
-- both null. The service-account JSON lives in the existing encrypted credential column.
-- A service-account JSON is a few kilobytes; the old 2048 limit truncated its ciphertext.
alter table providers alter column api_key_cipher set data type varchar(4096);

alter table providers add column project varchar(128);
alter table providers add column location varchar(64);

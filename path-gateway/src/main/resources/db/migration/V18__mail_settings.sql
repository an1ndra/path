-- Alert email configuration editable from the admin console (issue: UI email config).
-- One row; the SMTP password is encrypted with the same AES-GCM master key as provider
-- credentials and is never returned by the API.

create table mail_settings (
    id bigint not null,
    enabled boolean not null default false,
    host varchar(255),
    port integer,
    username varchar(255),
    password_cipher varchar(1024),
    from_address varchar(255),
    to_address varchar(1024),
    updated_at timestamp(6) with time zone,
    primary key (id)
);

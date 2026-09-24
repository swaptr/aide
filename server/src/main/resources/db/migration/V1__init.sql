-- Baseline schema. Flyway (not Hibernate) owns DDL; `spring.jpa.hibernate.ddl-auto=validate` fails startup
-- if an entity drifts from what is defined here. Column types are written to match Hibernate's expected
-- types exactly (varchar(n) for @Column(length = n), timestamp(6) with time zone for Instant) so the
-- validator passes.

create table app_setting (
    setting_key   varchar(128)                not null,
    setting_value varchar(1024)               not null,
    updated_at    timestamp(6) with time zone not null default now(),
    constraint pk_app_setting primary key (setting_key)
);

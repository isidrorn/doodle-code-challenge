-- Input hardening (design-decisions-v8.md):
--  * column sizes tightened to match the bean-validation @Size caps exactly, so an over-long
--    value is rejected as a 400 at the boundary and can never surface as a DB error
--  * user email is genuinely unique at the DB level — the service-level pre-check gives the
--    friendly 409, this constraint closes the concurrent-create race
--  * name/email/title are not null, matching @NotBlank on the request DTOs

-- Pre-constraint de-dupe: code that predates this migration inserted the seed users
-- unconditionally on every startup, so a database that has been restarted holds duplicate
-- emails and the unique index below could not be created over them (found live, not
-- hypothesized). Keep the oldest row per email untouched; rename the rest deterministically
-- (bounded well under the new 254 limit) instead of deleting — deleting would cascade into
-- calendars/slots/meeting participations that may legitimately reference the duplicate rows.
update app_user u
set email = left(u.email, 240) || '+dup' || u.id
where exists (select 1 from app_user o where o.email = u.email and o.id < u.id);

alter table app_user alter column name  type varchar(100);
alter table app_user alter column name  set not null;
alter table app_user alter column email type varchar(254);
alter table app_user alter column email set not null;
alter table app_user add constraint uq_app_user_email unique (email);

alter table meeting alter column title       type varchar(150);
alter table meeting alter column title       set not null;
alter table meeting alter column description type varchar(1000);

-- The order of the people inside a group of a function, set by admins (up / down in Tree and Groups). One row per
-- person per function, as a person has one group per function. group_id says which group the position is for
-- (null: the "Other" column, people without a group): after a move to another group the row no longer applies. Without
-- a row the person follows those who have one, by grade as before. A table of its own, not employee_overrides: a row
-- there would keep a person in the function even after AD moves them elsewhere. The AD sync never touches this table.

CREATE TABLE group_member_order (
    cs           VARCHAR(256) NOT NULL,
    employee_id  BIGINT       NOT NULL REFERENCES employees (id) ON DELETE CASCADE,
    group_id     BIGINT       REFERENCES function_groups (id) ON DELETE CASCADE,
    position     INT          NOT NULL,
    updated_by   VARCHAR(256),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (cs, employee_id)
);

CREATE INDEX ix_group_member_order_group ON group_member_order (group_id);
CREATE INDEX ix_group_member_order_employee ON group_member_order (employee_id);

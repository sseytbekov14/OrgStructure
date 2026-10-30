-- Tenders is no longer a function of its own: it is part of F&O (7 functions on the site). Proposals & Tenders of AD
-- now falls under the F&O row of department_function_map and stays a group inside F&O (its AD department). Whatever
-- admins made for Tenders goes with it: groups, memberships, the order inside groups and the FAQ.

DELETE FROM department_function_map WHERE cs_name = 'Tenders';

-- Groups: one of the same name in F&O takes the people, the others move after the groups of F&O
UPDATE employee_overrides o
SET group_id = f.id, group_name = f.name
FROM function_groups t
JOIN function_groups f ON f.cs = 'F&O' AND lower(f.name) = lower(t.name)
WHERE t.cs = 'Tenders' AND o.group_id = t.id;

DELETE FROM function_groups t
USING function_groups f
WHERE t.cs = 'Tenders' AND f.cs = 'F&O' AND lower(f.name) = lower(t.name);

UPDATE function_groups
SET cs = 'F&O',
    sort_order = sort_order + (SELECT coalesce(max(sort_order), 0) FROM function_groups WHERE cs = 'F&O')
WHERE cs = 'Tenders';

-- Memberships: somebody already in F&O keeps that row and gains the texts it lacks. The heads of Tenders become
-- members of their group: F&O has its own heads
UPDATE employee_overrides f
SET area_of_duties  = coalesce(f.area_of_duties, t.area_of_duties),
    job_description = coalesce(f.job_description, t.job_description)
FROM employee_overrides t
WHERE f.cs = 'F&O' AND t.cs = 'Tenders' AND f.employee_id = t.employee_id;

DELETE FROM employee_overrides t
USING employee_overrides f
WHERE t.cs = 'Tenders' AND f.cs = 'F&O' AND t.employee_id = f.employee_id;

UPDATE employee_overrides SET cs = 'F&O', head_rank = NULL WHERE cs = 'Tenders';

-- The order inside groups: the positions of the Other column of Tenders mean nothing among the people of F&O
DELETE FROM group_member_order WHERE cs = 'Tenders' AND group_id IS NULL;

DELETE FROM group_member_order t
USING group_member_order f
WHERE t.cs = 'Tenders' AND f.cs = 'F&O' AND t.employee_id = f.employee_id;

UPDATE group_member_order SET cs = 'F&O' WHERE cs = 'Tenders';

-- The FAQ: the sections of Tenders follow those of F&O, marked with the old name
UPDATE function_faq f
SET content = jsonb_set(f.content, '{sections}', (f.content -> 'sections') || (
        SELECT coalesce(jsonb_agg(jsonb_set(s.section, '{title}', to_jsonb(left('Tenders: ' || (s.section ->> 'title'), 80)))
                                  ORDER BY s.position), '[]'::jsonb)
        FROM jsonb_array_elements(t.content -> 'sections') WITH ORDINALITY AS s(section, position))),
    version = f.version + 1,
    updated_by = 'tenders-in-fo',
    updated_at = now()
FROM function_faq t
WHERE f.cs = 'F&O' AND t.cs = 'Tenders';

UPDATE function_faq
SET cs = 'F&O', updated_by = 'tenders-in-fo', updated_at = now()
WHERE cs = 'Tenders' AND NOT EXISTS (SELECT 1 FROM function_faq WHERE cs = 'F&O');

DELETE FROM function_faq WHERE cs = 'Tenders';

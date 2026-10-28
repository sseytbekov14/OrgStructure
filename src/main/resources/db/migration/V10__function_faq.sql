-- Admin & UX release U5: the "FAQ & Request Guidelines" of each function, editable by admins (before: the
-- FUNCTION_FAQ constant of function.js, generated from it; FaqSeedParityTest compares this seed with the
-- constant as it was, kept in src/test/resources/faq-legacy.json). Plain text, no HTML; the document model is
-- {sections: [{kind: GUIDELINES|MAILBOXES|QUESTIONS, title, items}]} (FaqRules). A mailbox address that was
-- not valid in the constant (shown as "Email to be added") is stored as null.

CREATE TABLE function_faq (
    cs          VARCHAR(256) PRIMARY KEY,
    content     JSONB        NOT NULL,
    version     BIGINT       NOT NULL DEFAULT 0,
    updated_by  VARCHAR(256),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

INSERT INTO function_faq (cs, content, updated_by) VALUES ('People',
    '{"sections":[{"kind":"GUIDELINES","title":"Request rules","items":[{"text":"Check the KPMG HR Portal first for standard templates and guidelines."},{"text":"Send all L&D/training request approvals at least two weeks in advance."},{"text":"Submit candidate referrals directly through the internal referral system."}]},{"kind":"MAILBOXES","title":"Team mailboxes","items":[{"label":"General Questions & HR Administration","email":"people@kpmg.kz"},{"label":"Learning & Development (L&D)","email":"learning@kpmg.kz"},{"label":"Graduate & Experienced Recruiting","email":"recruiting@kpmg.kz"}]}]}'::jsonb, 'migration')
ON CONFLICT (cs) DO NOTHING;

INSERT INTO function_faq (cs, content, updated_by) VALUES ('ITS',
    '{"sections":[{"kind":"GUIDELINES","title":"Request rules","items":[{"text":"Submit all standard requests and hardware issues via the IT Portal."},{"text":"Urgent issues: Call the Service Desk hotline at extension CCG-CQ-Ithelpdesk for immediate help."},{"text":"Request new software licenses using the official Software Request form."}]},{"kind":"MAILBOXES","title":"Team mailboxes","items":[{"label":"IT Service Desk (Incidents & Requests)","email":"itservicedesk@kpmg.kz"},{"label":"IT Operations & Infrastructure","email":"ccg-fmhni@kpmg.kz"},{"label":"1C Development Team","email":null}]}]}'::jsonb, 'migration')
ON CONFLICT (cs) DO NOTHING;

INSERT INTO function_faq (cs, content, updated_by) VALUES ('Finance',
    '{"sections":[{"kind":"GUIDELINES","title":"Request rules","items":[{"text":"Submit expense claims before the 25th of the current month to ensure payout."},{"text":"All invoices must include a valid Purchase Order (PO) number to be processed."},{"text":"Use the SharePoint Finance portal to download current tax and billing templates."}]},{"kind":"MAILBOXES","title":"Team mailboxes","items":[{"label":"Accounts Payable (Vendor Invoices)","email":"ap@kpmg.kz"},{"label":"Travel & Business Expense Claims","email":"travel@kpmg.kz"},{"label":"Client Billing & Invoicing Support","email":"billing@kpmg.kz"}]}]}'::jsonb, 'migration')
ON CONFLICT (cs) DO NOTHING;

INSERT INTO function_faq (cs, content, updated_by) VALUES ('Markets',
    '{"sections":[{"kind":"GUIDELINES","title":"Request rules","items":[{"text":"Submit brand review requests at least 3 working days before the deadline."},{"text":"Book meeting rooms and catering requests through the SharePoint portal."},{"text":"Use official business templates and presentation slide packs from the portal."}]},{"kind":"MAILBOXES","title":"Team mailboxes","items":[{"label":"Brand Review & Logo Approvals","email":"brand@kpmg.kz"},{"label":"Events Management & Catering Requests","email":"events@kpmg.kz"},{"label":"BD Opportunities & Markets General","email":"markets@kpmg.kz"}]}]}'::jsonb, 'migration')
ON CONFLICT (cs) DO NOTHING;

INSERT INTO function_faq (cs, content, updated_by) VALUES ('OGC',
    '{"sections":[{"kind":"GUIDELINES","title":"Request rules","items":[{"text":"Submit contracts for OGC review via the Legal Workflow System (LWS)."},{"text":"Standard contract templates do not require OGC review unless customized."},{"text":"Involve OGC early for high-risk engagements or new service offerings."}]},{"kind":"MAILBOXES","title":"Team mailboxes","items":[{"label":"Contract Reviews & Legal Queries","email":"legal@kpmg.kz"},{"label":"Regulatory Compliance Assistance","email":"compliance@kpmg.kz"},{"label":"General Legal Counsel Support","email":"ogc@kpmg.kz"}]}]}'::jsonb, 'migration')
ON CONFLICT (cs) DO NOTHING;

INSERT INTO function_faq (cs, content, updated_by) VALUES ('Tenders',
    '{"sections":[{"kind":"GUIDELINES","title":"Request rules","items":[{"text":"All bid documents must be signed off by the Engagement Partner before submission."},{"text":"Register new tender opportunities in the Tender Tracker tool immediately."},{"text":"Contact the Tender Support team as soon as the client''s RFP is released."}]},{"kind":"MAILBOXES","title":"Team mailboxes","items":[{"label":"Tender Proposals & Bid Submission Support","email":"tenders@kpmg.kz"},{"label":"Procurement & Bid Documentation","email":"procurement@kpmg.kz"},{"label":"General Bid Support Inquiries","email":"tender-ops@kpmg.kz"}]}]}'::jsonb, 'migration')
ON CONFLICT (cs) DO NOTHING;

INSERT INTO function_faq (cs, content, updated_by) VALUES ('QRM',
    '{"sections":[{"kind":"GUIDELINES","title":"Request rules","items":[{"text":"Complete client and engagement acceptance checks prior to commencing any work."},{"text":"Submit Sentinel code checks for review before distributing proposals."},{"text":"Contact QRM immediately if potential conflicts of interest are identified."}]},{"kind":"MAILBOXES","title":"Team mailboxes","items":[{"label":"Quality & Risk Management (Sentinel checks)","email":"qrm@kpmg.kz"},{"label":"Client & Engagement Acceptance Rules","email":"acceptance@kpmg.kz"},{"label":"Risk Management Helpdesk Support","email":"risk@kpmg.kz"}]}]}'::jsonb, 'migration')
ON CONFLICT (cs) DO NOTHING;

INSERT INTO function_faq (cs, content, updated_by) VALUES ('F&O',
    '{"sections":[{"kind":"GUIDELINES","title":"Request rules","items":[{"text":"Submit all expense claims and reimbursement requests before the 25th of the current month."},{"text":"All vendor invoices must include a valid Purchase Order (PO) number to be processed."},{"text":"Use the F&O SharePoint portal for current templates, forms, and approval workflows."}]},{"kind":"MAILBOXES","title":"Team mailboxes","items":[{"label":"Finance & Operations General Queries","email":"fo@kpmg.kz"},{"label":"Accounts Payable & Vendor Invoices","email":"ap@kpmg.kz"},{"label":"Travel & Business Expense Claims","email":"travel@kpmg.kz"}]}]}'::jsonb, 'migration')
ON CONFLICT (cs) DO NOTHING;

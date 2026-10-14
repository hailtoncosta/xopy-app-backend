ALTER TABLE payment_sessions ADD COLUMN recipient text NOT NULL DEFAULT 'seller' CHECK (recipient IN ('seller','xopy'));
UPDATE payment_sessions SET recipient='xopy' WHERE seller_id IS NULL;

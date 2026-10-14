ALTER TABLE dynamic_categories ADD COLUMN description text NOT NULL DEFAULT '';
ALTER TABLE dynamic_categories ADD COLUMN category_type text NOT NULL DEFAULT 'produto' CHECK (category_type IN ('produto','serviço','locação'));
ALTER TABLE stored_files ADD COLUMN display_name text;
ALTER TABLE stored_files ADD COLUMN alt_text text NOT NULL DEFAULT '';
ALTER TABLE stored_files ADD COLUMN description text NOT NULL DEFAULT '';

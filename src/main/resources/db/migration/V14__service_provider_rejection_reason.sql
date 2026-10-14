ALTER TABLE service_providers ADD COLUMN rejection_reason text CHECK (rejection_reason IS NULL OR length(rejection_reason) <= 2000);

ALTER TABLE campaign ADD COLUMN parameter_value TEXT;
UPDATE campaign SET parameter_value=preview;
ALTER TABLE campaign ALTER COLUMN parameter_value SET NOT NULL;

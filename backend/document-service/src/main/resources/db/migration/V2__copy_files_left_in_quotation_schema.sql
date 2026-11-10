-- Files of quotations from the single procurement service are copied here by V1 while they are still in
-- procurement.quotation_file. If the quotation service moved that table first (it moves it to its own schema and
-- only drops the bytes in its V2), copy the files from there instead. Same ids; only files not copied yet.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.columns
               WHERE table_schema = 'quotation' AND table_name = 'quotation_file' AND column_name = 'content') THEN
        EXECUTE 'INSERT INTO document (id, owner_type, owner_id, status, file_name, content_type, file_format, size_bytes,
                                       sha256, content, uploaded_by_id, uploaded_by_name, uploaded_at)
                 SELECT f.id, ''QUOTATION'', f.quotation_id, ''ATTACHED'', f.file_name, f.content_type, f.file_format,
                        f.size_bytes, f.sha256, f.content, f.uploaded_by_id, f.uploaded_by_name, f.uploaded_at
                 FROM quotation.quotation_file f
                 WHERE NOT EXISTS (SELECT 1 FROM document d WHERE d.id = f.id)';
        PERFORM setval(pg_get_serial_sequence('document', 'id'), GREATEST((SELECT max(id) FROM document), 1));
    END IF;
END $$;

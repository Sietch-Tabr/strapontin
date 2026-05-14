DELETE FROM concerts c1
USING concerts c2
WHERE c1.source = c2.source
  AND c1.url IS NOT NULL
  AND c1.url = c2.url
  AND c1.id != c2.id
  AND c1.updated_at < c2.updated_at;

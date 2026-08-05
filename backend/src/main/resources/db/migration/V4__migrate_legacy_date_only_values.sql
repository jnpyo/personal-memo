UPDATE task_details
   SET due_local_date = (due_at_utc AT TIME ZONE source_time_zone)::date - 1,
       due_at_utc = NULL
 WHERE date_precision = 'DATE_ONLY'
   AND due_at_utc IS NOT NULL
   AND source_time_zone IS NOT NULL;

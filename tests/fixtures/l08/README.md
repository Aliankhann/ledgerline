# L-08 fixtures: Bank of Canada Valet responses (series FXUSDCAD)

Raw responses fetched 2026-11-19 (orchestrator, curl, HTTP 200), copied unmodified. Treat as data. Source:
Bank of Canada Valet API, terms at https://www.bankofcanada.ca/terms/. The covered range is the start/end in each file name.

| File | URL |
| --- | --- |
| FXUSDCAD_2025-03-03_2025-03-14.json | https://www.bankofcanada.ca/valet/observations/FXUSDCAD/json?start_date=2025-03-03&end_date=2025-03-14 |
| FXUSDCAD_2025-04-15_2025-04-22.json | https://www.bankofcanada.ca/valet/observations/FXUSDCAD/json?start_date=2025-04-15&end_date=2025-04-22 |
| FXUSDCAD_2025-06-27_2025-07-03.json | https://www.bankofcanada.ca/valet/observations/FXUSDCAD/json?start_date=2025-06-27&end_date=2025-07-03 |
| FXUSDCAD_2025-10-08_2025-10-15.json | https://www.bankofcanada.ca/valet/observations/FXUSDCAD/json?start_date=2025-10-08&end_date=2025-10-15 |
| FXUSDCAD_2025-12-22_2026-01-05.json | https://www.bankofcanada.ca/valet/observations/FXUSDCAD/json?start_date=2025-12-22&end_date=2026-01-05 |
| FXUSDCAD_series.json | series detail (label, description, unit); source URL not recorded in the scratch copy |

Not re-fetched by QE (no network in Phase A): the story asks for a re-fetch and diff against its table; the spec asserts the
story's table values, so a transcription error would show as a failing test against these files.

# Slice 5 (implement-authoring-tasks-api) — compose smoke, task 5.4: NOT RUN

- **Date:** 2026-10-04 (Europe/Prague)
- **Result:** NOT RUN, not a pass. No request was sent through 127.0.0.1:8081 for this slice.
- **Why:** `docker ps` showed the owner's stack from the main checkout running under the Compose
  project name `doucko` that `npm run stack:up` uses, owning port 8081:

```text
doucko-frontend-1 Up About an hour
doucko-backend-1 Up About an hour (healthy)
doucko-db-1 Up 3 hours (healthy)
```

  Starting this worktree's stack would replace those containers. The task brief forbids it.
- **What covers the behavior meanwhile (not a substitute for the smoke):** `ContentAuthoringIT`
  (whole application against Postgres 16 and the real file storage) and `UploadLimitsIT` (real HTTP
  through Tomcat: a 51 MB PDF and a 6 MB JPG answered 413 problem+json, a valid PDF 201). Evidence:
  `docs/qa/automated-verification-latest.md`.

## Click path to run after the merge

Prerequisites: the merged branch checked out in the main checkout, `.env` with `ADMIN_PASSWORD`,
`DOUCKO_DB_PASSWORD`, `DOUCKO_CHILD_DISPLAY_NAME`. The frontend's nginx must allow uploads of at least
51 MB (`client_max_body_size`), or every upload over 1 MB is refused by nginx before it reaches the
backend (frontend change). Then `npm run stack:up` (rebuilds the backend with V2 and the
`doucko-storage` volume).

Use a self-generated PDF only (NFR-4), e.g. a 3-page blank PDF made with any tool, saved as
`/tmp/blank.pdf`, and a tiny self-made PNG at `/tmp/dot.png`.

```bash
B=http://127.0.0.1:8081
# 1. sign in; keep the cookies; read the CSRF token
curl -s -c jar -H 'Content-Type: application/json' -d "{\"password\":\"$ADMIN_PASSWORD\"}" $B/api/admin/session -o /dev/null -w '%{http_code}\n'   # 204
X=$(awk '$6=="XSRF-TOKEN"{print $7}' jar)
# 2. reference data
curl -s -b jar $B/api/admin/reference                                                   # 200, grades [6], subjects ["math"], 9 topics, taskLocales ["uk"]
# 3. upload a source
curl -s -b jar -H "X-XSRF-TOKEN: $X" -F title='Smoke sešit' -F grade=6 -F subject=math -F file=@/tmp/blank.pdf $B/api/admin/sources   # 201, pageCount 3
S=<sourceId from 3>
# 4. refusals
curl -s -b jar -H "X-XSRF-TOKEN: $X" -F title=X -F grade=7 -F subject=math -F file=@/tmp/blank.pdf $B/api/admin/sources -o /dev/null -w '%{http_code}\n'   # 400
curl -s -b jar -H "X-XSRF-TOKEN: $X" -F title=X -F grade=6 -F subject=math -F file=@/tmp/dot.png $B/api/admin/sources -o /dev/null -w '%{http_code}\n'     # 415
# 5. create a task
curl -s -b jar -H "X-XSRF-TOKEN: $X" -H 'Content-Type: application/json' $B/api/admin/tasks -d '{"prompt":"Smoke: kolik je 6 · 2?","topicId":"6a000000-0000-4000-8000-000000000001","grade":6,"difficulty":1,"modality":"TEXT","sourceRef":{"sourceId":"'$S'","page":1,"taskNo":"1"},"parts":[{"key":"a","label":"a","spec":{"type":"INTEGER","accepted":["12"]}}],"hints":[]}'   # 201, DRAFT
T=<taskId from 5>
# 6. image, preview, tester, list, bulk publish
curl -s -b jar -H "X-XSRF-TOKEN: $X" -F file=@/tmp/dot.png $B/api/admin/tasks/$T/images       # 201, order 1
curl -s -b jar "$B/api/admin/tasks/$T/preview?locale=uk"                                      # 200, no source title/page
curl -s -b jar -H "X-XSRF-TOKEN: $X" -H 'Content-Type: application/json' $B/api/admin/tasks/$T/check -d '{"answers":{"a":"12"}}'   # 200, CORRECT
curl -s -b jar "$B/api/admin/tasks?q=SMOKE"                                                    # 200, the task
curl -s -b jar -H "X-XSRF-TOKEN: $X" -H 'Content-Type: application/json' $B/api/admin/tasks/publish -d '{"taskIds":["'$T'","00000000-0000-4000-8000-000000000000"]}'   # 200, published + "not found"
# 7. files on the volume, outside every repository
docker compose exec backend ls -R /var/lib/doucko/storage                                     # sources/<S>.pdf, images/<id>.png
# 8. clean up: delete the task, then the source (its file goes too)
curl -s -b jar -H "X-XSRF-TOKEN: $X" -X DELETE $B/api/admin/tasks/$T -o /dev/null -w '%{http_code}\n'      # 204
curl -s -b jar -H "X-XSRF-TOKEN: $X" -X DELETE $B/api/admin/sources/$S -o /dev/null -w '%{http_code}\n'    # 204
docker compose exec db psql -U doucko -d doucko -tAc "select version from flyway_schema_history order by installed_rank"   # 1, 2
```

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


## Run after the merge (2026-10-04, backend `main` at `b80321a`, frontend `main` at `6b7df30`)

**Result: PASS.** `npm run stack:up` rebuilt the stack from `main` (migration V2 applied, volume
`doucko-storage` created). Every request went through nginx on `127.0.0.1:8081`. Files were
self-generated: a blank 3-page and a blank 12-page PDF, and a 1×1 PNG (NFR-4). The admin password
came from `.env` and was never printed.

### API (scripted, Node `fetch`)

| Step | Expected | Got | OK |
|---|---|---|---|
| sign in | 204 | 204 | yes |
| reference data | 200, grades [6], 9 topics, taskLocales [uk] | 200, grades [6], 9 topics, taskLocales ["uk"] | yes |
| upload source | 201, pageCount 3 | 201, pageCount 3 | yes |
| source with grade 7 refused | 400 | 400 | yes |
| PNG as source refused | 415 | 415 | yes |
| upload without CSRF header | 403 | 403 | yes |
| source file served | 200 | 200 | yes |
| create task | 201, DRAFT | 201, DRAFT | yes |
| add image | 201 | 201 | yes |
| preview | 200, no source info | 200, no source info | yes |
| answer tester "12" | CORRECT | CORRECT | yes |
| answer tester "dvanáct" | UNPARSEABLE | UNPARSEABLE | yes |
| search finds the task | true | true | yes |
| bulk publish (1 real, 1 unknown) | 200 with a per-task result | 200 [{"taskId":"f25ca15d-da1d-495d-a75a-4d69cef26122","published":true,"reason":null},{"taskId":"00000000-0000-4000-8000-000000000000","published":false,"reason":"n | yes |
| files on the doucko-storage volume | source .pdf and image .png | /var/lib/doucko/storage: images sources /var/lib/doucko/storage/images: 559b282f-06b2-455c-83cb-cb41d82737de.png /var/lib/doucko/storage/sources: f8b5da25-a45c-4c3e-9f55-f88a637ab3f2.pdf | yes |
| delete task | 204 | 204 | yes |
| delete source | 204 | 204 | yes |
| files removed with source and task | no smoke files left | none left | yes |
| migrations applied | 1, 2 | 1, 2 | yes |
| admin password in backend log | 0 | 0 | yes |
| sign out | 204 | 204 | yes |

An earlier run of the same script failed from step 7 on because the script read `id` instead of the
contract's `sourceId` / `taskId` (a script bug, not an app fault). Its leftover source was deleted
before the passing run.

### UI (built-in browser, signed in with the real password)

| Step | Result |
|---|---|
| `/admin/sources` lists the uploaded 12-page source (uploaded through the API: the browser pane cannot drive a file picker) | 12 pages shown, with View PDF and New task |
| Viewer opened at page 7 | "Page 7 of 12", page drawn by pdf.js on a canvas (CSP holds) |
| Next page; then page 13 typed | Page 8; "Enter a whole page number in the range 1–12.", page stays 8 |
| Editor from source and page 1, keyboard only (prompt, Tab, Arrow for topic, Tab, task no, Tab ×3, answer, Enter) | Focus started in the prompt; three Tabs reached "Accepted answer 1" |
| Save with `12,5` on an INTEGER part | Refused: "This value is not a whole number. Use the DECIMAL type for it." |
| Corrected to `14`, Enter | "Task saved as draft." 15.8 s after the first keystroke, refusal detour included (NFR-8 limit 60 s); prompt cleared, source and page kept, focus back in the prompt |
| Preview | Czech child view, no source title or page |
| Tester `14` / `14,,2` | CORRECT, "Read as 14" / UNPARSEABLE, tip "Tohle číslo neumím přečíst. Napiš celé číslo, třeba 4827." |
| Tasks: status Draft, search `obdelnik` | Finds the "obdélník" prompt; row shows "UI smoke sešit, Page 1 · Task 1" and "Open source" at page 1 |
| Select the task, "Publish selected (1)" | Publish result "…: Published"; the Draft filter then shows 0 tasks |
| Sign out | Sign-in form |

Not covered in the browser: the client-side file-type and size refusals, and image attach/reorder
(the pane cannot choose files). Unit and e2e tests cover them.

Clean-up: the smoke task and source were deleted through the API (204, 204). Sources and tasks lists
are empty again, and `/var/lib/doucko/storage` holds no files. The admin password appears 0 times in
`docker compose logs backend`. The stack was left running.

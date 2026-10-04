# Slice 1 compose smoke (task 4.4), 2026-10-04 Europe/Prague

Re-run after the review-gate fixes (bind check over `docker compose config`, actuator health only,
403 problem+json admin refusals, CSRF only under `/api/admin/`, owner-only backups). Stack started
with `npm run stack:up` (db, backend, frontend healthy), stopped with `npm run stack:down`.

```
$ npm run stack:up
stack: every published port binds an allowed address

$ docker compose ps --format "{{.Service}} | {{.Ports}}"
backend | 127.0.0.1:8080->8080/tcp
db | 5432/tcp
frontend | 127.0.0.1:8081->80/tcp

$ curl -s http://127.0.0.1:8080/actuator/health
{"groups":["liveness","readiness"],"status":"UP"}

$ curl -s -o /dev/null -w "%{http_code}" http://127.0.0.1:8080/actuator/modulith
404

$ curl -si http://127.0.0.1:8080/api/child/nope
HTTP/1.1 404 
X-Content-Type-Options: nosniff
Content-Type: application/problem+json
{"detail":"No API resource exists at this path.","instance":"/api/child/nope","status":404,"title":"Resource not found"}

$ curl -si -X POST http://127.0.0.1:8080/api/nope
HTTP/1.1 404 
X-Content-Type-Options: nosniff
Content-Type: application/problem+json
{"detail":"No API resource exists at this path.","instance":"/api/nope","status":404,"title":"Resource not found"}

$ curl -si -X POST http://127.0.0.1:8080/api/admin/sources
HTTP/1.1 403 
X-Content-Type-Options: nosniff
Content-Type: application/problem+json
{"detail":"This area is not open.","instance":"/api/admin/sources","status":403,"title":"Access denied"}

$ curl -s -o /dev/null -w "%{http_code}" http://127.0.0.1:8081/
200
$ curl -s -o /dev/null -w "%{http_code}" http://127.0.0.1:8081/api/child/nope (nginx proxy)
404

$ npm run backup
backup written: ~\doucko-backups\doucko-20261004-051158-114.dump (2485 bytes)
```

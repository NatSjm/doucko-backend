# Slice 1 compose smoke (task 4.4), 2026-10-04 Europe/Prague

Stack: `npm run stack:up` (db, backend, frontend all healthy), then `npm run stack:down`.

```
$ docker compose ps --format "{{.Service}} {{.Ports}}"
backend | 127.0.0.1:8080->8080/tcp
db | 5432/tcp
frontend | 127.0.0.1:8081->80/tcp

$ curl -s http://127.0.0.1:8080/actuator/health
{"groups":["liveness","readiness"],"status":"UP"}

$ curl -si http://127.0.0.1:8080/api/child/nope
HTTP/1.1 404 
X-Content-Type-Options: nosniff
Content-Type: application/problem+json
{"detail":"No API resource exists at this path.","instance":"/api/child/nope","status":404,"title":"Resource not found"}

$ curl -s -o /dev/null -w "%{http_code}" http://127.0.0.1:8081/
200
$ curl -s -o /dev/null -w "%{http_code}" http://127.0.0.1:8081/api/child/nope (nginx proxy)
404

$ npm run backup
backup written: ~\doucko-backups\doucko-20261003-232153.dump (2485 bytes)
```

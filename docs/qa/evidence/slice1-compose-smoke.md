# Slice 1 compose smoke (task 4.4), 2026-10-04 Europe/Prague

Re-run after the DNS-rebinding fix (`5bf3df9`: the backend no longer publishes 8080; the firewall
answers only localhost, bare IPv4 hosts and `backend`) and the round-3 review fixes (actuator
discovery root off, Tomcat parser logs capped). Stack started with `npm run stack:up` (db, backend,
frontend healthy), stopped with `npm run stack:down`. The API is reached through the frontend's nginx
on 8081; health is the in-container healthcheck.

Note: `http://127.0.0.1:8081/actuator` answers 200 because nginx serves the SPA shell for every
non-`/api/` path; it never reaches the backend, whose own `/actuator` is 404 problem+json
(`DouckoBackendApplicationIT`).

```
$ docker compose ps
backend | Up 22 seconds (healthy) | 8080/tcp
db | Up 25 seconds (healthy) | 5432/tcp
frontend | Up 1 second | 127.0.0.1:8081->80/tcp

$ docker compose exec -T backend wget -qO- http://localhost:8080/actuator/health
{"groups":["liveness","readiness"],"status":"UP"}

$ curl -si http://127.0.0.1:8081/api/child/nope
HTTP/1.1 404 
Server: nginx
Content-Type: application/problem+json
X-XSS-Protection: 0
Cache-Control: no-cache, no-store, max-age=0, must-revalidate
Pragma: no-cache
Expires: 0
X-Frame-Options: DENY
X-Content-Type-Options: nosniff
Referrer-Policy: no-referrer
Content-Security-Policy: default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data: blob:; font-src 'self' data:; connect-src 'self'; object-src 'none'; frame-ancestors 'none'; base-uri 'self'; form-action 'self'
{"detail":"No API resource exists at this path.","instance":"/api/child/nope","status":404,"title":"Resource not found"}


$ curl -si -X POST http://127.0.0.1:8081/api/admin/sources
HTTP/1.1 403 
Content-Type: application/problem+json
{"detail":"This area is not open.","instance":"/api/admin/sources","status":403,"title":"Access denied"}

$ curl -s -o /dev/null -w "%{http_code}" http://127.0.0.1:8081/actuator
200
$ curl -s -m 3 http://127.0.0.1:8080/actuator/health
(no connection: port not published)
$ curl -s -o /dev/null -w "%{http_code}" -H "Host: evil.example" http://127.0.0.1:8081/api/child/nope
421
$ docker compose exec -T backend wget -qS -O- --header "Host: evil.example" http://localhost:8080/api/child/nope
  HTTP/1.1 400 
wget: server returned error: HTTP/1.1 400 
$ npm run backup
backup written: C:\Users\user\doucko-backups\doucko-20261004-122713-886.dump (2485 bytes)
```

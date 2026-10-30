# Coffee Code #2 — Edición Banca

**"Tu core bancario es lento, tu servicio no tiene por qué serlo: WebFlux vs. Virtual Threads en Java 25"**

Laboratorio ejecutable que implementa **el mismo endpoint bancario dos veces**, una con Spring WebFlux y otra con Spring MVC sobre Virtual Threads, contra el mismo core bancario simulado, con los mismos timeouts, pools y contrato. Así se pueden comparar código, comportamiento ante fallos y rendimiento sin trampas.

```text
              ┌───────────────────────────────────┐
  k6 / curl ─▶│ banking-api-webflux         :8081 │──┐
              └───────────────────────────────────┘  │ 3 llamadas concurrentes
              ┌───────────────────────────────────┐  ├─────────▶ downstream-mock-service :8090
  k6 / curl ─▶│ banking-api-virtual-threads :8082 │──┘           (latencia 100–800 ms, 5xx, timeouts)
              └───────────────────────────────────┘
```

## Requisitos

| Herramienta | Versión | Obligatoria |
|---|---|---|
| JDK | **25** (probado con Temurin 25.0.4) | Sí |
| Maven | **3.9+** (probado con 3.9.9); o el wrapper incluido `./mvnw` | Sí (o el wrapper) |
| k6 | v2.x (probado con v2.3.0) | Solo para las pruebas de carga |
| Docker Desktop | probado con 27.0 | No: solo para Grafana, Prometheus y Tempo (opcional) |

Sin credenciales, sin APIs externas y sin infraestructura corporativa.

## Inicio rápido

```bash
# 1. Compilar y ejecutar todos los tests (67 tests, ~1 min). Con el wrapper no hace falta Maven instalado:
#    ./mvnw clean verify   (Windows: mvnw.cmd clean verify)
mvn clean verify

# 2. Levantar los tres servicios (mismos flags de JVM: -Xms512m -Xmx512m -XX:+UseG1GC)
./scripts/start-all.sh                       # Windows PowerShell: .\scripts\start-all.ps1
PROFILE=demo ./scripts/start-all.sh          # modo charla: logs DEBUG + endpoints de demo

# 3. Probar
curl -s localhost:8081/api/clients/C0001/financial-summary     # WebFlux
curl -s localhost:8082/api/clients/C0001/financial-summary     # Virtual Threads
./scripts/compare-contracts.sh               # compara ambas APIs byte a byte (8 escenarios)

# 4. Carga (con k6 instalado)
k6 run -e BASE_URL=http://localhost:8081 -e LABEL=webflux -e SCENARIO=medium load-tests/financial-summary.js
./load-tests/run-comparison.sh medium 3      # ambas APIs, 3 repeticiones, orden alterno + calentamiento

# 5. Parar
./scripts/stop-all.sh                        # PowerShell: .\scripts\stop-all.ps1
```

### Ejecutar cada servicio por separado (Maven o IntelliJ)

```bash
mvn -pl downstream-mock-service spring-boot:run
mvn -pl banking-api-webflux spring-boot:run
mvn -pl banking-api-virtual-threads spring-boot:run
# con perfil demo:
mvn -pl banking-api-webflux spring-boot:run -Dspring-boot.run.profiles=demo
```

En IntelliJ IDEA: *File → Open* sobre el `pom.xml` raíz, con el SDK del proyecto en 25 y el nivel de lenguaje en 25. Ejecutar las clases `DownstreamMockApplication`, `BankingWebfluxApplication` y `BankingVirtualThreadsApplication`. Para comparar en igualdad de condiciones, añadir `-Xms512m -Xmx512m -XX:+UseG1GC` en *VM options*.

## Endpoints

| Servicio | Endpoint |
|---|---|
| APIs (8081 / 8082) | `GET /api/clients/{clientId}/financial-summary` (`clientId` = `C####`) |
| | `GET /actuator/health`, `/actuator/metrics`, `/actuator/prometheus`, `/actuator/threaddump` |
| | `GET /api/demo/legacy-score/{clientId}?mode=...` (solo con el perfil `demo`) |
| Mock (8090) | `GET /mock/clients/{id}`, `/mock/balances/{id}`, `/mock/movements/{id}?limit=N` |
| | `GET/PUT /mock/admin/scenario`, `POST /mock/admin/scenario/reset` |

### Puertos

| Servicio | Puerto | URL |
|---|---|---|
| downstream-mock-service | 8090 | http://localhost:8090/mock/clients/C0001 |
| banking-api-webflux | 8081 | http://localhost:8081/api/clients/C0001/financial-summary |
| banking-api-virtual-threads | 8082 | http://localhost:8082/api/clients/C0001/financial-summary |
| Grafana (opcional, Docker) | 3000 | http://localhost:3000/d/webflux-vs-vt |
| Prometheus (opcional, Docker) | 9090 | http://localhost:9090 |
| Tempo (opcional, Docker) | 3200 (consulta), 4317/4318 (OTLP) | http://localhost:3200/ready |

### Colecciones de peticiones

| Herramienta | Ubicación | Uso |
|---|---|---|
| **Bruno** | `requests/bruno/` | *Open Collection* → carpeta `requests/bruno`, y elegir el entorno `webflux` o `virtual-threads` (arriba a la derecha). Cada petición trae aserciones: *Run* sobre la colección la ejecuta entera como prueba. CLI: `bru run -r --env webflux` desde esa carpeta |
| IntelliJ HTTP Client | `requests/coffee-code.http` | Botón ▶ junto a cada petición |
| Postman | `requests/coffee-code.postman_collection.json` | Importar y cambiar la variable `api` |

La colección de Bruno tiene 4 carpetas: *Financial summary* (éxito y todos los fallos), *Observabilidad* (health, métricas, pool, hilos y Grafana), *Demo event loop* (requiere el perfil `demo`) y *Mock* (cambiar el escenario del core para las **dos** APIs y restaurarlo).

Escenarios deterministas para la demo: `C0404` (404), `C0500` (502), `C0504` (504 por timeout), `C0599` (200 degradado), `C0800` (latencia de 1,5 s). 

## Estructura

```text
.
├── pom.xml                          parent: Spring Boot 4.1.1, Java 25, enforcer
├── downstream-mock-service/         core bancario simulado (WebFlux, latencia no bloqueante)
├── banking-api-webflux/             Proyecto A: WebFlux + Reactor + WebClient (Reactor Netty)
├── banking-api-virtual-threads/     Proyecto B: MVC + Virtual Threads + RestClient (Apache HttpClient 5)
├── contract-test-support/           (test) core falso + JSON de contrato compartidos por A y B
├── load-tests/                      k6: financial-summary.js, blocking-demo.js, run-comparison.{sh,ps1}
├── scripts/                         start-all, stop-all, compare-contracts (.sh y .ps1)
├── requests/                        colección Bruno, .http (IntelliJ) y colección Postman
├── observability/                   Prometheus, Tempo y Grafana (dashboard aprovisionado) — opcional
├── docker-compose.yml               pila de observabilidad + k6 en contenedor (opcional)
└── docs/
    ├── architecture.md              diagramas Mermaid, contrato, versiones, comparabilidad, matriz comparativa
    ├── troubleshooting.md           problemas reproducibles: síntomas, causa, diagnóstico y solución
    ├── benchmark-guide.md           metodología, escenarios, resultados medidos y limitaciones
    ├── observability.md             Grafana: métricas y trazas en tiempo real (opcional, Docker)
    └── presentation-guide.md        guion de la demo de 30 minutos
```

## Documentación

- [Arquitectura, contrato, versiones y matriz comparativa](docs/architecture.md)
- [Troubleshooting](docs/troubleshooting.md)
- [Guía de benchmark y resultados](docs/benchmark-guide.md)
- [Observabilidad: Grafana, métricas y trazas en tiempo real](docs/observability.md)
- [Guion de la presentación](docs/presentation-guide.md)

## Observabilidad (sin Docker)

```bash
curl -s localhost:8081/actuator/metrics/http.server.requests
curl -s localhost:8081/actuator/prometheus | grep reactor_netty_connection_provider     # pool WebFlux
curl -s localhost:8082/actuator/prometheus | grep httpcomponents_httpclient_pool         # pool VT
jcmd <pid> Thread.dump_to_file -format=json hilos.json                                   # incluye hilos virtuales
```

## Grafana: métricas y trazas en tiempo real (opcional, Docker)

```bash
docker compose up -d                                   # Grafana :3000 · Prometheus :9090 · Tempo :3200
PROFILE=observability ./scripts/start-all.sh           # APIs con trazas OTLP hacia Tempo
export K6_PROMETHEUS_RW_SERVER_URL=http://localhost:9090/api/v1/write K6_PROMETHEUS_RW_TREND_STATS="p(95),p(99),avg"
k6 run -o experimental-prometheus-rw -e BASE_URL=http://localhost:8081 -e LABEL=webflux -e SCENARIO=warmup load-tests/financial-summary.js
k6 run -o experimental-prometheus-rw -e BASE_URL=http://localhost:8081 -e LABEL=webflux -e SCENARIO=high load-tests/financial-summary.js
# abrir http://localhost:3000 (sin login) · al terminar: docker compose down
```

El dashboard superpone las dos APIs (WebFlux en azul, VT en naranja): req/s, p95/p99 con exemplars enlazados a trazas, peticiones en vuelo, hilos, pool de conexiones, heap, GC y las métricas de k6. El tracing está **apagado por defecto** para no alterar el benchmark. Detalles en [docs/observability.md](docs/observability.md).

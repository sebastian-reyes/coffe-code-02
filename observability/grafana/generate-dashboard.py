"""Genera observability/grafana/dashboards/webflux-vs-virtual-threads.json.

    python observability/grafana/generate-dashboard.py

El JSON generado se versiona; este script solo existe para mantener los paneles consistentes
(mismos colores por API, mismas unidades) si se añaden o cambian consultas.
"""
import json
import pathlib

URI = '/api/clients/{clientId}/financial-summary'
SRV = f'uri="{URI}"'
PROM = {"type": "prometheus", "uid": "prometheus"}
TEMPO = {"type": "tempo", "uid": "tempo"}

# Mismo color para cada API en todos los paneles.
COLOR_OVERRIDES = [
    {"matcher": {"id": "byRegexp", "options": ".*webflux.*"},
     "properties": [{"id": "color", "value": {"mode": "fixed", "fixedColor": "blue"}}]},
    {"matcher": {"id": "byRegexp", "options": ".*virtual-threads.*"},
     "properties": [{"id": "color", "value": {"mode": "fixed", "fixedColor": "orange"}}]},
]

panels = []
next_id = 1
y = 0


def row(title):
    global next_id, y
    panels.append({"type": "row", "title": title, "id": next_id, "collapsed": False,
                   "gridPos": {"h": 1, "w": 24, "x": 0, "y": y}, "panels": []})
    next_id += 1
    y += 1


def ts(title, targets, x, w=8, h=8, unit="short", description="", exemplar=False, stack=False, fill=10,
       legend="list"):
    global next_id
    panel = {
        "type": "timeseries", "title": title, "id": next_id, "description": description,
        "datasource": PROM,
        "gridPos": {"h": h, "w": w, "x": x, "y": y},
        "fieldConfig": {
            "defaults": {"unit": unit, "min": 0,
                         "custom": {"lineWidth": 2, "fillOpacity": fill, "showPoints": "never",
                                    "stacking": {"mode": "normal" if stack else "none"}}},
            "overrides": COLOR_OVERRIDES,
        },
        "options": {"legend": {"displayMode": legend, "placement": "bottom"},
                    "tooltip": {"mode": "multi", "sort": "desc"}},
        "targets": [{"refId": chr(65 + i), "datasource": PROM, "expr": expr, "legendFormat": legend,
                     "exemplar": exemplar, "range": True}
                    for i, (expr, legend) in enumerate(targets)],
    }
    panels.append(panel)
    next_id += 1


def advance(h=8):
    global y
    y += h


# --------------------------------------------------------------------------------------------
row("Petición de negocio: GET /api/clients/{clientId}/financial-summary (lado servidor)")
ts("Peticiones por segundo", [
    (f'sum by (implementation) (rate(http_server_requests_seconds_count{{{SRV}}}[$__rate_interval]))', "{{implementation}}"),
], x=0, unit="reqps")
ts("Latencia p95 (puntos = exemplars: clic para abrir la traza)", [
    (f'histogram_quantile(0.95, sum by (le, implementation) (rate(http_server_requests_seconds_bucket{{{SRV}}}[$__rate_interval])))', "p95 {{implementation}}"),
    (f'histogram_quantile(0.99, sum by (le, implementation) (rate(http_server_requests_seconds_bucket{{{SRV}}}[$__rate_interval])))', "p99 {{implementation}}"),
], x=8, unit="s", exemplar=True, fill=0,
   description="Exemplars disponibles solo con el perfil observability (tracing activo).")
ts("Respuestas con error por status", [
    (f'sum by (implementation, status) (rate(http_server_requests_seconds_count{{{SRV}, status!="200"}}[$__rate_interval]))', "{{implementation}} {{status}}"),
], x=16, unit="reqps", stack=True)
advance()

ts("Peticiones en vuelo (concurrencia real)", [
    # La plantilla de la URI aún no se conoce al empezar la petición (uri="UNKNOWN"): no se filtra por uri.
    # /actuator queda fuera por el ObservationPredicate de cada API.
    ('sum by (implementation) (http_server_requests_active_seconds_gcount{implementation=~"webflux|virtual-threads"})', "{{implementation}}"),
], x=0, description="Ley de Little: en vuelo ≈ req/s × latencia. Con 300 req/s y ~0,63 s ≈ 190 peticiones a la vez.")
ts("Hilos de plataforma vivos", [
    ('sum by (implementation) (jvm_threads_live_threads{implementation=~"webflux|virtual-threads"})', "{{implementation}}"),
], x=8, description="Los hilos virtuales NO cuentan aquí. Aunque haya cientos de peticiones en vuelo, ambas usan decenas de hilos de plataforma.")
ts("CPU del proceso", [
    ('max by (implementation) (process_cpu_usage{implementation=~"webflux|virtual-threads"})', "{{implementation}}"),
], x=16, unit="percentunit", description="Fracción de TODA la máquina (todos los cores).")
advance()

# --------------------------------------------------------------------------------------------
row("Core bancario (downstream) y pool de conexiones")
ts("Latencia p95 de cada llamada al core", [
    ('histogram_quantile(0.95, sum by (le, implementation, uri) (rate(http_client_requests_seconds_bucket{uri=~"/mock/.*"}[$__rate_interval])))', "{{implementation}} {{uri}}"),
], x=0, unit="s", fill=0, legend="table", h=10)
ts("Conexiones del pool en uso (máx 500)", [
    ('sum by (implementation) (reactor_netty_connection_provider_active_connections{name="core-banking"})', "{{implementation}}"),
    ('sum by (implementation) (httpcomponents_httpclient_pool_total_connections{httpclient="core-banking", state="leased"})', "{{implementation}}"),
], x=8, h=10, description="Reactor Netty (webflux) y Apache HttpClient 5 (virtual-threads). El límite lo pone el pool, no los hilos.")
ts("Llamadas esperando conexión libre (saturación)", [
    ('sum by (implementation) (reactor_netty_connection_provider_pending_connections{name="core-banking"})', "{{implementation}}"),
    ('sum by (implementation) (httpcomponents_httpclient_pool_total_pending{httpclient="core-banking"})', "{{implementation}}"),
], x=16, h=10, description="> 0 de forma sostenida = pool saturado: tras pending-acquire-timeout (1 s) se responde 503 CONNECTION_POOL_EXHAUSTED.")
advance(10)

# --------------------------------------------------------------------------------------------
row("JVM")
ts("Heap usado", [
    ('sum by (implementation) (jvm_memory_used_bytes{area="heap", implementation=~"webflux|virtual-threads"})', "{{implementation}}"),
], x=0, unit="bytes", description="Incluye basura aún no recolectada (diente de sierra del GC). Las pilas de los hilos virtuales aparcados viven en el heap.")
ts("Tiempo en pausas de GC", [
    ('sum by (implementation) (rate(jvm_gc_pause_seconds_sum{implementation=~"webflux|virtual-threads"}[$__rate_interval]))', "{{implementation}}"),
], x=8, unit="percentunit")
ts("Asignación de memoria", [
    ('sum by (implementation) (rate(jvm_gc_memory_allocated_bytes_total{implementation=~"webflux|virtual-threads"}[$__rate_interval]))', "{{implementation}}"),
], x=16, unit="Bps")
advance()

# --------------------------------------------------------------------------------------------
row("k6 (lado cliente) — requiere k6 run -o experimental-prometheus-rw")
ts("k6: peticiones por segundo", [
    ('sum by (api) (rate(k6_summary_requests_total[$__rate_interval]))', "{{api}}"),
], x=0, unit="reqps")
ts("k6: latencia vista por el cliente", [
    ('max by (api) (k6_summary_duration_p95)', "p95 {{api}}"),
    ('max by (api) (k6_summary_duration_p99)', "p99 {{api}}"),
], x=8, unit="s", fill=0, description="Incluye la red y la cola del servidor: es la latencia que percibe el usuario. Ventana móvil de k6 (no por intervalo).")
ts("k6: tasa de errores", [
    ('max by (api) (k6_summary_errors_rate)', "{{api}}"),
], x=16, unit="percentunit", description="Fracción acumulada de respuestas != 200 desde el inicio de la corrida.")
advance()

# --------------------------------------------------------------------------------------------
row("Trazas (Tempo) — requiere el perfil observability")
panels.append({
    "type": "table", "title": "Últimas trazas de financial-summary (clic en el traceID)", "id": next_id,
    "datasource": TEMPO,
    "gridPos": {"h": 10, "w": 24, "x": 0, "y": y},
    "targets": [{"refId": "A", "datasource": TEMPO, "queryType": "traceql", "limit": 20,
                 "query": '{ span.http.url =~ ".*financial-summary.*" || span.uri =~ ".*financial-summary.*" || name =~ ".*financial-summary.*" }'}],
    # Oculta la columna interna "nested" (spans en JSON) que devuelve la búsqueda de Tempo.
    "transformations": [
        {"id": "organize", "options": {"excludeByName": {"nested": True}}},
        # Tempo no garantiza el orden de la búsqueda: más recientes primero.
        {"id": "sortBy", "options": {"sort": [{"field": "Start time", "desc": True}]}},
    ],
})
next_id += 1
advance(10)

dashboard = {
    "uid": "webflux-vs-vt",
    "title": "Coffee Code #2 · WebFlux vs Virtual Threads",
    "description": "Comparación en tiempo real de banking-api-webflux (azul) y banking-api-virtual-threads (naranja).",
    "tags": ["coffee-code", "webflux", "virtual-threads"],
    "timezone": "browser",
    "refresh": "5s",
    "time": {"from": "now-15m", "to": "now"},
    "schemaVersion": 39,
    "editable": True,
    "graphTooltip": 1,
    "panels": panels,
}

out = pathlib.Path(__file__).parent / "dashboards" / "webflux-vs-virtual-threads.json"
out.parent.mkdir(parents=True, exist_ok=True)
out.write_text(json.dumps(dashboard, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
print(f"{out} ({len(panels)} paneles)")

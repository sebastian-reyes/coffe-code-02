// Prueba de carga de GET /api/clients/{clientId}/financial-summary.
// Mismo script para ambas APIs: solo cambian BASE_URL y LABEL.
//
//   k6 run -e BASE_URL=http://localhost:8081 -e LABEL=webflux -e SCENARIO=medium load-tests/financial-summary.js
//   k6 run -e BASE_URL=http://localhost:8082 -e LABEL=virtual-threads -e SCENARIO=medium load-tests/financial-summary.js
//
// Variables:
//   BASE_URL     API a probar (obligatoria)
//   LABEL        nombre de la API en los resultados (webflux | virtual-threads)
//   SCENARIO     warmup | low | medium | high | stress | slow-downstream | failing-downstream
//   MOCK_URL     URL del downstream-mock-service (default http://localhost:8090)
//   RATE         sobrescribe las peticiones/segundo del escenario
//   DURATION     sobrescribe la duración del escenario (p. ej. 30s, 2m)
//   RESULTS_DIR  carpeta de resultados (default load-tests/results)
//   RUN_ID       sufijo para distinguir repeticiones (default: timestamp)

import http from 'k6/http';
import { check } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL;
const LABEL = __ENV.LABEL || 'api';
const SCENARIO = __ENV.SCENARIO || 'low';
const MOCK_URL = __ENV.MOCK_URL || 'http://localhost:8090';
const RESULTS_DIR = __ENV.RESULTS_DIR || 'load-tests/results';
const RUN_ID = __ENV.RUN_ID || new Date().toISOString().replace(/[:.]/g, '-');

if (!BASE_URL) {
  throw new Error('Falta BASE_URL, p. ej. -e BASE_URL=http://localhost:8081');
}

// Escenario del mock: se aplica en setup() y se restaura en teardown().
const DEFAULT_MOCK = { latencyMinMs: 100, latencyMaxMs: 800, errorRate: 0.0, timeoutRate: 0.0, hangMs: 30000 };

// Tasa constante de llegada (modelo abierto): k6 no espera a que termine una petición para lanzar la
// siguiente, así que la latencia medida no se "auto-regula" (evita coordinated omission).
const PROFILES = {
  'warmup':             { rate: 50,  duration: '30s', mock: DEFAULT_MOCK },
  'low':                { rate: 50,  duration: '60s', mock: DEFAULT_MOCK },
  'medium':             { rate: 150, duration: '60s', mock: DEFAULT_MOCK },
  'high':               { rate: 300, duration: '60s', mock: DEFAULT_MOCK },
  // Rampa 100 -> 800 req/s: busca el punto en que se satura el pool de conexiones (500).
  'stress':             { stages: [
                            { target: 100, duration: '20s' },
                            { target: 800, duration: '100s' },
                            { target: 800, duration: '20s' },
                          ], mock: DEFAULT_MOCK },
  // Latencia 1000-2500 ms: parte de las llamadas supera el response-timeout (2 s) -> 504.
  'slow-downstream':    { rate: 50,  duration: '60s',
                          mock: { ...DEFAULT_MOCK, latencyMinMs: 1000, latencyMaxMs: 2500 } },
  // 10% de 503 y 2% de respuestas colgadas en cada endpoint del core.
  'failing-downstream': { rate: 100, duration: '60s',
                          mock: { ...DEFAULT_MOCK, errorRate: 0.10, timeoutRate: 0.02 } },
};

const profile = PROFILES[SCENARIO];
if (!profile) {
  throw new Error(`SCENARIO desconocido: ${SCENARIO}. Opciones: ${Object.keys(PROFILES).join(', ')}`);
}

const rate = Number(__ENV.RATE || profile.rate || 100);
const duration = __ENV.DURATION || profile.duration;
const peakRate = profile.stages ? Math.max(...profile.stages.map((s) => s.target)) : rate;
const totalSeconds = profile.stages
  ? profile.stages.reduce((sum, s) => sum + parseInt(s.duration, 10), 0)
  : parseInt(duration, 10) * (duration.endsWith('m') ? 60 : 1);

const loadScenario = profile.stages
  ? { executor: 'ramping-arrival-rate', startRate: 10, timeUnit: '1s', stages: profile.stages }
  : { executor: 'constant-arrival-rate', rate, timeUnit: '1s', duration };

export const options = {
  // Etiqueta todas las métricas con la API probada (se ve como label "api" en Prometheus/Grafana).
  tags: { api: LABEL, scenario_name: SCENARIO },
  discardResponseBodies: false,
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
  scenarios: {
    load: {
      ...loadScenario,
      exec: 'load',
      // Suficientes VUs para la latencia esperada (~0.5-2.5 s) sin que k6 limite la tasa.
      preAllocatedVUs: Math.ceil(peakRate * 1.5),
      maxVUs: Math.ceil(peakRate * 4),
      gracefulStop: '5s',
    },
    // Muestrea CPU, heap e hilos de la API cada segundo vía Actuator.
    sampler: {
      executor: 'constant-arrival-rate',
      exec: 'sample',
      rate: 1,
      timeUnit: '1s',
      duration: `${totalSeconds}s`,
      preAllocatedVUs: 2,
      maxVUs: 4,
    },
  },
  thresholds: {
    // Umbrales informativos (no abortan): ayudan a ver de un vistazo si el escenario se degradó.
    summary_errors: [{ threshold: 'rate<0.01', abortOnFail: false }],
    summary_duration: [{ threshold: 'p(95)<1500', abortOnFail: false }],
  },
};

// Métricas solo de la petición de negocio (el sampler no se mezcla).
const summaryDuration = new Trend('summary_duration', true);
const summaryRequests = new Counter('summary_requests');
const summaryErrors = new Rate('summary_errors');
const summaryTimeouts = new Counter('summary_timeouts');
const summaryDegraded = new Counter('summary_degraded');
const status2xx = new Counter('status_2xx');
const status4xx = new Counter('status_4xx');
const status502 = new Counter('status_502');
const status503 = new Counter('status_503');
const status504 = new Counter('status_504');
const statusOther = new Counter('status_other');

const apiCpu = new Trend('api_cpu_usage_pct');
const apiHeapMb = new Trend('api_heap_used_mb');
const apiThreads = new Trend('api_threads_live');

export function setup() {
  const res = http.put(`${MOCK_URL}/mock/admin/scenario`, JSON.stringify(profile.mock), {
    headers: { 'Content-Type': 'application/json' },
  });
  if (res.status !== 200) {
    throw new Error(`No se pudo configurar el mock (${MOCK_URL}): status ${res.status}`);
  }
  const target = http.get(`${BASE_URL}/actuator/health`);
  console.log(`[${LABEL}] escenario=${SCENARIO} mock=${JSON.stringify(profile.mock)} health=${target.status} ` +
    `impl=${target.headers['X-Implementation']}`);
}

export function load() {
  // IDs C1000..C8999: nunca coinciden con los IDs mágicos del mock (C0404, C0500, ...).
  const clientId = `C${1000 + Math.floor(Math.random() * 8000)}`;
  const res = http.get(`${BASE_URL}/api/clients/${clientId}/financial-summary`, {
    tags: { name: 'financial-summary' },
    timeout: '10s',
  });

  summaryRequests.add(1);
  summaryDuration.add(res.timings.duration);
  summaryErrors.add(res.status !== 200);

  if (res.status === 200) {
    status2xx.add(1);
    if (res.body && res.body.includes('MOVEMENTS_UNAVAILABLE')) {
      summaryDegraded.add(1);
    }
  } else if (res.status >= 400 && res.status < 500) {
    status4xx.add(1);
  } else if (res.status === 502) {
    status502.add(1);
  } else if (res.status === 503) {
    status503.add(1);
  } else if (res.status === 504) {
    status504.add(1);
    summaryTimeouts.add(1);
  } else {
    statusOther.add(1);   // incluye status 0 = timeout/conexión fallida del propio k6
    if (res.status === 0) {
      summaryTimeouts.add(1);
    }
  }

  check(res, {
    'status es 200': (r) => r.status === 200,
    'contrato: clientId presente': (r) => r.status !== 200 || r.json('clientId') === clientId,
  });
}

function metric(name, query = '') {
  const res = http.get(`${BASE_URL}/actuator/metrics/${name}${query}`, { tags: { name: 'sampler' } });
  if (res.status !== 200) {
    return null;
  }
  const value = res.json('measurements.0.value');
  return typeof value === 'number' ? value : null;
}

export function sample() {
  const cpu = metric('process.cpu.usage');
  const heap = metric('jvm.memory.used', '?tag=area:heap');
  const threads = metric('jvm.threads.live');
  if (cpu !== null) apiCpu.add(cpu * 100);
  if (heap !== null) apiHeapMb.add(heap / (1024 * 1024));
  if (threads !== null) apiThreads.add(threads);
}

export function teardown() {
  http.post(`${MOCK_URL}/mock/admin/scenario/reset`);
}

// ---------------------------------------------------------------------------------------------
// Resumen: tabla en consola + JSON completo + CSV de una fila (fácil de concatenar entre corridas).
// ---------------------------------------------------------------------------------------------
function val(data, metricName, stat) {
  const m = data.metrics[metricName];
  if (!m || !m.values) return 0;
  const v = m.values[stat];
  return typeof v === 'number' ? v : 0;
}

export function handleSummary(data) {
  const row = {
    label: LABEL,
    scenario: SCENARIO,
    run_id: RUN_ID,
    target_rps: profile.stages ? `ramp-${peakRate}` : rate,
    requests: val(data, 'summary_requests', 'count'),
    rps: val(data, 'summary_requests', 'rate'),
    avg_ms: val(data, 'summary_duration', 'avg'),
    p50_ms: val(data, 'summary_duration', 'med'),
    p95_ms: val(data, 'summary_duration', 'p(95)'),
    p99_ms: val(data, 'summary_duration', 'p(99)'),
    max_ms: val(data, 'summary_duration', 'max'),
    error_rate: val(data, 'summary_errors', 'rate'),
    timeouts: val(data, 'summary_timeouts', 'count'),
    s200: val(data, 'status_2xx', 'count'),
    s502: val(data, 'status_502', 'count'),
    s503: val(data, 'status_503', 'count'),
    s504: val(data, 'status_504', 'count'),
    other: val(data, 'status_other', 'count'),
    degraded: val(data, 'summary_degraded', 'count'),
    dropped_iterations: val(data, 'dropped_iterations', 'count'),
    cpu_avg_pct: val(data, 'api_cpu_usage_pct', 'avg'),
    cpu_max_pct: val(data, 'api_cpu_usage_pct', 'max'),
    heap_max_mb: val(data, 'api_heap_used_mb', 'max'),
    threads_max: val(data, 'api_threads_live', 'max'),
  };

  const fmt = (v) => (typeof v === 'number' ? (Number.isInteger(v) ? String(v) : v.toFixed(2)) : String(v));
  const header = Object.keys(row).join(',');
  const csv = `${header}\n${Object.values(row).map(fmt).join(',')}\n`;
  const text = [
    '',
    `===== ${LABEL} / ${SCENARIO} (run ${RUN_ID}) =====`,
    `requests=${row.requests}  rps=${fmt(row.rps)}  dropped_iterations=${row.dropped_iterations}`,
    `latencia ms: avg=${fmt(row.avg_ms)} p50=${fmt(row.p50_ms)} p95=${fmt(row.p95_ms)} p99=${fmt(row.p99_ms)} max=${fmt(row.max_ms)}`,
    `errores=${(row.error_rate * 100).toFixed(2)}%  timeouts=${row.timeouts}  200=${row.s200} 502=${row.s502} 503=${row.s503} 504=${row.s504} otros=${row.other}  degradadas=${row.degraded}`,
    `API: cpu avg=${fmt(row.cpu_avg_pct)}% max=${fmt(row.cpu_max_pct)}%  heap max=${fmt(row.heap_max_mb)} MB  hilos plataforma max=${fmt(row.threads_max)}`,
    '',
  ].join('\n');

  const base = `${RESULTS_DIR}/${LABEL}-${SCENARIO}-${RUN_ID}`;
  return {
    stdout: text,
    [`${base}.json`]: JSON.stringify(data, null, 2),
    [`${base}.csv`]: csv,
  };
}

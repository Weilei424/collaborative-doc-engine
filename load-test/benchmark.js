import http from 'k6/http';
import ws from 'k6/ws';
import { check, fail, sleep } from 'k6';
import { Trend, Counter, Rate } from 'k6/metrics';
import { randomBytes } from 'k6/crypto';

// ---------- Configuration ----------
const BASE_URL    = __ENV.BASE_URL    || 'http://localhost:8080';
const WS_BASE_URL = __ENV.WS_BASE_URL || 'ws://localhost:8080';
const STEADY_VUS  = parseInt(__ENV.K6_STEADY_VUS || '30');
const STRESS_VUS  = parseInt(__ENV.K6_STRESS_VUS || '100');

const OPS_PER_ITERATION  = 10;
const THINK_TIME_MS      = 200;
const SESSION_TIMEOUT_MS = 30000;
const SUB_READY_DELAY_MS = 200; // mirrors E2E test subscription stabilisation pause

// ---------- Custom metrics ----------
const operationLatency   = new Trend('operation_latency', true);
const operationsAccepted = new Counter('operations_accepted');
const operationErrorRate = new Rate('operation_error_rate');

// ---------- Options ----------
export const options = {
  stages: [
    { duration: '30s', target: STEADY_VUS },  // ramp-up / JVM warm
    { duration: '60s', target: STEADY_VUS },  // steady-state measurement
    { duration: '30s', target: STRESS_VUS },  // stress ramp
    { duration: '60s', target: STRESS_VUS },  // peak measurement
    { duration: '10s', target: 0 },           // ramp-down
  ],
  thresholds: {
    'operation_latency': ['p(95)<500'],   // 500 ms p95 budget
    'operation_error_rate': ['rate<0.01'], // < 1% errors
  },
};

// ---------- Helpers ----------

/**
 * Returns a UUID v4 string using k6's crypto module.
 */
function uuidv4() {
  const b = new Uint8Array(randomBytes(16));
  b[6] = (b[6] & 0x0f) | 0x40;
  b[8] = (b[8] & 0x3f) | 0x80;
  const hex = [...b].map(x => x.toString(16).padStart(2, '0')).join('');
  return hex.slice(0, 8) + '-' + hex.slice(8, 12) + '-' +
         hex.slice(12, 16) + '-' + hex.slice(16, 20) + '-' + hex.slice(20);
}

/**
 * Returns a lowercase alphanumeric string of the given length.
 */
function randomAlphanumeric(len) {
  const chars = 'abcdefghijklmnopqrstuvwxyz0123456789';
  return [...new Uint8Array(randomBytes(len))]
    .map(x => chars[x % chars.length])
    .join('');
}

/**
 * Encodes a STOMP 1.2 frame.
 * body is an empty string for command-only frames (CONNECT, SUBSCRIBE, DISCONNECT).
 */
function encodeStompFrame(command, headers, body) {
  body = body || '';
  let frame = command + '\n';
  for (const key of Object.keys(headers)) {
    frame += key + ':' + headers[key] + '\n';
  }
  return frame + '\n' + body + '\0';
}

/**
 * Sends a STOMP frame wrapped in the SockJS client message format.
 * Spring's SockJS endpoint requires client messages as a JSON array: ["<frame>"].
 */
function sockjsSend(socket, stompFrame) {
  socket.send(JSON.stringify([stompFrame]));
}


// ---------- Setup (runs once before VUs start) ----------
export function setup() {
  const users = [];
  for (let i = 0; i < STRESS_VUS; i++) {
    const username = 'bench' + randomAlphanumeric(10);
    const res = http.post(
      BASE_URL + '/api/auth/register',
      JSON.stringify({
        username: username,
        email: username + '@bench.test',
        password: 'password123',
      }),
      { headers: { 'Content-Type': 'application/json' } }
    );
    if (res.status !== 201) {
      fail('User registration failed at index ' + i + ': HTTP ' + res.status + ' ' + res.body);
    }
    users.push(res.json('token'));
  }
  return { users: users };
}

// ---------- Helper: send the next INSERT_TEXT operation ----------
function submitNext(socket, docId, state) {
  state.t0 = Date.now();
  sockjsSend(socket, encodeStompFrame(
    'SEND',
    {
      'destination': '/app/documents/' + docId + '/operations.submit',
      'content-type': 'application/json',
    },
    JSON.stringify({
      operationId: uuidv4(),
      baseVersion: state.baseVersion,
      operationType: 'INSERT_TEXT',
      payload: { path: [0], offset: state.opCount, text: 'x' },
    })
  ));
}

export default function(data) {
  // VU numbers are 1-indexed; modulo keeps within the pre-registered pool
  const jwt = data.users[(__VU - 1) % data.users.length];

  const docRes = http.post(
    BASE_URL + '/api/documents',
    JSON.stringify({
      title: 'bench-' + uuidv4(),
      content: '{"children":[{"type":"paragraph","text":"","children":[]}]}',
      visibility: 'PRIVATE',
    }),
    { headers: {
        'Content-Type': 'application/json',
        'Authorization': 'Bearer ' + jwt,
    }}
  );

  if (!check(docRes, { 'document created (201)': r => r.status === 201 })) {
    for (let i = 0; i < OPS_PER_ITERATION; i++) {
      operationErrorRate.add(1);
    }
    sleep(1); // throttle: prevent VUs from spinning at maximum speed on failures
    return;
  }

  const docId = docRes.json('id');

  // SockJS raw WebSocket transport URL:
  //   /ws/{server-id}/{session-id}/websocket?token=...
  // server-id: any 1-3 digit string; session-id: random alphanumeric.
  // JWT goes in the query string - the browser WS API does not support
  // custom headers on the HTTP upgrade request, and JwtHandshakeInterceptor
  // reads request.getParameter("token").
  const wsUrl = WS_BASE_URL + '/ws/100/' + randomAlphanumeric(20) + '/websocket?token=' + jwt;

  const state = {
    phase: 'handshake',  // 'handshake' | 'subscribed' | 'operating' | 'done'
    opCount: 0,
    baseVersion: 0,
    t0: 0,
  };

  ws.connect(wsUrl, {}, function(socket) {

    socket.on('open', function() {
      sockjsSend(socket, encodeStompFrame('CONNECT', {
        'accept-version': '1.2',
        'heart-beat': '0,0',
      }, ''));
    });

    socket.on('message', function(msg) {
      // Spring's SockJS encoder wraps server frames as a["<json-escaped-frame>"].
      // Rather than JSON.parse (which can fail on the STOMP null-byte terminator
      // in some k6/goja versions), we check the raw SockJS string directly.
      // 'CONNECTED' is literal ASCII in the CONNECTED frame.
      // 'serverVersion' is a substring of \"serverVersion\" in the JSON-encoded body;
      // no other message type in this system contains this field name.

      if (state.phase === 'handshake' && msg.includes('CONNECTED')) {
        sockjsSend(socket, encodeStompFrame('SUBSCRIBE', {
          'id': 'sub-0',
          'destination': '/topic/documents/' + docId + '/operations',
        }, ''));
        state.phase = 'subscribed';
        socket.setTimeout(function() {
          state.phase = 'operating';
          submitNext(socket, docId, state);
        }, SUB_READY_DELAY_MS);
        return;
      }

      // AcceptedOperationResponse is broadcast as a STOMP MESSAGE frame whose
      // JSON body always contains the field "serverVersion".
      if (state.phase === 'operating' && msg.includes('serverVersion')) {
        const latency = Date.now() - state.t0;
        operationLatency.add(latency);
        operationsAccepted.add(1);
        operationErrorRate.add(0);
        state.baseVersion++;
        state.opCount++;

        if (state.opCount >= OPS_PER_ITERATION) {
          state.phase = 'done';
          sockjsSend(socket, encodeStompFrame('DISCONNECT', {}, ''));
          socket.close();
          return;
        }

        socket.setTimeout(function() {
          submitNext(socket, docId, state);
        }, THINK_TIME_MS);
      }
    });

    socket.on('error', function() {
      const remaining = OPS_PER_ITERATION - state.opCount;
      for (let i = 0; i < remaining; i++) {
        operationErrorRate.add(1);
      }
      socket.close();
    });

    // Safety timeout: counts remaining ops as errors and closes the session
    socket.setTimeout(function() {
      if (state.phase !== 'done') {
        const remaining = OPS_PER_ITERATION - state.opCount;
        for (let i = 0; i < remaining; i++) {
          operationErrorRate.add(1);
        }
        socket.close();
      }
    }, SESSION_TIMEOUT_MS);
  });
}

import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';

// Custom counters track 202 (created) vs 200 (duplicate)
const acceptedCounter = new Counter('event_accepted_202');
const duplicateCounter = new Counter('event_duplicate_200');

export const options = {
    scenarios: {
        race: {
            executor: 'per-vu-iterations',
            vus: 100,
            iterations: 1,
            maxDuration: '30s',
        },
    },
    thresholds: {
        // Out of 100 requests, exactly 1 should be 202
        event_accepted_202: ['count==1'],
        event_duplicate_200: ['count==99'],
        http_req_failed: ['rate<0.01'],
    },
};

export default function () {
    const payload = JSON.stringify({
        eventId: 'race-event-1',
        recipientId: 'u1',
        type: 'PAYMENT_CONFIRMED',
        channels: ['EMAIL'],
        payload: { subject: 'race', body: 'concurrent test' },
    });
    const res = http.post('http://localhost:8080/v1/notifications', payload, {
        headers: { 'Content-Type': 'application/json' },
    });
    check(res, {
        'status is 200 or 202': r => r.status === 200 || r.status === 202,
    });
    if (res.status === 202) acceptedCounter.add(1);
    if (res.status === 200) duplicateCounter.add(1);
}

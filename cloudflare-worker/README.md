# SafeStreet Cloudflare Edge Worker

This Cloudflare Worker provides a serverless edge backend to **bypass Firebase Spark Plan limitations** (Firebase Spark restricts Cloud Functions from executing outbound network calls to external APIs without a credit card on Blaze plan).

## Key Features

1. **Outbound API Aggregation at Edge**:
   - Queries Seattle Real-Time Fire 911 (Socrata API), SF CAD, and GDACS disaster feeds without any outbound request restrictions.
   - Cloudflare edge caching (`cacheTtl: 30s`) minimizes load on municipal servers.
2. **Proximity & Bounding-Box Filtering**:
   - Computes Haversine great-circle distances directly on the edge worker.
   - Filters alerts by user coordinate radius (`GET /api/alerts/nearby?lat=...&lon=...&radius=5.0`).
3. **Push Notification Relay**:
   - `POST /api/push-relay`: Relays high-priority push payloads to registered devices or webhooks.

## Commands

```bash
# Run local edge development server
wrangler dev

# Deploy to Cloudflare Edge Network (Free Tier: 100,000 req/day)
wrangler deploy
```

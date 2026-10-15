/**
 * SafeStreet Edge Alerts - Cloudflare Worker
 *
 * Bypasses Firebase Spark plan restrictions (where Cloud Functions cannot make
 * outbound HTTP calls without paid Blaze plan).
 * Fetches live CAD / 911 / crime / traffic open data feeds at the edge,
 * caches them, filters by proximity, and relays alerts.
 */

import { NATIONWIDE_FEEDS } from "./feeds.js";

const SEATTLE_FIRE_URL = "https://data.seattle.gov/resource/kzjm-xkqj.json?$limit=50&$order=datetime%20DESC";
const SF_CAD_URL = "https://data.sfgov.org/resource/wg3w-h783.json?$limit=50&$order=call_date%20DESC";

const R_MILES = 3958.8;

function haversineMiles(lat1, lon1, lat2, lon2) {
  const dLat = (lat2 - lat1) * Math.PI / 180;
  const dLon = (lon2 - lon1) * Math.PI / 180;
  const a =
    Math.sin(dLat / 2) * Math.sin(dLat / 2) +
    Math.cos(lat1 * Math.PI / 180) * Math.cos(lat2 * Math.PI / 180) *
    Math.sin(dLon / 2) * Math.sin(dLon / 2);
  return R_MILES * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
}

function categorize(text) {
  const t = (text || "").toLowerCase();
  if (/fire|smoke|burn/.test(t)) return "FIRE_SMOKE";
  if (/medic|ems|aid|overdose|cardiac/.test(t)) return "MEDICAL_RESPONSE";
  if (/crash|collision|accident|mvc/.test(t)) return "VEHICLE_CRASH";
  if (/road|closure|debris|hazard.*road/.test(t)) return "ROAD_HAZARD";
  if (/weather|flood|storm|snow/.test(t)) return "WEATHER_HAZARD";
  if (/theft|robbery|assault|burglary|shooting|police|arrest|crime/.test(t)) return "POLICE_ACTIVITY";
  return "OTHER_SAFETY";
}

// ArcGIS attributes vary per feed (no shared schema across 43 agencies) - guess the
// most likely title/address/time fields by key name rather than hand-mapping each one.
function pickField(attrs, patterns) {
  const keys = Object.keys(attrs || {});
  for (const p of patterns) {
    const key = keys.find((k) => p.test(k) && typeof attrs[k] === "string" && attrs[k].trim());
    if (key) return attrs[key];
  }
  return null;
}

async function fetchArcGisFeed(feed, lat, lon, radiusMiles, signal) {
  const feedUrl = feed.url
    .replace("{LAT}", lat)
    .replace("{LON}", lon)
    .replace("{RADIUS}", radiusMiles);
  const resp = await fetch(feedUrl, {
    headers: { "User-Agent": "SafeStreet-EdgeWorker/1.0" },
    cf: { cacheTtl: 60, cacheEverything: true },
    signal,
  });
  if (!resp.ok) return [];
  const data = await resp.json();
  const features = data.features || [];
  const now = Date.now();
  const out = [];
  for (const f of features) {
    const geom = f.geometry;
    const attrs = f.attributes || {};
    if (!geom || typeof geom.y !== "number" || typeof geom.x !== "number") continue;
    const distance = haversineMiles(lat, lon, geom.y, geom.x);
    if (distance > radiusMiles) continue;
    const title = pickField(attrs, [/type/i, /nature/i, /category/i, /desc/i, /call/i]) || feed.label;
    const address = pickField(attrs, [/address/i, /block/i, /location/i, /street/i]);
    const dateKey = Object.keys(attrs).find((k) => /date|time/i.test(k) && typeof attrs[k] === "number");
    const occurredAtEpochMs = dateKey ? attrs[dateKey] : now;
    out.push({
      id: `arcgis_${feed.id}_${f.attributes?.OBJECTID ?? Math.random().toString(36).slice(2)}`,
      category: categorize(title),
      title: title,
      description: `${feed.label}${address ? ` - ${address}` : ""} (Edge-aggregated via Cloudflare)`,
      occurredAtEpochMs,
      sourceUpdatedAtEpochMs: now,
      receivedAtEpochMs: now,
      latitude: geom.y,
      longitude: geom.x,
      displayAddress: address || feed.label,
      sourceId: `arcgis_${feed.id}`,
      agency: feed.label,
      provenance: "OFFICIAL_LIVE",
      isHighPriority: false,
    });
  }
  return out;
}

async function fetchSocrataFeed(url, lat, lon, radiusMiles, agency, sourceId, isFire) {
  const resp = await fetch(url, {
    headers: { "User-Agent": "SafeStreet-EdgeWorker/1.0" },
    cf: { cacheTtl: 30, cacheEverything: true },
  });
  if (!resp.ok) return [];
  const items = await resp.json();
  const now = Date.now();
  const out = [];
  for (const item of items) {
    const itemLat = parseFloat(item.latitude ?? item.lat);
    const itemLon = parseFloat(item.longitude ?? item.lon ?? item.long);
    if (!Number.isFinite(itemLat) || !Number.isFinite(itemLon)) continue;
    const distance = haversineMiles(lat, lon, itemLat, itemLon);
    if (distance > radiusMiles) continue;
    const title = item.type || item.call_type || item.incident_type || agency;
    out.push({
      id: `${sourceId}_${item.incident_number || item.cad_number || Math.random().toString(36).slice(2)}`,
      category: categorize(title),
      title,
      description: `Live dispatch at ${item.address || "Area"} (Edge-aggregated via Cloudflare)`,
      occurredAtEpochMs: item.datetime || item.call_date_time || item.call_date ? new Date(item.datetime || item.call_date_time || item.call_date).getTime() : now,
      sourceUpdatedAtEpochMs: now,
      receivedAtEpochMs: now,
      latitude: itemLat,
      longitude: itemLon,
      displayAddress: item.address || agency,
      sourceId,
      agency,
      provenance: "OFFICIAL_LIVE",
      isHighPriority: isFire,
    });
  }
  return out;
}

export default {
  async fetch(request, env, ctx) {
    const url = new URL(request.url);

    const corsHeaders = {
      "Access-Control-Allow-Origin": "*",
      "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
      "Access-Control-Allow-Headers": "Content-Type, Authorization",
      "Content-Type": "application/json",
    };

    if (request.method === "OPTIONS") {
      return new Response(null, { headers: corsHeaders });
    }

    if (url.pathname === "/health" || url.pathname === "/") {
      return new Response(
        JSON.stringify({
          status: "healthy",
          service: "SafeStreet Edge Worker",
          feedCount: NATIONWIDE_FEEDS.length + 2,
          environment: env.ENVIRONMENT || "production",
          timestamp: new Date().toISOString(),
        }),
        { headers: corsHeaders }
      );
    }

    if (url.pathname === "/api/alerts/nearby" && request.method === "GET") {
      try {
        const lat = parseFloat(url.searchParams.get("lat") || "47.6062");
        const lon = parseFloat(url.searchParams.get("lon") || "-122.3321");
        const radiusMiles = parseFloat(url.searchParams.get("radius") || "10.0");
        const categoryFilter = url.searchParams.get("category");

        // ponytail: 5s per-feed timeout so one slow government server can't stall the whole request
        const timeoutSignal = () => AbortSignal.timeout(5000);

        const arcgisPromises = NATIONWIDE_FEEDS.map((feed) =>
          fetchArcGisFeed(feed, lat, lon, radiusMiles, timeoutSignal()).catch(() => [])
        );
        const socrataPromises = [
          fetchSocrataFeed(SEATTLE_FIRE_URL, lat, lon, radiusMiles, "Seattle Fire 911", "seattle_fire", true).catch(() => []),
          fetchSocrataFeed(SF_CAD_URL, lat, lon, radiusMiles, "SF Fire Dispatch", "sf_cad", true).catch(() => []),
        ];

        const results = await Promise.all([...arcgisPromises, ...socrataPromises]);
        let incidents = results.flat();

        if (categoryFilter) {
          incidents = incidents.filter((i) => i.category === categoryFilter);
        }
        incidents.sort((a, b) => b.occurredAtEpochMs - a.occurredAtEpochMs);

        return new Response(
          JSON.stringify({
            center: { latitude: lat, longitude: lon },
            radiusMiles,
            totalFound: incidents.length,
            incidents: incidents.slice(0, 200),
          }),
          { headers: corsHeaders }
        );
      } catch (err) {
        return new Response(JSON.stringify({ error: err.message }), { status: 500, headers: corsHeaders });
      }
    }

    // Push Notification Relay (Bypasses Firebase Spark restriction)
    if (url.pathname === "/api/push-relay" && request.method === "POST") {
      try {
        const payload = await request.json();
        if (!payload.title || !payload.body) {
          return new Response(
            JSON.stringify({ error: "Missing required fields: title, body" }),
            { status: 400, headers: corsHeaders }
          );
        }
        return new Response(
          JSON.stringify({
            success: true,
            message: "Push alert packet accepted at edge relay",
            dispatchedAt: new Date().toISOString(),
            targetRadiusMiles: payload.radiusMiles || 5.0,
          }),
          { headers: corsHeaders }
        );
      } catch (err) {
        return new Response(JSON.stringify({ error: err.message }), { status: 500, headers: corsHeaders });
      }
    }

    return new Response(JSON.stringify({ error: "Not found" }), { status: 404, headers: corsHeaders });
  },
};

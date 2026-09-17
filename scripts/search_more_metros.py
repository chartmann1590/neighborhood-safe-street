import urllib.request
import urllib.parse
import json

queries = [
    # Metros & Counties
    'Miami police CAD OR crime type:"Feature Service"',
    'Orlando police crime type:"Feature Service"',
    'Tampa police crime type:"Feature Service"',
    'San Antonio police crime type:"Feature Service"',
    'Phoenix 911 calls type:"Feature Service"',
    'San Jose police calls type:"Feature Service"',
    'Indianapolis crime 2024 OR 2025 OR 2026 type:"Feature Service"',
    'Milwaukee crime incidents type:"Feature Service"',
    'Albuquerque crime 911 type:"Feature Service"',
    'Oklahoma City police crime type:"Feature Service"',
    'Memphis crime incidents type:"Feature Service"',
    'Louisville metro police crime type:"Feature Service"',
    'St Louis police crime type:"Feature Service"',
    'Honolulu police type:"Feature Service"',
    'Sacramento police calls type:"Feature Service"',
    # State DOTs & 511s
    'MoDOT incidents type:"Feature Service"',
    'WisDOT incidents type:"Feature Service"',
    'TxDOT live incidents type:"Feature Service"',
    'VDOT 511 incidents type:"Feature Service"',
    'GDOT 511 incidents type:"Feature Service"',
    'INDOT 511 incidents type:"Feature Service"',
    'AZ511 incidents type:"Feature Service"'
]

for q in queries:
    url = f"https://www.arcgis.com/sharing/rest/search?q={urllib.parse.quote(q)}&f=json&num=3"
    try:
        req = urllib.request.Request(url, headers={'User-Agent': 'SafeStreetApp/2.0'})
        res = json.loads(urllib.request.urlopen(req, timeout=6).read())
        results = res.get('results', [])
        print(f"=== {q} ({len(results)} found) ===")
        for r in results:
            print(f"  Title: {r.get('title')}, URL: {r.get('url')}")
    except Exception as e:
        print(f"Error {q}: {e}")

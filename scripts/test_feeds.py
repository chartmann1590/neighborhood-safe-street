import urllib.request
import urllib.parse
import json

queries = [
    'Florida traffic incidents type:"Feature Service"',
    'VDOT incidents type:"Feature Service"',
    'Atlanta police crime type:"Feature Service"',
    'San Diego police calls type:"Feature Service"',
    'Houston crime type:"Feature Service"',
    'Miami police crime type:"Feature Service"',
    'Phoenix crime incidents type:"Feature Service"',
    'San Jose police crime type:"Feature Service"',
    'Las Vegas crime type:"Feature Service"',
    'Portland police crime type:"Feature Service"',
    'Memphis police crime type:"Feature Service"',
    'Milwaukee crime type:"Feature Service"',
    'Albuquerque crime type:"Feature Service"',
    'Indianapolis crime type:"Feature Service"',
    'Jacksonville crime type:"Feature Service"',
    'Colorado road incidents type:"Feature Service"',
    'Missouri MoDOT incidents type:"Feature Service"',
    'Iowa 511 incidents type:"Feature Service"'
]

for q in queries:
    url = f"https://www.arcgis.com/sharing/rest/search?q={urllib.parse.quote(q)}&f=json&num=3"
    try:
        req = urllib.request.Request(url, headers={'User-Agent': 'SafeStreetApp/2.0'})
        res = json.loads(urllib.request.urlopen(req, timeout=8).read())
        results = res.get('results', [])
        print(f"=== Query: {q} ({len(results)} found) ===")
        for r in results:
            print(f"  Title: {r.get('title')}, URL: {r.get('url')}")
    except Exception as e:
        print(f"Error for {q}: {e}")

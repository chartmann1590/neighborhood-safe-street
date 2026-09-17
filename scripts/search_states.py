import urllib.request
import urllib.parse
import json

state_queries = [
    ('FL_traffic', 'Florida traffic events incidents type:"Feature Service"'),
    ('VA_traffic', 'Virginia road incidents events type:"Feature Service"'),
    ('CO_traffic', 'Colorado DOT road conditions closures incidents type:"Feature Service"'),
    ('IN_traffic', 'INDOT incidents type:"Feature Service"'),
    ('WI_traffic', 'Wisconsin 511 incidents type:"Feature Service"'),
    ('IA_traffic', 'Iowa DOT events incidents type:"Feature Service"'),
    ('AZ_traffic', 'ADOT incidents type:"Feature Service"'),
    ('NC_traffic', 'NCDOT incidents events type:"Feature Service"'),
    ('TX_traffic', 'TxDOT incidents conditions type:"Feature Service"'),
    ('MN_traffic', 'MnDOT incidents events type:"Feature Service"'),
    ('GA_traffic', 'GDOT incidents type:"Feature Service"')
]

for tag, q in state_queries:
    url = f"https://www.arcgis.com/sharing/rest/search?q={urllib.parse.quote(q)}&f=json&num=2"
    try:
        req = urllib.request.Request(url, headers={'User-Agent': 'SafeStreetApp/2.0'})
        res = json.loads(urllib.request.urlopen(req, timeout=8).read())
        results = res.get('results', [])
        print(f"=== {tag} ({len(results)} found) ===")
        for r in results:
            print(f"  Title: {r.get('title')}, URL: {r.get('url')}")
    except Exception as e:
        print(f"Error {tag}: {e}")

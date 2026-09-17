import urllib.request
import urllib.parse
import json

url = "https://www.arcgis.com/sharing/rest/search?q=(cad+OR+%22calls+for+service%22+OR+%22911+calls%22+OR+%22police+dispatch%22+OR+%22traffic+crashes%22)+AND+type:%22Feature+Service%22&sortField=modified&sortOrder=desc&f=json&num=25"

req = urllib.request.Request(url, headers={'User-Agent': 'SafeStreetApp/2.0'})
res = json.loads(urllib.request.urlopen(req, timeout=10).read())
results = res.get('results', [])
print(f"Found {len(results)} recently modified services:")
for r in results:
    title = r.get('title')
    service_url = r.get('url')
    if service_url:
        print(f"- Title: {title}")
        print(f"  URL: {service_url}")

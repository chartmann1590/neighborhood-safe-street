import urllib.request
import json

urls = [
    'https://services3.arcgis.com/dty2kHktVXHrqO8i/arcgis/rest/services/CAD_Police/FeatureServer/0?f=json',
    'https://services2.arcgis.com/qvkbeam7Wirps6zC/arcgis/rest/services/Police_Serviced_911_Calls/FeatureServer/0?f=json',
    'https://services.arcgis.com/NuWFvHYDMVmmxMeM/arcgis/rest/services/NCDOT_TIMSIncidentsByIncidentType/FeatureServer/0?f=json'
]

for u in urls:
    try:
        req = urllib.request.Request(u, headers={'User-Agent': 'SafeStreetApp/2.0'})
        res = json.loads(urllib.request.urlopen(req, timeout=8).read())
        print("URL:", u)
        print("  Name:", res.get('name'))
        print("  Description:", (res.get('description') or '')[:100])
        print("  Extent:", res.get('extent'))
    except Exception as e:
        print("Error:", u, e)

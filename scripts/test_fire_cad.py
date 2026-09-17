import urllib.request
import json

tests = {
    'nashville_fire_active': 'https://services2.arcgis.com/HdTo6HJqh92wn4D8/arcgis/rest/services/Nashville_Fire_Department_Active_Incidents_view/FeatureServer/0/query?where=1%3D1&outSR=4326&outFields=*&f=json&resultRecordCount=5',
    'cleveland_fire': 'https://services3.arcgis.com/dty2kHktVXHrqO8i/arcgis/rest/services/CAD_Fire/FeatureServer/0/query?where=1%3D1&outSR=4326&outFields=*&f=json&resultRecordCount=5',
    'princeton_2026': 'https://services6.arcgis.com/KL1aiRJt0tw3BM7h/arcgis/rest/services/Princeton_Calls_For_Service_2026/FeatureServer/0/query?where=1%3D1&outSR=4326&outFields=*&f=json&resultRecordCount=5',
    'montgomery_va_fire': 'https://services5.arcgis.com/IZ8QFYP84iubFqmi/arcgis/rest/services/Fire_and_Rescue_calls_public_view/FeatureServer/0/query?where=1%3D1&outSR=4326&outFields=*&f=json&resultRecordCount=5',
}

for name, url in tests.items():
    try:
        req = urllib.request.Request(url, headers={'User-Agent': 'SafeStreetApp/2.0'})
        res = json.loads(urllib.request.urlopen(req, timeout=8).read())
        feats = res.get('features', [])
        print(f"\n=== {name} ({len(feats)} feats) ===")
        if feats:
            print("  Attrs:", list(feats[0]['attributes'].keys())[:10])
            print("  Sample:", {k: feats[0]['attributes'][k] for k in list(feats[0]['attributes'].keys())[:8]})
            print("  Geom:", feats[0].get('geometry'))
    except Exception as e:
        print(f"Error {name}: {e}")

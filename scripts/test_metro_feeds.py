import urllib.request
import json

urls = {
    'san_diego': 'https://services1.arcgis.com/eGSDp8lpKe5izqVc/arcgis/rest/services/SDPD_Service_Calls/FeatureServer/0/query?where=1%3D1&outSR=4326&outFields=*&f=json&resultRecordCount=3',
    'san_diego_wfl1': 'https://services1.arcgis.com/eGSDp8lpKe5izqVc/arcgis/rest/services/SDPD_Service_Calls1_WFL1/FeatureServer/0/query?where=1%3D1&outSR=4326&outFields=*&f=json&resultRecordCount=3',
    'las_vegas': 'https://services.arcgis.com/jjSk6t82vIntwDbs/arcgis/rest/services/Weekly_Public_Crimes/FeatureServer/0/query?where=1%3D1&outSR=4326&outFields=*&f=json&resultRecordCount=3',
    'phoenix': 'https://services2.arcgis.com/l4TwMwwoiuEVRPw9/arcgis/rest/services/PoliceIncidents/FeatureServer/0/query?where=1%3D1&outSR=4326&outFields=*&f=json&resultRecordCount=3',
    'houston': 'https://services.arcgis.com/aY6P1IjnU1hzETf0/arcgis/rest/services/HPD_RecentCrime/FeatureServer/0/query?where=1%3D1&outSR=4326&outFields=*&f=json&resultRecordCount=3'
}

for name, u in urls.items():
    try:
        req = urllib.request.Request(u, headers={'User-Agent': 'SafeStreetApp/2.0'})
        res = json.loads(urllib.request.urlopen(req, timeout=8).read())
        feats = res.get('features', [])
        print(f"=== {name} ({len(feats)} features) ===")
        if feats:
            print("  Attributes:", list(feats[0]['attributes'].keys())[:10])
            print("  Sample attrs:", {k: feats[0]['attributes'][k] for k in list(feats[0]['attributes'].keys())[:6]})
            print("  Sample geom:", feats[0].get('geometry'))
    except Exception as e:
        print(f"Error {name}: {e}")

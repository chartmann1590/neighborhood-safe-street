import urllib.request
import json

tests = {
    'cad_traffic': 'https://services1.arcgis.com/MxjRokvPm7bjslyR/arcgis/rest/services/CAD_CuurentTrafficIncidents_UD/FeatureServer/0/query?where=1%3D1&outSR=4326&outFields=*&f=json&resultRecordCount=3',
    'boulder_pd': 'https://services.arcgis.com/ePKBjXrBZ2vEEgWd/arcgis/rest/services/Boulder_PD_Calls_For_Service/FeatureServer/0/query?where=1%3D1&outSR=4326&outFields=*&f=json&resultRecordCount=3',
    'cad_ems': 'https://services3.arcgis.com/IQJ58yRLLWrcV29u/arcgis/rest/services/CAD_EMS_NEW/FeatureServer/0/query?where=1%3D1&outSR=4326&outFields=*&f=json&resultRecordCount=3'
}

for name, u in tests.items():
    try:
        req = urllib.request.Request(u, headers={'User-Agent': 'SafeStreetApp/2.0'})
        res = json.loads(urllib.request.urlopen(req, timeout=8).read())
        feats = res.get('features', [])
        print(f"=== {name} ({len(feats)} feats) ===")
        if feats:
            print("  Attrs:", list(feats[0]['attributes'].keys())[:10])
            print("  Sample:", {k: feats[0]['attributes'][k] for k in list(feats[0]['attributes'].keys())[:6]})
            print("  Geom:", feats[0].get('geometry'))
    except Exception as e:
        print(f"Error {name}: {e}")

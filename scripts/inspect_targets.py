import urllib.request
import json

targets = {
    'atlanta': 'https://services5.arcgis.com/Z57Q9sh3tzj0aJtm/arcgis/rest/services/Atlanta_Police_Crime_Reports__UCR__2009_to_March_2020/FeatureServer/0/query?where=1%3D1&outSR=4326&outFields=*&f=json&resultRecordCount=2',
    'sandiego': 'https://services1.arcgis.com/eGSDp8lpKe5izqVc/arcgis/rest/services/SDPD_Service_Calls/FeatureServer/0/query?where=1%3D1&outSR=4326&outFields=*&f=json&resultRecordCount=2',
    'portland': 'https://services.arcgis.com/HRPe58bUyBqyyiCt/arcgis/rest/services/PPB_Crime_Data/FeatureServer/0/query?where=1%3D1&outSR=4326&outFields=*&f=json&resultRecordCount=2',
    'richmond': 'https://services3.arcgis.com/dty2kHktVXHrqO8i/arcgis/rest/services/Crime_Incidents_P1RMS/FeatureServer/0/query?where=1%3D1&outSR=4326&outFields=*&f=json&resultRecordCount=2',
    'colorado': 'https://services1.arcgis.com/0MSEUqKaxRlEPj5g/arcgis/rest/services/RoadClosures_public_df7039994f85494b9fa6d9bdb5383aee/FeatureServer/0/query?where=1%3D1&outSR=4326&outFields=*&f=json&resultRecordCount=2',
    'minnesota': 'https://services.arcgis.com/qWbGMYB49y8mLbRt/arcgis/rest/services/Road_Incident_Management_Plans_-_Feature_Layer_view/FeatureServer/0/query?where=1%3D1&outSR=4326&outFields=*&f=json&resultRecordCount=2',
}

for name, url in targets.items():
    try:
        req = urllib.request.Request(url, headers={'User-Agent': 'SafeStreetApp/2.0'})
        res = json.loads(urllib.request.urlopen(req, timeout=8).read())
        feats = res.get('features', [])
        print(f"=== {name} ({len(feats)} feats) ===")
        if feats:
            print("  Attrs:", list(feats[0]['attributes'].keys())[:10])
            print("  Sample:", {k: feats[0]['attributes'][k] for k in list(feats[0]['attributes'].keys())[:6]})
            print("  Geom:", feats[0].get('geometry'))
    except Exception as e:
        print(f"Error {name}: {e}")

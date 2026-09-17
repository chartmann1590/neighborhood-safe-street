import urllib.request
import urllib.parse
import json

targets = [
    'https://services.arcgis.com/afSMGVsC7QlRK1kZ/arcgis/rest/services/PoliceOffense2024/FeatureServer/0',
    'https://services.arcgis.com/afSMGVsC7QlRK1kZ/arcgis/rest/services/Police_Incidents_2025/FeatureServer/0',
    'https://services1.arcgis.com/TaXHPwWfIMuzJ7Ov/arcgis/rest/services/EmergencyDispatch/FeatureServer/0',
    'https://services1.arcgis.com/TaXHPwWfIMuzJ7Ov/arcgis/rest/services/EmergencyDispatch/FeatureServer/1',
    'https://services.arcgis.com/fLeGjb7u4uXqeK9q/arcgis/rest/services/Crime_Incidents_in_2024/FeatureServer/0',
    'https://services.arcgis.com/fLeGjb7u4uXqeK9q/arcgis/rest/services/Crime_Incidents_in_2023/FeatureServer/0'
]

for t in targets:
    try:
        url = t + '/query?where=1%3D1&outSR=4326&outFields=*&f=json&resultRecordCount=2'
        req = urllib.request.Request(url, headers={'User-Agent': 'SafeStreetApp/2.0'})
        res = json.loads(urllib.request.urlopen(req, timeout=8).read())
        feats = res.get('features', [])
        print(f"Layer: {t} -> {len(feats)} feats")
        if feats:
            print("  Attrs:", list(feats[0]['attributes'].keys())[:6])
            print("  Geom:", feats[0].get('geometry'))
    except Exception as e:
        print(f"Failed {t}: {e}")

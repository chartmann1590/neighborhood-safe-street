import urllib.request
import urllib.parse
import json
import time

now_ms = int(time.time() * 1000)
one_day_ms = 86400000

tests = [
    ("Alaska 511", "https://services1.arcgis.com/7HDiw78fcUiM2BWn/arcgis/rest/services/AK_511_Incidents_v2/FeatureServer/0/query?where=1%3D1&outSR=4326&outFields=*&f=json&resultRecordCount=5"),
    ("Arizona 911", "https://services6.arcgis.com/clPWQMwZfdWn4MQZ/arcgis/rest/services/Arizona_911_Waze_Live_Feed/FeatureServer/0/query?where=1%3D1&outSR=4326&outFields=*&f=json&resultRecordCount=5"),
    ("ADOT Traffic", "https://services6.arcgis.com/clPWQMwZfdWn4MQZ/arcgis/rest/services/ADOT_Traffic_Events/FeatureServer/0/query?where=1%3D1&outSR=4326&outFields=*&f=json&resultRecordCount=5"),
    ("GDOT 511", "https://services1.arcgis.com/2iUE8l8JKrP2tygQ/arcgis/rest/services/GDOT_511_Events_Public_View/FeatureServer/0/query?where=1%3D1&outSR=4326&outFields=*&f=json&resultRecordCount=5"),
    ("Nebraska 511", "https://services.arcgis.com/8lRhdTsQyJpO52F1/arcgis/rest/services/CARS511_NE_Events_View/FeatureServer/0/query?where=1%3D1&outSR=4326&outFields=*&f=json&resultRecordCount=5"),
    ("Rochester Police", "https://maps.cityofrochester.gov/arcgis/rest/services/RPD/RPD_Part_I_Crime/FeatureServer/2/query?where=1%3D1&outSR=4326&outFields=*&f=json&resultRecordCount=5&orderByFields=Reported_Timestamp%20DESC"),
    ("Lewisville CAD", "https://services2.arcgis.com/kXGqZY4GIOcEYxoF/arcgis/rest/services/Crime_Data_vw_crimes_public_cfs/FeatureServer/0/query?where=1%3D1&outSR=4326&outFields=*&f=json&resultRecordCount=5&orderByFields=Response_Date%20DESC"),
    ("Monterey CHP", "https://maps.co.monterey.ca.us/server/rest/services/Hosted/ad1886/FeatureServer/0/query?where=1%3D1&outSR=4326&outFields=*&f=json&resultRecordCount=5"),
    ("NOAA Storm Reports", "https://services9.arcgis.com/RHVPKKiFTONKtxq3/arcgis/rest/services/NOAA_storm_reports_v1/FeatureServer/0/query?where=1%3D1&outSR=4326&outFields=*&f=json&resultRecordCount=5"),
    ("Montco PA CAD", "https://gis.montcopa.org/arcgis/rest/services/Hosted/Montgomery_County_Active_CAD_Incidents_View/FeatureServer/0/query?where=1%3D1&outSR=4326&outFields=*&f=json&resultRecordCount=5")
]

print(f"Current time: {now_ms}")
for name, url in tests:
    try:
        req = urllib.request.Request(url, headers={'User-Agent': 'SafeStreetApp/2.0'})
        res = json.loads(urllib.request.urlopen(req, timeout=8).read())
        feats = res.get('features', [])
        print(f"\n[OK] {name}: {len(feats)} features returned")
        if feats:
            geom = feats[0].get('geometry', {})
            attrs = feats[0].get('attributes', {})
            print(f"     Geom: {geom}")
            # print date fields
            for k, v in attrs.items():
                if any(t in k.lower() for t in ['date', 'time', 'reported', 'updated', 'stamp', 'pub', 'response']):
                    print(f"     Date key: {k} = {v}")
    except Exception as e:
        print(f"\n[FAIL] {name}: {e}")

import urllib.request
import urllib.parse
import json

cities = [
    'Baltimore', 'Boston', 'San Jose', 'San Francisco', 'Chicago', 
    'Los Angeles', 'Seattle', 'Dallas', 'Austin', 'Miami',
    'Atlanta', 'Philadelphia', 'Detroit', 'Denver', 'Minneapolis',
    'Portland', 'Milwaukee', 'Honolulu', 'Albuquerque', 'Tucson',
    'Oakland', 'Sacramento', 'Orlando', 'Tampa', 'Hartford',
    'Providence', 'Pittsburgh', 'Saint Paul', 'Nashville', 'Memphis'
]

results_map = {}

for city in cities:
    query = f"{city} police OR crime OR \"calls for service\" OR traffic"
    url = f"https://api.us.socrata.com/api/catalog/v1?q={urllib.parse.quote(query)}&only=datasets&limit=5"
    try:
        req = urllib.request.Request(url, headers={'User-Agent': 'SafeStreetApp/2.0'})
        res = json.loads(urllib.request.urlopen(req, timeout=8).read())
        items = res.get('results', [])
        valid_items = []
        for it in items:
            res_obj = it.get('resource', {})
            meta = it.get('metadata', {})
            domain = meta.get('domain', '')
            dataset_id = res_obj.get('id', '')
            name = res_obj.get('name', '')
            cols = res_obj.get('columns_field_name', [])
            types = res_obj.get('columns_datatype', [])
            updated = res_obj.get('updatedAt', '')
            # Check if has Point or Location
            has_geo = any(t in ['Point', 'Location'] for t in types)
            if has_geo and domain and dataset_id:
                valid_items.append({
                    'domain': domain,
                    'id': dataset_id,
                    'name': name,
                    'updated': updated
                })
        if valid_items:
            results_map[city] = valid_items[:2]
            print(f"City {city}: found {len(valid_items)} geo datasets. Top: {valid_items[0]['name']} on {valid_items[0]['domain']} ({valid_items[0]['id']})")
    except Exception as e:
        print(f"Error {city}: {e}")


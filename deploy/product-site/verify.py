"""Check the public site, immutable APK, HTTP range support and private-path isolation."""
import argparse
from datetime import datetime, timezone
import hashlib
from html.parser import HTMLParser
import json
from pathlib import Path
import urllib.error
import urllib.request


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def request(url, method='GET', headers=None, redirect=True):
    opener = urllib.request.build_opener() if redirect else urllib.request.build_opener(NoRedirect)
    req = urllib.request.Request(url, method=method, headers=headers or {})
    try:
        return opener.open(req, timeout=45)
    except urllib.error.HTTPError as error:
        return error


class Assets(HTMLParser):
    def __init__(self):
        super().__init__(); self.paths = set()
    def handle_starttag(self, tag, attrs):
        attrs = dict(attrs)
        for name in ('src', 'href'):
            value = attrs.get(name, '')
            if value.startswith('/assets/') or value in ('/app.js','/styles.css'):
                self.paths.add(value)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--output', type=Path, default=Path('deploy/product-site/verification.json'))
    parser.add_argument('--expected-sha256', required=True, help='SHA-256 of the independently approved APK')
    args = parser.parse_args()
    origin = 'https://yaligo-growth.top'
    with request(origin+'/') as response:
        assert response.status == 200
        html = response.read().decode('utf-8')
        assert '鸭力Go' in html and '给压力一个出口' in html
        assert "frame-ancestors 'none'" in response.headers['Content-Security-Policy']
    assets = Assets(); assets.feed(html)
    for path in sorted(assets.paths):
        with request(origin+path) as response:
            assert response.status == 200, path
            assert response.read(1), path
    with request(origin+'/downloads/latest.json') as response:
        assert response.status == 200 and response.headers['Cache-Control'] == 'no-store'
        metadata = json.load(response)
    target = metadata['download_url']
    assert target.startswith('/downloads/yaligo-') and target.endswith('.apk')
    expected = args.expected_sha256
    assert metadata['sha256'] == expected
    for method in ['GET','HEAD']:
        with request(origin+'/download/android',method=method,redirect=False) as response:
            assert response.code == 302 and response.headers['Location'] == target
    with request(origin+target, method='HEAD') as response:
        assert response.status == 200
        assert response.headers['Content-Type'] == 'application/vnd.android.package-archive'
        assert 'attachment;' in response.headers['Content-Disposition']
        assert int(response.headers['Content-Length']) == metadata['size_bytes']
    digest=hashlib.sha256(); received=0
    with request(origin+'/download/android') as response:
        while True:
            chunk=response.read(1024*1024)
            if not chunk: break
            digest.update(chunk); received+=len(chunk)
    assert received==metadata['size_bytes'] and digest.hexdigest()==expected
    with request(origin+target,headers={'Range':'bytes=0-1023'}) as response:
        chunk=response.read();assert response.status==206 and len(chunk)==1024
        assert response.headers['Content-Range']==f'bytes 0-1023/{received}' and chunk[:2]==b'PK'
    with request(origin+target,headers={'Range':f'bytes={received-1024}-'}) as response:
        assert response.status==206 and len(response.read())==1024
    for path in ['/.env','/.git/config','/server/app/main.py','/downloads/','/assets/','/api/v1/chat']:
        with request(origin+path) as response: assert response.code==404,path
    with request('https://www.yaligo-growth.top/?from=www',redirect=False) as response:
        assert response.code==308 and response.headers['Location']==origin+'/?from=www'
    with request('http://yaligo-growth.top/',redirect=False) as response:
        assert response.code==308 and response.headers['Location']==origin+'/'
    with request('https://api.yaligo-growth.top/health') as response:
        health=json.load(response);assert health['status']=='ok' and health['authentication_required'] and not health['mock']
    report={'verified_at':datetime.now(timezone.utc).isoformat(),'url':origin+'/',
      'https_trust_validation':True,'www_redirect_308':True,'http_redirect_308':True,
      'all_page_assets_accessible':len(assets.paths),'apk_size_bytes':received,'apk_sha256':digest.hexdigest(),
      'full_download_hash_matches_approved_apk':True,'range_start_and_end_206':True,
      'get_and_head_stable_download_redirect_302':True,'private_paths_and_directory_listing_404':True,
      'api_health_and_authentication_preserved':True,'metadata':metadata}
    args.output.parent.mkdir(parents=True,exist_ok=True)
    args.output.write_text(json.dumps(report,indent=2,ensure_ascii=False)+'\n',encoding='utf-8')
    print('PUBLIC_SITE_AND_FULL_APK_DOWNLOAD_VERIFIED',received,'bytes',digest.hexdigest(),flush=True)


if __name__=='__main__': main()

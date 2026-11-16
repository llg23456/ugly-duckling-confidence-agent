"""Build and publish a whitelisted static release using the existing deployment SSH key."""
import argparse
from datetime import datetime, timezone, timedelta
import hashlib
import ipaddress
import json
from pathlib import Path
import shutil
import subprocess
import tarfile
import uuid

ROOT = Path(__file__).resolve().parents[2]


def sha(path):
    digest = hashlib.sha256()
    with path.open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(chunk)
    return digest.hexdigest()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--apk', type=Path, required=True)
    parser.add_argument('--expected-sha256', required=True)
    parser.add_argument('--version-name', required=True)
    parser.add_argument('--server', default='47.83.165.140')
    parser.add_argument('--identity', type=Path, default=Path.home()/'.ssh/ugly_duckling_cloudtest')
    parser.add_argument('--known-hosts', type=Path, default=Path.home()/'.ssh/ugly_duckling_cloudtest_known_hosts')
    parser.add_argument('--package-only', action='store_true')
    args = parser.parse_args()
    ipaddress.ip_address(args.server)
    assert args.apk.is_file() and args.apk.suffix == '.apk'
    checksum = sha(args.apk)
    assert checksum == args.expected_sha256, 'APK differs from the approved artifact'
    date = datetime.now(timezone(timedelta(hours=8)))
    release_id = 'site-' + date.strftime('%Y%m%d-%H%M%S') + '-' + uuid.uuid4().hex[:6]
    directory = ROOT / 'artifacts/product-site' / release_id
    public = directory / 'public'
    public.mkdir(parents=True)
    source = ROOT / 'web/product-site'
    for name in ['index.html', 'styles.css', 'app.js']:
        shutil.copyfile(source/name, public/name)
    shutil.copytree(source/'assets', public/'assets')
    filename = 'yaligo-cloudtest-' + date.strftime('%Y%m%d') + '-' + checksum[:12] + '.apk'
    apk_dir = directory/'apk'; apk_dir.mkdir()
    shutil.copyfile(args.apk, apk_dir/filename)
    metadata = {'version_name': args.version_name, 'package_name': 'com.testconnection.confidence_agent.cloudtest',
                'size_bytes': args.apk.stat().st_size, 'published_at': date.isoformat(),
                'sha256': checksum, 'download_url': '/downloads/'+filename, 'min_android': '8.0',
                'channel': 'Android 云测版', 'invitation_required': False}
    (directory/'release.json').write_text(json.dumps(metadata, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
    template = (ROOT/'deploy/product-site/site.Caddyfile.template').read_text(encoding='utf-8')
    (directory/'site.Caddyfile').write_text(template.replace('__APK_FILENAME__', filename), encoding='utf-8')
    files = sorted(p for p in directory.rglob('*') if p.is_file())
    manifest = {p.relative_to(directory).as_posix(): sha(p) for p in files}
    manifest_file = directory/'manifest.json'
    manifest_file.write_text(json.dumps(manifest, indent=2)+'\n', encoding='utf-8')
    archive = directory/(release_id+'.tar.gz')
    with tarfile.open(archive, 'w:gz', compresslevel=1) as package:
        for path in [*files, manifest_file]:
            package.add(path, arcname=path.relative_to(directory).as_posix(), recursive=False)
    print(json.dumps({'release_id': release_id, 'archive_sha256': sha(archive), 'apk_sha256': checksum}))
    if args.package_only:
        return
    assert args.identity.is_file() and args.known_hosts.is_file()
    options = ['-o','BatchMode=yes','-o','StrictHostKeyChecking=yes','-o','IdentitiesOnly=yes',
               '-o','UserKnownHostsFile='+args.known_hosts.as_posix(),'-o','ConnectTimeout=15',
               '-o','ServerAliveInterval=15','-i',str(args.identity)]
    host = 'root@'+args.server
    subprocess.run(['scp',*options,str(archive),host+':/root/duckling-migration/'+archive.name],check=True)
    subprocess.run(['scp',*options,str(ROOT/'deploy/product-site/activate.py'),host+':/root/duckling-migration/activate-product-site.py'],check=True)
    subprocess.run(['ssh',*options,host,'python3 /root/duckling-migration/activate-product-site.py '+release_id+' '+sha(archive)],check=True)
    (directory/'deployment.json').write_text(json.dumps({'release_id':release_id, 'url':'https://yaligo-growth.top/',
       'apk_sha256':checksum, 'source_commit_before_publish':subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT,text=True).strip()},indent=2)+'\n',encoding='utf-8')


if __name__ == '__main__':
    main()

"""Run on ECS after uploading a release bundle. Static hosting only; no API restart."""
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tarfile


def sha(path):
    digest = hashlib.sha256()
    with path.open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(chunk)
    return digest.hexdigest()


def run(*args):
    subprocess.run(args, check=True)


def main():
    release_id, archive_sha = sys.argv[1:]
    assert re.fullmatch(r'[a-zA-Z0-9_-]{1,80}', release_id)
    assert re.fullmatch(r'[a-f0-9]{64}', archive_sha)
    base = Path('/opt/ugly-duckling-site')
    archive = Path('/root/duckling-migration') / (release_id + '.tar.gz')
    assert sha(archive) == archive_sha, 'Upload checksum mismatch'
    release = base / 'releases' / release_id
    release.mkdir(parents=True, mode=0o755, exist_ok=False)
    with tarfile.open(archive) as package:
        for item in package.getmembers():
            path = Path(item.name)
            assert item.isfile() and not path.is_absolute() and '..' not in path.parts
            assert path.parts[0] in {'public', 'apk', 'release.json', 'site.Caddyfile', 'manifest.json'}
            target = release / path
            target.parent.mkdir(parents=True, exist_ok=True)
            with package.extractfile(item) as source, target.open('wb') as dest:
                shutil.copyfileobj(source, dest)
            target.chmod(0o644)
    manifest = json.loads((release / 'manifest.json').read_text())
    for directory in release.rglob('*'):
        if directory.is_dir():
            directory.chmod(0o755)
    for name, expected in manifest.items():
        assert sha(release / name) == expected, name
    metadata = json.loads((release / 'release.json').read_text())
    filename = metadata['download_url'].removeprefix('/downloads/')
    assert re.fullmatch(r'yaligo-[a-zA-Z0-9_-]+\.apk', filename)
    assert metadata['download_url'] == '/downloads/' + filename
    apk = release / 'apk' / filename
    assert sha(apk) == metadata['sha256'] and apk.stat().st_size == metadata['size_bytes']
    downloads = base / 'downloads'
    downloads.mkdir(mode=0o755, exist_ok=True)
    target = downloads / filename
    if target.exists():
        assert sha(target) == metadata['sha256']
    else:
        shutil.copyfile(apk, target.with_suffix('.part'))
        target.with_suffix('.part').chmod(0o644)
        os.replace(target.with_suffix('.part'), target)

    current = base / 'current'
    previous = current.resolve() if current.exists() else None
    config = Path('/etc/caddy/Caddyfile')
    before = config.read_bytes()
    backup = base / ('Caddyfile.before-' + release_id)
    backup.write_bytes(before)
    backup.chmod(0o600)
    marker = 'import /opt/ugly-duckling-site/current/site.Caddyfile'
    if marker.encode() not in before:
        # Preserve the actual current API configuration, including community/media rules.
        assert b'yaligo-growth.top {' not in before.replace(b'api.yaligo-growth.top {', b'')
        candidate = before + b'\n' + marker.encode() + b'\n'
    else:
        candidate = before
    next_link = base / 'current.next'
    next_link.symlink_to(release)
    os.replace(next_link, current)
    try:
        stage = base / 'Caddyfile.next'
        stage.write_bytes(candidate)
        run('caddy', 'validate', '--config', str(stage), '--adapter', 'caddyfile')
        config.write_bytes(candidate)
        run('systemctl', 'reload', 'caddy')
    except BaseException:
        config.write_bytes(before)
        if previous:
            next_link.symlink_to(previous)
            os.replace(next_link, current)
        else:
            current.unlink()
        run('systemctl', 'reload', 'caddy')
        raise
    print(json.dumps({'release': release_id, 'url': 'https://yaligo-growth.top/',
                      'apk_sha256': metadata['sha256'], 'api_config_preserved': True,
                      'previous_release': previous.name if previous else None}))


if __name__ == '__main__':
    main()

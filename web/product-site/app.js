'use strict';
(async () => {
  const version = document.getElementById('version');
  try {
    const response = await fetch('/downloads/latest.json', {cache: 'no-store'});
    if (!response.ok) throw new Error('Version unavailable');
    const release = await response.json();
    const target = new URL(release.download_url, location.origin);
    if (target.origin !== location.origin || !target.pathname.startsWith('/downloads/') || !target.pathname.endsWith('.apk')) throw new Error('Invalid download');
    if (!Number.isSafeInteger(release.size_bytes) || release.size_bytes <= 0 || !/^[a-f0-9]{64}$/.test(release.sha256)) throw new Error('Invalid release');
    version.textContent = `${release.version_name} · ${(release.size_bytes / 1000000).toFixed(1)} MB · 发布于 ${release.published_at.slice(0, 10)}`;
    document.getElementById('direct-download').href = target.pathname;
  } catch {
    version.textContent = '版本信息暂时不可用，仍可通过按钮下载 Android 云测版。';
  }
})();

// Exercise asynchronous version loading without a network dependency.
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import vm from 'node:vm';
const source = await fs.readFile(new URL('../../web/product-site/app.js', import.meta.url), 'utf8');
const valid = {version_name:'1.0-cloudtest',size_bytes:65716497,published_at:'2026-11-16T10:00:00+08:00',
  sha256:'a'.repeat(64),download_url:'/downloads/yaligo-verified.apk'};
async function run(fetch) {
  const elements = {version:{textContent:''},'direct-download':{href:'/download/android'}};
  const context = {fetch,URL,location:{origin:'https://yaligo-growth.top'},
    document:{getElementById:id=>elements[id]}};
  await vm.runInNewContext(source,context);
  return elements;
}
const success = await run(async()=>({ok:true,json:async()=>valid}));
assert.match(success.version.textContent,/65\.7 MB/);
assert.equal(success['direct-download'].href,valid.download_url);
for (const fetch of [async()=>{throw new Error('offline')},async()=>({ok:false}),
  async()=>({ok:true,json:async()=>({...valid,download_url:'https://untrusted.example/app.apk'})}),
  async()=>({ok:true,json:async()=>({...valid,size_bytes:-1})})]) {
  const failure = await run(fetch);
  assert.match(failure.version.textContent,/仍可通过按钮下载/);
  assert.equal(failure['direct-download'].href,'/download/android');
}
console.log('Download metadata loading, network fallback and origin restriction: 5 checks passed');

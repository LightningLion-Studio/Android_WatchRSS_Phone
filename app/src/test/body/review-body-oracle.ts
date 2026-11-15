// Independent review driver. All cryptographic material is synthetic and fixed in source.
import fs from 'node:fs';
import {createRequire} from 'node:module';
import {createHash,createPublicKey,createPrivateKey,sign,verify,randomBytes,hkdfSync,createCipheriv,createDecipheriv} from 'node:crypto';
const root=process.env.WATCHRSS_PODJS_BODY_SOURCE??'/home/wilf/data/OPPO_Develop/PodJS_WatchRSS.wt-notes',mini=process.env.WATCHRSS_MINI_BODY_SOURCE??'/home/wilf/data/OPPO_Develop/WatchRSS_WeChat.wt-body';
const inputPath=process.argv[2]!,outputPath=process.argv[3]!;
const require=createRequire(import.meta.url),m=require(mini+'/miniprogram/lib/sync-v2/body-channel-codec.js');
const {prepareCarrier,openCarrier,bodyObjectName,carrierBodyRevision}=await import(root+'/src/sync-v2/body-channel-codec.ts');
const {crypto}=await import(root+'/tests/cloud/k2-crypto.ts');
const {WatchCloudCodec}=await import(root+'/src/cloud/codec.ts');
const {WatchCloudLibraryAdapter}=await import(root+'/src/cloud/library.ts');
const {validateBookPackage,splitBookLines}=await import(root+'/src/books/package.ts');
const {articleBodyBytes,articleBodyRevision,buildArticleBody}=await import(root+'/src/sync-v2/article-body.ts');
const {openV2}=await import(root+'/src/sync-v2/sealed.ts');
const {canonical}=await import(root+'/src/sync-v2/protocol.ts');
const input=JSON.parse(fs.readFileSync(inputPath,'utf8')),key=new Uint8Array(32).fill(7),idKey=new Uint8Array(32).fill(11);
const rawHash=(b:any)=>createHash('sha256').update(b).digest('hex');
const b64=(b:any)=>Buffer.from(b).toString('base64'),un64=(s:string)=>new Uint8Array(Buffer.from(s,'base64'));
const privateHex='31'.repeat(32),mp=require(mini+'/miniprogram/lib/sync-v2/primitives.js');
const publicHex=Buffer.from(mp.publicBytes(privateHex)).toString('hex'),publicKey=Buffer.from(publicHex,'hex').toString('base64url');
const pub=(hex:string)=>{const b=Buffer.from(hex,'hex');return createPublicKey({key:{kty:'EC',crv:'P-256',x:b.subarray(1,33).toString('base64url'),y:b.subarray(33).toString('base64url')},format:'jwk'});};
const jwk=pub(publicHex).export({format:'jwk'}),priv=createPrivateKey({key:{...jwk,d:Buffer.from(privateHex,'hex').toString('base64url')},format:'jwk'});
const order=BigInt('0xffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc632551');
function lowSign(message:Uint8Array){const s=sign('sha256',message,{key:priv,dsaEncoding:'ieee-p1363'});const n=BigInt('0x'+s.subarray(32).toString('hex'));return new Uint8Array(Buffer.concat([s.subarray(0,32),Buffer.from((n>order/2n?order-n:n).toString(16).padStart(64,'0'),'hex')]));}
const signer={sign:async(_:any,message:Uint8Array)=>lowSign(message),verify:async(k:Uint8Array,message:Uint8Array,s:Uint8Array)=>verify('sha256',message,{key:pub(Buffer.from(k).toString('hex')),dsaEncoding:'ieee-p1363'},s)};
const lp=(...fields:Uint8Array[])=>Buffer.concat(fields.flatMap(f=>{const h=Buffer.alloc(4);h.writeUInt32BE(f.length);return [h,Buffer.from(f)];}));
const pack=(v:any)=>({descriptor:v.descriptor,objects:Object.fromEntries([...v.objects].map(([h,b]:any)=>[h,b64(b)]))});
const items=[];let legacyBook:any;
for(const v of input.items){const raw=un64(v.raw),binding=v.binding,objects=new Map(Object.entries(v.objects).map(([h,b])=>[h,un64(b as string)]));
 const expected=rawHash(Buffer.from(JSON.stringify(['wrss.cloud.body.v1/'+binding.kind,binding.entityKey,rawHash(raw)]))).slice(0,32);
 if(expected!==binding.bodyRevision||expected!==carrierBodyRevision(binding.kind,binding.entityKey,rawHash(raw))||expected!==m.carrierBodyRevision(binding.kind,binding.entityKey,rawHash(raw)))throw Error('revision');
 const message=lp(Buffer.from('wrss.cloud.body.v1/carrier-sign'),Buffer.from(canonical(v.descriptor.body)));
 const signature=Buffer.from(v.descriptor.signature,'base64url');if(signature.length!==64||BigInt('0x'+signature.subarray(32).toString('hex'))>order/2n||!verify('sha256',message,{key:pub(input.publicHex),dsaEncoding:'ieee-p1363'},signature))throw Error('independent signature');
 let phoneToPodjs='PASS';try{const out=await openCarrier({descriptor:v.descriptor,scope:input.scope,binding,crypto,signer,publicKey:Buffer.from(input.publicHex,'hex').toString('base64url'),key:async()=>key.slice(),read:async(h:string)=>objects.get(h)!});if(rawHash(out.get(bodyObjectName(binding.kind,binding.logicalId)))!==rawHash(raw))throw Error('bytes');}catch(err){phoneToPodjs=(err as Error).message;}
 const out=await m.openCarrier({descriptor:v.descriptor,scope:input.scope,binding,publicHex:input.publicHex,key:async()=>key.slice(),read:async(h:string)=>objects.get(h)!});if(rawHash(out)!==rawHash(raw))throw Error('mini bytes');
 const podjs=await prepareCarrier({crypto,signer,alias:'synthetic',authorDeviceId:v.descriptor.body.authorDeviceId,scope:input.scope,anchor:input.anchor,binding,key:key.slice(),keyVersion:1,objects:[{name:bodyObjectName(binding.kind,binding.logicalId),bytes:raw,compress:true}]});
 const miniPack=await m.prepareCarrier({scope:input.scope,anchor:input.anchor,binding,key:key.slice(),keyVersion:1,authorDeviceId:v.descriptor.body.authorDeviceId,privateHex,raw});
 if(binding.kind==='book'){
  await validateBookPackage(splitBookLines((async function*(){yield Buffer.from(raw).toString('utf8')})()),async b=>rawHash(b),v.value);
  const snapshotId='11111111-2222-4333-8444-555555555555';
  // Use the real legacy library exporter, including privateBook and contentSha256.
  const library=new WatchCloudLibraryAdapter({
   cloudSnapshot:async()=>({articles:[],sources:[],stateArticles:[]}),
   cloudBooks:async()=>({books:[{...v.value,title:'metadata ignored by legacy exporter',kind:'txt',sourceUrl:'https://locator.invalid/metadata'}],reading:[]}),
   cloudBookBody:async()=>raw,mergeCloud:async()=>0
  },'synthetic',{...crypto,stableId:async(s:string)=>rawHash(Buffer.from(s))},{require(){}});
  const exported=await library.exportPayloads(true);
  const source=await new WatchCloudCodec(crypto,{require(){}}).create({accountKey:key,keyVersion:1,sourceDeviceId:'synthetic',sequence:1,snapshotId,createdAtMillis:0,parentHeads:{},observedHeads:{},objects:exported.objects});
  if(source.manifest.objects[0].privateBook?.title!=='Review')throw Error('real exporter locator missing');
  const manifestHash=rawHash(source.encryptedManifest),body={...podjs.descriptor.body,sourceSnapshotId:snapshotId,manifest:{sha256:manifestHash,sizeBytes:source.encryptedManifest.length},chunks:source.manifest.objects[0].chunks.map((c:any)=>({sha256:c.ciphertextSha256,sizeBytes:c.ciphertextBytes}))};
  const descriptor={body,signature:Buffer.from(lowSign(lp(Buffer.from('wrss.cloud.body.v1/carrier-sign'),Buffer.from(canonical(body))))).toString('base64url')};
  legacyBook=pack({descriptor,objects:new Map([...source.newChunks,[manifestHash,source.encryptedManifest]])});
  // Verify the exact locator works on both reference implementations.
  const bytes=new Map(Object.entries(legacyBook.objects).map(([h,b])=>[h,un64(b as string)]));
  const legacy=await openCarrier({descriptor,scope:input.scope,binding,crypto,signer,publicKey,key:async()=>key.slice(),read:async h=>bytes.get(h)!});if(rawHash(legacy.get(bodyObjectName('book',binding.logicalId)))!==rawHash(raw))throw Error('podjs legacy');
  const legacyMini=await m.openCarrier({descriptor,scope:input.scope,binding,publicHex,key:async()=>key.slice(),read:async(h:string)=>bytes.get(h)!});if(rawHash(legacyMini)!==rawHash(raw))throw Error('mini legacy');
 }
 items.push({phoneToPodjs,phoneToMini:'PASS',podjs:pack(podjs),mini:pack(miniPack)});
 console.log(JSON.stringify({kind:binding.kind,bytes:raw.length,bodyRevision:expected,independentSignature:'PASS',phoneToPodjs,phoneToMini:'PASS'}));
}
const a=input.article,aRaw=un64(a.raw),envCrypto={random:crypto.random,hkdf:crypto.hkdf,encrypt:async(k:any,n:any,p:any,a:any)=>{const c=createCipheriv('aes-256-gcm',k,n);c.setAAD(a);return new Uint8Array(Buffer.concat([c.update(p),c.final(),c.getAuthTag()]));},decrypt:async(k:any,n:any,p:any,a:any)=>{const c=createDecipheriv('aes-256-gcm',k,n);c.setAAD(a);c.setAuthTag(p.slice(-16));return new Uint8Array(Buffer.concat([c.update(p.slice(0,-16)),c.final()]));},gunzip:crypto.gunzip};
let recipe:any;const plains=new Map();for(const o of a.objects){const decoded=await openV2(envCrypto,idKey,key,input.scope,o.oid,un64(o.bytes));plains.set(o.oid,decoded.plain);if(o.oid.startsWith('recipe:'))recipe=decoded.value;}
if(rawHash(Buffer.concat(recipe.blocks.map((b:any)=>plains.get(b.oid))))!==rawHash(aRaw)||articleBodyRevision(a.entityKey,aRaw)!==a.bodyRevision)throw Error('article mismatch');
const built=await buildArticleBody(envCrypto,{id:idKey,content:key,version:1},input.scope,a.entityKey,aRaw);
console.log(JSON.stringify({kind:'article',bytes:aRaw.length,bodyRevision:built.binding.bodyRevision,phoneToPodjs:'PASS'}));
fs.writeFileSync(outputPath,JSON.stringify({publicHex,items,legacyBook,legacyLocatorTitle:'Review',article:{bodyRevision:built.binding.bodyRevision,objects:built.objects.map(o=>({oid:o.oid,bytes:b64(o.bytes)}))}}));

// Runs the actual PodJS implementation; no copied carrier parser or serializer.
import {createHash,createPublicKey,generateKeyPairSync,sign,verify} from 'node:crypto';
const source=process.env.WATCHRSS_PODJS_BODY_SOURCE??'/home/wilf/data/OPPO_Develop/PodJS_WatchRSS.wt-notes';
const {crypto:snapshotCrypto}=await import(source+'/tests/cloud/k2-crypto.ts');
const {prepareCarrier,openCarrier,bodyObjectName}=await import(source+'/src/sync-v2/body-channel-codec.ts');
const {WatchCloudCodec}=await import(source+'/src/cloud/codec.ts');
const {writeWrss}=await import(source+'/src/cloud/wrss.ts');
const {signControl}=await import(source+'/src/sync-v2/signing.ts');
const {validateBookPackage,splitBookLines}=await import(source+'/src/books/package.ts');
const pair=generateKeyPairSync('ec',{namedCurve:'prime256v1'}),jwk=pair.publicKey.export({format:'jwk'});
const publicKey=Buffer.concat([Buffer.from([4]),Buffer.from(jwk.x!,'base64url'),Buffer.from(jwk.y!,'base64url')]);
const order=BigInt('0xffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc632551');
const signer={async sign(_:string,input:Uint8Array){const sig=sign('sha256',input,{key:pair.privateKey,dsaEncoding:'ieee-p1363'}),s=BigInt('0x'+sig.subarray(32).toString('hex'));return new Uint8Array(Buffer.concat([sig.subarray(0,32),Buffer.from((s>order/2n?order-s:s).toString(16).padStart(64,'0'),'hex')]));},async verify(key:Uint8Array,input:Uint8Array,sig:Uint8Array){const k=createPublicKey({key:{kty:'EC',crv:'P-256',x:Buffer.from(key.subarray(1,33)).toString('base64url'),y:Buffer.from(key.subarray(33)).toString('base64url')},format:'jwk'});return verify('sha256',input,{key:k,dsaEncoding:'ieee-p1363'},sig);}};
const request=JSON.parse(await Bun.file(process.argv[2]!).text()),key=new Uint8Array(32).fill(7),outputs=[];
for(const item of request.items){
 const objects=new Map<string,Uint8Array>(Object.entries(item.objects).map(([h,b])=>[h,new Uint8Array(Buffer.from(b as string,'base64'))]));
 let phoneToPodjsError:string|null=null;
 try {
  const decoded=await openCarrier({descriptor:item.descriptor,scope:request.scope,binding:item.binding,crypto:snapshotCrypto,signer,publicKey:Buffer.from(request.publicHex,'hex').toString('base64url'),key:async()=>key.slice(),read:async(h:string)=>objects.get(h)!});
  if(Buffer.from(decoded.get(bodyObjectName(item.binding.kind,item.binding.logicalId))!).toString('base64')!==item.raw)throw Error('phone-to-podjs mismatch');
 }catch(error){phoneToPodjsError=(error as Error).message;if(phoneToPodjsError!==item.expectedPodjsError)throw error;}
 if(phoneToPodjsError!==(item.expectedPodjsError??null))throw Error('unexpected oracle result');
 const raw=new Uint8Array(Buffer.from(item.raw,'base64'));
 if(item.binding.kind==='book')await validateBookPackage(splitBookLines((async function*(){yield Buffer.from(raw).toString('utf8')})()),async(b:Uint8Array)=>createHash('sha256').update(b).digest('hex'),item.value);
 const pack=(p:any)=>({descriptor:p.descriptor,objects:Object.fromEntries([...p.objects].map(([h,b]:any)=>[h,Buffer.from(b).toString('base64')]))});
 const prepared=await prepareCarrier({crypto:snapshotCrypto,signer,alias:'key',authorDeviceId:item.descriptor.body.authorDeviceId,scope:request.scope,anchor:item.descriptor.body.anchor,binding:item.binding,key:key.slice(),keyVersion:1,objects:[{name:bodyObjectName(item.binding.kind,item.binding.logicalId),bytes:raw,compress:true}]});
 // Frozen legacy bridge exercises the real v1 parser and selective object closure.
 const sourceId='11111111-2222-4333-8444-555555555555';
 const sourceObject=item.binding.kind==='asset'?{name:'notes-assets.zip',bytes:writeWrss(new Map([[bodyObjectName('asset',item.binding.logicalId),raw]])),compress:false}:{name:bodyObjectName('book',item.binding.logicalId),bytes:raw,compress:true};
 const legacy=await new WatchCloudCodec(snapshotCrypto,{require(){}}).create({accountKey:key,keyVersion:1,sourceDeviceId:'oracle',snapshotId:sourceId,sequence:1,createdAtMillis:0,parentHeads:{},observedHeads:{},objects:[sourceObject,{name:'notes-state.json',bytes:new TextEncoder().encode('{"deleted":true}'),compress:false}]});
 const selected=legacy.manifest.objects.find((o:any)=>o.name===sourceObject.name)!;
 const hash=createHash('sha256').update(legacy.encryptedManifest).digest('hex');
 const legacyDescriptor=await signControl(signer,'key','wrss.cloud.body.v1/carrier-sign',{...prepared.descriptor.body,sourceSnapshotId:sourceId,manifest:{sha256:hash,sizeBytes:legacy.encryptedManifest.length},chunks:selected.chunks.map((c:any)=>({sha256:c.ciphertextSha256,sizeBytes:c.ciphertextBytes}))});
 outputs.push({...pack(prepared),phoneToPodjsError,legacy:pack({descriptor:legacyDescriptor,objects:new Map([...legacy.newChunks,[hash,legacy.encryptedManifest]])})});
}
await Bun.write(process.argv[3]!,JSON.stringify({publicHex:publicKey.toString('hex'),items:outputs}));

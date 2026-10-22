// Reproduce with: node generate-mini-fixture.cjs /path/to/WatchRSS_WeChat output.json
// Uses the same descriptor/flow as mini tests/online-watch-setup.test.mjs and watch-setup.test.mjs.
const path=require('node:path'),fs=require('node:fs'),nodeCrypto=require('node:crypto');
const root=path.resolve(process.argv[2]);
const crypto=require(path.join(root,'miniprogram/lib/crypto.js'));
const util=require(path.join(root,'miniprogram/lib/util.js'));
const codec=require(path.join(root,'miniprogram/lib/setup-crypto.js'));
const v2=require(path.join(root,'miniprogram/lib/cloud-v2.js'));
const envelope=require(path.join(root,'miniprogram/lib/envelope.js'));
const userId='33333333-3333-4333-8333-333333333333',now=1800000000000;
const descriptor={protocol:'watchrss-online-setup-v1',transport:'internet',sessionId:Buffer.alloc(16,8).toString('base64url'),secret:Buffer.alloc(32,7).toString('base64'),deviceId:'11111111-1111-4111-8111-111111111111',installId:'22222222-2222-4222-8222-222222222222',claimToken:'a'.repeat(64),expiresAt:now+300000};
function pair(scalar){const d=Buffer.alloc(32);d[31]=scalar;const e=nodeCrypto.createECDH('prime256v1');e.setPrivateKey(d);const pub=e.getPublicKey();const spki=Buffer.concat([Buffer.from('3059301306072a8648ce3d020106082a8648ce3d030107034200','hex'),pub]);const privateKey=nodeCrypto.createPrivateKey({key:{kty:'EC',crv:'P-256',d:d.toString('base64url'),x:pub.subarray(1,33).toString('base64url'),y:pub.subarray(33).toString('base64url')},format:'jwk'}).export({format:'der',type:'pkcs8'});return{privateKey:new Uint8Array(d),publicKey:new Uint8Array(pub),spki:new Uint8Array(spki),pkcs8:privateKey.toString('base64')};}
const watch=pair(1),ephemeral=pair(2);crypto.generateP256KeyPair=()=>ephemeral;
let nonce=Uint8Array.from({length:12},(_,i)=>i);crypto.randomBytes=n=>{if(n!==12)throw Error('unexpected random request');return nonce.slice();};
const phonePlain=JSON.stringify({action:'prepare',userId,phoneMasked:'138****8000'});
const phoneWire=codec.relayWire(codec.seal(descriptor,'phone',0,JSON.parse(phonePlain)));
nonce=Uint8Array.from({length:12},(_,i)=>i+12);
const watchPlain=JSON.stringify({success:true,deviceId:descriptor.deviceId,userId,publicKeySpki:util.base64PaddedEncode(watch.spki)});
const watchWire=codec.relayWire(codec.seal(descriptor,'watch',0,JSON.parse(watchPlain)));
nonce=new Uint8Array(12).fill(9);const key=new Uint8Array(32).fill(7),epoch='44444444-4444-4444-8444-444444444444';
const env={...envelope.createDeviceEnvelope({accountKey:key,userId,recipientDeviceId:descriptor.deviceId,recipientPublicKeyUncompressed:watch.publicKey,keyVersion:1}),recipientType:'device',recipientDeviceId:descriptor.deviceId,createdByDeviceId:'phone'};
const attestation={keyVersion:1,mac:v2.attest(key,userId,epoch,descriptor.deviceId,watch.spki)};
const fixture={source:['tests/online-watch-setup.test.mjs','tests/watch-setup.test.mjs','miniprogram/lib/setup-crypto.js','miniprogram/lib/cloud-v2.js','miniprogram/lib/envelope.js'],now,userId,epoch,descriptor,qr:'wrss-pair:'+Buffer.from(JSON.stringify(descriptor)).toString('base64url'),sas:v2.pairingSas(Buffer.from(descriptor.secret,'base64'),descriptor.sessionId,userId),phone:{plaintext:phonePlain,wire:phoneWire},watch:{plaintext:watchPlain,wire:watchWire},keyHex:Buffer.from(key).toString('hex'),watchPrivatePkcs8:watch.pkcs8,keyPayload:JSON.stringify({envelopes:[{keyVersion:1,envelope:env}],attestation})};
fs.writeFileSync(process.argv[3],JSON.stringify(fixture,null,2)+'\n');

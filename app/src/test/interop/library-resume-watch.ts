// Opt-in host peer. Executes the pinned PodJS application coordinator/repository,
// with its isolated SQLite + NativeFixture (no Android runtime or network).
import {createInterface} from 'node:readline';
import {resolve} from 'node:path';
const repo=process.argv[2];
if(!repo)throw Error('watch repository required');
const {fixture,vectors}=await import(resolve(repo,'tests/helpers/library-resume-fixture.ts'));
const f=await fixture();
const body=Buffer.from(vectors.bodyBase64,'base64');
const c=f.connect({source:async()=>({objects:vectors.objects,read:async(d:any,index:number)=>{
 if(d.kind!=='articleBody')return new Uint8Array();
 return new Uint8Array(body.subarray(index*65536,Math.min(body.length,(index+1)*65536)));
}})});
const lines=createInterface({input:process.stdin});
const incoming=(async()=>{for await(const line of lines){await c.phone.connection.writeFrame(JSON.parse(line));}})();
const outgoing=(async()=>{try{for(;;){const r=await c.phone.connection.readFrame(120000);process.stdout.write(JSON.stringify(r)+'\n');}}catch{}})();
await incoming;
await c.pair.close();await c.running;await outgoing;f.sql.close();

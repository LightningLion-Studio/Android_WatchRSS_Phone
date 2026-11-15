// Opt-in host peer. Executes the pinned PodJS application coordinator/repository,
// with its isolated SQLite + NativeFixture (no Android runtime or network).
import {createInterface} from 'node:readline';
import {resolve} from 'node:path';
import {once} from 'node:events';
const repo=process.argv[2],binary=process.argv[3]==='--binary';
if(!repo)throw Error('watch repository required');
const {fixture,vectors}=await import(resolve(repo,'tests/helpers/library-resume-fixture.ts'));
const f=await fixture();
const body=Buffer.from(vectors.bodyBase64,'base64');
const c=f.connect({source:async()=>({objects:vectors.objects,read:async(d:any,index:number)=>{
 if(d.kind!=='articleBody')return new Uint8Array();
 return new Uint8Array(body.subarray(index*65536,Math.min(body.length,(index+1)*65536)));
}})});
let stopping=false;
const incoming=(async()=>{
 if(!binary){for await(const line of createInterface({input:process.stdin}))await c.phone.connection.writeFrame(JSON.parse(line));return;}
 let buffered=Buffer.alloc(0);
 for await(const part of process.stdin){
  buffered=Buffer.concat([buffered,part]);
  while(buffered.length>=4){
   const size=buffered.readInt32BE(0);
   if(size<1||size>262144)throw Error('invalid binary frame size');
   if(buffered.length<size+4)break;
   await c.phone.connection.writeFrame(JSON.parse(buffered.subarray(4,size+4).toString('utf8')));
   buffered=buffered.subarray(size+4);
  }
 }
 if(buffered.length)throw Error('truncated binary frame');
})().catch(e=>{if(!stopping)throw e;});
const outgoing=(async()=>{try{for(;;){
 const r=await c.phone.connection.readFrame(120000),json=Buffer.from(JSON.stringify(r));
 let packet:Buffer;
 if(binary){const prefix=Buffer.alloc(4);prefix.writeInt32BE(json.length);packet=Buffer.concat([prefix,json]);}
 else packet=Buffer.concat([json,Buffer.from('\n')]);
 if(!process.stdout.write(packet))await once(process.stdout,'drain');
}}catch{}})();
if(binary){
 // Successful bilateral completion closes the watch coordinator. Let the phone
 // observe EOF only after all complete replies passed its real byte dispatcher.
 await Promise.race([incoming,c.running]);stopping=true;process.stdin.destroy();
 await c.pair.close();await Promise.allSettled([incoming,c.running,outgoing]);
 f.sql.close();if(c.errors.length)throw Error('watch coordinator failed: '+String(c.errors[0]));
}else{
 await incoming;await c.pair.close();await c.running;await outgoing;f.sql.close();
}

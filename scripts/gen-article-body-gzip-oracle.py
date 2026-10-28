#!/usr/bin/env python3
"""Reproduce the eight PodJS Java gzip oracle members; no device or network access."""
import base64
import hashlib
import json
from pathlib import Path
import subprocess
import tempfile

PHONE = Path(__file__).resolve().parents[1]
FRAMEWORK = Path('/home/wilf/data/PodJS/podjs-watch')
JSON_JAR = next((Path.home()/'.gradle/caches/modules-2/files-2.1/org.json/json/20240303').rglob('*.jar'))
SOURCE_PATH = 'platforms/android/runtime/src/main/java/dev/podjs/runtime/PodGzipJson.java'
COMMIT = subprocess.check_output(['git','-C',str(FRAMEWORK),'rev-parse','7bfe1e3'],text=True).strip()
ORACLE_BYTES = subprocess.check_output(['git','-C',str(FRAMEWORK),'show',f'{COMMIT}:{SOURCE_PATH}'])
CASES = [dict(a=None,b=''),dict(a='<p>中文🙂 / slash\\ "\n</p>',b='text '*1024),
         dict(a='\ud800 lone \udfff',b='\0\b\f\t\r\n /'),dict(a='a'*40000,b='中文 /'*2000)]
BRIDGE = r'''
package dev.podjs.runtime;
import java.io.*;import java.nio.file.*;import java.nio.charset.StandardCharsets;import java.util.*;import java.util.zip.GZIPOutputStream;import org.json.*;
public class PhoneBodyOracle {
 public static void main(String[] args)throws Exception {
  File root=new File(args[0]);JSONArray cases=new JSONArray(Files.readString(root.toPath().resolve("input.json"))),out=new JSONArray();
  for(int i=0;i<cases.length();i++)for(boolean article:new boolean[]{false,true}) {
   JSONArray fields=new JSONArray().put(new JSONObject().put("name",article?"contentHtml":"first").put("column","a").put("omitNull",true))
    .put(new JSONObject().put("name",article?"contentText":"second").put("column","b"));
   JSONObject row=cases.getJSONObject(i),result=PodGzipJson.fingerprint(row,fields,root,"",131072,1048576,()->{});
   byte[] plain=PodGzipJson.json(row,fields,root,1048576,()->{}).getBytes(StandardCharsets.UTF_8);
   ByteArrayOutputStream sink=new ByteArrayOutputStream();try(GZIPOutputStream gzip=new GZIPOutputStream(sink)){gzip.write(plain);}byte[] zipped=sink.toByteArray();
   Arrays.fill(zipped,4,8,(byte)0);zipped[8]=0;zipped[9]=(byte)255;
   result.put("name",(article?"article-":"generic-")+i).put("article",article)
    .put("utf8Base64",Base64.getEncoder().encodeToString(plain)).put("gzipBase64",Base64.getEncoder().encodeToString(zipped));out.put(result);
  }
  Files.writeString(root.toPath().resolve("output.json"),out.toString());
 }
}'''
with tempfile.TemporaryDirectory(prefix='phone-body-oracle-') as work:
    work = Path(work)
    (work/'input.json').write_text(json.dumps(CASES,ensure_ascii=True))
    (work/'PhoneBodyOracle.java').write_text(BRIDGE)
    (work/'PodGzipJson.java').write_bytes(ORACLE_BYTES)
    subprocess.run(['javac','-cp',str(JSON_JAR),'-d',str(work),str(work/'PodGzipJson.java'),str(work/'PhoneBodyOracle.java')],check=True)
    subprocess.run(['java','-cp',f'{work}:{JSON_JAR}','dev.podjs.runtime.PhoneBodyOracle',str(work)],check=True)
    rows=json.loads((work/'output.json').read_text())
    for at,row in enumerate(rows):
        row["input"]=CASES[at//2]
        zipped=base64.b64decode(row['gzipBase64'])
        assert zipped[:10].hex()=='1f8b08000000000000ff'
        assert hashlib.sha256(zipped).hexdigest()==row['sha256']
        assert len(zipped)==row['size']
    vector={'format':'watchrss.article-body.java-oracle.v1','frameworkCommit':COMMIT,
            'oracleSourceSha256':hashlib.sha256(ORACLE_BYTES).hexdigest(),'cases':rows}
    target=PHONE/'app/src/test/resources/ble/article-body-gzip-oracle.json'
    target.write_text(json.dumps(vector,ensure_ascii=True,sort_keys=True,indent=2)+'\n')
    print(f'{len(rows)} members -> {target}; sha256={hashlib.sha256(target.read_bytes()).hexdigest()}')

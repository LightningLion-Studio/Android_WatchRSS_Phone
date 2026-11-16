"""Generate local-only corpus. The manifest hashes exact bytes, including controls."""
import argparse,hashlib,json
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('--out',type=Path,required=True);p.add_argument('--user-sample',type=Path,required=True);a=p.parse_args();a.out.mkdir(parents=True,exist_ok=True);entries=[]
def put(name,value,purpose,issues=()):
 data=value if isinstance(value,bytes) else value.encode('utf-8');(a.out/name).write_bytes(data)
 entries.append(dict(file=name,sha256=hashlib.sha256(data).hexdigest(),bytes=len(data),purpose=purpose,issues=list(issues)))
def vector(id,value,purpose):put(id+'.json',json.dumps(value,ensure_ascii=True,indent=2)+'\n',purpose,[id])
static={'empty':'','single':'x','ordered-dot':'7. seven\n12. twelve','ordered-paren':'7) seven\n12) twelve','number-glued':'4.a\n12.b\n1.2 decimal','dash':'- a\n- b','star':'* a\n* b','plus':'+ a\n+ b','heading':'# a','quote':'> a\n> b','code':'```\nliteral\n```','html-code':'```html\n<img src="x">\n```','html-script-code':'```html\n<script>x</script>\n<p>literal</p>\n```','table':'| a | b |\n|---|---|\n| c | d |','link':'[a](https://example.invalid)','html':'<p>a <u>b</u></p>','backslash':'\\# a\n\\1. b\n\\\\x','hardbreak':'a  \nb','softbreak':'a\nb','combining':'e\u0301é','emoji-zwj':'👨‍👩‍👧‍👦','rtl':'abc\u202eאבג\u202c xyz','nul':'x\0y','title-scalar-cut':'.'*79+'😀','long-line':'x'*(1024*1024),'many-lines':('a\n'*20000),'json-escaping':'"\\\b\f\t\n\r\u0000\u2028\u2029','mixed-unicode':'\ufeff\t\u00a0\u3000\u200b\r\n\r\u2028\u2029e\u0301é👨‍👩‍👧‍👦אבג\\"\0'}
for kind,ch in {'tab':'\t','nbsp':'\u00a0','ideographic':'\u3000','zero-width':'\u200b','bom':'\ufeff','cr':'\r','crlf':'\r\n','ls':'\u2028','ps':'\u2029'}.items():static['whitespace-'+kind]=ch+'1. a'+ch+'2. b'+ch
for n in [65534,65535,65536,245759,245760,245761,262143,262144,262145]:static['boundary-'+str(n)]='x'*n+'😀\\"\r\n'
for name,text in static.items():put(name+'.txt',text,'Exact input for codec, renderer or Unicode boundary adversarial coverage')
put('user-sample-vocab.txt',a.user_sample.read_bytes(),'Unmodified user RCA sample; byte hash provenance')
vector('NA-01',{'base':'a','local':'x\na','remote':'b'},'Insertion touches replacement start; loss-free merge or conflict')
vector('NA-02',{'base':'a','local':'x\na','remote':'y\na','reversePeers':True},'Peer-order convergence')
vector('NA-03',{'title':'Manual title','markdown':'x\r\ny'},'Export/import title and body retention (not editor pixel oracle)')
vector('NA-04',{'base':'a','local':'local','localAt':101,'remote':'remote','remoteAt':300,'path':'syncNotes'},'Bluetooth loses divergent unsent local version')
vector('NA-05',{'initial':'a','remote':'remote','remoteAt':200,'beforeRemoteWrite':{'markdown':'unsent local','updatedAt':300}},'Suspend-boundary local/cloud interleaving')
vector('NA-06',{'base':'a','local':'local edit','remote':'a','remoteDeleted':True,'remoteAt':300},'Delete versus local edit must be explicit')
vector('NA-07',{'a':'a','local':'b','localBase':'a','remote':'c','remoteBase':'b'},'Sequential edits with immediate remote ancestor')
vector('NA-08',{'base':'a','local':'b','remote':'c','resolve':'resolved','replayRemote':'c'},'Resolved remote replay')
vector('NA-09',{'initialAt':1000,'pinAt':100,'replayAt':1000},'Clock rollback and metadata overwrite')
vector('NA-10',{'base':'a\nb','local':'A\nb','remote':'a\nB','remoteFolder':'peer-folder','remoteTitle':'Peer title'},'Disjoint body merge with uncontested metadata')
vector('NA-11',{'initial':'','sequences':[['paste','# a'],['paste','[a](https://example.invalid)'],['paste','1. a\n6. b'],['paste','a  \nb'],['text','a','select-all','underline'],['text','a','select-all','alignment-center'],['text','a','select-all','highlight'],['text','a','select-all','bold'],['text','a','select-all','heading'],['text','a','caret-end','ordered-list','insert-newline-b'],['load-markdown','1. a\n2. b\n3. c','delete-range-5-10'],['load-markdown','1. a\n2. b\n3. c','select-all','remove-ordered-list'],['text','a','caret-end','insert-newline']],'oracle':'editing render == save/reopen render; byte equality is NOT editor acceptance'},'Real RichTextState operation sequences; compare semantic render trees')
vector('NA-12',{'markdown':static['html-code']},'Fenced HTML must remain literal')
vector('NA-13',{'initial':'<p>a</p><p></p>','edited':'<p>a</p><p>  </p>','bodyChanged':True},'Mini whitespace edit swallowed by dirty comparison')
vector('NA-14',{'markdown':static['html-script-code']},'Mini editor HTML detection before fence parsing')
vector('NA-15',{'markdown':static['title-scalar-cut'],'title':''},'pC3 title UTF-16 cap splits emoji')
vector('NA-16',{'id':'n','base':'a','device':'peer','conflicts':[{'local':'x\0y','remote':'z'},{'local':'x','remote':'y\0z'}]},'Ambiguous NUL-delimited conflict identity')
vector('NA-17',{'markdown':static['code']},'Actual watch preview generator drops every code row')
vector('NA-18',{'initialText':'a','sequence':['save using NoteVisualDocument','peer raw editor changes visible >a< to >b<, keeps data attribute','phone reopens and appends c','save'],'expectedText':'bc'},'Parallel pC2 visual snapshot versus peer edited fallback')
vector('NA-19',{'markdown':'<div data-watchrss-editor-v1="'},'Truncated wrapper must not decode as successful empty rich state')
vector('UNCONFIRMED-01',{'sequence':['loadEditor <p>a</p>','setContents.complete','user input <p>ax</p> before getContents.success','getContents.success <p>ax</p>','leave page']},'Mini native callback ordering candidate; no native acceptance')
vector('UNCONFIRMED-02',{'format':'watchrss-notes','version':1,'notes':[],'folders':[{'id':'f','name':'Folder','sortOrder':0,'updatedAt':100,'modifiedBy':'compose','deleted':False,'deletedAt':0}]},'Frozen Compose optional folders field; legacy endpoint capability review')
vector('UNCONFIRMED-03',{'title':'x'*100000,'markdown':'a'},'Very long hand title: UI and v1/v2 size-policy boundary')
vector('UNCONFIRMED-04',{'title':'\ud83d','markdown':'x'},'Ill-formed UTF-16 wire literal; compare permissive v1 with strict v2')
(a.out/'manifest.json').write_text(json.dumps({'format':'T-NOTES-ADVERSARIAL-corpus-v1','encoding':'UTF-8 unless JSON escapes state otherwise','entries':entries},ensure_ascii=False,indent=2)+'\n')
print('corpus entries',len(entries))

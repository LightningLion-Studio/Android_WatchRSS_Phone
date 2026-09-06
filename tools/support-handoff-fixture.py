from http.server import ThreadingHTTPServer, BaseHTTPRequestHandler
import json
class Handler(BaseHTTPRequestHandler):
 def log_message(self,*args): pass
 def do_GET(self):
  if self.path.endswith('/agreement'): obj={'version':'fixture','content':'fixture','accepted':True}
  elif self.path.endswith('/conversations'): obj={'conversations':[{'id':'fixture'}]}
  else: obj={'messages':[{'id':'handoff','content':'导入完报错','answer':'您的问题需要寻求人工客服的帮助。是否同意上传日志？','sources':[],'actions':[{'kind':'log_upload_consent'},{'kind':'navigation','target':'fonts'}],'status':'ok'}]}
  self.reply(obj)
 def do_POST(self):
  body=json.loads(self.rfile.read(int(self.headers.get('Content-Length',0))) or '{}')
  if self.path.endswith('/messages'):
   payload='event: done\ndata: '+json.dumps({'answer':'悬浮窗中的新回答','sources':[],'actions':[]})+'\n\n'
   self.send_response(200); self.send_header('Content-Type','text/event-stream'); self.send_header('Content-Length',str(len(payload.encode()))); self.end_headers(); self.wfile.write(payload.encode())
  else: self.reply({'id':'fixture'})
 def reply(self,obj):
  b=json.dumps(obj).encode();self.send_response(200);self.send_header('Content-Type','application/json');self.send_header('Content-Length',str(len(b)));self.end_headers();self.wfile.write(b)
ThreadingHTTPServer(('127.0.0.1',18089),Handler).serve_forever()

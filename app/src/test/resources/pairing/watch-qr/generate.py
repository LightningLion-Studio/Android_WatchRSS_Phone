"""Deterministic simulated 466px OLED watch captures; no real account/photograph data.
Requires libqrencode (qrencode), Pillow, numpy and opencv-python for regeneration only.
The PNGs are checked in; normal JUnit runs do not need these tools.
"""
import hashlib
import io
import json
import pathlib
import subprocess
import cv2
import numpy as np
from PIL import Image, ImageDraw, ImageFilter

out = pathlib.Path(__file__).resolve().parent
payload = json.loads((out.parent / "mini-online-pairing.json").read_text())["qr"]
raw = subprocess.check_output(["qrencode", "-l", "L", "-s", "3", "-m", "4", "-o", "-"], input=payload.encode())
qr = Image.open(io.BytesIO(raw)).convert("RGBA").convert("L")
assert qr.width <= 326  # inscribed white square stays within the round 466px screen
base = Image.new("L", (466, 466), 22)
ImageDraw.Draw(base).ellipse((0, 0, 465, 465), fill=0)
base.paste(qr, ((466-qr.width)//2, (466-qr.height)//2))
base.save(out / "oled-466.png")
rows = []
def save(name, image, parameters):
    image.convert("L").save(out / name)
    rows.append({"file":name,"parameters":parameters,"sha256":hashlib.sha256((out/name).read_bytes()).hexdigest()})
save("oled-466.png", base, {"screen":466,"qrSquare":qr.width,"ecc":"L","quietZoneModules":4})
def scale(size):
    result=Image.new("L",(466,466),32)
    result.paste(base.resize((size,size),Image.Resampling.BILINEAR),((466-size)//2,(466-size)//2))
    return result
save("scaled-350.png",scale(350),{"watchDiameter":350,"resample":"bilinear"})
save("scaled-300.png",scale(300),{"watchDiameter":300,"resample":"bilinear"})
corners=np.float32([[0,0],[465,0],[465,465],[0,465]])
dst=np.float32([[62,20],[440,63],[455,404],[10,453]])
perspective=cv2.warpPerspective(np.array(base),cv2.getPerspectiveTransform(corners,dst),(466,466),borderValue=32)
save("perspective.png",Image.fromarray(perspective),{"screenCorners":dst.tolist(),"interpolation":"linear"})
save("blur-060.png",base.filter(ImageFilter.GaussianBlur(.6)),{"gaussianSigma":.6})
save("dim.png",Image.fromarray(np.clip(np.array(base,dtype=float)*.44+14,0,255).astype(np.uint8)),{"gain":.44,"offset":14})
y,x=np.mgrid[0:466,0:466]
glare=115*np.exp(-((x-.28*y-216)/34)**2/2)
reflective=np.clip(np.array(base,dtype=float)*.78+glare+6,0,255).astype(np.uint8)
save("reflection.png",Image.fromarray(reflective),{"gain":.78,"glarePeak":115,"glareSigma":34,"glareLine":"x-.28*y-216","offset":6})
combined=Image.fromarray(perspective).filter(ImageFilter.GaussianBlur(.45))
save("perspective-dim-blur.png",Image.fromarray(np.clip(np.array(combined,dtype=float)*.68+12,0,255).astype(np.uint8)),{"perspective":"perspective.png","gaussianSigma":.45,"gain":.68,"offset":12})
save("rotated-90.png",base.transpose(Image.Transpose.ROTATE_90),{"rotation":90})
save("inverted.png",Image.fromarray(255-np.array(base)),{"inverted":True,"purpose":"tryInvert regression, not ordinary dark-on-white watch UI"})
manifest={"description":"Synthetic watch-like OLED captures, not camera/physical-device acceptance", "payload":payload,"ecc":"L","fixtures":rows}
(out/"manifest.json").write_text(json.dumps(manifest,indent=2)+"\n")

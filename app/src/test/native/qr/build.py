"""Linux host JNI for the exact 3.1.1 Android wrapper/core; not packaged in the APK."""
import hashlib
import pathlib
import subprocess
import sys
import tarfile
import urllib.request

VERSION = "3.1.1"
SHA256 = "7286b1e6ade66fe82b7c8208b4595deeb55d6486b410834fdc65702f46650542"
out = pathlib.Path(sys.argv[1]).resolve()
out.mkdir(parents=True, exist_ok=True)
archive = out / f"zxing-cpp-{VERSION}.tar.gz"
if not archive.exists():
    urllib.request.urlretrieve(f"https://codeload.github.com/zxing-cpp/zxing-cpp/tar.gz/refs/tags/v{VERSION}", archive)
if hashlib.sha256(archive.read_bytes()).hexdigest() != SHA256:
    raise RuntimeError("Upstream ZXing-C++ source checksum mismatch")
source = out / f"zxing-cpp-{VERSION}"
if not source.exists():
    with tarfile.open(archive) as tar:
        tar.extractall(out, filter="data")
here = pathlib.Path(__file__).resolve().parent
subprocess.run(["cmake", "-S", str(here), "-B", str(out / "cmake"),
                f"-DZXING_SOURCE={source}", f"-DJAVA_HOME={sys.argv[2]}", f"-DCMAKE_LIBRARY_OUTPUT_DIRECTORY={out}",
                "-DCMAKE_BUILD_TYPE=Release"], check=True)
subprocess.run(["cmake", "--build", str(out / "cmake"), "--parallel", "4"], check=True)

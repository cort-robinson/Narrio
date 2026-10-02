"""Capture native Android evidence and exercise the real UI; requires adb."""
import pathlib, re, subprocess, time, xml.etree.ElementTree as ET

ADB = r"C:\Users\cortr\AppData\Local\Android\Sdk\platform-tools\adb.exe"
SERIAL = "emulator-5554"
OUT = pathlib.Path(".impeccable/review")

def adb(*args):
    return subprocess.check_output([ADB, "-s", SERIAL, *args]).decode(errors="replace")

def nodes():
    adb("shell", "uiautomator", "dump", "/sdcard/narrio-ui.xml")
    return list(ET.fromstring(adb("shell", "cat", "/sdcard/narrio-ui.xml")).iter("node"))

def click(label, desc=False, exact=True):
    for n in nodes():
        value = n.get("content-desc" if desc else "text", "")
        if value == label if exact else label in value:
            a = [int(x) for x in re.findall(r"\d+", n.get("bounds"))]
            if a[2] > a[0] and a[3] > a[1]:
                adb("shell", "input", "tap", str((a[0]+a[2])//2), str((a[1]+a[3])//2)); time.sleep(1)
                return
    raise RuntimeError("UI target not visible: " + label)

def capture(name):
    OUT.mkdir(parents=True, exist_ok=True)
    raw = subprocess.check_output([ADB,"-s",SERIAL,"exec-out","screencap","-p"])
    # Multi-display emulators can prefix stdout with a warning before the PNG.
    start = raw.find(b"\x89PNG\r\n\x1a\n")
    if start < 0:
        raise RuntimeError("The device did not return a PNG capture")
    (OUT / (name+".png")).write_bytes(raw[start:])
    print("captured",name,flush=True)

def texts():
    return [(n.get("text") or n.get("content-desc"), n.get("bounds")) for n in nodes() if n.get("text") or n.get("content-desc")]

if __name__ == "__main__":
    print(texts())

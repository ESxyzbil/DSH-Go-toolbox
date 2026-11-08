import sys, zipfile

apk, src, arc = sys.argv[1], sys.argv[2], sys.argv[3]
with zipfile.ZipFile(apk, "a", zipfile.ZIP_DEFLATED) as z:
    z.write(src, arc)
print("added", arc)

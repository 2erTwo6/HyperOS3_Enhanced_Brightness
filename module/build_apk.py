#!/usr/bin/env python3
"""Assemble the LSPosed module apk: hand-rolled AXML manifest + dex + assets/xposed_init, v1-signed.

Adapted from Hyper-Sunlight-Unlocker's build_apk.py (2erTwo6, MIT).
AXML layout (validated with androguard round-trip):
  container 0x0003 {type,hdr=8,size=total} | pool(0x0001) | resmap(0x0180) | NS on/off | tags
  START_ELEMENT 0x0102: hdr16{line,comment} ext{ns,name,attrStart=20,attrSize=20,attrCount,id,class,style} attrs*20
  END_ELEMENT   0x0103: hdr16{line,comment} ext{ns,name} = 24
  NAMESPACE     0x0100/0x0101: hdr16{line,comment} ext{prefix,uri} = 24
  attr(20): {ns,name,rawValue, typed{size=8,res0,dtype,data}}
"""
import struct, subprocess, os, sys, zipfile

ATTR = {
    'name': 0x01010003, 'value': 0x01010024, 'label': 0x01010001,
    'minSdkVersion': 0x0101020c, 'targetSdkVersion': 0x01010270,
    'versionCode': 0x0101021b, 'versionName': 0x0101021c,
}
NS_ANDROID = 'http://schemas.android.com/apk/res/android'
PKG = 'com.hyperos.brightness.lsp'
DESC = 'HyperOS3: sunlight-mode cap unlock + auto-brightness curve boost (merged, runtime adjustable)'
OUT = 'HyperOS-Enhanced-Brightness.apk'

def build_pool(strings):
    """UTF-16 LE unsorted pool. headerSize=28; offsets array at +28; strings at stringsStart."""
    data, offs, acc = b'', [], 0
    for t in strings:
        offs.append(acc)
        enc = t.encode('utf-16-le')
        assert len(t) < 0x8000
        data += struct.pack('<H', len(t)) + enc + b'\x00\x00'
        acc += 2 + len(enc) + 2
    while len(data) % 4:
        data += b'\x00'
    scount = len(strings)
    sstart = 28 + 4 * scount
    size = sstart + len(data)
    while size % 4:
        size += 1
    out = struct.pack('<HHIIIIII', 0x0001, 28, size, scount, 0, 0, sstart, 0)
    out += b''.join(struct.pack('<I', o) for o in offs) + data
    assert len(out) == size, (len(out), size)
    return out

# (tag, attrs); tag starting with '/' closes the element. attrs: (ns|None, name, kind, val)
DOC = [
    ('manifest', [
        (None, 'package', 'str', PKG),
        (NS_ANDROID, 'versionCode', 'int', int(os.environ.get('HBR_VERSION_CODE') or 1)),
        (NS_ANDROID, 'versionName', 'str', os.environ.get('HBR_VERSION_NAME') or '1.0'),
    ]),
    ('uses-sdk', [
        (NS_ANDROID, 'minSdkVersion', 'int', 29),
        (NS_ANDROID, 'targetSdkVersion', 'int', 29),
    ]),
    ('/uses-sdk', []),
    ('application', [(NS_ANDROID, 'label', 'str', 'HyperOS Enhanced Brightness')]),
    ('meta-data', [(NS_ANDROID, 'name', 'str', 'xposedmodule'), (NS_ANDROID, 'value', 'str', 'true')]),
    ('/meta-data', []),
    ('meta-data', [(NS_ANDROID, 'name', 'str', 'xposeddescription'), (NS_ANDROID, 'value', 'str', DESC)]),
    ('/meta-data', []),
    ('meta-data', [(NS_ANDROID, 'name', 'str', 'xposedminversion'), (NS_ANDROID, 'value', 'str', '93')]),
    ('/meta-data', []),
    ('meta-data', [(NS_ANDROID, 'name', 'str', 'xposedscope'), (NS_ANDROID, 'value', 'str', 'android')]),
    ('/meta-data', []),
    # 不声明 xposedsharedprefs：hook 只读 persist 属性，不需要 XSharedPreferences；
    # 声明它会让 LSPosed 把本 App 的 SharedPreferences 重定向到 misc 目录，
    # 造成「设置过又变回出厂值」的双份 prefs 问题。
    ('activity', [
        (NS_ANDROID, 'name', 'str', 'hbr.MainActivity'),
        (NS_ANDROID, 'label', 'str', 'HyperOS Enhanced Brightness'),
    ]),
    ('intent-filter', []),
    ('action', [(NS_ANDROID, 'name', 'str', 'android.intent.action.MAIN')]),
    ('/action', []),
    ('category', [(NS_ANDROID, 'name', 'str', 'android.intent.category.LAUNCHER')]),
    ('/category', []),
    ('/intent-filter', []),
    ('/activity', []),
    ('/application', []),
    ('queries', []),
    ('package', [(NS_ANDROID, 'name', 'str', 'android.miui')]),
    ('/package', []),
    ('/queries', []),
    ('/manifest', []),
]

def build_axml():
    strings, idx = [], {}
    def S(t):
        if t not in idx:
            idx[t] = len(strings)
            strings.append(t)
        return idx[t]
    S(NS_ANDROID); S('android')  # uri, prefix
    for tag, attrs in DOC:
        S(tag.lstrip('/'))
        for (ns, nm, kind, val) in attrs:
            S(nm)
            if kind == 'str': S(val)
    uri_i, prefix_i = idx[NS_ANDROID], idx['android']
    # resource map: pool index -> framework attr id ('package' attr is a non-framework attr -> 0)
    ids = [0 if t == 'package' else ATTR.get(t, 0) for t in strings]
    pool = build_pool(strings)
    resmap = struct.pack('<HHI', 0x0180, 8, 8 + 4 * len(ids)) + b''.join(struct.pack('<I', r) for r in ids)

    def attr_bytes(a):
        ns, nm, kind, val = a
        ns_i = 0xffffffff if ns is None else uri_i
        if kind == 'str':
            vi = idx[val]
            return struct.pack('<III', ns_i, idx[nm], vi) + struct.pack('<HBBI', 8, 0, 3, vi)
        return struct.pack('<III', ns_i, idx[nm], 0xffffffff) + struct.pack('<HBBI', 8, 0, 0x10, val)

    def start_el(tag, attrs):
        ext = struct.pack('<II', 0xffffffff, idx[tag])
        ext += struct.pack('<HHHHHH', 20, 20, len(attrs), 0, 0, 0)
        ext += b''.join(attr_bytes(a) for a in attrs)
        node = struct.pack('<HHIII', 0x0102, 16, 16 + len(ext), 1, 0xffffffff)
        return node + ext
    def end_el(tag):
        return struct.pack('<HHIIIII', 0x0103, 16, 24, 1, 0xffffffff, 0xffffffff, idx[tag])
    ns_on = struct.pack('<HHIIIII', 0x0100, 16, 24, 1, 0xffffffff, prefix_i, uri_i)
    ns_off = struct.pack('<HHIIIII', 0x0101, 16, 24, 1, 0xffffffff, prefix_i, uri_i)
    body = ns_on
    for tag, attrs in DOC:
        body += start_el(tag, attrs) if not tag.startswith('/') else end_el(tag[1:])
    body += ns_off
    total = 8 + len(pool) + len(resmap) + len(body)
    return struct.pack('<HHI', 0x0003, 8, total) + pool + resmap + body

def ensure_keystore():
    ks = 'signing.keystore'
    if not os.path.exists(ks):
        subprocess.run(['keytool', '-genkeypair', '-v', '-keystore', ks, '-alias', 'hbr',
                        '-keyalg', 'RSA', '-keysize', '2048', '-validity', '10000',
                        '-storepass', 'hbrboost', '-keypass', 'hbrboost',
                        '-dname', 'CN=HyperOS Enhanced Brightness'], check=True, capture_output=True)
    return ks

def main():
    manifest = build_axml()
    with zipfile.ZipFile(OUT, 'w', zipfile.ZIP_DEFLATED) as z:
        z.writestr('AndroidManifest.xml', manifest)
        z.writestr('classes.dex', open('dexout/classes.dex', 'rb').read())
        z.writestr('assets/xposed_init', b'hbr.Hook\n')
    ks = ensure_keystore()
    r = subprocess.run(['jarsigner', '-keystore', ks, '-storepass', 'hbrboost',
                        '-digestalg', 'SHA-256', '-sigalg', 'SHA256withRSA', OUT, 'hbr'],
                       capture_output=True, text=True)
    if r.returncode != 0:
        print('JARSIGNER FAIL:', r.stderr[:800]); sys.exit(1)
    v = subprocess.run(['jarsigner', '-verify', OUT], capture_output=True, text=True)
    if 'jar verified' not in v.stdout + v.stderr:
        print('VERIFY FAIL'); sys.exit(1)
    print('built:', OUT, os.path.getsize(OUT), 'bytes')

if __name__ == '__main__':
    main()
import re, subprocess, time, sys
ADB='adb'
def dump(tag):
    subprocess.run([ADB,'shell','uiautomator','dump',f'/sdcard/{tag}.xml'],capture_output=True)
    subprocess.run([ADB,'pull',f'/sdcard/{tag}.xml',f'/tmp/{tag}.xml'],capture_output=True)
    return open(f'/tmp/{tag}.xml',encoding='utf-8',errors='ignore').read()
def parse(xml):
    out=[]
    for t in re.findall(r'<node[^>]*/?>',xml):
        tx=re.search(r'text="([^"]*)"',t); cd=re.search(r'content-desc="([^"]*)"',t)
        b=re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"',t)
        if b:
            x1,y1,x2,y2=map(int,b.groups())
            out.append(((tx.group(1) if tx else '').strip(),(cd.group(1) if cd else '').strip(),
                        'clickable="true"' in t,(x1+x2)//2,(y1+y2)//2))
    return out
def find(cands,tag,use_desc=True):
    xml=dump(tag); ns=parse(xml)
    for c in cands:
        for tx,cd,cl,x,y in ns:
            if use_desc and cd and c.lower() in cd.lower(): return (c,x,y,ns)
            if c.lower()==tx.lower() or c.lower() in tx.lower(): return (c,x,y,ns)
    return (None,None,None,ns)
def tapxy(x,y):
    subprocess.run([ADB,'shell','input','tap',str(x),str(y)]); time.sleep(2.5)

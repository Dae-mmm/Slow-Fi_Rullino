import sys
BASE='/home/claude/slow-fi_rullino/app/src/main/java/com/rullino/app/'
class F:
    def __init__(s,p): s.p=BASE+p; s.t=open(s.p).read()
    def r(s,a,b,cnt=1):
        c=s.t.count(a)
        if c!=cnt: raise SystemExit(f"{s.p}: expected {cnt} got {c}: {a[:100]!r}")
        s.t=s.t.replace(a,b)
    def imp(s,*imps):
        for i in imps:
            line='import '+i+'\n'
            if line not in s.t:
                k=s.t.index('\nimport ')+1
                s.t=s.t[:k]+line+s.t[k:]
    def cut(s,start,end_marker):
        a=s.t.index(start); b=s.t.index(end_marker,a)
        s.t=s.t[:a]+s.t[b:]
    def save(s): open(s.p,'w').write(s.t)

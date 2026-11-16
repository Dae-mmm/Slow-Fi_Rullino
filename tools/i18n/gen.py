import os, re, sys
sys.path.insert(0,'/home/claude/i18n')
from table import T
RES='/home/claude/slow-fi_rullino/app/src/main/res'
langs={'en':1,'it':2,'fr':3,'es':4,'de':5}
def esc(s):
    s=s.replace('\\','\\\\').replace('&','&amp;').replace('<','&lt;').replace('>','&gt;')
    s=s.replace("'","\\'").replace('"','\\"').replace('\n','\\n')
    if s.startswith(' ') or s.endswith(' '): s='"'+s+'"'
    if s.startswith('@') or s.startswith('?'): s='\\'+s
    return s
for lang,idx in langs.items():
    d=RES+('/values' if lang=='en' else '/values-'+lang)
    os.makedirs(d,exist_ok=True)
    lines=['<?xml version="1.0" encoding="utf-8"?>','<resources>']
    if lang=='en': lines.append('    <string name="app_name" translatable="false">Rullino</string>')
    for t in T:
        lines.append('    <string name="%s">%s</string>'%(t[0],esc(t[idx])))
    lines.append('</resources>')
    open(d+'/strings.xml','w').write('\n'.join(lines)+'\n')
open(RES+'/resources.properties','w').write('unqualifiedResLocale=en-US\n')
print('ok')

# ---- iOS: Localizable.strings per lingua (stesse chiavi; %1$s -> %1$@) ----
import re as _re
IOS='/home/claude/slow-fi_rullino/ios/Rullino'
def ios_esc(s):
    s=_re.sub(r'%(\d+)\$s', r'%\1$@', s)
    return s.replace('\\','\\\\').replace('"','\\"').replace('\n','\\n')
for lang,idx in langs.items():
    d=IOS+'/'+lang+'.lproj'
    os.makedirs(d,exist_ok=True)
    out=['/* Generato da i18n/gen.py: non modificare a mano */']
    for t in T: out.append('"%s" = "%s";'%(t[0],ios_esc(t[idx])))
    open(d+'/Localizable.strings','w').write('\n'.join(out)+'\n')
print('ios ok')

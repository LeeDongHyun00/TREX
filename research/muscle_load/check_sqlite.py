"""실제 Kotlin 생성 SQL의 제약을 PC SQLite로 검사한다. Android 실행 검사는 아니다."""
from pathlib import Path
import re,sqlite3,json
root=Path(__file__).resolve().parents[2]
source=(root/'app/src/main/java/com/example/trex_kotlin/MuscleLoadStore.kt').read_text(encoding='utf-8')
db=sqlite3.connect(':memory:');db.execute('PRAGMA foreign_keys=ON')
ddl=re.findall(r'db.execSQL\("(CREATE [^"]+)"\)',source)
assert len(ddl)==5
for sql in ddl:db.execute(sql)
def put(key):
    db.execute('INSERT OR REPLACE INTO load_sets VALUES(?,?,?,?,?,?,?)',(key,'session','squat',123,'posture',1,'{}'))
    db.execute('INSERT INTO muscle_doses VALUES(?,?,?,?)',(key,'QUADS','L',1.0))
put('a');db.commit();put('a');db.commit()
assert db.execute('SELECT count(*) FROM load_sets').fetchone()[0]==1
assert db.execute('SELECT count(*) FROM muscle_doses').fetchone()[0]==1
put('b');db.rollback()
assert db.execute('SELECT count(*) FROM load_sets').fetchone()[0]==1
assert db.execute('SELECT count(*) FROM muscle_doses').fetchone()[0]==1
for row in [('missing','QUADS','L',1),('a','QUADS','X',1),('a','QUADS','R',-1)]:
    try: db.execute('INSERT INTO muscle_doses VALUES(?,?,?,?)',row)
    except sqlite3.IntegrityError: db.rollback()
    else: raise AssertionError('제약 누락')
db.execute('DELETE FROM load_sets WHERE id=?',('a',));db.commit()
assert db.execute('SELECT count(*) FROM muscle_doses').fetchone()[0]==0
assert db.execute('PRAGMA integrity_check').fetchone()[0]=='ok'
out=root/'outputs/muscle-load';out.mkdir(exist_ok=True,parents=True)
result={'ddlStatements':len(ddl),'checks':['같은 ID 교체','독립 수행 rollback','부모 없는 부하 거부','좌우 제약','음수 부하 거부','삭제 연쇄','integrity_check'],
    'scope':'PC SQLite에 실제 Kotlin 생성 SQL 적용. Android SQLiteOpenHelper 실행은 미검증.'}
(out/'sqlite-verification.json').write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf-8')
print(json.dumps(result,ensure_ascii=False))

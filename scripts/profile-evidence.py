# -*- coding: utf-8 -*-
"""复算取证库的数据画像：python scripts/profile-evidence.py（需在仓库根目录运行）。

TRUSTED 口径沿用《软件设计书》：≥30 题且 ≥3 次。
"""
import sqlite3, statistics

c = sqlite3.connect('build/real-device-backup/verify-40/t.db')


def report(label, where):
    w = ('where ' + where) if where else ''
    sess = c.execute(f"""
        select count(*), sum(totalCount), sum(correctCount), sum(durationSec),
               count(distinct date(startAt/1000,'unixepoch')),
               min(date(startAt/1000,'unixepoch')), max(date(startAt/1000,'unixepoch'))
        from session {w}
    """).fetchone()
    cats = c.execute(f"""
        select cat.name, count(*) n, sum(s.totalCount) q, sum(s.correctCount) cc
        from session s join category cat on cat.id = s.categoryId {w}
        group by cat.id
    """).fetchall()
    qs = [r[2] for r in cats]
    single = sum(1 for r in cats if r[1] == 1)
    trusted = [r for r in cats if r[2] >= 30 and r[1] >= 3]
    subj = c.execute(f"""
        select sub.name, count(*) n, sum(s.totalCount) q, sum(s.correctCount) cc, sum(s.durationSec) d
        from session s join subject sub on sub.id = s.subjectId {w}
        group by sub.id order by q desc
    """).fetchall()
    subj_trusted = [r for r in subj if r[2] >= 30 and r[1] >= 3]
    cond = "(correctCount < totalCount)" + (" and " + where if where else "")
    wrong = c.execute(f"select count(*), sum(totalCount - correctCount) from session where {cond}").fetchone()
    print(f'== {label} ==')
    print(' sessions with wrong / total wrong questions:', wrong)
    print(' sessions/questions/correct/sec/days/range:', sess)
    print(' used categories:', len(cats), 'median questions:', statistics.median(qs), 'cats with 1 session:', single)
    print(' TRUSTED categories:', len(trusted), [r[0] for r in trusted])
    print(' subjects:', [(r[0], r[1], r[2], round(100 * r[3] / r[2], 1), round(r[4] / r[2], 1)) for r in subj])
    print(' TRUSTED subjects:', len(subj_trusted), [r[0] for r in subj_trusted])
    print()


report('ALL 40', '')
report('REAL HISTORY (IMPORT)', "source='IMPORT'")

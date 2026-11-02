# Стенд миграции 20261005_app_releases.sql на настоящем PostgreSQL (pgserver из PyPI).
# Проверяет: подтвердить релиз может только eclips.ru@mail.ru; чужие ссылки, повтор, изменение, удаление — отказ.
# 10.94: после 20261022_app_releases_tester.sql — ещё и eclipsik.ru@mail.ru (только от своего имени); остальные — отказ.
# Запуск: PG_PY=/путь/к/python-с-pgserver bash tools/check.sh  или  python tools/app-releases-stand.py
import pgserver, tempfile, sys
srv = pgserver.get_server(tempfile.mkdtemp(), cleanup_mode='stop')
def q(sql):
    import subprocess, os, glob
    try: out = srv.psql(sql).strip()
    except Exception as e: return ('ERR', str(e)[-300:])
    return ('ERR' if 'ROLLBACK' in out.split('\n')[-1:] else 'OK', out)
print(q("""
create role anon nologin; create role authenticated nologin;
create schema auth; grant usage on schema auth to anon, authenticated;
create function auth.jwt() returns jsonb language sql stable as $$ select coalesce(nullif(current_setting('request.jwt.claims', true),''),'{}')::jsonb $$;
grant execute on function auth.jwt() to anon, authenticated; grant usage on schema public to anon, authenticated;"""))
mig=open('supabase/migrations/20261005_app_releases.sql').read()
print('apply1', q(mig)[0]); print('apply2 (повтор)', q(mig))
def as_(role,email,sql):
    claims = '{"email":"%s"}'%email if email else ''
    return q(f"begin; set local role {role}; set local request.jwt.claims = '{claims}'; {sql}; commit;")
ins="insert into public.app_releases(version_code,version_name,apk_url,reinstall,notes) values (%s,'%s','%s',true,'x')"
gh='https://github.com/eclipsru/Arenda-Nvk/raw/arena/01a101d5-arenda-nvk/ProkatInstrumenta-10.15.apk'
cases=[
 ('anon вставляет', as_('anon','',ins%(89,'10.15',gh)), 'ERR'),
 ('чужой пользователь', as_('authenticated','someone@mail.ru',ins%(89,'10.15',gh)), 'ERR'),
 ('eclipsik', as_('authenticated','eclipsik.ru@mail.ru',ins%(89,'10.15',gh)), 'ERR'),
 ('создатель, чужая ссылка', as_('authenticated','eclips.ru@mail.ru',ins%(89,'10.15','https://evil.ru/ProkatInstrumenta-10.15.apk')), 'ERR'),
 ('создатель подтверждает', as_('authenticated','Eclips.RU@mail.ru',ins%(89,'10.15',gh)), 'OK'),
 ('повтор той же версии', as_('authenticated','eclips.ru@mail.ru',ins%(89,'10.15',gh)), 'ERR'),
 ('создатель, относительная ссылка 10.16', as_('authenticated','eclips.ru@mail.ru',ins%(90,'10.16','ProkatInstrumenta-10.16.apk')), 'OK'),
 ('создатель подменяет confirmed_by', as_('authenticated','eclips.ru@mail.ru',"insert into public.app_releases(version_code,version_name,apk_url,confirmed_by) values (91,'10.17','ProkatInstrumenta-10.17.apk','x')"), 'ERR'),
 ('создатель удаляет', as_('authenticated','eclips.ru@mail.ru',"delete from public.app_releases"), 'ERR'),
 ('создатель меняет', as_('authenticated','eclips.ru@mail.ru',"update public.app_releases set apk_url='ProkatInstrumenta-1.1.apk'"), 'ERR'),
 ('anon читает почту', as_('anon','',"select confirmed_by from public.app_releases"), 'ERR'),
]
bad=0
for name,(st,out),exp in cases:
    ok = st==exp; bad += not ok
    print(('✔' if ok else '✘'), name, st, '' if st=='OK' else out.splitlines()[-1][:120])
# 10.94: право eclipsik (20261022) — применяем дважды (идемпотентность) и проверяем
mig2=open('supabase/migrations/20261022_app_releases_tester.sql').read()
print('apply 20261022', q(mig2)[0]); print('apply 20261022 (повтор)', q(mig2)[0])
cases2=[
 ('eclipsik выпускает 10.18', as_('authenticated','Eclipsik.RU@mail.ru',ins%(92,'10.18','ProkatInstrumenta-10.18.apk')), 'OK'),
 ('eclipsik от имени создателя', as_('authenticated','eclipsik.ru@mail.ru',"insert into public.app_releases(version_code,version_name,apk_url,confirmed_by) values (93,'10.19','ProkatInstrumenta-10.19.apk','eclips.ru@mail.ru')"), 'ERR'),
 ('eclipsik, чужая ссылка', as_('authenticated','eclipsik.ru@mail.ru',ins%(93,'10.19','https://evil.ru/ProkatInstrumenta-10.19.apk')), 'ERR'),
 ('eclipsik повторяет 10.18', as_('authenticated','eclipsik.ru@mail.ru',ins%(92,'10.18','ProkatInstrumenta-10.18.apk')), 'ERR'),
 ('eclipsik удаляет', as_('authenticated','eclipsik.ru@mail.ru',"delete from public.app_releases"), 'ERR'),
 ('eclipsik меняет', as_('authenticated','eclipsik.ru@mail.ru',"update public.app_releases set apk_url='ProkatInstrumenta-1.1.apk'"), 'ERR'),
 ('чужой после 20261022', as_('authenticated','someone@mail.ru',ins%(93,'10.19','ProkatInstrumenta-10.19.apk')), 'ERR'),
 ('anon после 20261022', as_('anon','',ins%(93,'10.19','ProkatInstrumenta-10.19.apk')), 'ERR'),
 ('создатель после 20261022', as_('authenticated','eclips.ru@mail.ru',ins%(93,'10.19','ProkatInstrumenta-10.19.apk')), 'OK'),
]
for name,(st,out),exp in cases2:
    ok = st==exp; bad += not ok
    print(('✔' if ok else '✘'), name, st, '' if st=='OK' else out.splitlines()[-1][:120])
who=q("select version_code||':'||confirmed_by from public.app_releases order by 1")[1].replace('\n',' ')
print('кто подтвердил:', who)
if '92:eclipsik.ru@mail.ru' not in who or '93:eclips.ru@mail.ru' not in who: bad += 1; print('✘ confirmed_by не тот')
pol=q("select count(*) from pg_policies where tablename='app_releases' and cmd='INSERT'")[1]
print('правил вставки:', pol.replace('\n',' '))
r=as_('anon','',"select version_code, version_name, apk_url from public.app_releases order by version_code desc limit 1")
print('anon читает последнюю:', r[1].replace('\n',' | ')[:200])
n=as_('authenticated','someone@mail.ru',"delete from public.app_releases returning 1")
print('чужой delete:', n)
print('строк осталось:', q("select count(*) from public.app_releases")[1].replace('\n',' '))
print("ИТОГ ошибок:", bad); srv.cleanup(); sys.exit(1 if bad else 0)

#!/usr/bin/env python3
"""Run real engine; capture protocol transcripts. No synthetic engine output."""
import subprocess, pathlib, tempfile, queue, threading, time, re, json, sys
root=pathlib.Path(__file__).resolve().parents[1]
binary=pathlib.Path(sys.argv[1]).resolve() if len(sys.argv)>1 else root/'build/host/rapfi/pbrain-rapfi'
assets=root/'app/src/main/assets/runtime'
results=[]
for mode in ['nnue','classical']:
    with tempfile.TemporaryDirectory() as directory:
        pathlib.Path(directory,'config.toml').write_text((assets/f'{mode}.toml').read_text().replace('@RUNTIME@',str(assets)))
        p=subprocess.Popen([str(binary)],cwd=directory,stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,bufsize=1)
        q=queue.Queue(); transcript=[]
        def read():
            for line in p.stdout: q.put(line.rstrip())
            q.put(None)
        threading.Thread(target=read,daemon=True).start()
        def send(s): transcript.append('> '+s); p.stdin.write(s+'\n'); p.stdin.flush()
        def until(predicate,timeout=45):
            deadline=time.monotonic()+timeout
            while time.monotonic()<deadline:
                line=q.get(timeout=max(.1,deadline-time.monotonic()))
                if line is None: raise RuntimeError('Engine exited')
                transcript.append('< '+line)
                if line.startswith('ERROR'): raise RuntimeError(line)
                if predicate(line): return line
            raise TimeoutError()
        try:
            send('ABOUT'); until(lambda l:'name=' in l)
            send('START 15'); until(lambda l:l=='OK')
            send('YXSHOWINFO'); send('INFO RULE 0'); send('INFO THREAD_NUM 1'); send('INFO HASH_SIZE 32768')
            send('INFO TIMEOUT_TURN 300'); send('INFO MAX_NODE 30000'); send('INFO MAX_DEPTH 12'); send('INFO SHOW_DETAIL 2')
            send('YXBOARD'); send('7,7,1'); send('8,8,2'); send('6,8,1'); send('8,6,2'); send('DONE')
            send('YXNBEST 3'); until(lambda l:bool(re.fullmatch(r'\d+,\d+',l)))
            assert any('INFO PV DONE' in l for l in transcript)
            assert any('INFO BESTLINE ' in l for l in transcript)
            send('RESTART'); until(lambda l:l=='OK')
            send('YXNBEST 3'); send('STOP'); until(lambda l:bool(re.fullmatch(r'\d+,\d+',l)))
            send('RESTART'); until(lambda l:l=='OK')
            send('END'); p.wait(timeout=5)
            results.append({'mode':mode,'passed':True,'exit':p.returncode})
        finally:
            if p.poll() is None: p.kill()
            (root/'tests'/f'transcript-{mode}.txt').write_text('\n'.join(transcript))
(root/'tests/smoke-results.json').write_text(json.dumps(results,indent=2));print(json.dumps(results))

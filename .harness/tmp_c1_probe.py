import io
txt = io.open(r'.harness\transcripts\20260824-152223-2a06b5-swarm20260824-152223-d94f-clone3.jsonl', encoding='utf-8', errors='replace').read()
for kw in ['SendKeys', 'keybd', 'PostMessage', 'tutorial', 'in-world', 'autoLoad', 'worldgen_debug']:
    idx = 0
    hits = 0
    while hits < 2:
        i = txt.find(kw, idx)
        if i < 0:
            break
        print('---', kw, '---')
        print(txt[max(0, i - 250):i + 250].replace('\n', ' ')[:460])
        print()
        idx = i + 1
        hits += 1

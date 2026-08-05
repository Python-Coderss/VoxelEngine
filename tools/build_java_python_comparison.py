#!/usr/bin/env python3
"""Build dev/voice-comparison/index.html from Java/Python WAV pairs."""
from __future__ import annotations

import base64
import json
import os
import sys
import wave
from pathlib import Path

import numpy as np


def read_wav(path: Path) -> tuple[int, np.ndarray]:
    with wave.open(str(path), "rb") as wav:
        rate = wav.getframerate()
        channels = wav.getnchannels()
        if wav.getsampwidth() != 2:
            raise ValueError(f"Expected 16-bit PCM WAV: {path}")
        data = np.frombuffer(wav.readframes(wav.getnframes()), dtype=np.int16)
    if channels > 1:
        data = data.reshape(-1, channels).mean(axis=1)
    return rate, data.astype(np.float32) / 32768.0


def active_span(samples: np.ndarray, rate: int) -> tuple[float, float]:
    if not len(samples):
        return 0.0, 0.0
    window = max(1, rate // 100)
    energy = np.convolve(samples * samples, np.ones(window, dtype=np.float32), mode="same")
    peak = float(energy.max())
    if peak <= 1e-10:
        return 0.0, len(samples) / rate
    active = np.flatnonzero(energy > peak * 0.10)
    if not len(active):
        return 0.0, len(samples) / rate
    return max(0, int(active[0]) - window) / rate, min(
        len(samples), int(active[-1]) + window) / rate


def _spectral_stats(samples: np.ndarray, rate: int) -> dict:
    if len(samples) < 32:
        return {"rmsDb": -120.0, "centroidHz": 0.0, "lowBandPercent": 0.0}
    start, end = active_span(samples, rate)
    active = samples[int(start * rate):max(int(end * rate), int(start * rate) + 1)]
    if len(active) < 32:
        active = samples
    window = np.hanning(len(active))
    power = np.abs(np.fft.rfft(active * window)) ** 2
    freqs = np.fft.rfftfreq(len(active), 1.0 / rate)
    total = max(float(power.sum()), 1.0e-12)
    return {
        "rmsDb": float(20.0 * np.log10(np.sqrt(np.mean(active * active)) + 1.0e-12)),
        "centroidHz": float((freqs * power).sum() / total),
        "lowBandPercent": float(power[(freqs >= 40.0) & (freqs < 500.0)].sum() / total * 100.0),
    }


def metrics(java_path: Path, python_path: Path) -> dict:
    jr, js = read_wav(java_path)
    pr, ps = read_wav(python_path)
    j0, j1 = active_span(js, jr)
    p0, p1 = active_span(ps, pr)
    jdur, pdur = j1 - j0, p1 - p0
    jspec, pspec = _spectral_stats(js, jr), _spectral_stats(ps, pr)
    return {
        "javaRate": jr, "pythonRate": pr,
        "javaSeconds": len(js) / jr, "pythonSeconds": len(ps) / pr,
        "javaActiveSeconds": jdur, "pythonActiveSeconds": pdur,
        "onsetDeltaSeconds": j0 - p0,
        "durationDeltaSeconds": jdur - pdur,
        "durationRelativePercent": abs(jdur - pdur) / max(pdur, 1e-6) * 100.0,
        "javaPeak": float(np.max(np.abs(js))) if len(js) else 0.0,
        "pythonPeak": float(np.max(np.abs(ps))) if len(ps) else 0.0,
        "javaRmsDb": jspec["rmsDb"], "pythonRmsDb": pspec["rmsDb"],
        "javaCentroidHz": jspec["centroidHz"], "pythonCentroidHz": pspec["centroidHz"],
        "javaLowBandPercent": jspec["lowBandPercent"],
        "pythonLowBandPercent": pspec["lowBandPercent"],
    }


def wav_data(path: Path) -> str:
    return base64.b64encode(path.read_bytes()).decode("ascii")


def main() -> int:
    root = Path(sys.argv[1] if len(sys.argv) > 1 else "dev/voice-comparison")
    java_dir, python_dir = root / "java", root / "python"
    labels = {
        "hello": "Hello",
        "i_am_haggling_you": "I am haggling you",
        "no": "No!!!",
        "what_are_you_doing": "What are you doing",
        "aha_that_seems_like_a_deal_that_will_work_for_both_of_us":
            "Aha that seems like a deal that will work for both of us",
    }
    java_files = {p.name: p for p in java_dir.glob("*.wav")}
    python_files = {p.name: p for p in python_dir.glob("*.wav")}
    missing_python = sorted(set(java_files) - set(python_files))
    missing_java = sorted(set(python_files) - set(java_files))
    if missing_python or missing_java:
        raise SystemExit("Incomplete Java/Python pairs: "
                         f"missing Python={missing_python}, missing Java={missing_java}")
    pairs = []
    for name in sorted(java_files):
        java_path, python_path = java_files[name], python_files[name]
        pairs.append({
            "id": java_path.stem,
            "text": labels.get(java_path.stem, java_path.stem.replace("_", " ")),
            "java": wav_data(java_path),
            "python": wav_data(python_path),
            "metrics": metrics(java_path, python_path),
        })
    if not pairs:
        raise SystemExit(f"No matching WAV pairs under {root}")
    (root / "manifest.json").write_text(json.dumps(
        [{"id": p["id"], "text": p["text"]} for p in pairs], indent=2) + "\n",
        encoding="utf-8")
    data = json.dumps(pairs, separators=(",", ":"))
    html = TEMPLATE.replace("__DATA__", data)
    (root / "index.html").write_text(html, encoding="utf-8")
    print(f"wrote {root / 'index.html'} with {len(pairs)} Java/Python pairs")
    return 0


TEMPLATE = r'''<!doctype html>
<html lang="en"><head><meta charset="utf-8">
<title>Java vs Python villager voice</title>
<style>
body{font-family:system-ui,sans-serif;max-width:1180px;margin:24px auto;padding:0 16px;background:#faf8f4;color:#202020}
h1{font-size:1.5rem;margin-bottom:4px}.note{color:#666;font-size:.88rem}
.card{background:#fff;border:1px solid #ddd6cb;border-radius:12px;padding:16px;margin:18px 0}
.head{display:flex;justify-content:space-between;gap:12px;align-items:center;flex-wrap:wrap}.head h2{font-size:1.05rem;margin:0}
button{border:1px solid #aebfa3;background:#eff7e9;border-radius:7px;padding:6px 12px;cursor:pointer}
.grid{display:grid;grid-template-columns:1fr 1fr;gap:16px}.panel{background:#f3f0e9;border-radius:9px;padding:10px}.panel h3{font-size:.9rem;margin:0 0 6px}.java{color:#155da8}.python{color:#8a4b12}
audio{width:100%;margin-bottom:6px}canvas{width:100%;height:210px;background:#090a11;border-radius:6px;display:block}
.metrics{font:12px ui-monospace,monospace;color:#555;margin-top:10px;line-height:1.55}
@media(max-width:760px){.grid{grid-template-columns:1fr}}
</style></head><body>
<h1>Java vs Python — pre-integration voice comparison</h1>
<p class="note">Identical text is sent separately to the Java and Python pipelines. Neither
side is used by the game here. Click <b>Play both</b> to hear them together; spectrograms
use the same frequency and time scales for each pair. This page is self-contained and
works from <code>file://</code>.</p><div id="app"></div>
<script>
const DATA=__DATA__, W=760,H=210;
function bytes(b){const s=atob(b),a=new Uint8Array(s.length);for(let i=0;i<s.length;i++)a[i]=s.charCodeAt(i);return a}
function wav(a){const d=new DataView(a.buffer,a.byteOffset,a.byteLength);let o=12,rate=0,off=0,len=0;while(o+8<=d.byteLength){let id=String.fromCharCode(...a.slice(o,o+4)),n=d.getUint32(o+4,true);if(id==='fmt ')rate=d.getUint32(o+12,true);if(id==='data'){off=o+8;len=n}o+=8+n+(n&1)}let x=new Float32Array(Math.floor(len/2));for(let i=0;i<x.length;i++)x[i]=d.getInt16(off+i*2,true)/32768;return{a:x,r:rate}}
function fft(re,im){let n=re.length;for(let i=1,j=0;i<n;i++){let b=n>>1;for(;j&b;b>>=1)j^=b;j^=b;if(i<j){let t=re[i];re[i]=re[j];re[j]=t;t=im[i];im[i]=im[j];im[j]=t}}for(let l=2;l<=n;l<<=1){let ar=-2*Math.PI/l,wr=Math.cos(ar),wi=Math.sin(ar);for(let i=0;i<n;i+=l){let cr=1,ci=0;for(let k=0;k<l/2;k++){let ur=re[i+k],ui=im[i+k],vr=re[i+k+l/2]*cr-im[i+k+l/2]*ci,vi=re[i+k+l/2]*ci+im[i+k+l/2]*cr;re[i+k]=ur+vr;im[i+k]=ui+vi;re[i+k+l/2]=ur-vr;im[i+k+l/2]=ui-vi;let nr=cr*wr-ci*wi;ci=cr*wi+ci*wr;cr=nr}}}}
function spec(c){let N=1024,h=256,n=Math.max(1,Math.floor(Math.max(0,c.a.length-N)/h)+1),m=new Float32Array(n*N/2),re=new Float32Array(N),im=new Float32Array(N),w=new Float32Array(N);for(let i=0;i<N;i++)w[i]=.5-.5*Math.cos(2*Math.PI*i/(N-1));for(let f=0;f<n;f++){for(let i=0;i<N;i++){re[i]=(c.a[f*h+i]||0)*w[i];im[i]=0}fft(re,im);for(let b=0;b<N/2;b++)m[f*N/2+b]=Math.hypot(re[b],im[b])}return{m,n,b:N/2,h}}
const cmap=[[0,0,5],[25,5,60],[80,25,90],[180,75,35],[255,225,120]];function col(x){x=Math.max(0,Math.min(.999,x))*(cmap.length-1);let i=Math.floor(x),f=x-i,a=cmap[i],b=cmap[i+1]||a;return[a[0]+(b[0]-a[0])*f,a[1]+(b[1]-a[1])*f,a[2]+(b[2]-a[2])*f]}
function paint(canvas,c,s,maxSec,sharedMaxF){canvas.width=W;canvas.height=H;let ctx=canvas.getContext('2d'),im=ctx.createImageData(W,H),maxF=Math.min(sharedMaxF,c.r/2),minF=40,lh=Math.log(maxF/minF);for(let x=0;x<W;x++){let sec=x/W*maxSec,fr=sec*c.r/s.h,fi=Math.min(s.n-1,Math.max(0,Math.floor(fr)));for(let y=0;y<H;y++){let f=maxF*Math.exp(-lh*y/H),bi=Math.min(s.b-1,Math.max(0,f/(c.r/N)));let b=Math.floor(bi),v=s.m[fi*s.b+b]||0,t=Math.max(0,Math.min(1,(20*Math.log10(v+1e-8)+95)/65)),q=col(t),k=(y*W+x)*4;im.data[k]=q[0];im.data[k+1]=q[1];im.data[k+2]=q[2];im.data[k+3]=255}}ctx.putImageData(im,0,0);ctx.fillStyle='rgba(255,255,255,.65)';ctx.font='10px monospace';[40,100,200,500,1000,2000,4000,8000].forEach(f=>{if(f>maxF)return;let y=H*Math.log(maxF/f)/lh;ctx.fillText(f>=1000?f/1000+'k':f+' Hz',4,Math.max(10,y-2));ctx.strokeStyle='rgba(255,255,255,.18)';ctx.beginPath();ctx.moveTo(0,y);ctx.lineTo(W,y);ctx.stroke()})}
function mount(parent,label,kind,data,limit,sharedMaxF){let p=document.createElement('div');p.className='panel';let h=document.createElement('h3');h.className=kind;h.textContent=label;p.appendChild(h);let raw=bytes(data),c=wav(raw),a=document.createElement('audio');a.controls=true;a.src=URL.createObjectURL(new Blob([raw],{type:'audio/wav'}));p.appendChild(a);let cv=document.createElement('canvas');p.appendChild(cv);paint(cv,c,spec(c),limit,sharedMaxF);parent.appendChild(p);return a}
for(const x of DATA){let card=document.createElement('section');card.className='card';let head=document.createElement('div');head.className='head';let title=document.createElement('h2');title.textContent=x.id.replaceAll('_',' ');let b=document.createElement('button');b.textContent='Play both';head.append(title,b);card.append(head);let g=document.createElement('div');g.className='grid';let sharedMaxF=Math.min(8000,x.metrics.javaRate/2,x.metrics.pythonRate/2),limit=Math.max(x.metrics.javaSeconds,x.metrics.pythonSeconds);let ja=mount(g,'Java','java',x.java,limit,sharedMaxF);let py=mount(g,'Python','python',x.python,limit,sharedMaxF);b.onclick=()=>{if(!ja.paused||!py.paused){ja.pause();py.pause();b.textContent='Play both'}else{ja.currentTime=0;py.currentTime=0;ja.play();py.play();b.textContent='Pause both'}};card.append(g);let m=document.createElement('div');m.className='metrics';m.textContent=`${x.text} · Java ${x.metrics.javaRate} Hz / ${x.metrics.javaSeconds.toFixed(2)}s · Python ${x.metrics.pythonRate} Hz / ${x.metrics.pythonSeconds.toFixed(2)}s · active duration Δ ${x.metrics.durationDeltaSeconds.toFixed(2)}s (${x.metrics.durationRelativePercent.toFixed(1)}%) · onset Δ Java−Python ${x.metrics.onsetDeltaSeconds.toFixed(3)}s · RMS ${x.metrics.javaRmsDb.toFixed(1)} / ${x.metrics.pythonRmsDb.toFixed(1)} dB · low <500Hz ${x.metrics.javaLowBandPercent.toFixed(0)}% / ${x.metrics.pythonLowBandPercent.toFixed(0)}% · centroid ${x.metrics.javaCentroidHz.toFixed(0)} / ${x.metrics.pythonCentroidHz.toFixed(0)} Hz`;card.append(m);document.getElementById('app').append(card)}
</script></body></html>'''

if __name__ == "__main__":
    raise SystemExit(main())

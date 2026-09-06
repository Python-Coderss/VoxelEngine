import os
import re
import urllib.request
import urllib.error
from pathlib import Path

BASE_URL = "https://downloads.khinsider.com"
INPUT_FILE = "season1.txt"
OUTPUT_DIR = Path("src/resources/music")

def sanitize_filename(name):
    name = re.sub(r'[<>:"/\\|?*]', '', name)
    name = name.strip('. ')
    return name

def main():
    with open(INPUT_FILE, 'r', encoding='utf-8') as f:
        content = f.read()

    rows = re.findall(r'<tr>.*?</tr>', content, re.DOTALL)
    print(f"Found {len(rows)} rows")
    
    downloaded = 0
    skipped = 0
    errors = 0

    for idx, row in enumerate(rows):
        link_match = re.search(r'href="(/game-soundtracks/album/minecraft-story-mode-gamerip-2016/[^"]+\.mp3)"', row)
        if not link_match:
            continue

        link_path = link_match.group(1)
        full_url = BASE_URL + link_path

        title_match = re.search(r'<td class="clickable-row"><a href="[^"]+">([^<]+)</a></td>', row)
        if not title_match:
            print(f"Row {idx}: no title match, link={link_path}")
            continue
        title = title_match.group(1).strip()

        chapter_match = re.search(r'/minecraft-story-mode-gamerip-2016/(\d+)\.', link_path)
        if not chapter_match:
            print(f"Row {idx}: no chapter, link={link_path}")
            continue
        chapter = chapter_match.group(1)

        if ' - Unused - ' in title:
            track_type = 'unused'
            name = title.split(' - Unused - ', 1)[1]
        else:
            track_type = 'main'
            parts = title.split(' - ', 1)
            if len(parts) == 2:
                name = parts[1]
                if '. ' in name:
                    name = name.split('. ', 1)[1]
            else:
                name = title

        name = sanitize_filename(name)
        if not name.endswith('.mp3'):
            name = name + '.mp3'

        out_dir = OUTPUT_DIR / chapter / track_type
        out_dir.mkdir(parents=True, exist_ok=True)
        out_path = out_dir / name

        if out_path.exists() and out_path.stat().st_size > 1000:
            with open(out_path, 'rb') as f:
                header = f.read(20)
            if header.startswith(b'ID3') or header.startswith(b'\xff\xfb') or header.startswith(b'\xff\xf3') or header.startswith(b'\xff\xf2'):
                skipped += 1
                continue

        print(f"Downloading: {title} -> {out_path}")
        try:
            req = urllib.request.Request(full_url, headers={
                'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36',
                'Referer': 'https://downloads.khinsider.com/'
            })
            with urllib.request.urlopen(req, timeout=30) as response:
                html = response.read().decode('utf-8', errors='ignore')

            mp3_links = re.findall(r'https://vgmtreasurechest\.com[^\s"\'<>]+\.mp3', html)
            if not mp3_links:
                print(f"  No MP3 link found in page")
                with open(out_path, 'w', encoding='utf-8') as f:
                    f.write(html)
                errors += 1
                continue

            mp3_url = mp3_links[0]
            print(f"  Found MP3: {mp3_url[:80]}...")
            
            req2 = urllib.request.Request(mp3_url, headers={
                'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36',
                'Referer': 'https://downloads.khinsider.com/'
            })
            with urllib.request.urlopen(req2, timeout=60) as response:
                mp3_data = response.read()
            
            with open(out_path, 'wb') as f:
                f.write(mp3_data)
            downloaded += 1
            print(f"  Saved {len(mp3_data)} bytes")
        except Exception as e:
            print(f"  Error: {e}")
            errors += 1

    print(f"\nDone: {downloaded} downloaded, {skipped} skipped, {errors} errors")

if __name__ == '__main__':
    main()

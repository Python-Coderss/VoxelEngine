import os
txt = open(r'tools\c3_final_boot.txt', errors='replace').read()
print('bytes:', len(txt))
print('---- last 2500 chars ----')
print(txt[-2500:])
